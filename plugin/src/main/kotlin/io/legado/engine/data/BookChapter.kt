package io.legado.engine.data

import io.legado.engine.shim.CacheManager
import io.legado.engine.shim.GSON
import io.legado.engine.shim.fromJsonObject

data class BookChapter(
    var url: String = "",
    var title: String = "",
    var isVolume: Boolean = false,
    var baseUrl: String = "",
    var bookUrl: String = "",
    var index: Int = 0,
    var isVip: Boolean = false,
    var isPay: Boolean = false,
    var resourceUrl: String? = null,
    var tag: String? = null,
    var wordCount: String? = null,
    var start: Long? = null,
    var end: Long? = null,
    var startFragmentId: String? = null,
    var endFragmentId: String? = null,
    var bookHtml: String? = null,
    var imgUrl: String? = null,
    /** 对齐 lyc486: 变量 JSON 字符串 */
    var variable: String? = null,
) {
    /**
     * 对齐 lyc486: lazy 从 variable JSON 反序列化
     */
    @delegate:Transient
    val variableMap: HashMap<String, String> by lazy {
        GSON.fromJsonObject<HashMap<String, String>>(variable) ?: hashMapOf()
    }

    /** 对齐 lyc486 displayTitle（简化版：直接返回 title） */
    val displayTitle: String get() = title

    val titleMD5: String? get() = null // 由宿主按需计算

    /**
     * 对齐 lyc486: putVariable 后同步写回 variable
     */
    fun putVariable(key: String, value: String?) {
        if (value == null) {
            variableMap.remove(key)
            putBigVariable(key, null)
        } else if (value.length < 10000) {
            putBigVariable(key, null)
            variableMap[key] = value
        } else {
            variableMap.remove(key)
            putBigVariable(key, value)
        }
        variable = GSON.toJson(variableMap)
    }

    fun putVariable(value: String?) = putVariable("", value)

    fun getVariable(key: String): String {
        return variableMap[key] ?: getBigVariable(key) ?: ""
    }

    fun getVariableValue(): String = getVariable("")

    /** 对齐 lyc486: 存入歌词文本 */
    fun putLyric(value: String?) {
        putVariable("lyric", value)
    }

    /** 对齐 lyc486: 存入弹幕文本 */
    fun putDanmaku(value: String?) {
        putVariable("danmaku", value)
    }

    fun putImgUrl(value: String?) {
        imgUrl = value
    }

    /** 大变量存储 key（按 bookUrl+url+key 隔离） */
    private fun bigVarKey(key: String): String = "bv_${bookUrl}_${url}_$key"

    fun putBigVariable(key: String, value: String?) {
        if (value == null) {
            CacheManager.delete(bigVarKey(key))
        } else {
            CacheManager.put(bigVarKey(key), value)
        }
    }

    fun getBigVariable(key: String): String? {
        return CacheManager.get(bigVarKey(key))
    }

    /** 对齐 lyc486: 绝对 URL 拼接 */
    fun getAbsoluteURL(): String {
        if (url.startsWith(title) && isVolume) return baseUrl
        return io.legado.engine.rule.AnalyzeUrl.getAbsoluteURL(baseUrl, url)
    }
}