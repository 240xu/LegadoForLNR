package io.legado.engine.http

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object SSLHelper {
    val unsafeTrustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val unsafeHostnameVerifier: HostnameVerifier = HostnameVerifier { _, _ -> true }

    val unsafeSSLSocketFactory: SSLSocketFactory by lazy {
        try {
            val trustAllCerts = arrayOf<TrustManager>(unsafeTrustManager)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            sslContext.socketFactory
        } catch (_: Exception) {
            SSLContext.getDefault().socketFactory
        }
    }

    val trustAllCerts: Array<TrustManager> = arrayOf(unsafeTrustManager)
}