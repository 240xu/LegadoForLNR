package io.legado.engine.data.rule

import com.google.gson.JsonDeserializer
import io.legado.engine.shim.INITIAL_GSON

data class ReviewRule(
    var reviewUrl: String? = null,
    var avatarRule: String? = null,
    var contentRule: String? = null,
    var postTimeRule: String? = null,
    var reviewQuoteUrl: String? = null,
    var voteUpUrl: String? = null,
    var voteDownUrl: String? = null,
    var postReviewUrl: String? = null,
    var postQuoteUrl: String? = null,
    var deleteUrl: String? = null
) {
    companion object {
        val jsonDeserializer = JsonDeserializer<ReviewRule?> { json, _, _ ->
            when {
                json.isJsonObject -> INITIAL_GSON.fromJson(json, ReviewRule::class.java)
                json.isJsonPrimitive -> INITIAL_GSON.fromJson(json.asString, ReviewRule::class.java)
                else -> null
            }
        }
    }
}