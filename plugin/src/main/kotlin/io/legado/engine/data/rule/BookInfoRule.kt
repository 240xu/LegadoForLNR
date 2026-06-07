package io.legado.engine.data.rule

import com.google.gson.JsonDeserializer
import io.legado.engine.shim.INITIAL_GSON

data class BookInfoRule(
    var init: String? = null,
    var name: String? = null,
    var author: String? = null,
    var intro: String? = null,
    var kind: String? = null,
    var lastChapter: String? = null,
    var updateTime: String? = null,
    var coverUrl: String? = null,
    var tocUrl: String? = null,
    var wordCount: String? = null,
    var canReName: String? = null,
    var downloadUrls: String? = null
) : io.legado.engine.data.RuleData() {
    companion object {
        val jsonDeserializer = JsonDeserializer<BookInfoRule?> { json, _, _ ->
            when {
                json.isJsonObject -> INITIAL_GSON.fromJson(json, BookInfoRule::class.java)
                json.isJsonPrimitive -> INITIAL_GSON.fromJson(json.asString, BookInfoRule::class.java)
                else -> null
            }
        }
    }
}