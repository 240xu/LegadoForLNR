package io.legado.engine.data

data class BookChapter(
    var index: Int = 0,
    var bookUrl: String = "",
    var url: String = "",
    var title: String = "",
    var isVolume: Boolean = false,
    var isVip: Boolean = false,
    var isPay: Boolean = false,
    var baseUrl: String = "",
    var bookHtml: String? = null,
    var tag: String? = null,
    var start: Long? = null,
    var end: Long? = null,
    var wordCount: String? = null,
    var updateTime: Long = 0,
    var variableMap: HashMap<String, String>? = null
) {
    fun putVariable(key: String, value: String) {
        if (variableMap == null) variableMap = hashMapOf()
        variableMap!![key] = value
    }

    fun putVariable(value: String) {
        putVariable("", value)
    }

    fun getVariable(key: String): String {
        return variableMap?.get(key) ?: ""
    }

    fun getVariable(): String {
        return getVariable("")
    }
}
