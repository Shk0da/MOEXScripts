#!/usr/bin/env kotlin

import java.io.FileInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.abs

data class Config(
    val fairValue: Double = 31.1035,
    val upperThreshold: Double = 32.2,
    val lowerThreshold: Double = 30.0,
    val meanReversionTarget: Double = 31.5,
    val brokerCommission: Double = 0.0005,
    val notionalRub: Double = 1_000_000.0,
    val gldRubInstrumentId: String = "GLDRUBF",
    val usdRubInstrumentId: String = "USDRUBF"
)

data class ForexQuote(
    val bid: Double,
    val ask: Double
) {
    val mid: Double get() = (bid + ask) / 2.0
    val spread: Double get() = ask - bid
    val spreadPercentOfMid: Double get() = if (mid == 0.0) 0.0 else spread / mid * 100
}

data class PriceData(
    val forex: ForexQuote,
    val gldRub: Double,
    val usdRub: Double,
    val timestamp: LocalDateTime,
    val source: String
)

data class TradeScenario(
    val direction: String,
    val entryRatio: Double,
    val targetRatio: Double,
    val expectedMovePercent: Double,
    val grossProfitRub: Double,
    val commissionRub: Double,
    val forexSpreadCostRub: Double,
    val totalCostsRub: Double,
    val netProfitRub: Double,
    val profitable: Boolean,
    val action: String
)

data class ArbitrageAnalysis(
    val midRatio: Double,
    val bidRatio: Double,
    val askRatio: Double,
    val deviationFromFair: Double,
    val deviationPercent: Double,
    val signal: String,
    val riskLevel: String,
    val upperScenario: TradeScenario,
    val lowerScenario: TradeScenario,
    val activeScenario: TradeScenario?
)

fun loadProperties(): Properties {
    val props = Properties()
    try {
        val scriptPath = object {}.javaClass.classLoader.getResource("application.properties")?.path
            ?: "${System.getProperty("user.dir")}/application.properties"
        props.load(FileInputStream(scriptPath))
    } catch (_: Exception) {}
    return props
}

val tcsApiKey = loadProperties().getProperty("tcs.apiKey", "")

fun createTrustAllSSLContext(): SSLContext {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    return SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    }
}

fun createHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(15))
    .sslContext(createTrustAllSSLContext())
    .build()

class ForexClient {
    private val http = createHttpClient()

    fun getXauUsdQuote(): ForexQuote? {
        getFinamQuote()?.let { return it }
        getAlfaForexQuote()?.let { return it }
        getGoldApiQuote()?.let { return it }
        getCurrencyApiQuote()?.let { return it }
        return null
    }

    private fun getGoldApiQuote(): ForexQuote? {
        return try {
            val response = http.send(HttpRequest.newBuilder()
                .uri(URI.create("https://api.gold-api.com/price/XAU"))
                .header("User-Agent", "Mozilla/5.0").GET().build(),
                HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            val match = Regex("\"price\"\\s*:\\s*(\\d+\\.\\d+)").find(response.body()) ?: return null
            val mid = match.groupValues[1].toDoubleOrNull() ?: return null
            if (mid !in 1500.0..5000.0) return null
            val spread = mid * 0.0003
            println("  [GoldAPI] XAU/USD: bid=${"%.2f".format(mid - spread / 2)}, ask=${"%.2f".format(mid + spread / 2)}")
            ForexQuote(bid = mid - spread / 2, ask = mid + spread / 2)
        } catch (e: Exception) {
            println("  [GoldAPI] Ошибка: ${e.message}")
            null
        }
    }

    private fun getCurrencyApiQuote(): ForexQuote? {
        return try {
            val today = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val response = http.send(HttpRequest.newBuilder()
                .uri(URI.create("https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@$today/v1/currencies/xau.json"))
                .header("User-Agent", "Mozilla/5.0").GET().build(),
                HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            val match = Regex("\"usd\"\\s*:\\s*(\\d+\\.\\d+)").find(response.body()) ?: return null
            val mid = match.groupValues[1].toDoubleOrNull() ?: return null
            if (mid !in 1500.0..5000.0) return null
            val spread = mid * 0.0003
            println("  [CurrencyAPI] XAU/USD: bid=${"%.2f".format(mid - spread / 2)}, ask=${"%.2f".format(mid + spread / 2)}")
            ForexQuote(bid = mid - spread / 2, ask = mid + spread / 2)
        } catch (e: Exception) {
            println("  [CurrencyAPI] Ошибка: ${e.message}")
            null
        }
    }

    private fun getFinamQuote(): ForexQuote? {
        return try {
            val now = LocalDateTime.now()
            val day = now.dayOfMonth - 1
            val month = now.monthValue
            val year = now.year
            val url = "https://www.finam.ru/quote/forex/xauusd/export/?apply=0&df=$day&mf=$month&yf=$year&from=093000&dt=$day&mt=$month&yt=$year&to=235900&cn=xauusd&interval=d60&c=1"
            val response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            val lines = response.body().split("\n").filter { it.isNotBlank() && !it.startsWith("<") }
            if (lines.isEmpty()) return null
            val lastLine = lines.last().trim().split(",")
            if (lastLine.size < 6) return null
            val close = lastLine[5].toDoubleOrNull()?.takeIf { it in 1500.0..5000.0 } ?: run { println("  [Finam] Неверная цена"); return null }
            val spread = close * 0.0003
            println("  [Finam] XAU/USD: bid=${"%.2f".format(close - spread / 2)}, ask=${"%.2f".format(close + spread / 2)}")
            ForexQuote(bid = close - spread / 2, ask = close + spread / 2)
        } catch (e: Exception) { println("  [Finam] Ошибка: ${e.message}"); null }
    }

    private fun getAlfaForexQuote(): ForexQuote? {
        return try {
            val response = http.send(HttpRequest.newBuilder()
                .uri(URI.create("https://alfaforex.ru/cfd/metals/xau-usd/"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            Regex("(?:bid|ask|Buy|Sell)[\"']?\\s*[:=]\\s*[\"']?(\\d{3,5}[.,]\\d{1,3})[\"']?", RegexOption.IGNORE_CASE)
                .findAll(response.body())
                .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
                .filter { it in 1500.0..5000.0 }.sorted().toList()
                .takeIf { it.size >= 2 && it.last() - it.first() < it.first() * 0.05 }
                ?.let { ForexQuote(bid = it.first(), ask = it.last()) }
        } catch (_: Exception) { null }
    }
}

class TBankMarketDataClient {
    private val http = createHttpClient()

    fun getLastPrice(instrumentId: String): Double? {
        if (tcsApiKey.isBlank()) return null
        return try {
            val response = http.send(HttpRequest.newBuilder()
                .uri(URI.create("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetLastPrices"))
                .header("Authorization", "Bearer $tcsApiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"instrumentId": ["$instrumentId"]}"""))
                .build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            val json = response.body()
            val lastPricesStart = json.indexOf("\"lastPrices\"")
            if (lastPricesStart < 0) return null
            val arrayStart = json.indexOf('[', lastPricesStart)
            if (arrayStart < 0) return null
            var depth = 0; var objStart = -1; var objEnd = -1
            for (i in arrayStart until json.length) {
                when (json[i]) {
                    '{' -> { if (depth == 0) objStart = i; depth++ }
                    '}' -> { depth--; if (depth == 0 && objStart >= 0) { objEnd = i; break } }
                }
            }
            if (objStart < 0 || objEnd < 0) return null
            val obj = json.substring(objStart, objEnd + 1)
            for (pattern in listOf("\"price\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+))?\"", "\"last\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+))?\"")) {
                Regex(pattern).find(obj)?.groupValues?.get(1)?.toDoubleOrNull()
                    ?.takeIf { it > 0 }?.let { return it }
            }
            null
        } catch (_: Exception) { null }
    }
}

class MoexClient {
    private val http = createHttpClient()

    fun getLastPrice(ticker: String, engine: String, market: String): Double? {
        return try {
            val url = "https://iss.moex.com/iss/engines/$engine/markets/$market/securities.json?securities=$ticker"
            val response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(url)).header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0").GET().build(),
                HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            parseMoexMarketdata(response.body(), ticker)
        } catch (e: Exception) { println("  [MOEX] Ошибка: ${e.message}"); null }
    }

    fun getLastPriceWithFallback(ticker: String, engine: String, market: String): Double? {
        getLastPrice(ticker, engine, market)?.let { return it }
        return try {
            val url = "https://iss.moex.com/iss/engines/$engine/markets/$market/securities.json?securities=$ticker"
            val resp = http.send(HttpRequest.newBuilder().uri(URI.create(url))
                .header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) return null
            val json = resp.body()
            val secStart = json.indexOf("\"securities\"")
            if (secStart < 0) return null
            val sec = json.substring(secStart)
            val colStart = sec.indexOf("\"columns\"")
            val colB1 = sec.indexOf('[', colStart); val colB2 = sec.indexOf(']', colB1)
            val cols = Regex("\"([^\"]+)\"").findAll(sec.substring(colB1 + 1, colB2)).map { it.groupValues[1] }.toList()
            val priceIdx = cols.indexOfFirst { it.equals("PREVSETTLEPRICE", ignoreCase = true) }
                .takeIf { it >= 0 } ?: cols.indexOfFirst { it.equals("PREVPRICE", ignoreCase = true) }
            if (priceIdx < 0) return null
            val dataStart = sec.indexOf("\"data\"", colB2)
            val dataB1 = sec.indexOf('[', dataStart)
            var pos = dataB1 + 1; var depth = 0; var rs = -1
            while (pos < sec.length) {
                when (sec[pos]) {
                    '[' -> { if (depth == 0) rs = pos; depth++ }
                    ']' -> { depth--; if (depth == 0 && rs >= 0) {
                        val vals = parseMoexArray(sec.substring(rs + 1, pos))
                        if (priceIdx < vals.size) {
                            vals[priceIdx]?.trimQuotes()?.toDoubleOrNull()?.takeIf { it > 0 }
                                ?.also { println("  [MOEX] $ticker: PREVPRICE=$it"); return it }
                        }
                        rs = -1
                    }; if (depth < 0) break }
                }
                pos++
            }
            null
        } catch (_: Exception) { null }
    }

    private fun parseMoexMarketdata(json: String, targetTicker: String): Double? {
        val mdStart = json.indexOf("\"marketdata\"")
        if (mdStart < 0) return null
        val colSection = json.substring(mdStart)
        val colArrayStart = colSection.indexOf("\"columns\"")
        if (colArrayStart < 0) return null
        val colBracketStart = colSection.indexOf('[', colArrayStart)
        val colBracketEnd = colSection.indexOf(']', colBracketStart)
        if (colBracketStart < 0 || colBracketEnd < 0) return null
        val columns = Regex("\"([^\"]+)\"").findAll(colSection.substring(colBracketStart + 1, colBracketEnd))
            .map { it.groupValues[1] }.toList()
        val lastIdx = columns.indexOfFirst { it.equals("LAST", ignoreCase = true) }
        val secidIdx = columns.indexOfFirst { it.equals("SECID", ignoreCase = true) }
        if (lastIdx < 0) return null
        val dataStart = colSection.indexOf("\"data\"", colBracketEnd)
        val dataBracketStart = colSection.indexOf('[', dataStart)
        if (dataBracketStart < 0) return null
        var pos = dataBracketStart + 1; var bracketDepth = 0; var rowStart = -1
        while (pos < colSection.length) {
            when (colSection[pos]) {
                '[' -> { if (bracketDepth == 0) rowStart = pos; bracketDepth++ }
                ']' -> {
                    bracketDepth--
                    if (bracketDepth == 0 && rowStart >= 0) {
                        val values = parseMoexArray(colSection.substring(rowStart + 1, pos))
                        val sidMatch = secidIdx >= 0 && secidIdx < values.size && values[secidIdx]?.trimQuotes() == targetTicker
                        if ((sidMatch || secidIdx < 0) && lastIdx < values.size) {
                            values[lastIdx]?.trimQuotes()?.toDoubleOrNull()?.takeIf { it > 0 }
                                ?.also { println("  [MOEX] $targetTicker: LAST=$it"); return it }
                        }
                        rowStart = -1
                    }
                    if (bracketDepth < 0) break
                }
            }
            pos++
        }
        return null
    }

    private fun parseMoexArray(s: String): List<String?> {
        val values = mutableListOf<String?>()
        var current = StringBuilder(); var inString = false
        for (c in s) {
            when {
                c == '"' && !inString -> inString = true
                c == '"' && inString -> { inString = false; values.add(current.toString()); current = StringBuilder() }
                c == ',' && !inString -> { values.add(if (current.isEmpty()) null else current.toString()); current = StringBuilder() }
                else -> current.append(c)
            }
        }
        values.add(if (current.isEmpty()) null else current.toString())
        return values
    }
}

private fun String.trimQuotes(): String = trim('"')

class GoldArbitrageScanner(private val config: Config) {
    private val forexClient = ForexClient()
    private val tBankClient = TBankMarketDataClient()
    private val moexClient = MoexClient()
    private val http = createHttpClient()

    private fun getCurrencyApiUsdRub(): Double? {
        return try {
            val today = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create("https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@$today/v1/currencies/rub.json"))
                .header("User-Agent", "Mozilla/5.0").GET().build(),
                HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) {
                println("  [CurrencyAPI USD/RUB] HTTP ${resp.statusCode()}")
                return null
            }
            val match = Regex("\"usd\"\\s*:\\s*(\\d+\\.\\d+)").find(resp.body())
            if (match == null) {
                println("  [CurrencyAPI USD/RUB] Нет совпадений в ответе")
                return null
            }
            val rubPerUsdInverse = match.groupValues[1].toDoubleOrNull()
            if (rubPerUsdInverse == null || rubPerUsdInverse !in 0.0..1.0) {
                println("  [CurrencyAPI USD/RUB] Недопустимый курс: $rubPerUsdInverse")
                return null
            }
            val usdRub = 1.0 / rubPerUsdInverse
            println("  [CurrencyAPI] USD/RUB: ${"%.4f".format(usdRub)}")
            usdRub
        } catch (e: Exception) {
            println("  [CurrencyAPI USD/RUB] Ошибка: ${e.message}")
            null
        }
    }

    fun fetchPrices(): PriceData? {
        println("\n=== Загрузка данных ===")

        print("  XAU/USD... ")
        val forex = forexClient.getXauUsdQuote() ?: run { println("СБОЙ"); return null }

        print("  GLDRUB (\$${config.gldRubInstrumentId})... ")
        val gldRub = tBankClient.getLastPrice(config.gldRubInstrumentId)
            ?: moexClient.getLastPrice(config.gldRubInstrumentId, "futures", "forts")
        if (gldRub != null) println("${"%.1f".format(gldRub)} руб/г")
        else { println("СБОЙ"); return null }

        print("  USD/RUB... ")
        val usdRub = tBankClient.getLastPrice("USD000UTSTOM")
            ?: moexClient.getLastPriceWithFallback("USD000UTSTOM", "currency", "selt")
            ?: getCurrencyApiUsdRub()
        if (usdRub != null) println("${"%.4f".format(usdRub)} руб")
        else { println("СБОЙ"); return null }

        return PriceData(forex = forex, gldRub = gldRub, usdRub = usdRub,
            timestamp = LocalDateTime.now(), source = "Auto")
    }

    fun calculateRatio(xauUsd: Double, gldRub: Double, usdRub: Double): Double = (xauUsd * usdRub) / gldRub

    fun analyze(prices: PriceData): ArbitrageAnalysis {
        val midRatio = calculateRatio(prices.forex.mid, prices.gldRub, prices.usdRub)
        val bidRatio = calculateRatio(prices.forex.bid, prices.gldRub, prices.usdRub)
        val askRatio = calculateRatio(prices.forex.ask, prices.gldRub, prices.usdRub)
        val deviation = midRatio - config.fairValue
        val deviationPercent = deviation / config.fairValue * 100
        val upperScenario = buildUpperScenario(prices)
        val lowerScenario = buildLowerScenario(prices)
        val signal = when {
            bidRatio >= config.upperThreshold -> "ВЕРХ"
            askRatio <= config.lowerThreshold -> "НИЗ"
            else -> "НЕЙТРАЛЬНО"
        }
        val activeScenario = when (signal) { "ВЕРХ" -> upperScenario; "НИЗ" -> lowerScenario; else -> null }
        val riskLevel = when { abs(deviationPercent) < 2.0 -> "Низкий"; abs(deviationPercent) < 5.0 -> "Средний"; else -> "Высокий" }
        return ArbitrageAnalysis(midRatio = midRatio, bidRatio = bidRatio, askRatio = askRatio,
            deviationFromFair = deviation, deviationPercent = deviationPercent,
            signal = signal, riskLevel = riskLevel,
            upperScenario = upperScenario, lowerScenario = lowerScenario, activeScenario = activeScenario)
    }

    private fun buildUpperScenario(prices: PriceData): TradeScenario {
        val entryRatio = calculateRatio(prices.forex.bid, prices.gldRub, prices.usdRub)
        val move = ((entryRatio - config.meanReversionTarget) / entryRatio).coerceAtLeast(0.0)
        val gross = config.notionalRub * move
        val perLeg = config.notionalRub / 2.0
        val costs = perLeg * config.brokerCommission * 4 + perLeg * ((prices.forex.ask - prices.forex.bid) / prices.forex.mid)
        val net = gross - costs
        return TradeScenario("ВЕРХ", entryRatio, config.meanReversionTarget, move * 100, gross, 0.0, 0.0, costs, net,
            entryRatio >= config.upperThreshold && net > 0, "Шорт XAU/USD + Лонг GLDRUB")
    }

    private fun buildLowerScenario(prices: PriceData): TradeScenario {
        val entryRatio = calculateRatio(prices.forex.ask, prices.gldRub, prices.usdRub)
        val move = ((config.meanReversionTarget - entryRatio) / entryRatio).coerceAtLeast(0.0)
        val gross = config.notionalRub * move
        val perLeg = config.notionalRub / 2.0
        val costs = perLeg * config.brokerCommission * 4 + perLeg * ((prices.forex.ask - prices.forex.bid) / prices.forex.mid)
        val net = gross - costs
        return TradeScenario("НИЗ", entryRatio, config.meanReversionTarget, move * 100, gross, 0.0, 0.0, costs, net,
            entryRatio <= config.lowerThreshold && net > 0, "Лонг XAU/USD + Шорт GLDRUB")
    }

    fun printReport(prices: PriceData, analysis: ArbitrageAnalysis) {
        println("\n=== Котировки ===")
        println("  XAU/USD: bid=${"%.2f".format(prices.forex.bid)} ask=${"%.2f".format(prices.forex.ask)} mid=${"%.2f".format(prices.forex.mid)} (спрэд ${"%.2f".format(prices.forex.spread)} = ${"%.4f".format(prices.forex.spreadPercentOfMid)}%)")
        println("  GLDRUB:  ${"%.1f".format(prices.gldRub)} руб/г")
        println("  USD/RUB: ${"%.4f".format(prices.usdRub)} руб")

        println("\n=== Соотношение (XAUUSD * USDRUB) / GLDRUB ===")
        println("  Бид:   ${"%.4f".format(analysis.bidRatio)}  Аск: ${"%.4f".format(analysis.askRatio)}  Средняя: ${"%.4f".format(analysis.midRatio)}")
        println("  Справедливое:  ${"%.4f".format(config.fairValue)}  Отклонение: ${"%.4f".format(analysis.deviationFromFair)} (${String.format("%.2f", analysis.deviationPercent)}%)")
        println("  Сигнал: ${analysis.signal} | Риск: ${analysis.riskLevel}")

        println("\n--- ВЕРХ (порог ${"%.1f".format(config.upperThreshold)}) ---")
        printScenario(config.upperThreshold, analysis.upperScenario)
        println("\n--- НИЗ (порог ${"%.1f".format(config.lowerThreshold)}) ---")
        printScenario(config.lowerThreshold, analysis.lowerScenario)

        println("\n=== Результат ===")
        when (val active = analysis.activeScenario) {
            null -> println("  Нет сигнала (диапазон ${config.lowerThreshold}..${config.upperThreshold})")
            else -> {
                println("  Сигнал: ${active.direction} => ${active.action}")
                println("  Выгодно: ${if (active.profitable) "да" else "нет"}")
            }
        }
        println("  Свапы не учтены. Не является инвестиционной рекомендацией.")
    }

    private fun printScenario(threshold: Double, s: TradeScenario) {
        println("  Вход: ${"%.4f".format(s.entryRatio)} => Цель: ${"%.4f".format(s.targetRatio)}  Движение: ${"%.2f".format(s.expectedMovePercent)}%")
        println("  Валовая: ${"%,.0f".format(s.grossProfitRub)} руб  Затраты: ${"%,.0f".format(s.totalCostsRub)} руб  Чистая: ${"%,.0f".format(s.netProfitRub)} руб")
        println("  Действие: ${s.action}")
    }
}

val argsMap = mutableMapOf<String, String>()
var showHelp = false
args.forEachIndexed { index, arg ->
    if (arg == "--help" || arg == "-h") { showHelp = true; return@forEachIndexed }
    if (arg.startsWith("--") && index + 1 < args.size) argsMap[arg.substring(2)] = args[index + 1]
}

if (showHelp) {
    println("Использование: kotlin gold-arbitrage-new.kts [опции]")
    println("  --xau-bid <число>        XAU/USD bid (вручную)")
    println("  --xau-ask <число>        XAU/USD ask (вручную)")
    println("  --gld-rub <число>        GLDRUB руб/г (вручную)")
    println("  --usd-rub <число>        USD/RUB (вручную)")
    println("  --commission <процент>   Комиссия брокера в процентах (по умолчанию 0.05)")
    println("  --notional <руб>         Номинал сделки в рублях (по умолчанию 1000000)")
    println("  --help")
} else {
    val config = Config(
        fairValue = argsMap["fair-value"]?.toDoubleOrNull() ?: 31.1035,
        upperThreshold = argsMap["upper"]?.toDoubleOrNull() ?: 32.2,
        lowerThreshold = argsMap["lower"]?.toDoubleOrNull() ?: 30.0,
        meanReversionTarget = argsMap["mean"]?.toDoubleOrNull() ?: 31.5,
        brokerCommission = (argsMap["commission"]?.toDoubleOrNull() ?: 0.05),
        notionalRub = argsMap["notional"]?.toDoubleOrNull() ?: 1_000_000.0
    )
    val manualBid = argsMap["xau-bid"]?.toDoubleOrNull()
    val manualAsk = argsMap["xau-ask"]?.toDoubleOrNull()
    val manualGldRub = argsMap["gld-rub"]?.toDoubleOrNull()
    val manualUsdRub = argsMap["usd-rub"]?.toDoubleOrNull()
    val scanner = GoldArbitrageScanner(config)
    val prices = if (manualBid != null && manualAsk != null && manualGldRub != null && manualUsdRub != null) {
        println("=== Данные введены вручную ===")
        PriceData(ForexQuote(manualBid, manualAsk), manualGldRub, manualUsdRub, LocalDateTime.now(), "Вручную")
    } else { scanner.fetchPrices() }
    if (prices != null) scanner.printReport(prices, scanner.analyze(prices))
    else {
        println("\n[ОШИБКА] Нет данных. Попробуйте ручной ввод:")
        println("  kotlin gold-arbitrage-new.kts --xau-bid 3300 --xau-ask 3302 --gld-rub 10000 --usd-rub 73.79")
    }
}
