package com.lectern.app.data

import android.content.Context
import com.lectern.app.text.Book
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibraryEntry(
    val id: String,
    val title: String,
    val uri: String?,
    val total: Int,
    val pos: Int,
    val updated: Long,
)

/** Small persistent state: settings, reading positions, the library list and parsed books. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("lectern", Context.MODE_PRIVATE)
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }

    fun getString(key: String, def: String) = prefs.getString(key, def) ?: def
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun getFloat(key: String, def: Float) = prefs.getFloat(key, def)
    fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()

    fun position(bookId: String) = prefs.getInt("pos:$bookId", 0)

    fun savePosition(bookId: String, pos: Int, total: Int) {
        prefs.edit().putInt("pos:$bookId", pos).apply()
        library().find { it.id == bookId }?.let { upsert(it.copy(pos = pos, total = total, updated = System.currentTimeMillis())) }
    }

    fun library(): List<LibraryEntry> {
        val arr = JSONArray(prefs.getString("library", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LibraryEntry(
                o.getString("id"), o.getString("title"), o.optString("uri").ifEmpty { null },
                o.optInt("total"), o.optInt("pos"), o.optLong("updated"),
            )
        }.sortedByDescending { it.updated }
    }

    fun upsert(e: LibraryEntry) {
        val list = library().filter { it.id != e.id } + e
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("uri", it.uri ?: "")
                .put("total", it.total).put("pos", it.pos).put("updated", it.updated))
        }
        prefs.edit().putString("library", arr.toString()).apply()
    }

    fun remove(id: String) {
        val arr = JSONArray()
        library().filter { it.id != id }.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("uri", it.uri ?: "")
                .put("total", it.total).put("pos", it.pos).put("updated", it.updated))
        }
        prefs.edit().putString("library", arr.toString()).remove("pos:$id").apply()
        File(booksDir, "$id.json").delete()
    }

    fun saveBook(id: String, book: Book) = File(booksDir, "$id.json").writeText(book.toJson())

    fun loadBook(id: String): Book? = File(booksDir, "$id.json").takeIf { it.isFile }?.let {
        runCatching { Book.fromJson(it.readText()) }.getOrNull()
    }
}
