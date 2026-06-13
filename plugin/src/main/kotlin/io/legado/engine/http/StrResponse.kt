package io.legado.engine.http

/**
 * 与 Legado StrResponse 接口兼容的响应包装。
 * Legado JS 代码可通过 response.body / response.code() / response.url() / response.isSuccessful() 访问。
 */
class StrResponse(
    private val response: HttpResponse
) {
    val url: String get() = response.url
    val code: Int get() = response.code
    val headers: Map<String, List<String>> get() = response.headers

    fun body(): String = response.body
    fun bodyString(): String = response.body
    fun header(name: String): String = response.header(name)
    fun isSuccessful(): Boolean = response.isSuccessful()
    fun code(): Int = response.code
    fun message(): String = if (response.isSuccessful()) "OK" else "Error ${response.code}"
    fun errorBody(): String? = if (response.isSuccessful()) null else response.body
    fun raw(): HttpResponse = response
    val callTime: Long = 0L

    override fun toString(): String = response.body
}

/**
 * Jsoup Connection.Response 的兼容包装。
 * JS 代码访问 .body (属性) / .url() / .code() / .statusCode 均可正常工作。
 */
class JsoupResponse(private val response: org.jsoup.Connection.Response) {
    val body: String get() = response.body()
    val url: String get() = response.url().toString()
    val code: Int get() = response.statusCode()
    val statusCode: Int get() = response.statusCode()
    val headers: Map<String, List<String>> get() = response.headers().mapValues { listOf(it.value) }

    fun bodyString(): String = response.body()
    fun header(name: String): String = response.header(name) ?: ""
    fun cookie(name: String): String? = response.cookie(name)
    fun cookies(): Map<String, String> = response.cookies()
    fun isSuccessful(): Boolean = response.statusCode() in 200..299
    fun code(): Int = response.statusCode()
    fun message(): String = response.statusMessage()
    fun errorBody(): String? = if (response.statusCode() in 200..299) null else response.body()
    fun raw(): org.jsoup.Connection.Response = response

    override fun toString(): String = response.body()
}
