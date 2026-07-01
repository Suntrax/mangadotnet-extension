package com.blissless.mangadotnet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mangadot.net scraper for the MangaClient manga client.
 *
 * Flow:
 *   1. GET /api/search?search=<manga>            -> pick best match (top hit)
 *   2. GET /api/manga/{id}/chapters/list         -> all chapters (metadata only)
 *   3a. If `chapter` is null/blank:
 *         Return immediately with { totalChapters, chapters:[{number,title,group}] }
 *         — no image fetching, fast.
 *   3b. If `chapter` is set:
 *         Find the matching chapter, fetch ONLY its image manifest:
 *           - source=user     -> GET /api/uploads/{id}/images   (no token)
 *           - source=official -> GET /api/chapters/{id}/images  (HMAC-signed)
 *         Return { totalChapters, chapter:{number,title,group,images:[...]} }
 *
 * Two response shapes:
 *
 *   Chapter list (no chapter param):
 *     { "totalChapters": 105,
 *       "chapters": [
 *         { "number": "1", "title": "...", "group": "Asura Scans" },
 *         { "number": "2", "title": "...", "group": "Asura Scans" },
 *         ... ] }
 *
 *   Single chapter (chapter param set):
 *     { "totalChapters": 105,
 *       "chapter": {
 *         "number": "38",
 *         "title": "Episode 38",
 *         "group": "Asura Scans",
 *         "images": ["https://mangadot.net/chapters/.../001.webp", ...]
 *       } }
 *
 *   Error:
 *     { "error": "..." }
 *     (often paired with totalChapters so the UI can still show the range)
 *
 * Chapter key = chapter number (normalized: "1" instead of "1.0").
 * When multiple versions of the same chapter number exist (different scanlation
 * groups), the group name is appended: "1 (Asura Scans)".
 */
object MangadotnetScraper {

    private const val BASE = "https://mangadot.net"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * @param context    Application context (unused for HTTP; kept for parity).
     * @param mangaName  Manga title to search for.
     * @param anilistId  AniList ID (unused by mangadot.net, but available).
     * @param chapter    Optional chapter number (as a string — supports "1",
     *                   "1.5", "12v2", or even a title). When null/blank,
     *                   the scraper returns the chapter list only (no images).
     *                   When set, the scraper fetches only that chapter's
     *                   images.
     * @return A Map serializable to JSON. See class KDoc for shapes.
     */
    fun scrape(
        context: Context,
        mangaName: String?,
        anilistId: String?,
        chapter: String? = null
    ): Any {
        if (mangaName.isNullOrBlank()) {
            return mapOf("error" to "No manga name provided.")
        }

        // 1. Search for the manga.
        val manga = try {
            searchManga(mangaName)
        } catch (e: Exception) {
            return mapOf("error" to "Search failed: ${e.message}")
        }
        if (manga == null) {
            return mapOf("error" to "No manga found for '$mangaName'.")
        }
        val mangaId = manga.getInt("id")

        // 2. List chapters (metadata only — no image fetching yet).
        val chapters = try {
            listChapters(mangaId)
        } catch (e: Exception) {
            return mapOf("error" to "Failed to list chapters: ${e.message}")
        }
        val totalChapters = chapters.length()

        // 3a. No specific chapter requested — return the chapter list only.
        //     This is the fast path: one search + one chapters/list call.
        if (chapter.isNullOrBlank()) {
            return mapOf(
                "totalChapters" to totalChapters,
                "chapters" to buildChapterList(chapters)
            )
        }

        // 3b. A specific chapter was requested — find it and fetch its images.
        val match = findChapter(chapters, chapter.trim())
        if (match == null) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Chapter '$chapter' not found. " +
                        "Available range: 1–$totalChapters."
            )
        }

        val chapterId = match.optInt("id", -1)
        if (chapterId <= 0) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Matched chapter has invalid id."
            )
        }

        val source = match.optString("source", "user")
        val groupName = match.optString("group_name", "")
            .ifBlank { match.optString("scanlator_name", "") }

        val images = try {
            if (source == "user") {
                fetchUserChapterImages(chapterId)
            } else {
                fetchOfficialChapterImages(chapterId)
            }
        } catch (e: Exception) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Failed to fetch chapter $chapter images: ${e.message}"
            )
        }

        if (images.isEmpty()) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Chapter $chapter returned no images."
            )
        }

        val chapterObj = JSONObject()
        chapterObj.put("number", chapter.trim())
        chapterObj.put("title", match.optString("title", ""))
        chapterObj.put("group", groupName)
        chapterObj.put("images", JSONArray(images))

        return mapOf(
            "totalChapters" to totalChapters,
            "chapter" to chapterObj
        )
    }

    // ---------- Chapter list / matching ----------

    /**
     * Build a lightweight chapter list (number, title, group) — no image URLs.
     * Used in the no-chapter path so the main app can render a picker UI
     * without paying the cost of fetching every chapter's image manifest.
     */
    private fun buildChapterList(chapters: JSONArray): JSONArray {
        val out = JSONArray()
        val usedKeys = HashSet<String>()
        for (i in 0 until chapters.length()) {
            val ch = chapters.optJSONObject(i) ?: continue
            val groupName = ch.optString("group_name", "")
                .ifBlank { ch.optString("scanlator_name", "") }
            val key = buildChapterKey(ch, groupName, usedKeys)
            usedKeys.add(key)

            val entry = JSONObject()
            entry.put("number", key)
            entry.put("title", ch.optString("title", ""))
            entry.put("group", groupName)
            out.put(entry)
        }
        return out
    }

    /**
     * Find the first chapter whose normalized number matches [requested],
     * falling back to a case-insensitive title match.
     */
    private fun findChapter(chapters: JSONArray, requested: String): JSONObject? {
        val requestedNorm = requested.trim()

        // Pass 1: exact match on chapter_number.
        for (i in 0 until chapters.length()) {
            val ch = chapters.optJSONObject(i) ?: continue
            val numStr = normalizeChapterNumber(ch)
            if (numStr == requestedNorm) return ch
        }

        // Pass 2: case-insensitive title match.
        for (i in 0 until chapters.length()) {
            val ch = chapters.optJSONObject(i) ?: continue
            val title = ch.optString("title", "")
            if (title.equals(requestedNorm, ignoreCase = true)) return ch
        }

        // Pass 3: numeric prefix match — "1" matches "1" but also "1.5"
        // when the user requested "1" and there's no exact "1" (edge case
        // for manga with only decimal-numbered chapters).
        val requestedNum = requestedNorm.toDoubleOrNull()
        if (requestedNum != null) {
            for (i in 0 until chapters.length()) {
                val ch = chapters.optJSONObject(i) ?: continue
                val raw = ch.opt("chapter_number")
                if (raw is Number && raw.toDouble() == requestedNum) return ch
            }
        }

        return null
    }

    // ---------- API helpers ----------

    private fun searchManga(query: String): JSONObject? {
        val url = "$BASE/api/search?search=${URLEncoder.encode(query, "UTF-8")}"
        val body = httpGet(url)
        val data = JSONObject(body)
        val list = data.optJSONArray("manga_list") ?: return null
        if (list.length() == 0) return null
        return list.getJSONObject(0)
    }

    private fun listChapters(mangaId: Int): JSONArray {
        val body = httpGet("$BASE/api/manga/$mangaId/chapters/list")
        return JSONArray(body)
    }

    /** User-uploaded chapter — no token required. */
    private fun fetchUserChapterImages(chapterId: Int): List<String> {
        val body = httpGet("$BASE/api/uploads/$chapterId/images")
        val data = JSONObject(body)
        return extractImageUrls(data.optJSONArray("images"))
    }

    /** Official chapter — needs the HMAC-signed token flow. */
    private fun fetchOfficialChapterImages(chapterId: Int): List<String> {
        val token = getToken(chapterId, type = "chapter")
        val url = "$BASE/api/chapters/$chapterId/images"

        val headers = if (token.pageToken.isBlank() && token.signingKey.isBlank()) {
            emptyMap()
        } else {
            val nonce = UUID.randomUUID().toString()
            val ts = (System.currentTimeMillis() / 1000).toString()
            val payload = "$nonce|$ts|$url"
            val sig = hmacSha256(token.signingKey, payload)
            mapOf(
                "X-Page-Token" to token.pageToken,
                "X-Nonce" to nonce,
                "X-Timestamp" to ts,
                "X-Signature" to sig
            )
        }

        val body = httpGet(url, headers)
        val data = JSONObject(body)
        return extractImageUrls(data.optJSONArray("images"))
    }

    private data class Token(val pageToken: String, val signingKey: String)

    private fun getToken(chapterId: Int, type: String): Token {
        val body = httpGet("$BASE/api/token/generate?chapter_id=$chapterId&type=$type")
        val obj = JSONObject(body)
        return Token(
            pageToken = obj.optString("page_token", ""),
            signingKey = obj.optString("signing_key", "")
        )
    }

    private fun extractImageUrls(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val img = arr.optJSONObject(i) ?: continue
            val rel = img.optString("url").ifBlank { img.optString("filename") }
            if (rel.isBlank()) continue
            // API returns relative URLs like "/chapters/manga_68/.../001.webp".
            out.add(if (rel.startsWith("http")) rel else "$BASE${rel}")
        }
        return out
    }

    private fun normalizeChapterNumber(ch: JSONObject): String {
        val raw = ch.opt("chapter_number")
        return when (raw) {
            null -> ch.optInt("id").toString()
            is Number -> {
                val d = raw.toDouble()
                if (d == d.toLong().toDouble()) d.toLong().toString()
                else d.toString().trimEnd('0').trimEnd('.')
            }
            else -> raw.toString()
        }
    }

    private fun buildChapterKey(ch: JSONObject, groupName: String, used: HashSet<String>): String {
        val numStr = normalizeChapterNumber(ch)

        // First try the bare number.
        if (!used.contains(numStr)) return numStr

        // Collision: append group name if available.
        val withGroup = if (groupName.isNotBlank()) "$numStr ($groupName)" else null
        if (withGroup != null && !used.contains(withGroup)) return withGroup

        // Last-resort disambiguation: append chapter id.
        val withId = "$numStr #${ch.optInt("id", 0)}"
        var n = 1
        var candidate = withId
        while (used.contains(candidate)) {
            candidate = "$withId.$n"
            n++
        }
        return candidate
    }

    // ---------- HTTP ----------

    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap()): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "$BASE/")
            setRequestProperty("Accept", "application/json, */*;q=0.8")
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        try {
            val code = conn.responseCode
            if (code in 200..299) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            }
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IOException("HTTP $code for $urlStr${if (err.isNotBlank()) ": $err" else ""}")
        } finally {
            conn.disconnect()
        }
    }

    private fun hmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }
}
