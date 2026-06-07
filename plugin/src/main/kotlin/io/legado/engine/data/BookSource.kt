package io.legado.engine.data

import io.legado.engine.data.rule.BookInfoRule
import io.legado.engine.data.rule.ContentRule
import io.legado.engine.data.rule.ExploreRule
import io.legado.engine.data.rule.ReviewRule
import io.legado.engine.shim.fromJsonArray
import io.legado.engine.data.rule.SearchRule
import io.legado.engine.data.rule.TocRule

data class BookSource(
    var bookSourceUrl: String = "",
    var bookSourceName: String = "",
    var bookSourceGroup: String? = null,
    var bookSourceType: Int = 0,
    var bookUrlPattern: String? = null,
    var customOrder: Int = 0,
    var enabled: Boolean = true,
    var enabledExplore: Boolean = true,
    override var jsLib: String? = null,
    override var enabledCookieJar: Boolean? = true,
    override var concurrentRate: String? = null,
    override var header: String? = null,
    override var loginUrl: String? = null,
    override var loginUi: String? = null,
    var loginCheckJs: String? = null,
    var coverDecodeJs: String? = null,
    var bookSourceComment: String? = null,
    var variableComment: String? = null,
    var lastUpdateTime: Long = 0,
    var respondTime: Long = 180000L,
    var weight: Int = 0,
    var exploreUrl: String? = null,
    var exploreScreen: String? = null,
    var searchUrl: String? = null,
    var ruleExplore: ExploreRule? = null,
    var ruleSearch: SearchRule? = null,
    var ruleBookInfo: BookInfoRule? = null,
    var ruleToc: TocRule? = null,
    var ruleContent: ContentRule? = null,
    var ruleReview: ReviewRule? = null,
    var eventListener: Boolean = false,
    var customButton: Boolean = false
) : BaseSource {

    override fun getTag(): String = bookSourceName.ifBlank { bookSourceUrl }
    override fun getKey(): String = bookSourceUrl

    fun getSearchRule() = ruleSearch ?: SearchRule().also { ruleSearch = it }
    fun getExploreRule() = ruleExplore ?: ExploreRule().also { ruleExplore = it }
    fun getBookInfoRule() = ruleBookInfo ?: BookInfoRule().also { ruleBookInfo = it }
    fun getTocRule() = ruleToc ?: TocRule().also { ruleToc = it }
    fun getContentRule() = ruleContent ?: ContentRule().also { ruleContent = it }
    fun getReviewRule() = ruleReview ?: ReviewRule().also { ruleReview = it }

    fun getExploreKinds(): List<ExploreKind> {
        val kinds = mutableListOf<ExploreKind>()
        val raw = exploreUrl ?: return kinds
        if (raw.isBlank()) return kinds
        val ruleStr = try {
            when {
                raw.startsWith("@js:", true) -> evalJS(raw.substring(4))?.toString()?.trim() ?: raw
                raw.startsWith("<js>", true) -> evalJS(raw.substring(4, raw.lastIndexOf("<")))?.toString()?.trim() ?: raw
                else -> raw
            }
        } catch (_: Exception) { raw }
        if (ruleStr.trimStart().startsWith("[")) {
            try {
                @Suppress("UNCHECKED_CAST")
                val arr = io.legado.engine.shim.GSON.fromJsonArray<ExploreKind>(ruleStr)
                if (arr != null) { kinds.addAll(arr); return kinds }
            } catch (_: Exception) {}
        }
        ruleStr.split("(&&|\n)+".toRegex()).forEach { kindStr ->
            val parts = kindStr.split("::")
            val name = parts.first().trim()
            if (name.isNotBlank()) {
                kinds.add(ExploreKind(name, parts.getOrNull(1)?.trim() ?: ""))
            }
        }
        return kinds
    }

    fun equals(source: BookSource): Boolean {
        return bookSourceUrl == source.bookSourceUrl
                && bookSourceName == source.bookSourceName
                && bookSourceGroup == source.bookSourceGroup
                && bookSourceType == source.bookSourceType
                && loginUrl == source.loginUrl
                && loginUi == source.loginUi
                && searchUrl == source.searchUrl
                && exploreUrl == source.exploreUrl
    }
}

data class ExploreKind(
    var title: String = "",
    var url: String = "",
    var type: String = "url",
    var action: String? = null,
    var chars: Array<String?>? = null,
    var default: String? = null,
    var viewName: String? = null,
    var style: Any? = null
)
