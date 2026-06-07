package io.legado.engine.data

import io.legado.engine.shim.CacheManager
import io.legado.engine.shim.GSON
import io.legado.engine.shim.fromJsonObject

interface BaseBook : RuleDataInterface {
    var name: String
    var author: String
    var bookUrl: String
    var tocUrl: String
    var kind: String?
    var wordCount: String?
    var type: Int
    var order: Int
    var imageStyle: String?
    var durChapterIndex: Int
    /** 对齐 lyc486: 变量 JSON 字符串，由 variableMap lazy 反序列化 */
    var variable: String?

    var infoHtml: String?
    var tocHtml: String?

    /**
     * 对齐 lyc486: putVariable 后同步写回 variable = GSON.toJson(variableMap)
     */
    override fun putVariable(key: String, value: String?): Boolean {
        val result = super.putVariable(key, value)
        variable = GSON.toJson(variableMap)
        return result
    }

    override fun putVariable(value: String?): Boolean = putVariable("", value)

    fun putCustomVariable(value: String?) {
        putVariable("custom", value)
    }

    fun getCustomVariable(): String {
        return getVariable("custom")
    }

    override fun putBigVariable(key: String, value: String?) {
        if (value == null) {
            CacheManager.delete("bv_${bookUrl}_$key")
        } else {
            CacheManager.put("bv_${bookUrl}_$key", value)
        }
    }

    override fun getBigVariable(key: String): String? {
        return CacheManager.get("bv_${bookUrl}_$key")
    }

    fun getKindList(): List<String> {
        val kindList = arrayListOf<String>()
        wordCount?.let {
            if (it.isNotBlank()) kindList.add(it)
        }
        kind?.let {
            val kinds = it.split(",", "\n").map { s -> s.trim() }.filter { s -> s.isNotBlank() }
            kindList.addAll(kinds)
        }
        return kindList
    }
}