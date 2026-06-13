package io.legado.engine.shim

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

/**
 * GSON shim - replaces Legado's GSON utils
 */
val GSON: Gson = GsonBuilder().create()
val GSONStrict: Gson = GsonBuilder().create()

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
