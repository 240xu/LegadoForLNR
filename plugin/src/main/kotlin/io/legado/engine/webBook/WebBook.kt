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
                // Legado 模式：将 response 作为 result 绑定传给 evalJS
                val checkResult = bookSource.evalJS(loginCheckJs) { b ->
                    b["result"] = io.legado.engine.http.StrResponse(response)
                    b["book"] = null
                    b["chapter"] = null
                }
                if (checkResult == false || checkResult?.toString() == "false") {
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
                } else if (checkResult is io.legado.engine.http.StrResponse) {
                    // checkJs 返回了修改后的 StrResponse
                    response = io.legado.engine.http.HttpResponse(
                        checkResult.url, checkResult.body(), checkResult.code, checkResult.headers
                    )
                }
            } catch (e: Exception) {
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
                val next = ar.getString(rule.nextTocUrl!!)
                if (next.isNotBlank() && next != currentUrl) AnalyzeUrl.getAbsoluteURL(currentUrl!!, next) else null
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
            allParts.addAll(ar.getStringList(contentRule.content ?: "") ?: emptyList())
            if (!contentRule.subContent.isNullOrBlank()) allParts.addAll(ar.getStringList(contentRule.subContent!!) ?: emptyList())
            if (chTitle.isBlank() && !contentRule.title.isNullOrBlank()) chTitle = ar.getString(contentRule.title!!)
            currentUrl = if (!contentRule.nextContentUrl.isNullOrBlank()) {
                val next = ar.getString(contentRule.nextContentUrl!!)
                if (next.isNotBlank() && next != currentUrl) AnalyzeUrl.getAbsoluteURL(res.url, next) else null
            } else null
            pageCount++
        }
        var content = allParts.joinToString("\n")
        if (!contentRule.replaceRegex.isNullOrBlank()) content = applyReplaceRegex(content, contentRule.replaceRegex!!)
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
        try {
            val ar = AnalyzeRule(source = bookSource).setContent(body, baseUrl)
            val elements = ar.getElements(rule.chapterList ?: "")
            for ((index, element) in elements.withIndex()) {
                try {
                    val itemAr = AnalyzeRule(ruleData = book, source = bookSource).setContent(element, baseUrl)
                    val chName = itemAr.getString(rule.chapterName ?: "").trim()
                    val chUrl = itemAr.getString(rule.chapterUrl ?: "", isUrl = true).trim()
                    if (chName.isBlank() && chUrl.isBlank()) continue
                    val absUrl = AnalyzeUrl.getAbsoluteURL(baseUrl, chUrl)
                    var finalTitle = chName
                    if (!rule.formatJs.isNullOrBlank()) {
                        try { val fAr = AnalyzeRule(source = bookSource).setContent(chName, absUrl); val formatted = fAr.getString(rule.formatJs!!); if (formatted.isNotBlank()) finalTitle = formatted } catch (_: Exception) {}
                    }
                    chapters.add(BookChapter(
                        bookUrl = book.bookUrl,
                        url = absUrl,
                        title = finalTitle.ifBlank { "unknown" },
                        index = index,
                        baseUrl = baseUrl,
                        isVolume = rule.isVolume?.let { runCatching { toBooleanLenient(itemAr.getString(it)) }.getOrNull() } ?: false,
                        isVip = rule.isVip?.let { runCatching { toBooleanLenient(itemAr.getString(it)) }.getOrNull() } ?: false,
                        isPay = rule.isPay?.let { runCatching { toBooleanLenient(itemAr.getString(it)) }.getOrNull() } ?: false,
                        updateTime = rule.updateTime?.let { runCatching { itemAr.getString(it).toLongOrNull() ?: 0L }.getOrNull() } ?: 0L
                    ))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { Debug.log("BookChapterList error: " + e.message) }
        return chapters
    }

    private fun toBooleanLenient(value: String): Boolean {
        val v = value.trim().lowercase()
        return v == "1" || v == "true" || v == "yes" || v == "是"
    }
}
