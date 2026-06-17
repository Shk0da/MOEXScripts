#!/usr/bin/env kotlin

import java.io.FileInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.LocalDate
import java.util.Properties
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

fun loadProperties(): Properties {
    val props = Properties()
    try {
        val path = "${System.getProperty("user.dir")}/application.properties"
        props.load(FileInputStream(path))
    } catch (e: Exception) {
        println("  [WARN] application.properties: ${e.message}")
    }
    return props
}

val tcsApiKey = loadProperties().getProperty("tcs.apiKey", "")

data class NewsItem(
    val id: String,
    val title: String,
    val text: String,
    val source: String = "",
    val time: String = "",
    val instrumentIds: List<String> = emptyList()
)

fun createTrustAllSSLContext(): SSLContext {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    return ctx
}

// --- JSON helpers ---

fun extractString(json: String, key: String): String {
    val m = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)
    return m?.groupValues?.get(1) ?: ""
}

fun extractStringArray(json: String, key: String): List<String> {
    val idx = json.indexOf("\"$key\"")
    if (idx < 0) return emptyList()
    val rest = json.substring(idx)
    val start = rest.indexOf('[')
    if (start < 0) return emptyList()
    val result = mutableListOf<String>()
    var inStr = false
    val current = StringBuilder()
    for (i in (start + 1) until rest.length) {
        val c = rest[i]
        when {
            c == '"' -> {
                inStr = !inStr
                if (!inStr && current.isNotEmpty()) {
                    result.add(current.toString())
                    current.clear()
                }
            }
            inStr -> current.append(c)
            c == ']' -> break
        }
    }
    return result
}

fun parseJsonArray(json: String, key: String): List<String> {
    val idx = json.indexOf("\"$key\"")
    if (idx < 0) return emptyList()
    val rest = json.substring(idx)
    val start = rest.indexOf('[')
    if (start < 0) return emptyList()
    var depth = 0
    var inStr = false
    val items = mutableListOf<String>()
    var objStart = -1
    for (i in (start + 1) until rest.length) {
        val c = rest[i]
        when {
            c == '"' -> inStr = !inStr
            inStr -> continue
            c == '{' -> {
                if (depth == 0) objStart = i
                depth++
            }
            c == '}' -> {
                depth--
                if (depth == 0 && objStart >= 0) {
                    items.add(rest.substring(objStart, i + 1))
                    objStart = -1
                }
            }
            c == ']' && depth == 0 -> break
        }
    }
    return items
}

// --- News API client ---

class NewsClient {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .sslContext(createTrustAllSSLContext())
        .build()

    fun getNews(limit: Int = 100): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        var cursor = ""

        do {
            val body = buildString {
                append("{\"limit\": $limit")
                if (cursor.isNotEmpty()) {
                    append(", \"cursor\": \"$cursor\"")
                }
                append("}")
            }

            val json = post(body)
            if (json.isEmpty()) break

            // Extract cursor for next page (check hasNext:true explicitly)
            val hasNext = json.contains("\"hasNext\":true") || json.contains("\"hasNext\": true")
            cursor = extractString(json, "nextCursor")
                .ifEmpty { extractString(json, "cursor") }

            val newsList = parseJsonArray(json, "items")

            for (itemJson in newsList) {
                val id = extractString(itemJson, "id").ifEmpty { continue }
                val title = extractString(itemJson, "title").ifEmpty { continue }
                val content = extractString(itemJson, "content")
                    .ifEmpty { extractString(itemJson, "text") }
                val source = extractString(itemJson, "source")
                val time = extractString(itemJson, "ts")
                    .ifEmpty { extractString(itemJson, "time") }

                // Extract instrument tickers from instrumentId array
                val instrIds = extractInstrumentTickers(itemJson)

                items.add(NewsItem(id, title, content, source, time, instrIds))
            }

            if (newsList.isEmpty()) break
            if (!hasNext && cursor.isEmpty()) break
        } while (true)

        return items
    }

    // Extract tickers from instrumentId array: [{"instrument": {"ticker": "SBER", ...}}, ...]
    private fun extractInstrumentTickers(json: String): List<String> {
        val result = mutableListOf<String>()
        val instrArray = parseJsonArray(json, "instrumentId")
        for (instr in instrArray) {
            val ticker = extractString(instr, "ticker")
            if (ticker.isNotEmpty()) result.add(ticker)
        }
        return result
    }

    private fun post(body: String): String {
        return try {
            val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/News"
            val resp = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer $tcsApiKey")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (resp.statusCode() != 200) {
                println("  [ERROR] News API: HTTP ${resp.statusCode()}")
                println("  ${resp.body().take(300)}")
                return ""
            }
            resp.body()
        } catch (e: Exception) {
            println("  [ERROR] News API: ${e.message}")
            ""
        }
    }
}

// --- Ollama LLM client ---

class LlmClient(private val model: String = "0xroyce/plutus") {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(60))
        .build()

    fun analyze(newsItems: List<NewsItem>): String {
        if (newsItems.isEmpty()) return "Нет новостей для анализа"

        val prompt = buildPrompt(newsItems)
        val response = sendToOllama(prompt)
        return extractContent(response)
    }

    private fun buildPrompt(items: List<NewsItem>): String {
        val sb = StringBuilder()
        sb.appendLine("Сегодня ${LocalDate.now()}. Проанализируй следующие финансовые новости Т-Банка и их влияние на российский фондовый рынок.")
        sb.appendLine()
        sb.appendLine("Новости:")
        sb.appendLine()

        items.forEachIndexed { i, item ->
            sb.appendLine("${i + 1}. ${item.title}")
            if (item.text.isNotEmpty()) {
                sb.appendLine("   ${item.text.take(500)}")
            }
            if (item.instrumentIds.isNotEmpty()) {
                sb.appendLine("   Инструменты: ${item.instrumentIds.joinToString(", ")}")
            }
            sb.appendLine()
        }

        sb.appendLine()
        sb.appendLine("Для каждой новости определи:")
        sb.appendLine("1. Тематику (макроэкономика, отрасль, компания, регуляторика)")
        sb.appendLine("2. Сентимент (позитивный/негативный/нейтральный)")
        sb.appendLine("3. Какие сектора и инструменты могут быть затронуты")
        sb.appendLine("4. Потенциальное влияние на рынок")
        sb.appendLine()
        sb.appendLine("В конце дай общий вывод: какие тренды формируются и на что обратить внимание инвестору.")

        return sb.toString()
    }

    private fun sendToOllama(prompt: String): String {
        return try {
            val url = "http://localhost:11434/api/chat"
            val escaped = prompt
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")

            val body = """{"model":"$model","messages":[{"role":"user","content":"$escaped"}],"stream":false}"""

            val resp = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )

            if (resp.statusCode() != 200) {
                return "[ERROR] Ollama HTTP ${resp.statusCode()}: ${resp.body().take(200)}"
            }
            resp.body()
        } catch (e: Exception) {
            "[ERROR] Ollama: ${e.message}. Убедись что Ollama запущена (ollama serve)"
        }
    }

    private fun extractContent(json: String): String {
        // Handle error responses
        if (json.startsWith("[ERROR]")) return json

        // Extract message content from Ollama response
        val msgIdx = json.indexOf("\"message\"")
        if (msgIdx < 0) return json

        val contentIdx = json.indexOf("\"content\"", msgIdx)
        if (contentIdx < 0) return json

        val start = json.indexOf('"', contentIdx + 10) // skip "content": "
        if (start < 0) return json

        val sb = StringBuilder()
        var i = start + 1
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    when (json[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> { sb.append(c); sb.append(json[i + 1]) }
                    }
                    i += 2
                }
                c == '"' -> break
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString().trim()
    }
}


fun isToday(item: NewsItem): Boolean {
    if (item.time.isEmpty()) return false
    return try {
        val date = item.time.substringBefore("T").substringBefore(" ")
        date == LocalDate.now().toString()
    } catch (_: Exception) {
        false
    }
}

fun printSeparator(title: String) {
    println()
    println("=".repeat(80))
    println("  $title")
    println("=".repeat(80))
}

fun main(args: Array<String>) {
    val limit = if (args.size >= 2 && args[0] == "--limit") args[1].toIntOrNull() ?: 100 else 100
    val model = if (args.size >= 2 && args[0] == "--model") args[1] else "0xroyce/plutus"

    println("=== T-Bank News Analyzer ===")
    println()

    if (tcsApiKey.isEmpty()) {
        println("  [ERROR] tcs.apiKey не найден в application.properties")
        return
    }

    // Step 1: Fetch news
    printSeparator("Загрузка новостей из T-Bank Invest API")
    println("  Лимит на страницу: $limit")
    println()

    val client = NewsClient()
    val allNews = client.getNews(limit)
    println("  Всего загружено: ${allNews.size} новостей")

    // Step 2: Filter by today
    val todayNews = allNews.filter { isToday(it) }
    println("  За сегодня (${LocalDate.now()}): ${todayNews.size} новостей")

    if (todayNews.isEmpty()) {
        println()
        println("  Новостей за сегодня нет. Используем все загруженные.")
        println()
    }

    val newsToAnalyze = todayNews.ifEmpty { allNews }.take(50)

    // Step 3: Print news summary
    printSeparator("Новости для анализа (${newsToAnalyze.size})")
    newsToAnalyze.forEachIndexed { i, item ->
        println("  ${i + 1}. [${item.time.take(10)}] ${item.title}")
        if (item.text.isNotEmpty()) {
            println("     ${item.text.take(200).replace("\n", " ")}...")
        }
    }

    // Step 4: Send to LLM
    if (newsToAnalyze.isEmpty()) {
        println()
        println("  Нет новостей для анализа.")
        return
    }

    printSeparator("Анализ через локальную LLM ($model)")
    println("  Отправка ${newsToAnalyze.size} новостей на анализ...")
    println("  (может занять 30-60 секунд)")
    println()

    val llm = LlmClient(model)
    val analysis = llm.analyze(newsToAnalyze)

    println(analysis)
    println()
    println("=".repeat(80))
    println("  Анализ завершён")
    println("=".repeat(80))
}

main(args)
