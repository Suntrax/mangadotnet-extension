package com.blissless.oni_extension_template

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlin.collections.iterator

class ScraperProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.blissless.oni_extension_template.provider"
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
                val animeName = uri.getQueryParameter("manga")
                val anilistId = uri.getQueryParameter("anilistId")
                val cursor = MatrixCursor(arrayOf("data"))

                try {
                    // Call your scraper logic here
                    val result = TemplateScraper.scrape(context!!, animeName, anilistId)

                    if (result.isEmpty()) {
                        cursor.addRow(arrayOf("{\"error\":\"No results found.\"}"))
                    } else {
                        // Detect if result is a Map (Episodes) or List (Flat Magnets)
                        val json = if (result is Map<*, *>) {
                            val obj = JSONObject()
                            for ((key, value) in result) {
                                obj.put(key.toString(), JSONObject(value as Map<*, *>))
                            }
                            obj.toString()
                        } else {
                            val arr = JSONArray()
                            for (item in (result as List<*>)) arr.put(item.toString())
                            arr.toString()
                        }
                        cursor.addRow(arrayOf(json))
                    }
                } catch (e: Exception) {
                    cursor.addRow(arrayOf("{\"error\":\"Scraping failed: ${e.message}\"}"))
                }
                return cursor
            }
        }
        return null
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
