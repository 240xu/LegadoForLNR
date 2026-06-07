package io.legado.engine.http

/**
 * StrResponse — 对齐 lyc486 Legado 的 StrResponse
 * 封装 HttpResponse，暴露 raw/body/url/code/header/isSuccessful 等属性，
 * 使得 loginCheckJs / JS 脚本中可直接访问 response.url / response.code / response.body 等。
 */
class StrResponse(val raw: HttpResponse) {

    /** 响应体文本 */
    var body: String? = raw.body
        private set

    /** 最终请求 URL */
    val url: String get() = raw.url

    /** HTTP 状态码 */
    val code: Int get() = raw.code

    /** 响应头 */
    val headers: Map<String, List<String>> get() = raw.headers

    fun body(): String? = body
    fun bodyString(): String = body ?: ""
    fun url(): String = url
    fun code(): Int = code
    fun raw(): HttpResponse = raw
    fun header(name: String): String = raw.header(name)
    fun isSuccessful(): Boolean = raw.isSuccessful()
    fun message(): String = ""

    /** 是否为重定向响应 */
    val isRedirect: Boolean
        get() = code in 301..308 || header("Location").isNotBlank()

    override fun toString(): String = body ?: ""
}
