package io.legado.engine.data.rule

import com.google.gson.JsonDeserializer
import io.legado.engine.shim.INITIAL_GSON

data class ContentRule(
    var content: String? = null,
    var subContent: String? = null,
    var title: String? = null,
    var nextContentUrl: String? = null,
    var webJs: String? = null,
    var sourceRegex: String? = null,
    var replaceRegex: String? = null,
    var imageStyle: String? = null,
    var imageDecode: String? = null,
    var payAction: String? = null,
    var callBackJs: String? = null
) : io.legado.engine.data.RuleData() {
    companion object {
        val jsonDeserializer = JsonDeserializer<ContentRule?> { json, _, _ ->
            when {
                json.isJsonObject -> INITIAL_GSON.fromJson(json, ContentRule::class.java)
                json.isJsonPrimitive -> INITIAL_GSON.fromJson(json.asString, ContentRule::class.java)
                else -> null
            }
        }
    }
}