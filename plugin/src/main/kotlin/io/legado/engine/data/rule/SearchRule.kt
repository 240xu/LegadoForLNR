package io.legado.engine.data.rule

import com.google.gson.JsonDeserializer
import io.legado.engine.shim.INITIAL_GSON

data class SearchRule(
    var checkKeyWord: String? = null,
    override var bookList: String? = null,
    override var name: String? = null,
    override var author: String? = null,
    override var intro: String? = null,
    override var kind: String? = null,
    override var lastChapter: String? = null,
    override var updateTime: String? = null,
    override var bookUrl: String? = null,
    override var coverUrl: String? = null,
    override var wordCount: String? = null
) : BookListRule {
    companion object {
        val jsonDeserializer = JsonDeserializer<SearchRule?> { json, _, _ ->
            when {
                json.isJsonObject -> INITIAL_GSON.fromJson(json, SearchRule::class.java)
                json.isJsonPrimitive -> INITIAL_GSON.fromJson(json.asString, SearchRule::class.java)
                else -> null
            }
        }
    }
}