#!/usr/bin/env kotlin

import java.io.FileInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Properties
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

fun loadProperties(): Properties {
    val props = Properties()
    try {
        val path = "${System.getProperty("user.dir")}/application.properties"
        props.load(FileInputStream(path))
    } catch (e: Exception) { }
    return props
}

val props = loadProperties()
val apiKey = props.getProperty("tcs.apiKey", "")

val trustAll = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}
val sslContext = SSLContext.getInstance("TLS")
sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())

val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(15))
    .sslContext(sslContext)
    .build()

val accountId = props.getProperty("tcs.accountId", "")

// GetWithdrawLimits
val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetWithdrawLimits"
val body = """{"accountId": "$accountId"}"""

val resp = http.send(
    HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
    HttpResponse.BodyHandlers.ofString()
)

println("=== GetWithdrawLimits Response ===")
println(resp.body())
