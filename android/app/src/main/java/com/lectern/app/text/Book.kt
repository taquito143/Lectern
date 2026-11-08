package com.lectern.app.text

import org.json.JSONArray
import org.json.JSONObject

data class Section(val title: String, val paras: List<String>)

data class Book(val title: String, val sections: List<Section>) {
    fun toJson(): String = JSONObject().apply {
        put("title", title)
        put("sections", JSONArray().apply {
            sections.forEach { s ->
                put(JSONObject().apply {
                    put("title", s.title)
                    put("paras", JSONArray(s.paras))
                })
            }
        })
    }.toString()

    companion object {
        fun fromJson(s: String): Book {
            val o = JSONObject(s)
            val arr = o.getJSONArray("sections")
            val sections = (0 until arr.length()).map { i ->
                val so = arr.getJSONObject(i)
                val p = so.getJSONArray("paras")
                Section(so.getString("title"), (0 until p.length()).map { p.getString(it) })
            }
            return Book(o.getString("title"), sections)
        }
    }
}

/** One speakable unit (usually a sentence). [para] is the paragraph index inside its section. */
data class Sentence(val text: String, val section: Int, val para: Int, val last: Boolean)
