package io.legado.engine.data

import io.legado.engine.data.rule.BookInfoRule
import io.legado.engine.data.rule.ContentRule
import io.legado.engine.data.rule.ExploreRule
import io.legado.engine.data.rule.ReviewRule
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

    /**
     * 执行事件回调 - 对应 Legado 事件监听系统
     * @param event 事件名称 (如 "clickBookName", "clickCustomButton")
     * @param result 事件对应内容
     * @return true 表示事件已被消费，不需要执行默认操作
     */
    fun executeCallback(event: String, result: String = ""): Boolean {
        if (!eventListener) return false
        val callbackJs = getContentRule().callBackJs?.takeIf { it.isNotBlank() } ?: return false
        return try {
            val eventJson = com.google.gson.Gson().toJson(event)
            val resultJson = com.google.gson.Gson().toJson(result)
            val js = "(function(){\nvar event = $eventJson;\nvar result = $resultJson;\n$callbackJs\n})()"
            val evalResult = evalJS(js)
            evalResult == true || evalResult?.toString() == "true"
        } catch (_: Exception) { false }
    }

    fun getExploreKinds(): List<ExploreKind> {
        val kinds = mutableListOf<ExploreKind>()
        exploreUrl?.let { url ->
            url.split("(&&|\n)".toRegex()).forEach { part ->
                val name = part.substringBefore("{").substringBefore("::").trim()
                val urlStr = part
                if (name.isNotBlank()) {
                    kinds.add(ExploreKind(name, urlStr))
                }
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
