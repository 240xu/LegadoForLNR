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

