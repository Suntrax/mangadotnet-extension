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
 * One chapter at a time — the caller MUST specify which chapter to fetch.
 * The extension never returns a chapter list; it always fetches and returns
 * the image URLs for the single requested chapter.
 *
 * Flow:
 *   1. GET /api/search?search=<manga>            -> pick best match (top hit)
 *   2. GET /api/manga/{id}/chapters/list         -> all chapters (metadata only,
 *      used to find the requested chapter and to report totalChapters)
 *   3. Find the matching chapter via three-pass matcher (exact number →
 *      case-insensitive title → numeric equality), then fetch its image
 *      manifest:
 *        - source=user     -> GET /api/uploads/{id}/images   (no token)
 *        - source=official -> GET /api/chapters/{id}/images  (HMAC-signed)
 *
 * Response shape (always includes totalChapters so the UI can show the
 * available range even on error):
 *
 *   Success:
 *     { "totalChapters": 105,
 *       "chapter": {
 *         "number": "38",
 *         "title": "Episode 38",
 *         "group": "Asura Scans",
 *         "images": ["https://mangadot.net/chapters/.../001.webp", ...]
 *       } }
 *
 *   Error (no manga name):
 *     { "error": "No manga name provided." }
 *
 *   Error (chapter not found — totalChapters still included):
 *     { "totalChapters": 105,
 *       "error": "Chapter '99' not found. Available range: 1–105." }
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
     * @param chapter    REQUIRED chapter identifier (as a string — supports
     *                   "1", "1.5", "12v2", or even a chapter title like
     *                   "Episode 38"). The scraper finds the matching chapter
     *                   via a three-pass matcher and fetches only its image
     *                   manifest.
     * @return A Map serializable to JSON. See class KDoc for shapes.
     */
    fun scrape(
        context: Context,
        mangaName: String?,
        anilistId: String?,
        chapter: String?
    ): Any {
        if (mangaName.isNullOrBlank()) {
            return mapOf("error" to "No manga name provided.")
        }
        if (chapter.isNullOrBlank()) {
            return mapOf("error" to "No chapter provided. " +
                    "This extension requires a chapter number (e.g. '1', '1.5', " +
                    "'12v2') or a chapter title (e.g. 'Episode 38').")
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

        // 2. List chapters (metadata only — used to find the requested
        //    chapter and to report totalChapters in the response).
        val chapters = try {
            listChapters(mangaId)
        } catch (e: Exception) {
            return mapOf("error" to "Failed to list chapters: ${e.message}")
        }
        val totalChapters = chapters.length()

        // 3. Find the requested chapter and fetch its images.
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

    // ---------- Chapter matching ----------

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
