package io.legado.engine.data

import io.legado.engine.shim.GSON
import io.legado.engine.shim.fromJsonObject

data class SearchBook(
    override var bookUrl: String = "",
    var origin: String = "",
    var originName: String = "",
    override var name: String = "",
    override var author: String = "",
    override var kind: String? = null,
    var coverUrl: String? = null,
    var intro: String? = null,
    override var wordCount: String? = null,
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
    /** 对齐 lyc486: 变量 JSON 字符串 */
    override var variable: String? = null,
) : BaseBook {

    /**
     * 对齐 lyc486: lazy 从 variable JSON 反序列化
     */
    @delegate:Transient
    override val variableMap: HashMap<String, String> by lazy {
        GSON.fromJsonObject<HashMap<String, String>>(variable) ?: hashMapOf()
    }

    fun toBook(): Book = Book(
        bookUrl = bookUrl,
        name = name,
        author = author,
        kind = kind,
        coverUrl = coverUrl,
        intro = intro,
        latestChapterTitle = latestChapterTitle,
        wordCount = wordCount,
        origin = origin,
        originName = originName,
        originOrder = originOrder,
        tag = tag,
        tocUrl = tocUrl,
        variable = variable,
    )

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
                tocUrl = book.tocUrl,
                variable = book.variable,
            )
        }
    }
}