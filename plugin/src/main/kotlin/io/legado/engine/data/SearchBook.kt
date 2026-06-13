package io.legado.engine.data

data class SearchBook(
    override var bookUrl: String = "",
    var name: String = "",
    var author: String = "",
    var kind: String? = null,
    var coverUrl: String? = null,
    var intro: String? = null,
    var latestChapterTitle: String? = null,
    var wordCount: String? = null,
    var origin: String = "",
    var originName: String = "",
    var originOrder: Int = 0,
    var tag: String? = null,
    override var tocUrl: String? = null,
    override var type: Int = 0,
    override var order: Int = 0,
    override var imageStyle: String? = null,
    override var durChapterIndex: Int = 0,
    override var variableMap: HashMap<String, String> = hashMapOf(),
    var time: Long = 0
) : BaseBook {
    companion object {
        fun fromBook(book: Book): SearchBook {
            return SearchBook(
                bookUrl = book.bookUrl,
                name = book.name,
                author = book.author,
                kind = book.kind,
                coverUrl = book.coverUrl,
                intro = book.intro,
                latestChapterTitle = book.latestChapterTitle,
                wordCount = book.wordCount,
                origin = book.origin,
                originName = book.originName,
                originOrder = book.originOrder,
                tag = book.tag,
                tocUrl = book.tocUrl
            )
        }
    }
}
