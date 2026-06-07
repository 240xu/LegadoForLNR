package io.legado.engine.rule

import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.engine.constant.AppPattern
import io.legado.engine.data.BaseBook
import io.legado.engine.data.BaseSource
import io.legado.engine.data.BookChapter
import io.legado.engine.data.RssArticle
import io.legado.engine.data.RuleDataInterface
import io.legado.engine.data.Book
import io.legado.engine.data.BookSource
import io.legado.engine.webBook.WebBook
import io.legado.engine.http.CookieStore
import io.legado.engine.js.JsExtensions
import io.legado.engine.js.SharedJsScope
import io.legado.engine.shim.CacheManager
import io.legado.engine.shim.Debug
import io.legado.engine.shim.GSON
import io.legado.engine.shim.fromJsonArray
import io.legado.engine.shim.fromJsonObject
import org.apache.commons.text.StringEscapeUtils
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import com.script.rhino.CompiledScript
import org.mozilla.javascript.Scriptable
import org.jsoup.nodes.Node
import java.lang.ref.WeakReference
import java.net.URL

class AnalyzeRule(
    private var ruleData: RuleDataInterface? = null,
    private val source: BaseSource? = null,
    private val preUpdateJs: Boolean = false,
) : JsExtensions {

    private val book get() = ruleData as? BaseBook
    private val rssArticle get() = ruleData as? RssArticle
    private var isFromBookInfo: Boolean = false
    private var chapter: BookChapter? = null
    private var nextChapterUrl: String? = null
    private var content: Any? = null
    private var baseUrl: String? = null
    private var redirectUrl: URL? = null
    private var isJSON: Boolean = false
    private var isRegex: Boolean = false
    private var ruleName: String? = null

    private var analyzeByXPath: AnalyzeByXPath? = null
    private var analyzeByJSoup: AnalyzeByJSoup? = null
    private var analyzeByJSonPath: AnalyzeByJSonPath? = null

    private val stringRuleCache = hashMapOf<String, List<SourceRule>>()
    private val regexCache = hashMapOf<String, Regex?>()
    private val scriptCache = hashMapOf<String, CompiledScript>()
    private var evalJSCallCount = 0
    private var coroutineContext: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext

    fun setRuleName(name: String) { if (name.isNotBlank()) ruleName = name }

    fun setContent(content: Any?, baseUrl: String? = null): AnalyzeRule {
        if (content == null) throw AssertionError("Content cannot be null")
        this.content = content
        isJSON = when (content) {
            is Node -> false
            else -> content.toString().trim().let {
                (it.startsWith("{") && it.endsWith("}")) || (it.startsWith("[") && it.endsWith("]"))
            }
        }
        setBaseUrl(baseUrl)
        analyzeByXPath = null; analyzeByJSoup = null; analyzeByJSonPath = null
        return this
    }

    fun setBaseUrl(url: String?): AnalyzeRule { url?.let { this.baseUrl = it }; return this }
    fun setRedirectUrl(url: String): URL? { try { redirectUrl = URL(url) } catch (_: Exception) {}; return redirectUrl }
    fun setNextChapterUrl(url: String?): AnalyzeRule { nextChapterUrl = url; return this }
    fun setChapter(ch: BookChapter?): AnalyzeRule { chapter = ch; return this }
    fun setFromBookInfo(value: Boolean): AnalyzeRule { isFromBookInfo = value; return this }

    private fun getAnalyzeByXPath(o: Any): AnalyzeByXPath =
        if (o != content) AnalyzeByXPath(o) else (analyzeByXPath ?: AnalyzeByXPath(content!!).also { analyzeByXPath = it })
    private fun getAnalyzeByJSoup(o: Any): AnalyzeByJSoup =
        if (o != content) AnalyzeByJSoup(o) else (analyzeByJSoup ?: AnalyzeByJSoup(content!!).also { analyzeByJSoup = it })
    private fun getAnalyzeByJSonPath(o: Any): AnalyzeByJSonPath =
        if (o != content) AnalyzeByJSonPath(o) else (analyzeByJSonPath ?: AnalyzeByJSonPath(content!!).also { analyzeByJSonPath = it })

    private fun splitSourceRuleCacheString(rule: String?): List<SourceRule> {
        if (rule.isNullOrEmpty()) return emptyList()
        return splitSourceRule(rule)
    }

    /**
     * Split rule string into rule list (with allInOne parameter)
     */
    fun splitSourceRule(ruleStr: String?, allInOne: Boolean = false): List<SourceRule> {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        val ruleList = ArrayList<SourceRule>()
        var mMode: Mode = Mode.Default
        var start = 0
        if (allInOne && ruleStr.startsWith(":")) {
            mMode = Mode.Regex; isRegex = true; start = 1
        } else if (isRegex) { mMode = Mode.Regex }
        var tmp: String
        val jsMatcher = AppPattern.JS_PATTERN.matcher(ruleStr)
        while (jsMatcher.find()) {
            if (jsMatcher.start() > start) { tmp = ruleStr.substring(start, jsMatcher.start()).trim(); if (tmp.isNotEmpty()) ruleList.add(SourceRule(tmp, mMode)) }
            ruleList.add(SourceRule(jsMatcher.group(), mMode)); start = jsMatcher.end()
        }
        if (start < ruleStr.length) { tmp = ruleStr.substring(start).trim(); if (tmp.isNotEmpty()) ruleList.add(SourceRule(tmp, mMode)) }
        return ruleList
    }

    private fun putRule(putMap: Map<String, String>?) { putMap?.forEach { (k, v) -> put(k, v) } }

    private fun replaceRegex(result: String, sr: SourceRule): String {
        if (sr.replaceRegex.isEmpty()) return result
        return try {
            val regex = regexCache.getOrPut(sr.replaceRegex) { Regex(sr.replaceRegex) } ?: Regex(sr.replaceRegex)
            if (sr.replaceFirst) {
                val matcher = regex.toPattern().matcher(result)
                if (matcher.find()) matcher.group(0)!!.replaceFirst(regex, sr.replacement) else ""
            } else regex.replace(result, sr.replacement)
        } catch (_: Exception) { result }
    }

    fun getStringList(rule: String?, mContent: Any? = null, isUrl: Boolean = false): List<String>? {
        if (rule.isNullOrEmpty()) return null
        return getStringList(splitSourceRuleCacheString(rule), mContent, isUrl)
    }

    fun getStringList(ruleList: List<SourceRule>, mContent: Any? = null, isUrl: Boolean = false): List<String>? {
        var result: Any? = null
        val c = mContent ?: this.content ?: return null
        if (ruleList.isEmpty()) return null
        result = c
        if (ruleList.size == 1) {
            val single = ruleList.first()
            getDirectValue(result, single.rule)?.let { return listOf(replaceRegex(it, single)) }
        }
        for (sr in ruleList) {
            putRule(sr.putMap); sr.makeUpRule(this, result); result ?: continue
            val r = sr.rule
            if (r.isNotEmpty()) {
                result = when (sr.mode) {
                    Mode.Js -> evalJS(r, result)
                    Mode.WebJs -> { val webResult = getWebJsResult(r, result as Any); try { val arr = GSON.fromJsonArray<String>(webResult); if (!arr.isNullOrEmpty()) arr else listOf(webResult) } catch (_: Exception) { webResult.split("\n") } }
                    Mode.Json -> getAnalyzeByJSonPath(result).getStringList(r)
                        .ifEmpty { getDirectValue(result, r)?.let { listOf(it) } ?: emptyList() }
                    Mode.XPath -> getAnalyzeByXPath(result).getStringList(r)
                    Mode.Regex -> { val rr = AnalyzeByRegex.getElement(result.toString(), r.split("&&").toTypedArray()); if (rr is List<*>) rr.map { it.toString() } else listOf(rr.toString()) }
                    else -> getAnalyzeByJSoup(result).getStringList(r)
                }
            }
            if (sr.replaceRegex.isNotEmpty()) { result = when (result) { is List<*> -> result.map { replaceRegex(it.toString(), sr) }; else -> replaceRegex(result.toString(), sr) } }
        }
        if (result == null) return null
        if (result is String) result = result.split("\n")
        @Suppress("UNCHECKED_CAST") return result as? List<String>
    }

    fun getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false): String {
        if (ruleStr.isNullOrEmpty()) return ""
        return getString(splitSourceRuleCacheString(ruleStr), mContent, isUrl)
    }

    fun getStringFrom(mContent: Any?, ruleStr: String?, isUrl: Boolean = false): String {
        return getString(ruleStr, mContent, isUrl)
    }

    fun getString(ruleStr: String): String {
        return getString(ruleStr as String?, null, false)
    }

    fun getString(ruleStr: String?, unescape: Boolean): String {
        if (ruleStr.isNullOrEmpty()) return ""
        return getString(splitSourceRuleCacheString(ruleStr), null, false)
    }

    fun getString(ruleList: List<SourceRule>, mContent: Any? = null, isUrl: Boolean = false, unescape: Boolean = true): String {
        var result: Any? = null
        val c = mContent ?: this.content ?: return ""
        if (ruleList.isEmpty()) return ""
        result = c
        if (result is NativeObject) {
            val sourceRule = ruleList.first()
            putRule(sourceRule.putMap)
            sourceRule.makeUpRule(this, result)
            result = if (sourceRule.getParamSize() > 1) {
                sourceRule.rule
            } else {
                (result as NativeObject)[sourceRule.rule]?.toString()
            }?.let { replaceRegex(it, sourceRule) }
        } else if (result is com.google.gson.internal.LinkedTreeMap<*, *>) {
            result = (result as Map<*, *>)[ruleList.first().rule]?.toString()
        } else {
        for (sr in ruleList) {
            putRule(sr.putMap); sr.makeUpRule(this, result); result ?: continue
            val r = sr.rule
            if (r.isNotEmpty()) {
                result = when (sr.mode) {
                    Mode.WebJs -> getWebJsResult(r, result as Any)
                    Mode.Js -> evalJS(r, result)
                    Mode.Json -> getAnalyzeByJSonPath(result).getString(r) ?: getDirectValue(result, r)
                    Mode.XPath -> getAnalyzeByXPath(result).getString(r)
                    Mode.Regex -> { val rr = AnalyzeByRegex.getElement(result.toString(), r.split("&&").toTypedArray()); if (rr is List<*>) rr.firstOrNull()?.toString() ?: "" else rr.toString() }
                    else -> getAnalyzeByJSoup(result).getString(r)
                }
            }
            if (sr.replaceRegex.isNotEmpty()) { result = replaceRegex(result.toString(), sr) }
        }
        } // end else (not NativeObject/LinkedTreeMap)
        if (result == null) return ""
        val str = result.toString()
        if (isUrl && str.isNotBlank()) { return AnalyzeUrl.getAbsoluteURL((redirectUrl?.toString() ?: baseUrl).orEmpty(), str) }
        return StringEscapeUtils.unescapeHtml4(str)
    }

    fun getElement(ruleStr: String): Any? {
        if (ruleStr.isEmpty()) return null
        var result: Any? = content ?: return null
        val ruleList = splitSourceRule(ruleStr, true)
        if (ruleList.isEmpty()) return null
        for (sr in ruleList) {
            putRule(sr.putMap); result ?: continue
            val r = sr.rule
            result = when (sr.mode) {
                Mode.Js, Mode.WebJs -> getWebJsResult(r, result as Any)
                Mode.Json -> getAnalyzeByJSonPath(result).getObject(r)
                Mode.XPath -> getAnalyzeByXPath(result).getElements(r)
                Mode.Regex -> AnalyzeByRegex.getElement(result.toString(), r.split("&&").toTypedArray())
                else -> getAnalyzeByJSoup(result).getElements(r)
            }
        }
        return result
    }

    fun getElements(ruleStr: String?): List<Any> {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        val ruleList = splitSourceRule(ruleStr, true)
        var result: Any? = content ?: return emptyList()
        for (sr in ruleList) {
            putRule(sr.putMap); result ?: continue
            val r = sr.rule
            result = when (sr.mode) {
                Mode.Js, Mode.WebJs -> evalJS(r, result)
                Mode.Json -> getAnalyzeByJSonPath(result).getList(r)
                Mode.XPath -> getAnalyzeByXPath(result).getElements(r)
                Mode.Regex -> AnalyzeByRegex.getElements(result.toString(), r.split("&&").toTypedArray())
                else -> getAnalyzeByJSoup(result).getElements(r)
            }
        }
        return toElementList(result)
    }

    fun getObject(ruleStr: String?): Any? {
        if (ruleStr.isNullOrEmpty()) return null
        val ruleList = splitSourceRule(ruleStr)
        var result: Any? = content ?: return null
        for (sr in ruleList) {
            putRule(sr.putMap); result ?: continue
            val r = sr.rule
            result = when (sr.mode) {
                Mode.Js, Mode.WebJs -> getWebJsResult(r, result as Any)
                Mode.Json -> getAnalyzeByJSonPath(result).getObject(r)
                Mode.XPath -> getAnalyzeByXPath(result).getElements(r)
                else -> getAnalyzeByJSoup(result).getElements(r)
            }
        }
        return result
    }

    fun evalJS(jsStr: String, result: Any? = null): Any? {
        val js = unwrapJs(jsStr)
        val bindings = buildScriptBindings { b ->
            b["java"] = this; b["cookie"] = CookieStore; b["cache"] = CacheManager
            b["source"] = source; b["book"] = book; b["result"] = result
            b["baseUrl"] = baseUrl ?: ""; b["chapter"] = chapter
            b["title"] = chapter?.title ?: ""; b["src"] = content ?: ""
            b["nextChapterUrl"] = nextChapterUrl ?: ""
            b["rssArticle"] = rssArticle
            b["fromBookInfo"] = isFromBookInfo
        }
        val sharedScope = source?.let { SharedJsScope.getScope(it.jsLib) }
        val scope = if (sharedScope != null) {
            bindings.apply { prototype = sharedScope }
            RhinoScriptEngine.getRuntimeScope(bindings)
        } else {
            RhinoScriptEngine.getRuntimeScope(bindings).also {
                if (evalJSCallCount++ > 16) {
                    // Prevent excessive recursion
                }
            }
        }
        val compiled = compileScriptCache(js)
        return try { compiled.eval(scope) } catch (e: Exception) { Debug.log("evalJS error: ${e.message}"); null }
    }

    private fun compileScriptCache(jsStr: String): CompiledScript {
        return scriptCache.getOrPut(jsStr) { RhinoScriptEngine.compile(jsStr) }
    }

    override fun getSource(): BaseSource? = source
    override fun getTag(): String? = source?.getTag() ?: ruleName

    override fun ajax(url: Any): String? {
        val urlStr = url.toString()
        return try { AnalyzeUrl(urlStr, source = source, ruleData = ruleData, chapter = chapter).getStrResponse().body } catch (e: Exception) { Debug.log("ajax error: ${e.message}"); null }
    }

    fun put(key: String, value: String): String { chapter?.putVariable(key, value) ?: book?.putVariable(key, value) ?: ruleData?.putVariable(key, value) ?: source?.put(key, value); return value }
    fun get(key: String): String { return chapter?.getVariable(key)?.takeIf { it.isNotEmpty() } ?: book?.getVariable(key)?.takeIf { it.isNotEmpty() } ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() } ?: source?.get(key)?.takeIf { it.isNotEmpty() } ?: "" }
    fun put(value: String): String { chapter?.putVariable(value) ?: book?.putVariable(value) ?: ruleData?.putVariable(value) ?: source?.putVariable(value); return value }
    fun get(): String { return chapter?.getVariableValue()?.takeIf { it.isNotEmpty() } ?: book?.getVariableValue()?.takeIf { it.isNotEmpty() } ?: ruleData?.getVariableValue()?.takeIf { it.isNotEmpty() } ?: source?.getVariable().orEmpty() }

    private fun getWebJsResult(jsStr: String, result: Any): String {
        return try {
            val resp = io.legado.engine.webview.BackstageWebView.getSource(
                html = content?.toString(),
                url = baseUrl,
                js = jsStr,
                headerMap = source?.getHeaderMap(true),
                timeout = 10000
            )
            resp.body
        } catch (e: Exception) {
            Debug.log("getWebJsResult error: " + e.message)
            result.toString()
        }
    }

    private fun getDirectValue(target: Any?, rule: String): String? {
        if (target == null || rule.isBlank()) return null
        val keys = listOf(
            rule,
            rule.removePrefix("$.").removePrefix("$.."),
            rule.substringAfterLast('.')
        ).distinct().filter { it.isNotBlank() }
        return when (target) {
            is NativeObject -> keys.firstNotNullOfOrNull { key ->
                NativeObject.getProperty(target, key)
                    ?.takeUnless { it is org.mozilla.javascript.Undefined }
                    ?.toString()
            }
            is Map<*, *> -> keys.firstNotNullOfOrNull { key ->
                target[key]?.toString()
            }
            else -> null
        }
    }

    private fun toElementList(value: Any?): List<Any> {
        return when (value) {
            null -> emptyList()
            is List<*> -> value.filterNotNull()
            is Array<*> -> value.filterNotNull()
            is NativeArray -> (0 until value.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .mapNotNull { index -> value.get(index, value).takeUnless { it is org.mozilla.javascript.Undefined } }
            is Map<*, *> -> listOf(value)
            is NativeObject -> listOf(value)
            is Scriptable -> listOf(value)
            else -> emptyList()
        }
    }

    /**
     * lyc486 SourceRule pattern: uses evalPattern to split rules into
     * ruleParam/ruleType pairs, where ruleType encodes the substitution type.
     */
    inner class SourceRule(ruleStr: String, initialMode: Mode = Mode.Default) {
        var rule: String = ""
        var mode: Mode = initialMode
        var replaceRegex: String = ""
        var replacement: String = ""
        var replaceFirst: Boolean = false
        val putMap = HashMap<String, String>()

        val ruleParam = ArrayList<String>()
        val ruleType = ArrayList<Int>()

        init {
            var r = ruleStr
            when {
                mode == Mode.Js || mode == Mode.Regex || mode == Mode.WebJs -> { }
                r.startsWith("@XPath:", true) -> { mode = Mode.XPath; r = r.substring(7) }
                r.startsWith("@Json:", true) -> { mode = Mode.Json; r = r.substring(6) }
                r.startsWith("@CSS:", true) -> { mode = Mode.Default }
                r.startsWith("@@") -> { mode = Mode.Default; r = r.substring(2) }
                isJSON || r.startsWith("$.") || r.startsWith("$[") -> mode = Mode.Json
                r.startsWith("/") -> mode = Mode.XPath
            }
            r = splitPutRule(r, putMap)
            val parts = r.split("##")
            rule = parts[0].trim()
            if (parts.size > 1) replaceRegex = parts[1]
            if (parts.size > 2) replacement = parts[2]
            if (parts.size > 3) replaceFirst = true

            // lyc486 evalPattern splitting: match @get:{...} and {{...}} patterns
            val evalMatcher = evalPattern.matcher(rule)
            var lastEnd = 0
            while (evalMatcher.find()) {
                // Text before the match
                if (evalMatcher.start() > lastEnd) {
                    val textBefore = rule.substring(lastEnd, evalMatcher.start())
                    if (textBefore.isNotEmpty()) {
                        val splitParts = textBefore.split(splitRegex)
                        for (sp in splitParts) {
                            if (sp.isNotEmpty()) {
                                ruleParam.add(sp)
                                ruleType.add(0) // plain text
                            }
                        }
                    }
                }
                val matched = evalMatcher.group()
                if (matched != null) {
                    when {
                        matched.startsWith("@get:") -> {
                            val inner = matched.substring(6).removeSurrounding("{", "}")
                            ruleParam.add(inner)
                            ruleType.add(-2) // @get variable
                        }
                        matched.startsWith("{{") -> {
                            val inner = matched.removeSurrounding("{{", "}}")
                            ruleParam.add(inner)
                            ruleType.add(-1) // JS expression or sub-rule
                        }
                    }
                }
                lastEnd = evalMatcher.end()
            }
            // Trailing text after last match
            if (lastEnd < rule.length) {
                val textAfter = rule.substring(lastEnd)
                if (textAfter.isNotEmpty()) {
                    val splitParts = textAfter.split(splitRegex)
                    for (sp in splitParts) {
                        if (sp.isNotEmpty()) {
                            ruleParam.add(sp)
                            ruleType.add(0)
                        }
                    }
                }
            }
        }

        fun getParamSize(): Int = ruleParam.size

        fun makeUpRule(analyzer: AnalyzeRule, currentResult: Any?) {
            if (ruleParam.isEmpty()) return
            val sb = StringBuilder()
            for (i in ruleParam.indices) {
                val type = ruleType[i]
                val param = ruleParam[i]
                when {
                    type > 0 -> {
                        // Regex capture group: extract group[type] from result
                        @Suppress("UNCHECKED_CAST")
                        val groups = currentResult as? List<String?>
                        val captured = groups?.getOrNull(type)
                        if (captured != null) {
                            sb.append(captured)
                        } else {
                            sb.append(param)
                        }
                    }
                    type == -1 -> {
                        // JS expression or sub-rule in {{...}}
                        if (isRule(param)) {
                            val subResult = analyzer.getString(param, currentResult)
                            sb.append(subResult)
                        } else {
                            val jsResult = analyzer.evalJS(param, currentResult)
                            when (jsResult) {
                                is Double -> if (jsResult % 1.0 == 0.0) sb.append("%.0f".format(jsResult)) else sb.append(jsResult)
                                else -> sb.append(jsResult?.toString() ?: "")
                            }
                        }
                    }
                    type == -2 -> {
                        // @get:{variable}
                        sb.append(analyzer.get(param))
                    }
                    else -> {
                        // Plain text (type == 0)
                        sb.append(param)
                    }
                }
            }
            rule = sb.toString()
        }

        private fun isRule(ruleStr: String): Boolean {
            return ruleStr.startsWith("@") || ruleStr.startsWith("$.") || ruleStr.startsWith("//")
        }

        private fun splitPutRule(rule: String, putMap: HashMap<String, String>): String {
            val regex = Regex("@put:\\{([^}]+?)\\}", RegexOption.IGNORE_CASE)
            var result = rule
            regex.findAll(rule).forEach { match ->
                val content = match.groupValues[1]
                val eqIdx = content.indexOf("=")
                if (eqIdx > 0) {
                    putMap[content.substring(0, eqIdx).trim()] = content.substring(eqIdx + 1).trim()
                }
                result = result.replace(match.value, "")
            }
            return result
        }
    }

    enum class Mode { XPath, Json, Default, Js, Regex, WebJs }

    /**
     * lyc486 AnalyzeRule.reGetBook()
     * Precise search by book name + author
     */
    fun reGetBook() {
        if (!preUpdateJs) throw AssertionError("only callable in preUpdateJs context")
        val bookSource = source as? BookSource
        val bk = book as? Book
        if (bookSource == null || bk == null) return
        val matched = WebBook.preciseSearchAwait(bookSource, bk.name, bk.author)
        if (matched != null) {
            bk.bookUrl = matched.bookUrl
            matched.variableMap.forEach { entry -> bk.putVariable(entry.key, entry.value) }
        }
        WebBook.getBookInfoAwait(bookSource, bk, false)
    }

    fun refreshTocUrl() {
        if (!preUpdateJs) throw AssertionError("only callable in preUpdateJs context")
        val bookSource = source as? BookSource
        val bk = book as? Book
        if (bookSource == null || bk == null) return
        WebBook.getBookInfoAwait(bookSource, bk, false)
    }

    companion object {
        // lyc486 evalPattern: matches @get:{...} and {{...}}
        private val evalPattern: java.util.regex.Pattern = java.util.regex.Pattern.compile(
            "@get:\\{[^}]+?\\}|\\{\\{[\\w\\W]*?\\}\\}"
        )

        // lyc486 splitRegex: captures $1/$2 groups for regex rule reconstruction
        private val splitRegex = Regex("(?<=\\$[12])(?=\\D)|(?<=\\D)(?=\\$[12])")

        private fun unwrapJs(jsStr: String): String {
            return when {
                jsStr.startsWith("@js:", true) -> jsStr.substring(4)
                jsStr.startsWith("<js>", true) && jsStr.lastIndexOf("<") > 0 -> jsStr.substring(4, jsStr.lastIndexOf("<"))
                jsStr.startsWith("@webJs:", true) -> jsStr.substring(7)
                jsStr.startsWith("<webJs>", true) && jsStr.lastIndexOf("<") > 0 -> jsStr.substring(7, jsStr.lastIndexOf("<"))
                else -> jsStr
            }
        }

        private const val SINGLE_SOURCE_RULE_CACHE_MAX = 512

        fun getOrCreateSingleSourceRule(cache: HashMap<String, List<SourceRule>>, key: String, creator: () -> List<SourceRule>): List<SourceRule> {
            return cache.getOrPut(key) { creator() }.also {
                if (cache.size > SINGLE_SOURCE_RULE_CACHE_MAX) {
                    val oldest = cache.keys.first()
                    cache.remove(oldest)
                }
            }
        }

        fun AnalyzeRule.setCoroutineContext(context: kotlin.coroutines.CoroutineContext): AnalyzeRule {
            this.coroutineContext = context
            return this
        }
        fun AnalyzeRule.setRuleData(rd: RuleDataInterface?): AnalyzeRule { this.ruleData = rd; return this }
    }
}