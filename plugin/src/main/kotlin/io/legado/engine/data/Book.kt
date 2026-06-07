package io.legado.engine.data

data class Book(
    override var bookUrl: String = "",
    override var tocUrl: String = "",
    var origin: String = "",
    var originName: String = "",
    var name: String = "",
    var author: String = "",
    var kind: String? = null,
    var customTag: String? = null,
    var coverUrl: String? = null,
    var customCoverUrl: String? = null,
    var intro: String? = null,
    var customIntro: String? = null,
    var charset: String? = null,
    var latestChapterTitle: String? = null,
    var latestChapterTime: Long = System.currentTimeMillis(),
    var lastCheckTime: Long = System.currentTimeMillis(),
    var lastCheckCount: Int = 0,
    var isNewChapter: Boolean = false,
    var totalChapterNum: Int = 0,
    var durChapterTitle: String? = null,
    override var durChapterIndex: Int = 0,
    var durVolumeIndex: Int = 0,
    var chapterInVolumeIndex: Int = 0,
    var durChapterPos: Int = 0,
    var durChapterPage: Int = 0,
    var durChapterTime: Long = System.currentTimeMillis(),
    var group: Long = 0,
    var tag: String? = null,
    var originOrder: Int = 0,
    var canUpdate: Boolean = true,
    var useReplaceRule: Boolean = true,
    var wordCount: String? = null,
    override var type: Int = 0,
    override var order: Int = 0,
    override var imageStyle: String? = null,
    var syncTime: Long = 0L,
    var readConfig: String? = null,
    override var infoHtml: String? = null,
    override var tocHtml: String? = null,
    var downloadUrls: List<String>? = null,
    override var variableMap: HashMap<String, String> = hashMapOf(),
) : BaseBook {

    fun getBookSource(): String = origin

    companion object {
        fun fromSearchBook(searchBook: SearchBook): Book {
            return Book(
                bookUrl = searchBook.bookUrl,
                name = searchBook.name,
                author = searchBook.author,
                kind = searchBook.kind,
                coverUrl = searchBook.coverUrl,
                intro = searchBook.intro,
                latestChapterTitle = searchBook.latestChapterTitle,
                wordCount = searchBook.wordCount,
                origin = searchBook.origin,
                originName = searchBook.originName,
                originOrder = searchBook.originOrder,
                tag = searchBook.tag,
                tocUrl = searchBook.tocUrl
            )
        }
    }
}
