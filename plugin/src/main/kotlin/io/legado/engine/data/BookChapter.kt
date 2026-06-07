package io.legado.engine.data

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
    var variableMap: HashMap<String, String>? = null
) {
    val displayTitle: String get() = title
    val titleMD5: String? get() = null // 由宿主按需计算

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
