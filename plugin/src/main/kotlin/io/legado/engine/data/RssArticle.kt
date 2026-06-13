package io.legado.engine.data

data class RssArticle(
    var origin: String = "",
    var title: String = "",
    var content: String? = null,
    var link: String = "",
    var pubDate: String? = null,
    var description: String? = null,
    var image: String? = null,
    var variableMap: HashMap<String, String> = hashMapOf()
)
