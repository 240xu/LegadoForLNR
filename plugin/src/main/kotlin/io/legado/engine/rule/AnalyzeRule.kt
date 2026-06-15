package io.legado.engine.rule

import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.engine.constant.AppPattern
import io.legado.engine.data.BaseBook
import io.legado.engine.data.BaseSource
import io.legado.engine.data.BookChapter
import io.legado.engine.data.RuleDataInterface
import io.legado.engine.http.CookieStore
import io.legado.engine.js.JsExtensions
import io.legado.engine.js.SharedJsScope
import io.legado.engine.shim.CacheManager
import io.legado.engine.shim.Debug
import io.legado.engine.shim.GSON
import io.legado.engine.shim.fromJsonObject
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.jsoup.nodes.Node
import org.apache.commons.text.StringEscapeUtils
import com.google.gson.internal.LinkedTreeMap
import java.net.URL

class AnalyzeRule(
    private var ruleData: RuleDataInterface? = null,
    private val source: BaseSource? = null
) : JsExtensions {

    private val book get() = ruleData as? BaseBook
    private var chapter: BookChapter? = null
    private var nextChapterUrl: String? = null
    private var content: Any? = null
    private var baseUrl: String? = null
    private var redirectUrl: URL? = null
    private var isJSON: Boolean = false
    private var isRegex: Boolean = false
    private var ruleName: String? = null
    /** 最近一次正则匹配的捕获组，供 $1/$2 引用 */
    var lastRegexGroups: List<String> = emptyList()

    private var analyzeByXPath: AnalyzeByXPath? = null
    private var analyzeByJSoup: AnalyzeByJSoup? = null
    private var analyzeByJSonPath: AnalyzeByJSonPath? = null

    private val stringRuleCache = hashMapOf<String, List<SourceRule>>()
    private val regexCache = hashMapOf<String, Regex?>()

    fun setRuleName(name: String) { if (name.isNotBlank()) ruleName = name }

    fun setContent(content: Any?, baseUrl: String? = null): AnalyzeRule {
        if (content == null) throw AssertionError("Content cannot be null")
        this.content = content
        isJSON = when (content) {
            is Node -> false
            else -> content.toString().trimStart().let { it.startsWith("{") || it.startsWith("[") }
        }
        setBaseUrl(baseUrl)
        analyzeByXPath = null; analyzeByJSoup = null; analyzeByJSonPath = null
        return this
    }

    fun setBaseUrl(url: String?): AnalyzeRule { url?.let { this.baseUrl = it }; return this }
    fun setRedirectUrl(url: String): URL? { try { redirectUrl = URL(url) } catch (_: Exception) {}; return redirectUrl }
    fun setNextChapterUrl(url: String?): AnalyzeRule { nextChapterUrl = url; return this }
    fun setChapter(ch: BookChapter?): AnalyzeRule { chapter = ch; return this }

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
     * 分解规则生成规则列表 (带allInOne参数)
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
            ruleList.add(SourceRule(jsMatcher.group(2) ?: jsMatcher.group(1) ?: "", Mode.Js)); start = jsMatcher.end()
        }
        val webJsMatcher = AppPattern.WebJS_PATTERN.matcher(ruleStr)
        while (webJsMatcher.find()) {
            if (webJsMatcher.start() > start) { tmp = ruleStr.substring(start, webJsMatcher.start()).trim(); if (tmp.isNotEmpty()) ruleList.add(SourceRule(tmp, mMode)) }
            ruleList.add(SourceRule(webJsMatcher.group(2) ?: webJsMatcher.group(1) ?: "", Mode.WebJs)); start = webJsMatcher.end()
        }
        if (ruleStr.length > start) { tmp = ruleStr.substring(start).trim(); if (tmp.isNotEmpty()) ruleList.add(SourceRule(tmp, mMode)) }
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
        if (result is NativeObject) {
            val sourceRule = ruleList.first()
            putRule(sourceRule.putMap)
            sourceRule.makeUpRule(this, result)
            result = if (sourceRule.getParamSize() > 1) sourceRule.rule else (result as NativeObject)[sourceRule.rule]
            result?.let {
                if (sourceRule.replaceRegex.isNotEmpty() && it is List<*>) {
                    result = it.map { o -> replaceRegex(o.toString(), sourceRule) }
                } else if (sourceRule.replaceRegex.isNotEmpty()) {
                    result = replaceRegex(result.toString(), sourceRule)
                }
            }
        } else if (result is LinkedTreeMap<*, *>) {
            result = (result as LinkedTreeMap<String, *>)[ruleList.first().rule]
        } else {
            for (sr in ruleList) {
                putRule(sr.putMap); sr.makeUpRule(this, result); result ?: continue
                val r = sr.rule
                if (r.isNotEmpty()) {
                    result = when (sr.mode) {
                        Mode.WebJs -> {
                            val webResult = io.legado.engine.webview.BackstageWebView.getSource(
                                html = content?.toString(), url = baseUrl, js = r,
                                headerMap = source?.getHeaderMap(true)
                            ).body ?: ""
                            parseWebJsList(webResult)
                        }
                        Mode.Js -> evalJS(r, result)
                        Mode.Json -> getAnalyzeByJSonPath(result).getStringList(r)
                        Mode.XPath -> getAnalyzeByXPath(result).getStringList(r)
                        Mode.Regex -> { val rr = AnalyzeByRegex.getElement(result.toString(), r.split("&&").toTypedArray()); if (rr is List<*>) rr.map { it.toString() } else listOf(rr.toString()) }
                        Mode.Default -> getAnalyzeByJSoup(result).getStringList(r)
                        else -> r
                    }
                }
                if (sr.replaceRegex.isNotEmpty() && result is List<*>) {
                    result = (result as List<*>).map { replaceRegex(it.toString(), sr) }
                } else if (sr.replaceRegex.isNotEmpty()) {
                    result = replaceRegex(result.toString(), sr)
                }
            }
        }
        if (result == null) return null
        if (result is String) result = result.split("\n")
        if (isUrl) {
            val urlList = ArrayList<String>()
            if (result is List<*>) {
                for (url in result) {
                    val absoluteURL = AnalyzeUrl.getAbsoluteURL((redirectUrl?.toString() ?: baseUrl).orEmpty(), url.toString())
                    if (absoluteURL.isNotEmpty() && !urlList.contains(absoluteURL)) urlList.add(absoluteURL)
                }
            }
            return urlList
        }
        @Suppress("UNCHECKED_CAST") return (result as? List<String>)?.map { StringEscapeUtils.unescapeHtml4(it) }
    }

    fun getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false): String {
        if (ruleStr.isNullOrEmpty()) return ""
        return getString(splitSourceRuleCacheString(ruleStr), mContent, isUrl)
    }


    /**
     * 获取文本 - 带 mContent 参数的重载
     * 对应 Legado 的 getString(mContent, ruleStr, isUrl)
     */
    fun getStringFrom(mContent: Any?, ruleStr: String?, isUrl: Boolean = false): String {
        return getString(ruleStr, mContent, isUrl)
    }

    fun getString(ruleStr: String): String {
        return getString(ruleStr as String?, null, false)
    }

    fun getString(ruleStr: String?, unescape: Boolean): String {
        if (ruleStr.isNullOrEmpty()) return ""
        return getString(splitSourceRuleCacheString(ruleStr), null, false, unescape)
    }

    fun getString(ruleList: List<SourceRule>, mContent: Any? = null, isUrl: Boolean = false, unescape: Boolean = true): String {
        var result: Any? = null
        val c = mContent ?: this.content ?: return ""
        if (ruleList.isEmpty()) return ""
        result = c
        // NativeObject 快速路径：JS返回的对象直接按键值访问
        if (result is NativeObject) {
            val sourceRule = ruleList.first()
            putRule(sourceRule.putMap)
            sourceRule.makeUpRule(this, result)
            result = if (sourceRule.getParamSize() > 1) {
                sourceRule.rule
            } else {
                (result as NativeObject)[sourceRule.rule]?.toString()
            }?.let { replaceRegex(it, sourceRule) }
        } else if (result is LinkedTreeMap<*, *>) {
            // LinkedTreeMap 快速路径：Gson解析的JSON对象直接键值访问
            result = (result as LinkedTreeMap<String, *>)[ruleList.first().rule]?.toString()
        } else {
            if (ruleList.size == 1) {
                val single = ruleList.first()
                val direct = getDirectValue(result, single.rule)
                if (direct != null) {
                    result = replaceRegex(direct, single)
                }
            }
            if (result == null || ruleList.size > 1 || getDirectValue(result, ruleList.first().rule) == null) {
                for (sr in ruleList) {
                    putRule(sr.putMap); sr.makeUpRule(this, result); result ?: continue
                    val r = sr.rule
                    if (r.isNotBlank() || sr.replaceRegex.isEmpty()) {
                        result = when (sr.mode) {
                            Mode.WebJs -> io.legado.engine.webview.BackstageWebView.getSource(
                                html = content?.toString(),
                                url = baseUrl,
                                js = r,
                                headerMap = source?.getHeaderMap(true)
                            ).body ?: ""
                            Mode.Js -> evalJS(r, result)
                            Mode.Json -> getAnalyzeByJSonPath(result).getString(r)
                            Mode.XPath -> getAnalyzeByXPath(result).getString(r)
                            Mode.Regex -> { val rr = AnalyzeByRegex.getElement(result.toString(), r.split("&&").toTypedArray()); if (rr is List<*>) { lastRegexGroups = rr.map { it.toString() }; rr.firstOrNull()?.toString() ?: "" } else rr.toString() }
                            Mode.Default -> if (isUrl) getAnalyzeByJSoup(result).getString0(r) else getAnalyzeByJSoup(result).getString(r)
                            else -> r
                        }
                    }
                    if (result != null && sr.replaceRegex.isNotEmpty()) { result = replaceRegex(result.toString(), sr) }
                }
            }
        }
        if (result == null) result = ""
        val resultStr = result.toString()
        val str = if (unescape && resultStr.indexOf('&') > -1) {
            StringEscapeUtils.unescapeHtml4(resultStr)
        } else {
            resultStr
        }
        if (isUrl) {
            return if (str.isBlank()) {
                baseUrl ?: ""
            } else {
                AnalyzeUrl.getAbsoluteURL((redirectUrl?.toString() ?: baseUrl).orEmpty(), str)
            }
        }
        return str
    }


    /**
     * 获取单个Element
     */
    fun getElement(ruleStr: String): Any? {
        if (ruleStr.isEmpty()) return null
        var result: Any? = content ?: return null
        val ruleList = splitSourceRule(ruleStr, true)
        if (ruleList.isEmpty()) return null
        for (sr in ruleList) {
            putRule(sr.putMap); result ?: continue
            val r = sr.rule
            result = when (sr.mode) {
                Mode.WebJs -> {
                    val webResult = io.legado.engine.webview.BackstageWebView.getSource(
                        html = content?.toString(), url = baseUrl, js = r,
                        headerMap = source?.getHeaderMap(true)
                    ).body ?: ""
                    parseWebJsObject(webResult) ?: evalJS(r, result)
                }
                Mode.Js -> evalJS(r, result)
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
                Mode.WebJs -> {
                    val webResult = io.legado.engine.webview.BackstageWebView.getSource(
                        html = content?.toString(), url = baseUrl, js = r,
                        headerMap = source?.getHeaderMap(true)
                    ).body ?: ""
                    parseWebJsArray(webResult)
                }
                Mode.Js -> evalJS(r, result)
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
                Mode.WebJs -> {
                    val webResult = io.legado.engine.webview.BackstageWebView.getSource(
                        html = content?.toString(), url = baseUrl, js = r,
                        headerMap = source?.getHeaderMap(true)
                    ).body ?: ""
                    parseWebJsObject(webResult) ?: evalJS(r, result)
                }
                Mode.Js -> evalJS(r, result)
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
            b["source"] = source; b["baseSource"] = source; b["book"] = book; b["result"] = result
            b["baseUrl"] = baseUrl ?: ""; b["chapter"] = chapter
            b["title"] = chapter?.title ?: ""; b["src"] = content ?: ""
            b["nextChapterUrl"] = nextChapterUrl ?: ""
            b["fromBookInfo"] = false
        }
        val sharedScope = source?.let { SharedJsScope.getScope(it.jsLib) }
        val scope = if (sharedScope == null) {
            RhinoScriptEngine.getRuntimeScope(bindings)
        } else {
            bindings.apply { prototype = sharedScope }
        }
        return try { RhinoScriptEngine.eval(js, scope) } catch (e: Exception) { Debug.log("evalJS error: ${e.message}"); null }
    }

    override fun getSource(): BaseSource? = source
    override fun getTag(): String? = source?.getTag() ?: ruleName

    override fun ajax(url: Any): String? {
        val urlStr = url.toString()
        return try { AnalyzeUrl(urlStr, source = source, ruleData = ruleData, chapter = chapter).getStrResponse().body } catch (e: Exception) { Debug.log("ajax error: ${e.message}"); null }
    }

    fun put(key: String, value: String): String { chapter?.putVariable(key, value) ?: book?.putVariable(key, value) ?: ruleData?.putVariable(key, value) ?: source?.put(key, value); return value }
    fun get(key: String): String {
        // Legado 特殊处理：bookName 和 title 直接返回
        when (key) {
            "bookName" -> (ruleData as? io.legado.engine.data.Book)?.let { return it.name }
            "title" -> chapter?.let { return it.title }
        }
        return chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: book?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: source?.get(key)?.takeIf { it.isNotEmpty() }
            ?: ""
    }
    fun put(value: String): String { chapter?.putVariable(value) ?: book?.putVariable(value) ?: ruleData?.putVariable(value) ?: source?.putVariable(value); return value }
    fun get(): String { return chapter?.getVariable()?.takeIf { it.isNotEmpty() } ?: book?.getVariable()?.takeIf { it.isNotEmpty() } ?: ruleData?.getVariable()?.takeIf { it.isNotEmpty() } ?: source?.getVariable().orEmpty() }

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

    enum class Mode { XPath, Json, Default, Js, Regex, WebJs }

    inner class SourceRule(ruleStr: String, initialMode: Mode = Mode.Default) {
        var rule: String = ""
        var mode: Mode = initialMode
        var replaceRegex: String = ""
        var replacement: String = ""
        var replaceFirst: Boolean = false
        val putMap = HashMap<String, String>()
        private val ruleParam = ArrayList<String>()
        private val ruleType = ArrayList<Int>()
        private val getRuleType = -2
        private val jsRuleType = -1
        private val defaultRuleType = 0

        init {
            var r = when {
                mode == Mode.Js || mode == Mode.Regex || mode == Mode.WebJs -> ruleStr
                ruleStr.startsWith("@CSS:", true) -> { mode = Mode.Default; ruleStr }
                ruleStr.startsWith("@@") -> { mode = Mode.Default; ruleStr.substring(2) }
                ruleStr.startsWith("@XPath:", true) -> { mode = Mode.XPath; ruleStr.substring(7) }
                ruleStr.startsWith("@Json:", true) -> { mode = Mode.Json; ruleStr.substring(6) }
                isJSON || ruleStr.startsWith("$.") || ruleStr.startsWith("$[") -> { mode = Mode.Json; ruleStr }
                ruleStr.startsWith("/") -> { mode = Mode.XPath; ruleStr }
                else -> ruleStr
            }
            r = splitPutRule(r, putMap)
            // 拆分 @get:{} 和 {{}}
            var start = 0
            val evalMatcher = evalPattern.matcher(r)
            if (evalMatcher.find()) {
                val tmp = r.substring(start, evalMatcher.start())
                if (mode != Mode.Js && mode != Mode.Regex && mode != Mode.WebJs &&
                    (evalMatcher.start() == 0 || !tmp.contains("##"))
                ) {
                    mode = Mode.Regex
                }
                do {
                    if (evalMatcher.start() > start) {
                        splitRegex(r.substring(start, evalMatcher.start()))
                    }
                    val matched = evalMatcher.group()
                    when {
                        matched.startsWith("@get:", true) -> {
                            ruleType.add(getRuleType)
                            ruleParam.add(matched.substring(6, matched.lastIndex))
                        }
                        matched.startsWith("{{") -> {
                            ruleType.add(jsRuleType)
                            ruleParam.add(matched.substring(2, matched.length - 2))
                        }
                        else -> splitRegex(matched)
                    }
                    start = evalMatcher.end()
                } while (evalMatcher.find())
            }
            if (r.length > start) {
                splitRegex(r.substring(start))
            }
            // 如果没有 ruleParam，按 ## 分割替换规则
            if (ruleParam.isEmpty()) {
                val parts = r.split("##")
                rule = parts[0].trim()
                if (parts.size > 1) replaceRegex = parts[1]
                if (parts.size > 2) replacement = parts[2]
                if (parts.size > 3) replaceFirst = true
            }
        }

        /** 拆分 $1/$2 正则捕获组引用 */
        private fun splitRegex(ruleStr: String) {
            var start = 0
            val ruleStrArray = ruleStr.split("##")
            val regexMatcher = regexPattern.matcher(ruleStrArray[0])
            if (regexMatcher.find()) {
                if (mode != Mode.Js && mode != Mode.Regex && mode != Mode.WebJs) {
                    mode = Mode.Regex
                }
                do {
                    if (regexMatcher.start() > start) {
                        ruleType.add(defaultRuleType)
                        ruleParam.add(ruleStr.substring(start, regexMatcher.start()))
                    }
                    val matched = regexMatcher.group()
                    ruleType.add(matched.substring(1).toInt())
                    ruleParam.add(matched)
                    start = regexMatcher.end()
                } while (regexMatcher.find())
            }
            if (ruleStr.length > start) {
                ruleType.add(defaultRuleType)
                ruleParam.add(ruleStr.substring(start))
            }
        }

        /** 替换 @get:{} / {{}} / $1/$2 */
        fun makeUpRule(analyzer: AnalyzeRule, currentResult: Any?) {
            if (ruleParam.isNotEmpty()) {
                val infoVal = StringBuilder()
                var index = ruleParam.size
                while (index-- > 0) {
                    val regType = ruleType[index]
                    when {
                        regType > defaultRuleType -> {
                            // $1/$2 正则捕获组引用
                            @Suppress("UNCHECKED_CAST")
                            (currentResult as? List<String?>)?.run {
                                if (this.size > regType) {
                                    this[regType]?.let { infoVal.insert(0, it) }
                                }
                            } ?: infoVal.insert(0, ruleParam[index])
                        }
                        regType == jsRuleType -> {
                            // {{JS表达式}} 或 {{规则}}
                            val inner = ruleParam[index]
                            val jsResult = if (inner.startsWith("$.") || inner.startsWith("$[") || inner.startsWith("//") || inner.startsWith("@")) {
                                // Legado: $.、$[、//、@ 开头的作为规则解析
                                analyzer.getString(inner, currentResult)
                            } else {
                                analyzer.evalJS(inner, currentResult)
                            }
                            when (jsResult) {
                                null -> Unit
                                is Double -> if (jsResult % 1.0 == 0.0) infoVal.insert(0, "%.0f".format(jsResult)) else infoVal.insert(0, jsResult)
                                else -> infoVal.insert(0, jsResult.toString())
                            }
                        }
                        regType == getRuleType -> {
                            // @get:{key}
                            infoVal.insert(0, analyzer.get(ruleParam[index]))
                        }
                        else -> {
                            // 普通文本
                            infoVal.insert(0, ruleParam[index])
                        }
                    }
                }
                rule = infoVal.toString()
            }
            // 分离替换规则
            val ruleStrS = rule.split("##")
            rule = ruleStrS[0].trim()
            if (ruleStrS.size > 1) replaceRegex = ruleStrS[1]
            if (ruleStrS.size > 2) replacement = ruleStrS[2]
            if (ruleStrS.size > 3) replaceFirst = true
        }

        private fun splitPutRule(ruleStr: String, putMap: HashMap<String, String>): String {
            var vRuleStr = ruleStr
            val putMatcher = putPattern.matcher(vRuleStr)
            while (putMatcher.find()) {
                vRuleStr = vRuleStr.replace(putMatcher.group(), "")
                val putJsonStr = putMatcher.group(1) ?: continue
                val putJson = io.legado.engine.shim.GSON.fromJsonObject<Map<String, String>>(putJsonStr)
                if (putJson != null) {
                    putMap.putAll(putJson)
                    continue
                }
                val kv = putJsonStr.split("=")
                if (kv.size >= 2) {
                    putMap[kv[0].trim()] = kv[1].trim()
                }
            }
            return vRuleStr
        }

        fun getParamSize(): Int = ruleParam.size
    }

    /** WebJs结果解析为字符串列表（getStringList用） */
    private fun parseWebJsList(webResult: String): Any {
        return try {
            val arr = com.google.gson.Gson().fromJson<Array<String>>(webResult, Array<String>::class.java)
            if (arr != null && arr.isNotEmpty()) arr.toList() else listOf(webResult)
        } catch (_: Exception) { webResult.split("\n") }
    }

    /** WebJs结果解析为Map对象（getElement/getObject用） */
    private fun parseWebJsObject(webResult: String): Any? {
        return try {
            val type = com.google.gson.reflect.TypeToken.getParameterized(Map::class.java, String::class.java, Any::class.java).type
            com.google.gson.Gson().fromJson<Map<String, Any?>>(webResult, type)
        } catch (_: Exception) { null }
    }

    /** WebJs结果解析为列表（getElements用） */
    private fun parseWebJsArray(webResult: String): Any {
        return try {
            val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, Map::class.java).type
            com.google.gson.Gson().fromJson<List<Map<String, Any?>>>(webResult, type) ?: listOf(webResult)
        } catch (_: Exception) { webResult.split("\n") }
    }

    companion object {
        // @get:{} 和 {{}} 匹配模式
        private val evalPattern = java.util.regex.Pattern.compile(
            "@get:\\{[^}]+?\\}|\\{\\{[\\w\\W]*?\\}\\}", java.util.regex.Pattern.CASE_INSENSITIVE
        )
        // $1/$2 正则捕获组引用
        private val regexPattern = java.util.regex.Pattern.compile("\\$\\d{1,2}")
        // @put:{} 匹配模式 — 括号捕获花括号内JSON内容
        private val putPattern = java.util.regex.Pattern.compile(
            "@put:(\\{[^}]+?\\})", java.util.regex.Pattern.CASE_INSENSITIVE
        )

        private fun unwrapJs(jsStr: String): String {
            return when {
                jsStr.startsWith("@js:", true) -> jsStr.substring(4)
                jsStr.startsWith("<js>", true) -> AppPattern.unwrapJsTag(jsStr, "js")
                jsStr.startsWith("@webJs:", true) -> jsStr.substring(7)
                jsStr.startsWith("<webJs>", true) -> AppPattern.unwrapJsTag(jsStr, "webJs")
                else -> jsStr
            }
        }
        fun AnalyzeRule.setCoroutineContext(context: kotlin.coroutines.CoroutineContext): AnalyzeRule = this
        fun AnalyzeRule.setRuleData(rd: RuleDataInterface?): AnalyzeRule { this.ruleData = rd; return this }
    }
}
