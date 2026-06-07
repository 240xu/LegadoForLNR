package io.legado.engine.data

import io.legado.engine.constant.AppPattern
import io.legado.engine.shim.CacheManager
import io.legado.engine.shim.GSON
import io.legado.engine.shim.fromJsonObject
import java.security.MessageDigest

data class BookChapter(
    var url: String = "",
    var title: String = "",
    var isVolume: Boolean = false,
    var baseUrl: String = "",
    var bookUrl: String = "",
    var index: Int = 0,
    var isVip: Boolean = false,
    var isPay: Boolean = false,
    var resourceUrl: String? = null,
    var tag: String? = null,
    var wordCount: String? = null,
    var start: Long? = null,
    var end: Long? = null,
    var startFragmentId: String? = null,
    var endFragmentId: String? = null,
    var bookHtml: String? = null,
    var imgUrl: String? = null,
    var variable: String? = null,
) : RuleDataInterface {

    @delegate:Transient
    override val variableMap: HashMap<String, String> by lazy {
        GSON.fromJsonObject<HashMap<String, String>>(variable) ?: hashMapOf()
    }

    private var _titleMD5: String? = null

    val titleMD5: String?
        get() {
            if (_titleMD5 == null) {
                _titleMD5 = md5Encode16(title)
            }
            return _titleMD5
        }

    override fun putVariable(key: String, value: String?): Boolean {
        if (super.putVariable(key, value)) {
            variable = GSON.toJson(variableMap)
        }
        return true
    }

    override fun putBigVariable(key: String, value: String?) {
        if (value == null) {
            CacheManager.delete(bigVarKey(key))
        } else {
            CacheManager.put(bigVarKey(key), value)
        }
    }

    override fun getBigVariable(key: String): String? {
        return CacheManager.get(bigVarKey(key))
    }

    fun putImgUrl(value: String?) {
        imgUrl = value
    }

    fun putLyric(value: String?) {
        putVariable("lyric", value)
    }

    fun putDanmaku(value: String?) {
        putVariable("danmaku", value)
    }

    override fun hashCode() = url.hashCode()

    override fun equals(other: Any?): Boolean {
        if (other is BookChapter) return other.url == url
        return false
    }

    fun primaryStr(): String = bookUrl + url

    fun getDisplayTitle(): String {
        return title.replace(AppPattern.rnRegex, "")
    }

    fun getAbsoluteURL(): String {
        if (url.startsWith(title) && isVolume) return baseUrl
        return io.legado.engine.rule.AnalyzeUrl.getAbsoluteURL(baseUrl, url)
    }

    fun getFileName(suffix: String = "nb"): String {
        return String.format("%05d-%s.%s", index, titleMD5, suffix)
    }

    fun getFontName(): String {
        return String.format("%05d-%s.ttf", index, titleMD5)
    }

    private fun bigVarKey(key: String): String = "bv_${bookUrl}_${url}_$key"

    companion object {
        private fun md5Encode16(str: String): String {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(str.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}