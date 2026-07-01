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
 * Mangadot.net scraper for the Oni manga client.
 *
 * Flow:
 *   1. GET /api/search?search=<manga>            -> pick best match (top hit)
 *   2. GET /api/manga/{id}/chapters/list         -> all chapters
 *   3. For each chapter, fetch its image manifest:
 *        - source=user    -> GET /api/uploads/{id}/images   (no token)
 *        - source=official -> GET /api/chapters/{id}/images (HMAC-signed)
 *
 * Returns a Map<String, List<String>>:
 *   { "1": ["https://mangadot.net/chapters/.../001.webp", ...],
 *     "2": [...], ... }
 *
 * Chapter key = chapter number (normalized: "1" instead of "1.0").
 * When multiple versions of the same chapter number exist (different scanlation
 * groups), the group name is appended: "1 (Asura Scans)".
 */
object MangadotnetScraper {

    private const val BASE = "https://mangadot.net"

    /** Cap the number of chapters we'll fetch images for, to avoid runaway HTTP. */
    private const val MAX_CHAPTERS = 100

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /** Return either a Map<String, List<String>> (chapter -> image urls) OR a Map<String,String> error. */
    fun scrape(context: Context, mangaName: String?, anilistId: String?): Any {
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

        // 2. List chapters.
        val chapters = try {
            listChapters(mangaId)
        } catch (e: Exception) {
            return mapOf("error" to "Failed to list chapters: ${e.message}")
        }

        // 3. For each chapter, fetch images.
        val result = LinkedHashMap<String, List<String>>()
        val usedKeys = HashSet<String>()

        var processed = 0
        for (i in 0 until chapters.length()) {
            if (processed >= MAX_CHAPTERS) break
            val ch = chapters.optJSONObject(i) ?: continue

            val chapterId = ch.optInt("id", -1)
            if (chapterId <= 0) continue

            val source = ch.optString("source", "user")
            val groupName = ch.optString("group_name", "").ifBlank { ch.optString("scanlator_name", "") }

            val images = try {
                if (source == "user") {
                    fetchUserChapterImages(chapterId)
                } else {
                    fetchOfficialChapterImages(chapterId)
                }
            } catch (e: Exception) {
                // Skip chapters that fail; don't abort the whole batch.
                continue
            }
            if (images.isEmpty()) continue

            val key = buildChapterKey(ch, groupName, usedKeys)
            result[key] = images
            usedKeys.add(key)
            processed++
        }

        return result
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

    private fun buildChapterKey(ch: JSONObject, groupName: String, used: HashSet<String>): String {
        // chapter_number may come back as int (0,1,2) or float (1.5).
        val raw = ch.opt("chapter_number")
        val numStr = when (raw) {
            null -> ch.optInt("id").toString()
            is Number -> {
                val d = raw.toDouble()
                if (d == d.toLong().toDouble()) d.toLong().toString()
                else d.toString().trimEnd('0').trimEnd('.')
            }
            else -> raw.toString()
        }

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
