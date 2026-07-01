package com.blissless.mangadotnet

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * ContentProvider queried by the MangaClient main app.
 *
 * Query URI (chapter is REQUIRED):
 *   content://com.blissless.mangadotnet.provider/scrape
 *     ?manga=<title>&anilistId=<id>&chapter=<number-or-title>
 *
 * The extension fetches ONLY the requested chapter's image URLs and returns:
 *   { "totalChapters": 105,
 *     "chapter": {"number":"38","title":"...","group":"...","images":[...]} }
 *
 * If `chapter` is missing, the extension returns an error:
 *   { "error": "No chapter provided. This extension requires a chapter ..." }
 *
 * Returns a single-row MatrixCursor whose "data" column holds the JSON string.
 */
class ScraperProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.blissless.mangadotnet.provider"
        const val PATH_SCRAPE = "scrape"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SCRAPE")
        private const val CODE_SCRAPES = 1

        private const val TAG = "MangaDotNet"
    }

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, PATH_SCRAPE, CODE_SCRAPES)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        when (uriMatcher.match(uri)) {
            CODE_SCRAPES -> {
                val mangaName = uri.getQueryParameter("manga")
                val anilistId = uri.getQueryParameter("anilistId")
                val chapter   = uri.getQueryParameter("chapter")
                val cursor = MatrixCursor(arrayOf("data"))

                Log.d(TAG, "scrape called: manga='$mangaName'  " +
                        "anilistId=$anilistId  chapter='$chapter'")

                try {
                    val result = MangadotnetScraper.scrape(
                        context!!, mangaName, anilistId, chapter
                    )
                    val json = serializeResult(result)
                    Log.d(TAG, "scrape result (${json.length} chars): " +
                            json.take(300) +
                            if (json.length > 300) "..." else "")
                    cursor.addRow(arrayOf(json))
                } catch (e: Exception) {
                    Log.e(TAG, "scrape threw", e)
                    cursor.addRow(arrayOf(
                        "{\"error\":\"Scraping failed: " +
                                "${e.message?.replace("\"", "\\\"")}\"}"
                    ))
                }
                return cursor
            }
        }
        Log.w(TAG, "URI did not match: $uri")
        return null
    }

    /**
     * Serializes the scraper result to JSON. Supports:
     *   - Map<String, *>  -> {"key": value, ...}  (totalChapters/chapters/chapter/error)
     *   - List<*>         -> ["...", "..."]       (flat list — legacy)
     */
    private fun serializeResult(result: Any): String {
        return when (result) {
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((key, value) in result) {
                    when (value) {
                        is Map<*, *> -> obj.put(key.toString(), JSONObject(value as Map<*, *>))
                        is List<*> -> {
                            val arr = JSONArray()
                            for (item in value) arr.put(item)
                            obj.put(key.toString(), arr)
                        }
                        is JSONArray -> obj.put(key.toString(), value)
                        is JSONObject -> obj.put(key.toString(), value)
                        null -> obj.put(key.toString(), JSONObject.NULL)
                        else -> obj.put(key.toString(), value)
                    }
                }
                obj.toString()
            }
            is List<*> -> {
                val arr = JSONArray()
                for (item in result) arr.put(item)
                arr.toString()
            }
            else -> result.toString()
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
