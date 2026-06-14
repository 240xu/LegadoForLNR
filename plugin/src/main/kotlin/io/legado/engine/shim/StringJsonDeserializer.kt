package io.legado.engine.shim

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

class StringJsonDeserializer : JsonDeserializer<String?> {

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext?
    ): String? {
        return when {
            json.isJsonPrimitive -> json.asString
            json.isJsonNull -> null
            else -> json.toString()
        }
    }
}