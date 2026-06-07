package io.legado.engine.webBook

import io.legado.engine.data.*
import io.legado.engine.data.rule.ContentRule
import io.legado.engine.http.ConcurrentRateLimiter
import io.legado.engine.http.HttpResponse
import io.legado.engine.http.StrResponse
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

    /**
     * 对齐 lyc486: 执行请求并检查 loginCheckJs
     * loginCheckJs 作为后处理脚本，接收 StrResponse 作为 result
     */
    fun executeWithLoginCheck(bookSource: BookSource, analyzeUrl: AnalyzeUrl): HttpResponse {
        applyRateLimit(bookSource)
        val loginCheckJs = bookSource.loginCheckJs
        val response = try {
            val raw = analyzeUrl.execute()
            if (!loginCheckJs.isNullOrBlank()) {
                val strResp = StrResponse(raw)
                val checkResult = analyzeUrl.evalJS(loginCheckJs, strResp)
                if (checkResult is StrResponse) HttpResponse(checkResult.url, checkResult.body ?: "", checkResult.code)
                else raw
            } else raw
        } catch (throwable: Throwable) {
            if (!loginCheckJs.isNullOrBlank()) {
                try {
                    val errResp = StrResponse(HttpResponse(analyzeUrl.url, throwable.message ?: "", 500))
                    val checkResult = analyzeUrl.evalJS(loginCheckJs, errResp)
                    if (checkResult is StrResponse) {
                        if (checkResult.code == 500) throw throwable
                        HttpResponse(checkResult.url, checkResult.body ?: "", checkResult.code)
                    } else throw throwable
                } catch (_: Throwable) { throw throwable }
            } else throw throwable
        }
        return response
    }

    // ==================== 对齐 lyc486: runPreUpdateJs 独立方法 ====================

    /**
     * 对齐 lyc486 WebBook.runPreUpdateJs()
     * 执行书源的 preUpdateJs 脚本
     */
    fun runPreUpdateJs(bookSource: BookSource, book: Book, isFromBookInfo: Boolean = false) {
        val rule = bookSource.getTocRule()
        if (!rule.preUpdateJs.isNullOrBlank()) {
            try {
                val ar = AnalyzeRule(ruleData = book, source = bookSource, preUpdateJs = true)
                ar.setFromBookInfo(isFromBookInfo)
                ar.evalJS(rule.preUpdateJs!!)
            } catch (e: Exception) {
                Debug.log(bookSource.bookSourceUrl, "preUpdateJs error: ${e.message}")
            }
        }
    }

    // ==================== 搜索 ====================

    fun searchBookAwait(bookSource: BookSource, key: String, page: Int? = 1): ArrayList<SearchBook> {
        val searchUrl = bookSource.searchUrl
        if (searchUrl.isNullOrBlank()) return arrayListOf()
        val analyzeUrl = AnalyzeUrl(mUrl = searchUrl, key = key, page = page, baseUrl = bookSource.bookSourceUrl, source = bookSource)
        val res = executeWithLoginCheck(bookSource, analyzeUrl)
        val rule = bookSource.getSearchRule()
        return BookList.analyzeBookList(bookSource, analyzeUrl, res.url, res.body, rule, isSearch = true)
    }

    // ==================== 对齐 lyc486: 精确搜索 ====================

    /**
     * 对齐 lyc486 WebBook.preciseSearchAwait()
     * 按书名+作者精确搜索，返回匹配的第一本书
     */
    fun preciseSearchAwait(bookSource: BookSource, name: String, author: String): Book? {
        return try {
            val results = searchBookAwait(bookSource, name)
            val matched = results.firstOrNull { it.name == name && it.author == author }
            matched?.toBook()
        } catch (e: Exception) {
            Debug.log(bookSource.bookSourceUrl, "preciseSearch error: ${e.message}")
            null
        }
    }

    // ==================== 发现 ====================

    fun exploreBookAwait(bookSource: BookSource, url: String, page: Int? = 1): ArrayList<SearchBook> {
        val analyzeUrl = AnalyzeUrl(mUrl = url, page = page, baseUrl = bookSource.bookSourceUrl, source = bookSource)
        val res = executeWithLoginCheck(bookSource, analyzeUrl)
        val exploreRule = bookSource.getExploreRule()
        val rule = if (exploreRule.bookList.isNullOrBlank()) bookSource.getSearchRule() else exploreRule
        return BookList.analyzeBookList(bookSource, analyzeUrl, res.url, res.body, rule, isSearch = false)
    }

    // ==================== 详情 ====================

    fun getBookInfoAwait(bookSource: BookSource, book: Book, canReName: Boolean = true): Book {
        val rule = bookSource.getBookInfoRule()
        val analyzeUrl = AnalyzeUrl(mUrl = book.bookUrl, baseUrl = bookSource.bookSourceUrl, source = bookSource, ruleData = book)
        val res = executeWithLoginCheck(bookSource, analyzeUrl)
        BookInfo.analyzeBookInfo(bookSource, book, res.url, res.body, rule, canReName)
        return book
    }

    // ==================== 目录 ====================

    fun getChapterListAwait(bookSource: BookSource, book: Book): List<BookChapter> {
        val rule = bookSource.getTocRule()
        // 对齐 lyc486: 使用独立 runPreUpdateJs
        runPreUpdateJs(bookSource, book)
        val allChapters = mutableListOf<BookChapter>()
        var currentUrl: String? = book.tocUrl?.ifBlank { book.bookUrl } ?: book.bookUrl
        var pageCount = 0
        while (currentUrl != null && pageCount < 20) {
            val analyzeUrl = AnalyzeUrl(mUrl = currentUrl!!, baseUrl = book.bookUrl, source = bookSource, ruleData = book)
            val res = executeWithLoginCheck(bookSource, analyzeUrl)
            val chapters = BookChapterList.analyzeChapterList(bookSource, book, res.url, res.body, rule)
            allChapters.addAll(chapters)
            currentUrl = if (!rule.nextTocUrl.isNullOrBlank()) {
                val ar = AnalyzeRule(source = bookSource).setContent(res.body, res.url)
                val next = ar.getString(rule.nextTocUrl!!)
                if (next.isNotBlank() && next != currentUrl) AnalyzeUrl.getAbsoluteURL(res.url, next) else null
            } else null
            pageCount++
        }
        return allChapters
    }

    // ==================== 正文 ====================

    fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null
    ): String {
        val contentRule = bookSource.getContentRule()
        if (contentRule.content.isNullOrEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "正文规则为空,使用章节链接:${bookChapter.url}")
            return bookChapter.url
        }
        // 对齐 lyc486: 一级目录不解析规则
        if (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title)) {
            Debug.log(bookSource.bookSourceUrl, "一级目录正文不解析规则")
            return bookChapter.tag ?: ""
        }
        val baseUrl = bookChapter.url
        val redirectUrl = baseUrl
        val body: String
        // 对齐 lyc486: 当 chapterUrl == bookUrl 时使用 tocHtml
        if (bookChapter.url == book.bookUrl && !book.tocHtml.isNullOrEmpty()) {
            body = book.tocHtml!!
        } else {
            val analyzeUrl = AnalyzeUrl(mUrl = baseUrl, baseUrl = book.tocUrl ?: "", source = bookSource, ruleData = book, chapter = bookChapter)
            val res = executeWithLoginCheck(bookSource, analyzeUrl)
            body = if (!contentRule.sourceRegex.isNullOrBlank()) {
                try { Regex(contentRule.sourceRegex!!).find(res.body)?.value ?: res.body } catch (_: Exception) { res.body }
            } else res.body
        }
        // 解析正文
        val ar = AnalyzeRule(source = bookSource).setContent(body, baseUrl)
        ar.setChapter(bookChapter)
        // webJs 后处理
        var processedBody = body
        if (!contentRule.webJs.isNullOrBlank()) {
            ar.evalJS(contentRule.webJs!!, body)?.toString()?.takeIf { it.isNotBlank() }?.let { webBody ->
                processedBody = webBody
                ar.setContent(processedBody, baseUrl).setChapter(bookChapter)
            }
        }
        val allParts = mutableListOf<String>()
        // 主内容
        allParts.addAll(ar.getStringList(contentRule.content ?: "") ?: emptyList())
        // 副内容
        if (!contentRule.subContent.isNullOrBlank()) {
            val subContent = ar.getString(contentRule.subContent!!)
            if (subContent.isNotBlank()) {
                if (subContent.startsWith("http", true)) {
                    try {
                        val subRes = AnalyzeUrl(mUrl = subContent, source = bookSource, ruleData = book).getStrResponse()
                        allParts.add(subRes.body ?: "")
                    } catch (_: Exception) {}
                } else {
                    allParts.add(subContent)
                }
            }
        }
        // 多页正文（nextContentUrl）
        if (!contentRule.nextContentUrl.isNullOrBlank()) {
            val nextUrls = ar.getStringList(contentRule.nextContentUrl!!, isUrl = true) ?: emptyList()
            for (nextUrl in nextUrls) {
                if (nextUrl.isBlank() || nextUrl == baseUrl) continue
                try {
                    val absUrl = AnalyzeUrl.getAbsoluteURL(redirectUrl, nextUrl)
                    val nextRes = AnalyzeUrl(mUrl = absUrl, source = bookSource, ruleData = book, chapter = bookChapter).getStrResponse()
                    val nextAr = AnalyzeRule(source = bookSource).setContent(nextRes.body ?: "", nextRes.url).setChapter(bookChapter)
                    allParts.addAll(nextAr.getStringList(contentRule.content ?: "") ?: emptyList())
                } catch (_: Exception) {}
            }
        }
        var contentStr = allParts.joinToString("\n")
        // replaceRegex — 对齐 lyc486: 使用 analyzeRule.getString 执行替换规则
        if (!contentRule.replaceRegex.isNullOrBlank()) {
            try {
                val replaced = ar.getString(contentRule.replaceRegex!!, contentStr)
                if (replaced.isNotBlank()) contentStr = replaced
            } catch (_: Exception) {}
        }
        // 对齐 lyc486: title 规则解析
        if (!contentRule.title.isNullOrBlank()) {
            try {
                val title = ar.getString(contentRule.title!!)
                if (title.isNotBlank()) bookChapter.title = title
            } catch (_: Exception) {}
        }
        return contentStr
    }

    // ==================== 辅助：replaceRegex 应用 ====================

    fun applyReplaceRegex(text: String, replaceRules: String): String {
        var result = text
        for (rule in replaceRules.split("\n").filter { it.isNotBlank() }) {
            try {
                when {
                    rule.contains("::") -> { val parts = rule.split("::", limit = 2); result = result.replace(Regex(parts[0].trim()), parts[1].trim()) }
                    rule.startsWith("/") -> { val clean = rule.removeSurrounding("/"); val parts = clean.split("/", limit = 2); if (parts.size >= 2) result = result.replace(Regex(parts[0]), parts[1]) }
                }
            } catch (_: Exception) {}
        }
        return result
    }
}

// ==================== BookList ====================

object BookList {
    fun analyzeBookList(
        bookSource: BookSource,
        analyzeUrl: AnalyzeUrl,
        baseUrl: String,
        body: String,
        rule: io.legado.engine.data.rule.BookListRule,
        isSearch: Boolean
    ): ArrayList<SearchBook> {
        val books = ArrayList<SearchBook>()
        try {
            val ar = AnalyzeRule(source = bookSource).setContent(body, baseUrl)
            // 对齐 lyc486: 支持 -/+ 前缀反转列表
            var listRule = rule.bookList ?: ""
            var reverse = false
            if (listRule.startsWith("-")) { reverse = true; listRule = listRule.substring(1) }
            if (listRule.startsWith("+")) { listRule = listRule.substring(1) }
            val elements = ar.getElements(listRule)
            val items = if (reverse) elements.reversed() else elements
            for (element in items) {
                try {
                    val searchBook = SearchBook(origin = bookSource.bookSourceUrl, originName = bookSource.bookSourceName)
                    val itemAr = AnalyzeRule(ruleData = searchBook, source = bookSource).setContent(element, baseUrl)
                    val name = itemAr.getString(rule.name ?: "").trim()
                    val author = itemAr.getString(rule.author ?: "").trim()
                    val bookUrl = itemAr.getString(rule.bookUrl ?: "", isUrl = true).trim()
                    if (name.isBlank() && bookUrl.isBlank()) continue
                    val absUrl = AnalyzeUrl.getAbsoluteURL(baseUrl, bookUrl)
                    searchBook.bookUrl = absUrl
                    searchBook.name = name
                    searchBook.author = author
                    searchBook.coverUrl = itemAr.getString(rule.coverUrl ?: "", isUrl = true).trim().takeIf { it.isNotBlank() }
                    searchBook.intro = itemAr.getString(rule.intro ?: "").trim().takeIf { it.isNotBlank() }
                    searchBook.kind = itemAr.getString(rule.kind ?: "").trim().takeIf { it.isNotBlank() }
                    searchBook.latestChapterTitle = itemAr.getString(rule.lastChapter ?: "").trim().takeIf { it.isNotBlank() }
                    searchBook.wordCount = itemAr.getString(rule.wordCount ?: "").trim().takeIf { it.isNotBlank() }
                    books.add(searchBook)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { Debug.log("BookList error: " + e.message) }
        return books
    }
}

// ==================== BookInfo ====================

object BookInfo {
    fun analyzeBookInfo(
        bookSource: BookSource,
        book: Book,
        baseUrl: String,
        body: String,
        rule: io.legado.engine.data.rule.BookInfoRule,
        canReName: Boolean = true
    ) {
        try {
            val ar = AnalyzeRule(ruleData = book, source = bookSource).setContent(body, baseUrl)
            if (!rule.init.isNullOrBlank()) {
                ar.getElement(rule.init!!)?.let { initialized ->
                    ar.setContent(initialized, baseUrl)
                }
            }
            val mCanReName = canReName && !rule.canReName.isNullOrBlank()
            if (!rule.name.isNullOrBlank()) ar.getString(rule.name!!).let { if (it.isNotBlank() && (mCanReName || book.name.isEmpty())) book.name = it }
            if (!rule.author.isNullOrBlank()) ar.getString(rule.author!!).let { if (it.isNotBlank() && (mCanReName || book.author.isEmpty())) book.author = it }
            if (!rule.coverUrl.isNullOrBlank()) ar.getString(rule.coverUrl!!, isUrl = true).let { if (it.isNotBlank()) book.coverUrl = it }
            if (!rule.intro.isNullOrBlank()) ar.getString(rule.intro!!).let { if (it.isNotBlank()) book.intro = it }
            if (!rule.kind.isNullOrBlank()) ar.getString(rule.kind!!).let { if (it.isNotBlank()) book.kind = it }
            if (!rule.lastChapter.isNullOrBlank()) ar.getString(rule.lastChapter!!).let { if (it.isNotBlank()) book.latestChapterTitle = it }
            if (!rule.tocUrl.isNullOrBlank()) ar.getString(rule.tocUrl!!, isUrl = true).let { if (it.isNotBlank()) book.tocUrl = it }
            if (!rule.wordCount.isNullOrBlank()) ar.getString(rule.wordCount!!).let { if (it.isNotBlank()) book.wordCount = it }
            if (book.tocUrl.isBlank()) book.tocUrl = baseUrl
            if (book.tocUrl == baseUrl && body.isNotBlank()) book.tocHtml = body
            if (!rule.downloadUrls.isNullOrBlank()) {
                val urls = ar.getStringList(rule.downloadUrls!!, isUrl = true)
                if (!urls.isNullOrEmpty()) book.downloadUrls = urls
            }
        } catch (e: Exception) { Debug.log("BookInfo error: " + e.message) }
    }
}

// ==================== BookChapterList ====================

object BookChapterList {
    private val wordCountRegex = Regex("(?:^|[\\u5B57\\u6570\\u3010\\u3011\\uFF0C\\u3001\\uFF0C]|\\s+)([0-9\\u4E07\\u5343\\u767E\\u5341\\.]{1,6}\\u5B57)")

    /**
     * 对齐 lyc486 BookChapterList.analyzeChapterList()
     * 支持 -/+ 前缀反转、isVolume/vip/pay 规则解析、upChapterInfo 持久化
     */
    fun analyzeChapterList(
        bookSource: BookSource,
        book: Book,
        baseUrl: String,
        body: String,
        rule: io.legado.engine.data.rule.TocRule
    ): List<BookChapter> {
        val chapters = mutableListOf<BookChapter>()
        try {
            val ar = AnalyzeRule(source = bookSource).setContent(body, baseUrl)
            // 对齐 lyc486: 支持 -/+ 前缀
            var listRule = rule.chapterList ?: ""
            var reverse = false
            if (listRule.startsWith("-")) { reverse = true; listRule = listRule.substring(1) }
            if (listRule.startsWith("+")) { listRule = listRule.substring(1) }
            val elements = ar.getElements(listRule)
            val items = if (reverse) elements.reversed() else elements
            for ((index, element) in items.withIndex()) {
                try {
                    val itemAr = AnalyzeRule(ruleData = book, source = bookSource).setContent(element, baseUrl)
                    val chName = itemAr.getString(rule.chapterName ?: "").trim()
                    val chUrl = itemAr.getString(rule.chapterUrl ?: "", isUrl = true).trim()
                    if (chName.isBlank() && chUrl.isBlank()) continue
                    val absUrl = AnalyzeUrl.getAbsoluteURL(baseUrl, chUrl)
                    var finalTitle = chName
                    if (!rule.formatJs.isNullOrBlank()) {
                        try {
                            val fAr = AnalyzeRule(source = bookSource).setContent(chName, absUrl)
                            val formatted = fAr.getString(rule.formatJs!!)
                            if (formatted.isNotBlank()) finalTitle = formatted
                        } catch (_: Exception) {}
                    }
                    val info = itemAr.getString(rule.updateTime ?: "").trim()
                    val ch = BookChapter(bookUrl = book.bookUrl, url = absUrl, title = finalTitle.ifBlank { "unknown" }, index = index, baseUrl = baseUrl)
                    // 对齐 lyc486: isVolume 解析
                    if (!rule.isVolume.isNullOrBlank()) {
                        val isVolumeStr = itemAr.getString(rule.isVolume!!)
                        if (isVolumeStr == "true" || isVolumeStr == "1") {
                            ch.isVolume = true
                            ch.tag = info
                        }
                    }
                    // 对齐 lyc486: isVip / isPay 解析
                    if (!rule.isVip.isNullOrBlank()) {
                        val vipStr = itemAr.getString(rule.isVip!!)
                        if (vipStr == "true" || vipStr == "1") ch.isVip = true
                    }
                    if (!rule.isPay.isNullOrBlank()) {
                        val payStr = itemAr.getString(rule.isPay!!)
                        if (payStr == "true" || payStr == "1") ch.isPay = true
                    }
                    if (!ch.isVolume) {
                        if (info.isNotBlank()) {
                            wordCountRegex.find(info)?.let { match ->
                                ch.wordCount = match.groupValues[1].trim()
                                ch.tag = info.replaceFirst(match.value, "")
                            } ?: run { ch.tag = info }
                        }
                    }
                    chapters.add(ch)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { Debug.log("BookChapterList error: " + e.message) }
        return chapters
    }
}