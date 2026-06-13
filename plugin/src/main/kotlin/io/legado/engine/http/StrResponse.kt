package io.legado.engine.http

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

    override fun toString(): String = response.body
}

/**
 * Jsoup Connection.Response 的兼容包装，提供与 Legado StrResponse 一致的接口。
 * JS 代码访问 .body (属性) / .url() / .code() / .statusCode 均可正常工作。
 */
class JsoupResponse(private val response: org.jsoup.Connection.Response) {
    /** Legado 的 body 是属性，Jsoup 的是方法 body()，这里提供属性兼容 */
    val body: String get() = response.body()
    val url: String get() = response.url().toString()
    val code: Int get() = response.statusCode()
    val statusCode: Int get() = response.statusCode()
    val headers: Map<String, List<String>> get() = response.headers().mapValues { listOf(it.value) }

    fun bodyString(): String = response.body()
    fun header(name: String): String = response.header(name) ?: ""
    fun isSuccessful(): Boolean = response.statusCode() in 200..299

    override fun toString(): String = response.body()
}
