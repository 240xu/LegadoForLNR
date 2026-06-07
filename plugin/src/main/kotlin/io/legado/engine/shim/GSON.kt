package io.legado.engine.shim

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import io.legado.engine.data.rule.BookInfoRule
import io.legado.engine.data.rule.ContentRule
import io.legado.engine.data.rule.ExploreRule
import io.legado.engine.data.rule.ReviewRule
import io.legado.engine.data.rule.SearchRule
import io.legado.engine.data.rule.TocRule

val INITIAL_GSON: Gson by lazy {
    GsonBuilder()
        .registerTypeAdapter(
            object : TypeToken<Map<String?, Any?>?>() {}.type,
            MapDeserializerDoubleAsIntFix()
        )
        .registerTypeAdapter(Int::class.java, IntJsonDeserializer())
        .registerTypeAdapter(String::class.java, StringJsonDeserializer())
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .disableHtmlEscaping()
        .create()
}

val GSON: Gson by lazy {
    INITIAL_GSON.newBuilder()
        .registerTypeAdapter(ExploreRule::class.java, ExploreRule.jsonDeserializer)
        .registerTypeAdapter(SearchRule::class.java, SearchRule.jsonDeserializer)
        .registerTypeAdapter(BookInfoRule::class.java, BookInfoRule.jsonDeserializer)
        .registerTypeAdapter(TocRule::class.java, TocRule.jsonDeserializer)
        .registerTypeAdapter(ContentRule::class.java, ContentRule.jsonDeserializer)
        .registerTypeAdapter(ReviewRule::class.java, ReviewRule.jsonDeserializer)
        .create()
}

val GSONStrict: Gson by lazy {
    GSON.newBuilder()
        .setStrictness(Strictness.STRICT)
        .create()
}

inline fun <reified T> Gson.fromJsonObject(json: String?): T? {
    if (json.isNullOrBlank()) return null
    return try {
        fromJson(json, object : TypeToken<T>() {}.type)
    } catch (_: Exception) {
        null
    }
}

inline fun <reified T> Gson.fromJsonArray(json: String?): List<T>? {
    if (json.isNullOrBlank()) return null
    return try {
        fromJson(json, object : TypeToken<List<T>>() {}.type)
    } catch (_: Exception) {
        null
    }
}