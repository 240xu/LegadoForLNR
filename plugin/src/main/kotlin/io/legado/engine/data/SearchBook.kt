package io.legado.engine.data

data class SearchBook(
    override var bookUrl: String = "",
    var origin: String = "",
    var originName: String = "",
    var name: String = "",
    var author: String = "",
    var kind: String? = null,
    var coverUrl: String? = null,
    var intro: String? = null,
    var wordCount: String? = null,
    var latestChapterTitle: String? = null,
    override var tocUrl: String = "",
    var time: Long = System.currentTimeMillis(),
    var originOrder: Int = 0,
    var tag: String? = null,
    var chapterWordCountText: String? = null,
    var chapterWordCount: Int = -1,
    var respondTime: Int = -1,
    override var type: Int = 0,
    override var order: Int = 0,
    override var imageStyle: String? = null,
    override var durChapterIndex: Int = 0,
    override var infoHtml: String? = null,
    override var tocHtml: String? = null,
    override var variableMap: HashMap<String, String> = hashMapOf(),
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
