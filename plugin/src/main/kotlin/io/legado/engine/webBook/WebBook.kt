package io.legado.engine.webBook

import io.legado.engine.data.*
import io.legado.engine.data.rule.ContentRule
import io.legado.engine.http.ConcurrentRateLimiter
import io.legado.engine.http.HttpResponse
import io.legado.engine.rule.AnalyzeRule
import io.legado.engine.rule.AnalyzeUrl
import io.legado.engine.js.SourceLoginCallback
import io.legado.engine.shim.Debug

object WebBook {

    private fun applyRateLimit(bookSource: BookSource) {
        val rate = bookSource.getEffectiveConcurrentRate()
        if (!rate.isNullOrBlank()) {
            ConcurrentRateLimiter.acquire(bookSource.getKey(), rate)
        }
    }

    fun executeWithLoginCheck(bookSource: BookSource, analyzeUrl: AnalyzeUrl): HttpResponse {
        applyRateLimit(bookSource)
        val loginCheckJs = bookSource.loginCheckJs
        var response = analyzeUrl.execute()
        if (!loginCheckJs.isNullOrBlank()) {
            try {
                // Legado: analyzeUrl.evalJS(checkJs, StrResponse)
                val strResp = io.legado.engine.http.StrResponse(response)
                val checkResult = analyzeUrl.evalJS(loginCheckJs, strResp)
                // 按Opus建议：StrResponse优先判断，避免toString()误判
                when {
                    checkResult is io.legado.engine.http.StrResponse -> {
                        response = io.legado.engine.http.HttpResponse(
                            checkResult.url, checkResult.body(), checkResult.code, checkResult.headers
                        )
                    }
                    checkResult == false || checkResult?.toString() == "false" -> {
                        Debug.log("loginCheckJs failed for ${bookSource.bookSourceName}")
                        val loginJs = bookSource.getLoginJs()
                        if (!loginJs.isNullOrBlank()) {
                            try { bookSource.evalJS(loginJs) } catch (e: Exception) {
                                Debug.log("loginJs exec error: ${e.message}")
                            }
                        }
                        SourceLoginCallback.requestLogin(bookSource)
                        applyRateLimit(bookSource)
                        response = analyzeUrl.execute()
                    }
                }
            } catch (e: Exception) {
                // Legado: 即使请求失败也执行 checkJs，传入错误响应
                if (!loginCheckJs.isNullOrBlank()) {
                    try {
                        val errResp = io.legado.engine.http.StrResponse(
                            io.legado.engine.http.HttpResponse(analyzeUrl.url, e.message ?: "", 500)
                        )
                        val checkResult = analyzeUrl.evalJS(loginCheckJs, errResp)
                        if (checkResult is io.legado.engine.http.StrResponse && checkResult.code != 500) {
                            response = io.legado.engine.http.HttpResponse(
                                checkResult.url, checkResult.body(), checkResult.code, checkResult.headers
                            )
                        }
                    } catch (_: Exception) {}
                }
                Debug.log("loginCheckJs error: ${e.message}")
            }
        }
        return response
    }

    fun searchBookAwait(bookSource: BookSource, key: String, page: Int? = 1): ArrayList<SearchBook> {
        val searchUrl = bookSource.searchUrl
        if (searchUrl.isNullOrBlank()) return arrayListOf()
        val analyzeUrl = AnalyzeUrl(mUrl = searchUrl, key = key, page = page, baseUrl = bookSource.bookSourceUrl, source = bookSource)
        val res = executeWithLoginCheck(bookSource, analyzeUrl)
        val rule = bookSource.getSearchRule()
        return BookList.analyzeBookList(bookSource, analyzeUrl, res.url, res.body, rule, isSearch = true)
    }

    fun exploreBookAwait(bookSource: BookSource, url: String, page: Int? = 1, infoMap: MutableMap<String, String>? = null): ArrayList<SearchBook> {
        val analyzeUrl = AnalyzeUrl(mUrl = url, page = page, baseUrl = bookSource.bookSourceUrl, source = bookSource, infoMap = infoMap)
        val res = executeWithLoginCheck(bookSource, analyzeUrl)
        val rule = bookSource.getExploreRule()
        return BookList.analyzeBookList(bookSource, analyzeUrl, res.url, res.body, rule, isSearch = false)
    }

    fun getBookInfoAwait(bookSource: BookSource, book: Book, canReName: Boolean = true): Book {
        val rule = bookSource.getBookInfoRule()
        val analyzeUrl = AnalyzeUrl(mUrl = book.bookUrl, baseUrl = bookSource.bookSourceUrl, source = bookSource, ruleData = book)
        val res = executeWithLoginCheck(bookSource, analyzeUrl)
        BookInfo.analyzeBookInfo(bookSource, book, res.url, res.body, rule, canReName)
        return book
    }

    fun getChapterListAwait(bookSource: BookSource, book: Book): List<BookChapter> {
        val rule = bookSource.getTocRule()
        if (!rule.preUpdateJs.isNullOrBlank()) {
            try { AnalyzeRule(ruleData = book, source = bookSource).evalJS(rule.preUpdateJs!!) }
            catch (e: Exception) { Debug.log("preUpdateJs error: " + e.message) }
        }
        val allChapters = mutableListOf<BookChapter>()
        var currentUrl: String? = book.tocUrl?.ifBlank { book.bookUrl } ?: book.bookUrl
        var pageCount = 0
        while (currentUrl != null && pageCount < 20) {
            val analyzeUrl = AnalyzeUrl(mUrl = currentUrl!!, baseUrl = book.bookUrl, source = bookSource, ruleData = book)
            val res = executeWithLoginCheck(bookSource, analyzeUrl)
            val chapters = BookChapterList.analyzeChapterList(bookSource, book, res.url, res.body, rule)
            allChapters.addAll(chapters)
            currentUrl = if (!rule.nextTocUrl.isNullOrBlank()) {
                val ar = AnalyzeRule(source = bookSource).setContent(res.body, currentUrl)
                // Legado: getStringList(nextTocRule, isUrl=true) 支持多URL
                val nextUrls = ar.getStringList(rule.nextTocUrl!!, isUrl = true)
                nextUrls?.firstOrNull { it.isNotBlank() && it != currentUrl }
            } else null
            pageCount++
        }
        return allChapters
    }

    fun getContentAwait(bookSource: BookSource, book: Book, bookChapter: BookChapter, nextChapterUrl: String? = null): String {
        if (book.name.isBlank() || book.tocUrl.isNullOrBlank()) {
            runCatching { getBookInfoAwait(bookSource, book, canReName = false) }
        }
        val contentRule = bookSource.getContentRule()
        if (contentRule.content.isNullOrEmpty()) return bookChapter.url
        val allParts = mutableListOf<String>()
        var chTitle = ""
        var currentUrl: String? = bookChapter.url
        var pageCount = 0
        while (currentUrl != null && pageCount < 10) {
            val analyzeUrl = AnalyzeUrl(mUrl = currentUrl!!, baseUrl = book.tocUrl ?: book.bookUrl, source = bookSource, ruleData = book, chapter = bookChapter)
            val res = executeWithLoginCheck(bookSource, analyzeUrl)
            var body = if (!contentRule.sourceRegex.isNullOrBlank()) {
                try { Regex(contentRule.sourceRegex!!).find(res.body)?.value ?: res.body } catch (_: Exception) { res.body }
            } else res.body
            val ar = AnalyzeRule(source = bookSource).setContent(body, res.url)
            ar.setChapter(bookChapter)
            if (!contentRule.webJs.isNullOrBlank()) {
                // webJs 应在 WebView 上下文中执行（可访问DOM），回退到 Rhino
                val webResult = try {
                    io.legado.engine.webview.BackstageWebView.getSource(
                        html = body, url = res.url, js = contentRule.webJs!!, headerMap = bookSource.getHeaderMap(false)
                    )
                } catch (_: Exception) { null }
                (webResult?.body ?: ar.evalJS(contentRule.webJs!!, body)?.toString())?.takeIf { it.isNotBlank() }?.let { webBody ->
                    body = webBody
                    ar.setContent(body, res.url).setChapter(bookChapter)
                }
            }
            // Legado: content = analyzeRule.getString(contentRule.content, unescape = false)
            val contentStr = ar.getString(contentRule.content ?: "", unescape = false)
            // Legado: HtmlFormatter.formatKeepImg - 保留图片的HTML格式化
            val formatted = formatContentHtml(contentStr, res.url)
            allParts.addAll(formatted.split("\n").filter { it.isNotBlank() })
            if (!contentRule.subContent.isNullOrBlank()) allParts.addAll(ar.getStringList(contentRule.subContent!!) ?: emptyList())
            if (chTitle.isBlank() && !contentRule.title.isNullOrBlank()) chTitle = ar.getString(contentRule.title!!)
            // Legado: nextContentUrl 使用 getStringList(isUrl=true) 支持多URL
            currentUrl = if (!contentRule.nextContentUrl.isNullOrBlank()) {
                val nextUrls = ar.getStringList(contentRule.nextContentUrl!!, isUrl = true)
                nextUrls?.firstOrNull { it.isNotBlank() && it != currentUrl }?.let {
                    AnalyzeUrl.getAbsoluteURL(res.url, it)
                }
            } else null
            pageCount++
        }
        var content = allParts.joinToString("\n")
        // Legado: replaceRegex 通过 analyzeRule.getString 解析，支持 @get/{{}} 规则
        if (!contentRule.replaceRegex.isNullOrBlank()) {
            val replaceAr = AnalyzeRule(source = bookSource).setContent(content, book.tocUrl ?: book.bookUrl)
            content = replaceAr.getString(contentRule.replaceRegex!!, content)
        }
        return content
    }

    fun applyReplaceRegex(text: String, replaceRules: String): String {
        var result = text
        for (rule in replaceRules.split("\n").filter { it.isNotBlank() }) {
            try {
                when {
                    // Legado 格式: ##正则##替换 或 ##正则##替换##
                    rule.contains("##") -> {
                        val parts = rule.split("##").map { it.trim() }
                        if (parts.size >= 3 && parts[1].isNotEmpty()) {
                            val regex = parts[1]
                            val replacement = parts[2]
                            val replaceFirst = parts.size > 3 && parts[3].isNotBlank()
                            val compiled = Regex(regex)
                            result = if (replaceFirst) {
                                compiled.replaceFirst(result, replacement)
                            } else {
                                compiled.replace(result, replacement)
                            }
                        }
                    }
                    // 兼容旧格式: /regex/replacement/
                    rule.startsWith("/") -> {
                        val clean = rule.removeSurrounding("/")
                        val parts = clean.split("/", limit = 2)
                        if (parts.size >= 2) result = Regex(parts[0]).replace(result, parts[1])
                    }
                    // 兼容旧格式: regex::replacement
                    rule.contains("::") -> {
                        val parts = rule.split("::", limit = 2)
                        result = Regex(parts[0].trim()).replace(result, parts[1].trim())
                    }
                }
            } catch (_: Exception) {}
        }
        return result
    }

    /** Legado HtmlFormatter.formatKeepImg */
    private fun formatContentHtml(html: String, baseUrl: String): String {
        if (html.isBlank()) return ""
        return try {
            val doc = org.jsoup.Jsoup.parseBodyFragment(html, baseUrl)
            val imgs = doc.select("img")
            val imgSrcs = imgs.map { it.attr("src") }.filter { it.isNotBlank() }
            val text = doc.body().text()
            val sb = StringBuilder(text)
            imgSrcs.forEach { src -> sb.appendLine("<img src=\"$src\">") }
            sb.toString()
        } catch (_: Exception) { html }
    }
}

object BookList {
    fun analyzeBookList(bookSource: BookSource, analyzeUrl: AnalyzeUrl, baseUrl: String, body: String, rule: io.legado.engine.data.rule.BookListRule, isSearch: Boolean): ArrayList<SearchBook> {
        val books = ArrayList<SearchBook>()
        val seen = linkedSetOf<String>() // 去重
        val isReverse = rule.bookList?.startsWith("-") == true
        val listRule = if (isReverse) rule.bookList?.substring(1) ?: "" else rule.bookList ?: ""
        try {
            val ar = AnalyzeRule(source = bookSource).setContent(body, baseUrl)
            val elements = ar.getElements(listRule)
            for (element in elements) {
                try {
                    val searchBook = SearchBook(origin = bookSource.bookSourceUrl, originName = bookSource.bookSourceName)
                    val itemAr = AnalyzeRule(ruleData = searchBook, source = bookSource).setContent(element, baseUrl)
                    val name = formatBookName(itemAr.getString(rule.name ?: "").trim())
                    val author = formatBookAuthor(itemAr.getString(rule.author ?: "").trim())
                    val bookUrl = itemAr.getString(rule.bookUrl ?: "", isUrl = true).trim()
                    if (name.isBlank() && bookUrl.isBlank()) continue
                    val absUrl = AnalyzeUrl.getAbsoluteURL(baseUrl, bookUrl)
                    // 去重
                    val dedupKey = absUrl.ifBlank { name }
                    if (!seen.add(dedupKey)) continue
                    searchBook.bookUrl = absUrl
                    searchBook.name = name
                    searchBook.author = author
                    searchBook.coverUrl = itemAr.getString(rule.coverUrl ?: "", isUrl = true).trim().takeIf { it.isNotBlank() }
                    searchBook.intro = formatHtml(itemAr.getString(rule.intro ?: "").trim()).takeIf { it.isNotBlank() }
                    searchBook.kind = itemAr.getString(rule.kind ?: "").trim().takeIf { it.isNotBlank() }
                    searchBook.latestChapterTitle = itemAr.getString(rule.lastChapter ?: "").trim().takeIf { it.isNotBlank() }
                    searchBook.wordCount = wordCountFormat(itemAr.getString(rule.wordCount ?: "").trim()).takeIf { it.isNotBlank() }
                    books.add(searchBook)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { Debug.log("BookList error: " + e.message) }
        if (isReverse) books.reverse()
        return books
    }

    /** 格式化字数：123456 -> "12.3万" */
    private fun wordCountFormat(raw: String): String {
        val num = raw.filter { it.isDigit() }.toLongOrNull() ?: return raw
        return when {
            num >= 100_000_000 -> "%.1f亿".format(num / 1e8)
            num >= 10_000 -> "%.1f万".format(num / 1e4)
            else -> raw
        }
    }

    /** 清理书名多余空白/符号 */
    private fun formatBookName(name: String): String {
        return name.replace(Regex("[\\s　]+"), " ").trim()
            .replace(Regex("^[《【\\[]|[》】\\]]$"), "").trim()
    }

    /** 清理作者多余空白/符号 */
    private fun formatBookAuthor(author: String): String {
        return author.replace(Regex("[\\s　]+"), " ").trim()
            .replace(Regex("^(作者|著|编|译)[：:]\\s*"), "").trim()
    }

    /** 清理HTML标签 */
    private fun formatHtml(html: String): String {
        return org.jsoup.Jsoup.parseBodyFragment(html).text()
            .replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
    }
}

object BookInfo {
    fun analyzeBookInfo(bookSource: BookSource, book: Book, baseUrl: String, body: String, rule: io.legado.engine.data.rule.BookInfoRule, canReName: Boolean = true) {
        try {
            val ar = AnalyzeRule(ruleData = book, source = bookSource).setContent(body, baseUrl)
            if (!rule.init.isNullOrBlank()) {
                ar.getElement(rule.init!!)?.let { initialized ->
                    ar.setContent(initialized, baseUrl)
                }
            }
            if (canReName && !rule.name.isNullOrBlank()) ar.getString(rule.name!!).let { if (it.isNotBlank()) book.name = it }
            if (!rule.author.isNullOrBlank()) ar.getString(rule.author!!).let { if (it.isNotBlank()) book.author = it }
            if (!rule.coverUrl.isNullOrBlank()) ar.getString(rule.coverUrl!!, isUrl = true).let { if (it.isNotBlank()) book.coverUrl = it }
            if (!rule.intro.isNullOrBlank()) ar.getString(rule.intro!!).let { if (it.isNotBlank()) book.intro = it }
            if (!rule.kind.isNullOrBlank()) ar.getString(rule.kind!!).let { if (it.isNotBlank()) book.kind = it }
            if (!rule.lastChapter.isNullOrBlank()) ar.getString(rule.lastChapter!!).let { if (it.isNotBlank()) book.latestChapterTitle = it }
            if (!rule.tocUrl.isNullOrBlank()) ar.getString(rule.tocUrl!!, isUrl = true).let { if (it.isNotBlank()) book.tocUrl = it }
            if (!rule.wordCount.isNullOrBlank()) ar.getString(rule.wordCount!!).let { if (it.isNotBlank()) book.wordCount = it }
        } catch (e: Exception) { Debug.log("BookInfo error: " + e.message) }
    }
}

object BookChapterList {
    fun analyzeChapterList(bookSource: BookSource, book: Book, baseUrl: String, body: String, rule: io.legado.engine.data.rule.TocRule): List<BookChapter> {
        val chapters = mutableListOf<BookChapter>()
        // Legado: chapterList 支持 - 前缀反转、+ 前缀去除
        var listRule = rule.chapterList ?: ""
        var reverse = false
        if (listRule.startsWith("-")) { reverse = true; listRule = listRule.substring(1) }
        if (listRule.startsWith("+")) { listRule = listRule.substring(1) }
        try {
            val ar = AnalyzeRule(source = bookSource).setContent(body, baseUrl)
            val elements = ar.getElements(listRule)
            for ((index, element) in elements.withIndex()) {
                try {
                    val itemAr = AnalyzeRule(ruleData = book, source = bookSource).setContent(element, baseUrl)
                    val chName = itemAr.getString(rule.chapterName ?: "").trim()
                    val chUrl = itemAr.getString(rule.chapterUrl ?: "", isUrl = true).trim()
                    if (chName.isBlank() && chUrl.isBlank()) continue
                    val absUrl = AnalyzeUrl.getAbsoluteURL(baseUrl, chUrl)
                    chapters.add(BookChapter(
                        bookUrl = book.bookUrl,
                        url = absUrl,
                        title = chName.ifBlank { "unknown" },
                        index = index,
                        baseUrl = baseUrl,
                        isVolume = rule.isVolume?.let { runCatching { toBooleanLenient(itemAr.getString(it)) }.getOrNull() } ?: false,
                        isVip = rule.isVip?.let { runCatching { toBooleanLenient(itemAr.getString(it)) }.getOrNull() } ?: false,
                        isPay = rule.isPay?.let { runCatching { toBooleanLenient(itemAr.getString(it)) }.getOrNull() } ?: false,
                        tag = rule.updateTime?.let { runCatching { itemAr.getString(it).trim() }.getOrNull()?.takeIf { s -> s.isNotBlank() } }
                    ))
                } catch (_: Exception) {}
            }
            // 批量应用 formatJs（与 Legado 一致，共享 gInt 计数器）
            if (!rule.formatJs.isNullOrBlank()) {
                val gInt = intArrayOf(0)
                for (ch in chapters) {
                    try {
                        val fAr = AnalyzeRule(ruleData = book, source = bookSource).setContent(ch.title, ch.url)
                        fAr.put("gInt", gInt[0].toString())
                        val formatted = fAr.getString(rule.formatJs!!)
                        if (formatted.isNotBlank()) ch.title = formatted
                        gInt[0]++
                    } catch (_: Exception) { gInt[0]++ }
                }
            }
        } catch (e: Exception) { Debug.log("BookChapterList error: " + e.message) }
        if (reverse) chapters.reverse()
        return chapters
    }

    private fun toBooleanLenient(value: String): Boolean {
        val v = value.trim().lowercase()
        return v == "1" || v == "true" || v == "yes" || v == "是"
    }
}
