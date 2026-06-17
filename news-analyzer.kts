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
    val instrumentIds: List<String> = emptyList(),
    val instrumentUids: List<String> = emptyList()
)

data class FundamentalData(
    val ticker: String,
    val assetUid: String = "",
    val name: String = "",
    val marketCap: Double = 0.0,
    val peRatio: Double = 0.0,
    val priceToBook: Double = 0.0,
    val evToEbitda: Double = 0.0,
    val debtToEquity: Double = 0.0,
    val roe: Double = 0.0,
    val dividendYield: Double = 0.0,
    val revenueGrowth5y: Double = 0.0,
    val netMargin: Double = 0.0,
    val beta: Double = 0.0
)

data class InstrumentAssetInfo(
    val assetUid: String = "",
    val ticker: String = "",
    val figi: String = ""
)

data class ForecastTarget(
    val source: String,
    val company: String,
    val targetPrice: Double,
    val currentPrice: Double,
    val priceChangeRel: Double,
    val recommendation: String,
    val date: String = ""
)

data class ForecastData(
    val ticker: String = "",
    val company: String = "",
    val consensusTarget: Double = 0.0,
    val minTarget: Double = 0.0,
    val maxTarget: Double = 0.0,
    val currentPrice: Double = 0.0,
    val upsidePercent: Double = 0.0,
    val consensus: String = "",
    val targets: List<ForecastTarget> = emptyList()
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

fun extractObject(json: String, key: String): String {
    val idx = json.indexOf("\"$key\"")
    if (idx < 0) return ""
    val chunk = json.substring(idx)
    val start = chunk.indexOf('{')
    if (start < 0) return ""
    var depth = 0
    var inStr = false
    for (i in start until chunk.length) {
        val c = chunk[i]
        if (c == '"') inStr = !inStr
        if (inStr) continue
        when (c) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return chunk.substring(start, i + 1) }
        }
    }
    return ""
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

                val instrIds = extractInstrumentTickers(itemJson)
                val instrUids = extractInstrumentUids(itemJson)

                items.add(NewsItem(id, title, content, source, time, instrIds, instrUids))
            }

            if (newsList.isEmpty()) break
            if (!hasNext && cursor.isEmpty()) break
        } while (true)

        return items
    }

    private fun extractInstruments(json: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val instrArray = parseJsonArray(json, "instrumentId")
        for (instr in instrArray) {
            // instrument is nested: {"instrument": {"ticker": "...", "instrumentUid": "...", ...}}
            val instrumentJson = extractObject(instr, "instrument")
            if (instrumentJson.isEmpty()) continue
            val ticker = extractString(instrumentJson, "ticker")
            val uid = extractString(instrumentJson, "instrumentUid")
            if (ticker.isNotEmpty()) result.add(ticker to uid)
        }
        return result
    }

    private fun extractInstrumentTickers(json: String): List<String> {
        return extractInstruments(json).map { it.first }
    }

    private fun extractInstrumentUids(json: String): List<String> {
        return extractInstruments(json).map { it.second }.filter { it.isNotEmpty() }
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

// --- Fundamentals API client ---

class FundamentalsClient {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .sslContext(createTrustAllSSLContext())
        .build()

    private fun post(url: String, body: String): String {
        return try {
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
                println("    [WARN] HTTP ${resp.statusCode()}")
                return ""
            }
            resp.body()
        } catch (e: Exception) {
            println("    [WARN] ${e.message}")
            ""
        }
    }

    // Get mapping from instrumentUid to asset info (assetUid, figi) via GetAssets
    fun getInstrumentInfoMap(): Map<String, InstrumentAssetInfo> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetAssets"
        val body = """{"instrumentType":"INSTRUMENT_TYPE_SHARE"}"""
        val json = post(url, body)
        if (json.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, InstrumentAssetInfo>()
        val assets = parseJsonArray(json, "assets")
        for (asset in assets) {
            val assetUid = extractString(asset, "uid")
            if (assetUid.isEmpty()) continue
            val instruments = parseJsonArray(asset, "instruments")
            for (inst in instruments) {
                val instUid = extractString(inst, "uid")
                if (instUid.isNotEmpty()) {
                    val ticker = extractString(inst, "ticker")
                    val figi = extractString(inst, "figi")
                    result[instUid] = InstrumentAssetInfo(assetUid, ticker, figi)
                }
            }
        }
        return result
    }

    fun getFundamentals(assetUids: List<String>): List<FundamentalData> {
        if (assetUids.isEmpty()) return emptyList()

        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetAssetFundamentals"
        val idsJson = assetUids.joinToString(",") { "\"$it\"" }
        val body = """{"assets":[$idsJson]}"""
        val json = post(url, body)
        if (json.isEmpty()) return emptyList()

        val result = mutableListOf<FundamentalData>()
        val items = parseJsonArray(json, "fundamentals")
        for (item in items) {
            val assetUid = extractString(item, "assetUid")
            val ticker = extractString(item, "ticker")
            result.add(FundamentalData(
                ticker = ticker,
                assetUid = assetUid,
                marketCap = extractDouble(item, "marketCapitalization"),
                peRatio = extractDouble(item, "peRatioTtm"),
                priceToBook = extractDouble(item, "priceToBookTtm"),
                evToEbitda = extractDouble(item, "evToEbitdaMrq"),
                debtToEquity = extractDouble(item, "totalDebtToEquityMrq"),
                roe = extractDouble(item, "roe"),
                dividendYield = extractDouble(item, "dividendYieldDailyTtm"),
                revenueGrowth5y = extractDouble(item, "fiveYearAnnualRevenueGrowthRate"),
                netMargin = extractDouble(item, "netMarginMrq"),
                beta = extractDouble(item, "beta")
            ))
        }
        return result
    }

    private fun extractDouble(json: String, key: String): Double {
        val m = Regex("\"$key\"\\s*:\\s*([0-9.]+(?:[eE][+-]?\\d+)?)").find(json)
        return m?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    // --- Forecast By ---

    fun getForecastBy(figi: String): ForecastData? {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetForecastBy"
        val body = """{"instrumentId":"$figi"}"""
        val json = post(url, body)
        return parseForecast(json)
    }

    private fun parseForecast(json: String): ForecastData? {
        if (json.isEmpty()) return null
        val targets = extractNestedArray(json, "targets")
        if (targets.isEmpty()) return null

        val parsedTargets = targets.mapNotNull { t ->
            val source = extractString(t, "uid")
            val company = extractString(t, "company")
            val tp = extractUnitsNano(t, "targetPrice")
            val cp = extractUnitsNano(t, "currentPrice")
            val changeRel = extractUnitsNano(t, "priceChangeRel")
            val rec = extractString(t, "recommendation")
            val date = extractString(t, "recommendationDate")
            if (source.isNotEmpty()) {
                ForecastTarget(source, company, tp, cp, changeRel, rec, date)
            } else null
        }
        if (parsedTargets.isEmpty()) return null

        val consensusJson = extractObject(json, "consensus")
        val ticker = if (consensusJson.isNotEmpty()) extractString(consensusJson, "ticker") else ""
        val company = extractString(json, "showName").ifEmpty {
            if (consensusJson.isNotEmpty()) extractString(consensusJson, "showName") else ""
        }
        var consensusTarget = 0.0
        var minTarget = 0.0
        var maxTarget = 0.0
        var currentPrice = 0.0
        var upsidePercent = 0.0
        var consensus = ""

        if (consensusJson.isNotEmpty()) {
            consensusTarget = extractUnitsNano(consensusJson, "consensus")
            minTarget = extractUnitsNano(consensusJson, "minTarget")
            maxTarget = extractUnitsNano(consensusJson, "maxTarget")
            currentPrice = extractUnitsNano(consensusJson, "currentPrice")
            upsidePercent = extractUnitsNano(consensusJson, "priceChangeRel")
            consensus = extractString(consensusJson, "recommendation")
        }

        return ForecastData(
            ticker = ticker,
            company = company,
            consensusTarget = consensusTarget,
            minTarget = minTarget,
            maxTarget = maxTarget,
            currentPrice = currentPrice,
            upsidePercent = upsidePercent,
            consensus = consensus,
            targets = parsedTargets
        )
    }

    private fun extractUnitsNano(json: String, key: String): Double {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return 0.0
        val chunk = json.substring(idx)
        val u = Regex("\"units\"\\s*:\\s*\"?(\\d+|-?\\d+)\"?").find(chunk)
        val n = Regex("\"nano\"\\s*:\\s*(-?\\d+)").find(chunk)
        val units = u?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val nano = n?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return units + nano / 1_000_000_000.0
    }

    private fun extractNestedArray(json: String, key: String): List<String> {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return emptyList()
        val rest = json.substring(idx)
        val start = rest.indexOf('[')
        if (start < 0) return emptyList()
        var depth = 0
        var inStr = false
        val items = mutableListOf<String>()
        var itemStart = -1
        for (i in start + 1 until rest.length) {
            val c = rest[i]
            if (c == '"') inStr = !inStr
            if (inStr) continue
            when (c) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth < 0) break
                }
                '{' -> {
                    if (depth == 0 && itemStart < 0) itemStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && itemStart >= 0) {
                        items.add(rest.substring(itemStart, i + 1))
                        itemStart = -1
                    }
                }
            }
        }
        return items
    }
}

// --- Ollama LLM client ---

class LlmClient(private val model: String = "0xroyce/plutus") {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(60))
        .build()

    fun analyze(newsItems: List<NewsItem>, fundamentals: Map<String, FundamentalData>, forecasts: Map<String, ForecastData> = emptyMap()): String {
        if (newsItems.isEmpty()) return "Нет новостей для анализа"

        val prompt = buildPrompt(newsItems, fundamentals, forecasts)
        val response = sendToOllama(prompt)
        return extractContent(response)
    }

    private fun buildPrompt(items: List<NewsItem>, fundamentals: Map<String, FundamentalData>, forecasts: Map<String, ForecastData>): String {
        val sb = StringBuilder()
        sb.appendLine("Сегодня ${LocalDate.now()}. Проанализируй следующие финансовые новости и их влияние на российский фондовый рынок.")
        sb.appendLine()
        sb.appendLine("Новости:")
        sb.appendLine()

        items.forEachIndexed { i, item ->
            sb.appendLine("${i + 1}. ${item.title}")
            if (item.text.isNotEmpty()) {
                sb.appendLine("   ${item.text.take(300)}")
            }
            if (item.instrumentIds.isNotEmpty()) {
                sb.appendLine("   Инструменты: ${item.instrumentIds.joinToString(", ")}")
                for (ticker in item.instrumentIds) {
                    val f = fundamentals[ticker]
                    if (f != null) {
                        sb.appendLine("     $ticker: P/E ${fmt(f.peRatio)} | P/B ${fmt(f.priceToBook)} | EV/EBITDA ${fmt(f.evToEbitda)} | ROE ${pct(f.roe)} | Div ${pct(f.dividendYield)} | Кап ${fmtMoney(f.marketCap)}")
                    }
                    val fore = forecasts[ticker]
                    if (fore != null && fore.targets.isNotEmpty()) {
                        val upside = if (fore.upsidePercent != 0.0) ", потенциал ${fmtPct(fore.upsidePercent)}" else ""
                        val consensus = when {
                            fore.consensus.contains("BUY") -> "Покупать"
                            fore.consensus.contains("HOLD") -> "Держать"
                            fore.consensus.contains("SELL") -> "Продавать"
                            else -> fore.consensus.removePrefix("RECOMMENDATION_")
                        }
                        sb.appendLine("     Прогноз инвестдомов: $consensus @ ${fmtMoney(fore.consensusTarget)}$upside")
                    }
                }
            }
            sb.appendLine()
        }

        sb.appendLine()
        sb.appendLine("Для каждой новости определи:")
        sb.appendLine("1. Тематику (макроэкономика, отрасль, компания, регуляторика)")
        sb.appendLine("2. Сентимент (позитивный/негативный/нейтральный)")
        sb.appendLine("3. Какие сектора и инструменты могут быть затронуты (с учётом их мультипликаторов и прогнозов аналитиков)")
        sb.appendLine("4. Потенциальное влияние на рынок")
        sb.appendLine()
        sb.appendLine("В конце дай общий вывод: какие тренды формируются, на какие инструменты стоит обратить внимание с учётом их оценки и прогнозов, и общий настрой рынка.")

        return sb.toString()
    }

    private fun fmt(v: Double): String = if (v > 0) "%.1f".format(v) else "—"
    private fun pct(v: Double): String = if (v > 0) "%.1f%%".format(v) else "—"

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

fun fmtPct(v: Double): String = if (v > 0) "+%.1f%%".format(v) else "%.1f%%".format(v)

fun fmtMoney(v: Double): String = when {
    v >= 1_000_000_000_000 -> "%.1fT".format(v / 1_000_000_000_000)
    v >= 1_000_000_000 -> "%.1fB".format(v / 1_000_000_000)
    v >= 1_000_000 -> "%.0fM".format(v / 1_000_000)
    else -> "%.0f".format(v)
}

fun main(args: Array<String>) {
    var limit = 100
    var model = "0xroyce/plutus"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--limit" -> limit = args.getOrElse(++i) { "100" }.toIntOrNull() ?: 100
            "--model" -> model = args.getOrElse(++i) { "0xroyce/plutus" }
        }
        i++
    }

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

    val newsClient = NewsClient()
    val allNews = newsClient.getNews(limit)
    println("  Всего загружено: ${allNews.size} новостей")

    // Step 2: Filter by today
    val todayNews = allNews.filter { isToday(it) }
    println("  За сегодня (${LocalDate.now()}): ${todayNews.size} новостей")

    val newsToAnalyze = todayNews.ifEmpty { allNews }.take(50)

    if (newsToAnalyze.isEmpty()) {
        println()
        println("  Нет новостей для анализа.")
        return
    }

    // Step 3: Collect unique instrument UIDs for fundamentals lookup
    val uniqueUids = newsToAnalyze.flatMap { it.instrumentUids }.distinct()
    val uniqueTickers = newsToAnalyze.flatMap { it.instrumentIds }.distinct().sorted()

    println()
    if (uniqueTickers.isNotEmpty()) {
        println("  Затронутые инструменты (${uniqueTickers.size}): ${uniqueTickers.joinToString(", ")}")
    }

    val fundamentals = mutableMapOf<String, FundamentalData>()
    val forecasts = mutableMapOf<String, ForecastData>()

    if (uniqueUids.isNotEmpty()) {
        val fc = FundamentalsClient()
        val instrumentInfoMap = fc.getInstrumentInfoMap()

        val uidToTicker = newsToAnalyze
            .flatMap { it.instrumentIds.zip(it.instrumentUids) }
            .distinct()
            .associate { (ticker, uid) -> uid to ticker }

        val uidToFigi = mutableMapOf<String, String>()

        val assetUids = uniqueUids.mapNotNull { uid ->
            val info = instrumentInfoMap[uid]
            if (info != null) {
                uidToFigi[uid] = info.figi
                if (info.assetUid.isNotEmpty()) {
                    info.assetUid to (uidToTicker[uid] ?: info.ticker)
                } else null
            } else null
        }
        val assetToTicker = assetUids.associate { (assetUid, ticker) -> assetUid to ticker }

        // Load fundamentals
        if (assetUids.isNotEmpty()) {
            print("  Загрузка фундаментальных показателей...")
            val data = fc.getFundamentals(assetUids.map { it.first })
            for (d in data) {
                val ticker = d.ticker.ifEmpty { assetToTicker[d.assetUid] ?: "" }
                if (ticker.isNotEmpty()) {
                    fundamentals[ticker] = d.copy(ticker = ticker)
                }
            }
            println(" ${fundamentals.size} инструментов")
        }

        // Load forecasts
        val figis = uniqueUids.mapNotNull { uid ->
            val info = instrumentInfoMap[uid]
            if (info != null && info.figi.isNotEmpty()) {
                val ticker = uidToTicker[uid]?.ifEmpty { info.ticker } ?: info.ticker
                if (ticker.isNotEmpty()) ticker to info.figi else null
            } else null
        }

        if (figis.isNotEmpty()) {
            print("  Загрузка прогнозов инвестдомов...")
            var count = 0
            for ((ticker, figi) in figis) {
                val f = fc.getForecastBy(figi)
                if (f != null) {
                    forecasts[ticker] = f
                    count++
                }
            }
            println(" $count инструментов")
        }
    }

    // Step 4: Print news summary with instruments and fundamentals
    printSeparator("Новости для анализа (${newsToAnalyze.size})")
    newsToAnalyze.forEachIndexed { i, item ->
        println("  ${i + 1}. [${item.time.take(10)}] ${item.title}")
        if (item.instrumentIds.isNotEmpty()) {
            val instrStr = item.instrumentIds.joinToString(", ")
            println("     Инструменты: $instrStr")

            for (ticker in item.instrumentIds) {
                val f = fundamentals[ticker]
                if (f != null) {
                    val pe = if (f.peRatio > 0) "P/E %.1f".format(f.peRatio) else ""
                    val pb = if (f.priceToBook > 0) "P/B %.1f".format(f.priceToBook) else ""
                    val ev = if (f.evToEbitda > 0) "EV/EBITDA %.1f".format(f.evToEbitda) else ""
                    val roe = if (f.roe > 0) "ROE %.1f%%".format(f.roe) else ""
                    val div = if (f.dividendYield > 0) "Div %.1f%%".format(f.dividendYield) else ""
                    val mc = if (f.marketCap > 0) {
                        when {
                            f.marketCap >= 1_000_000_000_000 -> "Cap %.1fT".format(f.marketCap / 1_000_000_000_000)
                            f.marketCap >= 1_000_000_000 -> "Cap %.1fB".format(f.marketCap / 1_000_000_000)
                            else -> "Cap %.0fM".format(f.marketCap / 1_000_000)
                        }
                    } else ""
                    val line = listOf(pe, pb, ev, roe, div, mc).filter { it.isNotEmpty() }.joinToString(" | ")
                    if (line.isNotEmpty()) {
                        println("     └ $line")
                    }
                }
                val fore = forecasts[ticker]
                if (fore != null && fore.targets.isNotEmpty()) {
                    val upside = if (fore.upsidePercent != 0.0) " (${fmtPct(fore.upsidePercent)})" else ""
                    val range = if (fore.minTarget > 0) ", диапазон ${fmtMoney(fore.minTarget)}-${fmtMoney(fore.maxTarget)}" else ""
                    val consensus = when {
                        fore.consensus.contains("BUY") -> "Покупать"
                        fore.consensus.contains("HOLD") -> "Держать"
                        fore.consensus.contains("SELL") -> "Продавать"
                        else -> fore.consensus.removePrefix("RECOMMENDATION_")
                    }
                    println("       Прогноз: $consensus → ${fmtMoney(fore.consensusTarget)}$upside$range")
                    if (fore.targets.size <= 5) {
                        for (t in fore.targets) {
                            val rec = when {
                                t.recommendation.contains("BUY") -> "Покупать"
                                t.recommendation.contains("HOLD") -> "Держать"
                                t.recommendation.contains("SELL") -> "Продавать"
                                else -> t.recommendation.removePrefix("RECOMMENDATION_")
                            }
                            val change = if (t.currentPrice > 0) (t.targetPrice / t.currentPrice - 1) * 100 else t.priceChangeRel
                            println("       ├ ${t.company.padEnd(18)} TP ${fmtMoney(t.targetPrice)} → $rec (${fmtPct(change)})")
                        }
                    }
                }
            }
        }
        if (item.text.isNotEmpty()) {
            println("     ${item.text.take(200).replace("\n", " ")}...")
        }
    }

    // Step 5: Send to LLM
    printSeparator("Анализ через локальную LLM ($model)")
    println("  Отправка ${newsToAnalyze.size} новостей на анализ...")
    println("  (может занять 30-60 секунд)")
    println()

    val llm = LlmClient(model)
    val analysis = llm.analyze(newsToAnalyze, fundamentals, forecasts)

    println(analysis)
    println()
    println("=".repeat(80))
    println("  Анализ завершён")
    println("=".repeat(80))
}

main(args)
