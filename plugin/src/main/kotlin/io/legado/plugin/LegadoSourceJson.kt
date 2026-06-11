package io.legado.plugin

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.engine.constant.BookType
import io.legado.engine.data.BookSource
import io.legado.engine.shim.GSON

private typealias GsonJsonObject = JsonObject

object LegadoSourceJson {
    fun parseSource(json: String): BookSource? {
        val element = runCatching { JsonParser.parseString(json) }.getOrNull()
        return runCatching {
            if (element != null) {
                GSON.fromJson(migrateSourceJson(element), BookSource::class.java)
            } else {
                GSON.fromJson(json, BookSource::class.java)
            }
        }.getOrNull()
    }

    fun parseSources(json: String): List<BookSource> {
        val trimmed = json.trim()
        val root = runCatching { JsonParser.parseString(trimmed) }.getOrNull()
        if (root != null) {
            val sources = sourceElementsFromImportRoot(root)
                .mapNotNull { parseSourceElement(it) }
                .filter { it.bookSourceUrl.isNotBlank() }
            if (sources.isNotEmpty()) return sources
        }
        return runCatching {
            val array = JsonParser.parseString(trimmed).asJsonArray
            array.mapNotNull { parseSourceElement(it) }
        }.getOrNull()?.filter { it.bookSourceUrl.isNotBlank() } ?: emptyList()
    }

    fun migrateSourceJson(element: JsonElement): JsonElement {
        if (!element.isJsonObject) return element
        val obj = element.asJsonObject.deepCopy()
        copyMissing(obj, "enabled", "enable")
        copyMissing(obj, "customOrder", "serialNumber")
        copyMissing(obj, "bookUrlPattern", "ruleBookUrlPattern")
        copyMissing(obj, "header", "httpHeaders")
        migrateBookSourceType(obj)
        if (!obj.hasNonBlank("header")) {
            obj.getStringOrNull("httpUserAgent")?.let { ua ->
                obj.addProperty("header", GSON.toJson(mapOf("User-Agent" to ua)))
            }
        }
        copyMissingUrl(obj, "searchUrl", "ruleSearchUrl", single = true)
        copyMissingUrl(obj, "exploreUrl", "ruleFindUrl", single = false)
        putRuleObjectIfMissing(obj, "ruleSearch", mapOf(
            "checkKeyWord" to "ruleSearchCheckKeyWord",
            "bookList" to "ruleSearchList",
            "name" to "ruleSearchName",
            "author" to "ruleSearchAuthor",
            "intro" to "ruleSearchIntroduce",
            "kind" to "ruleSearchKind",
            "bookUrl" to "ruleSearchNoteUrl",
            "coverUrl" to "ruleSearchCoverUrl",
            "lastChapter" to "ruleSearchLastChapter",
            "updateTime" to "ruleSearchUpdateTime",
            "wordCount" to "ruleSearchWordCount"
        ))
        putRuleObjectIfMissing(obj, "ruleExplore", mapOf(
            "bookList" to "ruleFindList",
            "name" to "ruleFindName",
            "author" to "ruleFindAuthor",
            "intro" to "ruleFindIntroduce",
            "kind" to "ruleFindKind",
            "bookUrl" to "ruleFindNoteUrl",
            "coverUrl" to "ruleFindCoverUrl",
            "lastChapter" to "ruleFindLastChapter",
            "updateTime" to "ruleFindUpdateTime",
            "wordCount" to "ruleFindWordCount"
        ))
        putRuleObjectIfMissing(obj, "ruleBookInfo", mapOf(
            "init" to "ruleBookInfoInit",
            "name" to "ruleBookName",
            "author" to "ruleBookAuthor",
            "intro" to "ruleIntroduce",
            "kind" to "ruleBookKind",
            "coverUrl" to "ruleCoverUrl",
            "lastChapter" to "ruleBookLastChapter",
            "updateTime" to "ruleBookUpdateTime",
            "tocUrl" to "ruleChapterUrl",
            "wordCount" to "ruleBookWordCount",
            "canReName" to "ruleBookCanReName",
            "downloadUrls" to "ruleDownloadUrls"
        ))
        putRuleObjectIfMissing(obj, "ruleToc", mapOf(
            "preUpdateJs" to "ruleTocPreUpdateJs",
            "chapterList" to "ruleChapterList",
            "chapterName" to "ruleChapterName",
            "chapterUrl" to "ruleContentUrl",
            "formatJs" to "ruleChapterFormat",
            "isVolume" to "ruleChapterIsVolume",
            "isVip" to "ruleChapterIsVip",
            "isPay" to "ruleChapterIsPay",
            "updateTime" to "ruleChapterUpdateTime",
            "nextTocUrl" to "ruleChapterUrlNext"
        ))
        putRuleObjectIfMissing(obj, "ruleContent", mapOf(
            "content" to "ruleBookContent",
            "subContent" to "ruleBookSubContent",
            "title" to "ruleBookContentTitle",
            "webJs" to "ruleBookContentWebJs",
            "sourceRegex" to "ruleBookContentSourceRegex",
            "replaceRegex" to "ruleBookContentReplace",
            "nextContentUrl" to "ruleContentUrlNext",
            "imageStyle" to "ruleBookContentImageStyle",
            "imageDecode" to "ruleBookContentImageDecode",
            "payAction" to "ruleBookContentPayAction",
            "callBackJs" to "ruleBookContentCallBackJs"
        ))
        putRuleObjectIfMissing(obj, "ruleReview", mapOf(
            "reviewUrl" to "ruleReviewUrl",
            "avatarRule" to "ruleReviewAvatar",
            "contentRule" to "ruleReviewContent",
            "postTimeRule" to "ruleReviewPostTime",
            "reviewQuoteUrl" to "ruleReviewQuoteUrl",
            "voteUpUrl" to "ruleReviewVoteUpUrl",
            "voteDownUrl" to "ruleReviewVoteDownUrl",
            "postReviewUrl" to "ruleReviewPostReviewUrl",
            "postQuoteUrl" to "ruleReviewPostQuoteUrl",
            "deleteUrl" to "ruleReviewDeleteUrl"
        ))
        copyRuleAlias(obj, "ruleBookInfo", "canReName", "ruleCanReName")
        copyRuleAlias(obj, "ruleBookInfo", "downloadUrls", "ruleBookDownloadUrls")
        copyRuleAlias(obj, "ruleToc", "formatJs", "ruleChapterFormatJs")
        copyRuleAlias(obj, "ruleToc", "isVolume", "ruleChapterVolume", "ruleIsVolume")
        copyRuleAlias(obj, "ruleToc", "isVip", "ruleChapterVip", "ruleIsVip")
        copyRuleAlias(obj, "ruleToc", "isPay", "ruleChapterPay", "ruleIsPay")
        copyRuleAlias(obj, "ruleContent", "subContent", "ruleSubContent")
        copyRuleAlias(obj, "ruleContent", "title", "ruleContentTitle")
        copyRuleAlias(obj, "ruleContent", "webJs", "ruleContentWebJs")
        copyRuleAlias(obj, "ruleContent", "sourceRegex", "ruleContentSourceRegex")
        copyRuleAlias(obj, "ruleContent", "nextContentUrl", "ruleBookContentUrlNext")
        copyRuleAlias(obj, "ruleContent", "imageStyle", "ruleContentImageStyle")
        copyRuleAlias(obj, "ruleContent", "imageDecode", "ruleImageDecode", "ruleContentImageDecode")
        copyRuleAlias(obj, "ruleContent", "payAction", "rulePayAction", "ruleContentPayAction")
        copyRuleAlias(obj, "ruleContent", "callBackJs", "ruleCallBackJs", "ruleContentCallBackJs")
        copyRuleAlias(obj, "ruleReview", "reviewUrl", "ruleCommentUrl", "ruleReviewListUrl")
        copyRuleAlias(obj, "ruleReview", "avatarRule", "ruleAvatar", "ruleReviewAvatarRule")
        copyRuleAlias(obj, "ruleReview", "contentRule", "ruleReviewContentRule")
        copyRuleAlias(obj, "ruleReview", "postTimeRule", "rulePostTime", "ruleReviewTime")
        copyRuleAlias(obj, "ruleReview", "reviewQuoteUrl", "ruleQuoteUrl", "ruleReviewQuote")
        copyRuleAlias(obj, "ruleReview", "voteUpUrl", "ruleVoteUpUrl")
        copyRuleAlias(obj, "ruleReview", "voteDownUrl", "ruleVoteDownUrl")
        copyRuleAlias(obj, "ruleReview", "postReviewUrl", "rulePostReviewUrl")
        copyRuleAlias(obj, "ruleReview", "postQuoteUrl", "rulePostQuoteUrl")
        copyRuleAlias(obj, "ruleReview", "deleteUrl", "ruleDeleteReviewUrl")
        return obj
    }

    private fun sourceElementsFromImportRoot(element: JsonElement): List<JsonElement> {
        return when {
            element.isJsonArray -> element.asJsonArray.flatMap { sourceElementsFromImportRoot(it) }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                if (obj.has("bookSourceUrl")) {
                    listOf(obj)
                } else {
                    obj.entrySet().flatMap { (_, value) ->
                        if (value.isJsonArray || value.isJsonObject) sourceElementsFromImportRoot(value) else emptyList()
                    }
                }
            }
            else -> emptyList()
        }
    }

    private fun parseSourceElement(element: JsonElement): BookSource? {
        return runCatching {
            GSON.fromJson(migrateSourceJson(element), BookSource::class.java)
        }.getOrNull()
    }

    private fun migrateBookSourceType(obj: GsonJsonObject) {
        val type = obj.get("bookSourceType") ?: return
        if (!type.isJsonPrimitive) return
        val primitive = type.asJsonPrimitive
        if (primitive.isNumber) return
        val mapped = when (primitive.asString.trim().uppercase()) {
            "AUDIO" -> BookType.audio
            "IMAGE" -> BookType.image
            "FILE" -> BookType.file
            "VIDEO" -> BookType.video
            "TEXT", "DEFAULT" -> BookType.default
            else -> primitive.asString.toIntOrNull()
        }
        if (mapped != null) obj.addProperty("bookSourceType", mapped)
    }

    private fun copyMissing(obj: GsonJsonObject, newKey: String, oldKey: String) {
        if (!obj.hasNonBlank(newKey)) {
            obj.get(oldKey)?.let { if (!it.isJsonNull) obj.add(newKey, it.deepCopy()) }
        }
    }

    private fun copyMissingUrl(obj: GsonJsonObject, newKey: String, oldKey: String, single: Boolean) {
        if (!obj.hasNonBlank(newKey)) {
            val oldValue = obj.getStringOrNull(oldKey) ?: return
            val migrated = if (single) migrateOldUrl(oldValue) else migrateOldUrls(oldValue)
            if (!migrated.isNullOrBlank()) obj.addProperty(newKey, migrated)
        }
    }

    private fun putRuleObjectIfMissing(obj: GsonJsonObject, targetKey: String, fields: Map<String, String>) {
        val existed = obj.has(targetKey) && obj.get(targetKey).isJsonObject
        val target = if (existed) obj.getAsJsonObject(targetKey) else GsonJsonObject()
        fields.forEach { (newKey, oldKey) ->
            if (target.hasNonBlank(newKey)) return@forEach
            val value = obj.getStringOrNull(oldKey) ?: return@forEach
            var migrated = migrateOldRule(value)
            if (targetKey == "ruleContent" && newKey == "content" && migrated.startsWith("$") && !migrated.startsWith("$.")) {
                migrated = migrated.substring(1)
            }
            if (migrated.isNotBlank()) target.addProperty(newKey, migrated)
        }
        if (!existed && target.entrySet().isNotEmpty()) obj.add(targetKey, target)
    }

    private fun copyRuleAlias(obj: GsonJsonObject, targetKey: String, newKey: String, vararg oldKeys: String) {
        val existed = obj.has(targetKey) && obj.get(targetKey).isJsonObject
        val target = if (existed) obj.getAsJsonObject(targetKey) else GsonJsonObject()
        if (target.hasNonBlank(newKey)) return
        val oldValue = oldKeys.firstNotNullOfOrNull { obj.getStringOrNull(it) } ?: return
        var migrated = migrateOldRule(oldValue)
        if (targetKey == "ruleContent" && newKey == "content" && migrated.startsWith("$") && !migrated.startsWith("$.")) {
            migrated = migrated.substring(1)
        }
        if (migrated.isNotBlank()) {
            target.addProperty(newKey, migrated)
            if (!existed) obj.add(targetKey, target)
        }
    }

    private fun GsonJsonObject.hasNonBlank(key: String): Boolean = getStringOrNull(key)?.isNotBlank() == true

    private fun GsonJsonObject.getStringOrNull(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asString }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun migrateOldUrls(oldUrls: String?): String? {
        if (oldUrls.isNullOrBlank()) return null
        if (oldUrls.startsWith("@js:", true) || oldUrls.startsWith("<js>", true)) return oldUrls
        if (!oldUrls.contains("\n") && !oldUrls.contains("&&")) return migrateOldUrl(oldUrls)
        return oldUrls.split("(&&|\r?\n)+".toRegex())
            .mapNotNull { migrateOldUrl(it)?.replace("\n\\s*".toRegex(), "") }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    private fun migrateOldUrl(oldUrl: String?): String? {
        if (oldUrl.isNullOrBlank()) return null
        if (oldUrl.startsWith("<js>", true)) {
            return oldUrl.replace("=searchKey", "={{key}}")
                .replace("=searchPage", "={{page}}")
        }
        val option = linkedMapOf<String, String>()
        var url = oldUrl.trim()
        Regex("@Header:\\{[^}]*}", RegexOption.IGNORE_CASE).find(url)?.let { match ->
            url = url.replace(match.value, "")
            option["headers"] = match.value.substringAfter("@Header:")
        }
        val charsetSplit = url.split("|", limit = 2)
        url = charsetSplit[0]
        if (charsetSplit.size > 1) {
            charsetSplit[1].substringAfter("=", "").takeIf { it.isNotBlank() }?.let { option["charset"] = it }
        }
        val jsPattern = Regex("""\{[^{}]*}""")
        val jsList = mutableListOf<String>()
        jsPattern.findAll(url).forEach { match ->
            jsList.add(match.value)
            url = url.replace(match.value, "$" + jsList.lastIndex)
        }
        url = url.replace("{", "<").replace("}", ">")
            .replace("searchKey", "{{key}}")
            .replace("<searchPage([-+]1)>".toRegex(), "{{page$1}}")
            .replace("searchPage([-+]1)".toRegex(), "{{page$1}}")
            .replace("searchPage", "{{page}}")
        jsList.forEachIndexed { index, js ->
            url = url.replace("$" + index, js.replace("searchKey", "key").replace("searchPage", "page"))
        }
        val postSplit = url.split("@", limit = 2)
        url = postSplit[0]
        if (postSplit.size > 1) {
            option["method"] = "POST"
            option["body"] = postSplit[1]
        }
        return if (option.isEmpty()) url else "$url,${GSON.toJson(option)}"
    }

    private fun migrateOldRule(oldRule: String?): String {
        if (oldRule.isNullOrBlank()) return ""
        var newRule = oldRule
        var reverse = false
        var allInOne = false
        if (newRule.startsWith("-")) {
            reverse = true
            newRule = newRule.substring(1)
        }
        if (newRule.startsWith("+")) {
            allInOne = true
            newRule = newRule.substring(1)
        }
        if (!newRule.startsWith("@CSS:", true) &&
            !newRule.startsWith("@XPath:", true) &&
            !newRule.startsWith("//") &&
            !newRule.startsWith("##") &&
            !newRule.startsWith(":") &&
            !newRule.contains("@js:", true) &&
            !newRule.contains("<js>", true)
        ) {
            if (newRule.contains("#") && !newRule.contains("##")) newRule = oldRule.replace("#", "##")
            if (newRule.contains("|") && !newRule.contains("||")) {
                newRule = if (newRule.contains("##")) {
                    val list = newRule.split("##")
                    buildString {
                        append(list.first().replace("|", "||"))
                        list.drop(1).forEach { append("##").append(it) }
                    }
                } else {
                    newRule.replace("|", "||")
                }
            }
            if (newRule.contains("&") && !newRule.contains("&&") && !newRule.contains("http") && !newRule.startsWith("/")) {
                newRule = newRule.replace("&", "&&")
            }
        }
        if (allInOne) newRule = "+$newRule"
        if (reverse) newRule = "-$newRule"
        return newRule
    }
}
