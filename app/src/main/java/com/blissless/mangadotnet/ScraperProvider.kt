package com.blissless.mangadotnet

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

class ScraperProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.blissless.mangadotnet.provider"
        const val PATH_SCRAPE = "scrape"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SCRAPE")
        private const val CODE_SCRAPES = 1
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
                val cursor = MatrixCursor(arrayOf("data"))

                try {
                    val result = MangadotnetScraper.scrape(context!!, mangaName, anilistId)

                    val json = serializeResult(result)
                    cursor.addRow(arrayOf(json))
                } catch (e: Exception) {
                    cursor.addRow(arrayOf("{\"error\":\"Scraping failed: ${e.message?.replace("\"", "\\\"")}\"}"))
                }
                return cursor
            }
        }
        return null
    }

    /**
     * Serializes the scraper result to JSON. Supports:
     *   - Map<String, List<*>>      -> {"key": [...], ...}   (chapter -> image urls)
     *   - Map<String, Map<String,*>>-> {"key": {...}, ...}   (episode -> quality -> url)
     *   - Map<String, String>       -> {"key": "...", ...}   (error object)
     *   - List<*>                   -> ["...", "..."]        (flat list)
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
