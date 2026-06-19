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
import java.time.Year
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

data class ShareInfo(
    val uid: String,
    val figi: String,
    val ticker: String,
    val name: String,
    val currency: String,
    val lot: Int,
    val forQualInvestorFlag: Boolean = false
) {
    val forNonQualifiedInvestor: Boolean get() = !forQualInvestorFlag
}

data class DividendEvent(
    val dividendDate: String,
    val dividendAmount: Double,
    val currency: String = "rub"
)

data class YearlyDividend(
    val year: Int,
    val totalAmount: Double,
    val paymentsCount: Int,
    val averagePerPayment: Double
)

data class DividendHistory(
    val ticker: String,
    val name: String,
    val currentPrice: Double,
    val yearlyDividends: List<YearlyDividend>,
    val totalPaid5y: Double,
    val averageYield5y: Double,
    val growthRate: Double,
    val consecutiveYears: Int,
    val lastPaymentAmount: Double,
    val forecast5y: Double,
    val forecastYield: Double,
    val currentYield: Double
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

fun extractString(json: String, key: String): String {
    val m = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)
    return m?.groupValues?.get(1) ?: ""
}

fun extractDouble(json: String, key: String): Double {
    val m = Regex("\"$key\"\\s*:\\s*([0-9.eE+-]+)").find(json)
    return m?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
}

fun extractMoney(json: String, key: String): Double? {
    val m = Regex("\"$key\"\\s*:\\s*\\{[^}]*\"units\"\\s*:\\s*\"?([0-9.]+)\"?[^}]*\\}").find(json)
    return m?.groupValues?.get(1)?.toDoubleOrNull()
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

class TcsDividendScanner {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .sslContext(createTrustAllSSLContext())
        .build()

    private fun post(url: String, body: String, retries: Int = 3): String? {
        for (attempt in 1..retries) {
            try {
                val resp = http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer $tcsApiKey")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                if (resp.statusCode() == 429) {
                    Thread.sleep(2000L * attempt)
                    continue
                }
                if (resp.statusCode() != 200) {
                    println("  [WARN] HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
                    return null
                }
                return resp.body()
            } catch (e: Exception) {
                if (attempt == retries) {
                    println("  [ERROR] ${e.message}")
                    return null
                }
                Thread.sleep(1000L)
            }
        }
        return null
    }

    fun getAllShares(): List<ShareInfo> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/Shares"
        val body = """{}"""
        val json = post(url, body) ?: return emptyList()

        val shares = mutableListOf<ShareInfo>()
        val instruments = parseJsonArray(json, "instruments")

        for (inst in instruments) {
            val uid = extractString(inst, "uid")
            val figi = extractString(inst, "figi")
            val ticker = extractString(inst, "ticker")
            val name = extractString(inst, "name")
            val currency = extractString(inst, "currency")
            val lot = extractDouble(inst, "lot").toInt()
            val forQual = extractString(inst, "forQualInvestorFlag").toBooleanStrictOrNull() ?: false

            if (ticker.isNotEmpty() && uid.isNotEmpty()) {
                shares.add(ShareInfo(uid, figi, ticker, name, currency, lot, forQual))
            }
        }
        return shares
    }

    fun getDividends(instrumentId: String): List<DividendEvent> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetDividends"
        val fromYear = LocalDate.now().year - 7
        val body = """{"instrumentId": "$instrumentId", "from": "${fromYear}-01-01T00:00:00Z", "to": "2099-12-31T23:59:59Z"}"""
        val json = post(url, body, retries = 2) ?: return emptyList()
        return parseDividendsJson(json)
    }

    fun getQuote(figi: String): Double {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetLastPrices"
        val body = """{"instrumentId":["$figi"]}"""
        val json = post(url, body) ?: return 0.0

        val lastPrices = parseJsonArray(json, "lastPrices")
        if (lastPrices.isEmpty()) return 0.0

        val priceObj = lastPrices.first()
        // Price is nested in "price" object
        val priceIdx = priceObj.indexOf("\"price\"")
        if (priceIdx < 0) return 0.0
        val priceChunk = priceObj.substring(priceIdx)
        val units = Regex("\"units\"\\s*:\\s*\"?(\\d+)\"?").find(priceChunk)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val nano = Regex("\"nano\"\\s*:\\s*(-?\\d+)").find(priceChunk)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return units + nano / 1_000_000_000.0
    }

    private fun parseDividendsJson(json: String): List<DividendEvent> {
        val events = mutableListOf<DividendEvent>()
        val eventsStart = json.indexOf("\"dividends\"")
        if (eventsStart < 0) return events

        var depth = 0
        var inArray = false
        var arrayStart = -1

        for (i in eventsStart until json.length) {
            val ch = json[i]
            if (ch == '[' && !inArray) {
                inArray = true
                arrayStart = i
            } else if (inArray) {
                if (ch == '[') depth++
                if (ch == ']') {
                    depth--
                    if (depth < 0) {
                        val content = json.substring(arrayStart + 1, i)
                        var objDepth = 0
                        var objStart = -1
                        for (j in content.indices) {
                            when (content[j]) {
                                '{' -> { if (objDepth == 0) objStart = j; objDepth++ }
                                '}' -> {
                                    objDepth--
                                    if (objDepth == 0 && objStart >= 0) {
                                        val obj = content.substring(objStart, j + 1)
                                        val amount = extractMoney(obj, "dividendNet") ?: extractMoney(obj, "dividendAmount") ?: 0.0
                                        if (amount > 0) {
                                            val paymentDate = extractString(obj, "paymentDate").substringBefore("T")
                                                .ifEmpty { extractString(obj, "recordDate").substringBefore("T") }
                                            events.add(DividendEvent(paymentDate, amount))
                                        }
                                        objStart = -1
                                    }
                                }
                            }
                        }
                        break
                    }
                }
            }
        }
        return events
    }
}

fun analyzeDividendHistory(
    ticker: String,
    name: String,
    currentPrice: Double,
    dividends: List<DividendEvent>
): DividendHistory? {
    if (dividends.isEmpty() || currentPrice <= 0) return null

    val now = LocalDate.now()
    val currentYear = now.year

    val yearlyMap = mutableMapOf<Int, MutableList<DividendEvent>>()
    for (div in dividends) {
        if (div.dividendDate.isEmpty()) continue
        try {
            val date = LocalDate.parse(div.dividendDate)
            val year = date.year
            if (year >= currentYear - 7) {
                yearlyMap.getOrPut(year) { mutableListOf() }.add(div)
            }
        } catch (_: Exception) {}
    }

    if (yearlyMap.size < 3) return null

    val yearlyDividends = yearlyMap.map { (year, events) ->
        val total = events.sumOf { it.dividendAmount }
        YearlyDividend(year, total, events.size, total / events.size)
    }.sortedBy { it.year }

    val consecutiveYears = countConsecutiveYears(yearlyDividends.map { it.year })

    val recent5y = yearlyDividends.filter { it.year >= currentYear - 5 }
    val totalPaid5y = recent5y.sumOf { it.totalAmount }

    val lastYearDividend = yearlyDividends.lastOrNull()?.totalAmount ?: 0.0
    val currentYield = if (currentPrice > 0) lastYearDividend / currentPrice * 100 else 0.0
    val averageYield5y = if (currentPrice > 0) totalPaid5y / 5.0 / currentPrice * 100 else 0.0

    val growthRate = calculateCAGR(yearlyDividends)
    val lastPayment = lastYearDividend

    val forecast5y = forecastDividend(yearlyDividends, 5)
    val forecastYield = if (currentPrice > 0) forecast5y / currentPrice * 100 else 0.0

    return DividendHistory(
        ticker = ticker,
        name = name,
        currentPrice = currentPrice,
        yearlyDividends = yearlyDividends,
        totalPaid5y = totalPaid5y,
        averageYield5y = averageYield5y,
        growthRate = growthRate,
        consecutiveYears = consecutiveYears,
        lastPaymentAmount = lastPayment,
        forecast5y = forecast5y,
        forecastYield = forecastYield,
        currentYield = currentYield
    )
}

fun countConsecutiveYears(years: List<Int>): Int {
    if (years.isEmpty()) return 0
    val sorted = years.distinct().sorted()
    if (sorted.size == 1) return 1

    var maxCount = 1
    var currentCount = 1
    for (i in 1 until sorted.size) {
        if (sorted[i] == sorted[i - 1] + 1) {
            currentCount++
            maxCount = maxOf(maxCount, currentCount)
        } else {
            currentCount = 1
        }
    }
    return maxCount
}

fun calculateCAGR(yearlyDividends: List<YearlyDividend>): Double {
    if (yearlyDividends.size < 2) return 0.0

    val first = yearlyDividends.first().totalAmount
    val last = yearlyDividends.last().totalAmount
    val years = yearlyDividends.last().year - yearlyDividends.first().year

    if (first <= 0 || years <= 0) return 0.0

    return (Math.pow(last / first, 1.0 / years) - 1.0) * 100
}

fun forecastDividend(yearlyDividends: List<YearlyDividend>, yearsAhead: Int): Double {
    if (yearlyDividends.isEmpty()) return 0.0

    val lastDividend = yearlyDividends.last().totalAmount
    val growthRate = calculateCAGR(yearlyDividends) / 100.0

    if (growthRate <= 0) {
        return lastDividend * yearsAhead
    }

    var forecast = 0.0
    var current = lastDividend
    for (i in 1..yearsAhead) {
        current *= (1 + growthRate)
        forecast += current
    }
    return forecast
}

fun formatNumber(n: Double): String {
    return if (n >= 1_000_000) {
        String.format("%.1fM", n / 1_000_000)
    } else if (n >= 1_000) {
        String.format("%.1fK", n / 1_000)
    } else {
        String.format("%.2f", n)
    }
}

fun formatCurrency(n: Double): String {
    return String.format("%,.2f ₽", n)
}

fun main() {
    println("=== Анализ дивидендов ММВБ ===")
    println()

    val scanner = TcsDividendScanner()

    println("  [1/4] Загрузка списка акций...")
    val shares = scanner.getAllShares()
    val nonQualShares = shares.filter { it.forNonQualifiedInvestor && it.currency.lowercase() == "rub" }
    println("  Всего акций: ${shares.size} (доступно неквалифицированным: ${nonQualShares.size})")
    println()

    println("  [2/4] Загрузка дивидендной истории...")
    val histories = mutableListOf<DividendHistory>()
    var processed = 0
    var withDividends = 0

    for (share in nonQualShares) {
        processed++
        if (processed % 50 == 0) {
            println("  Обработано: $processed/${nonQualShares.size} (с дивидендами: $withDividends)")
        }

        if (share.figi.isEmpty()) continue

        val dividends = scanner.getDividends(share.figi)
        if (dividends.isEmpty()) continue
        withDividends++

        val currentPrice = scanner.getQuote(share.figi)
        if (currentPrice <= 0) continue

        val history = analyzeDividendHistory(share.ticker, share.name, currentPrice, dividends)
        if (history != null && history.consecutiveYears >= 2) {
            histories.add(history)
        }

        Thread.sleep(150L)
    }

    println("  Найдено акций с историей: ${histories.size}")
    println()

    println("  [3/4] Ранжирование по дивидендной истории...")
    val ranked = histories.sortedWith(
        compareByDescending<DividendHistory> { it.consecutiveYears }
            .thenByDescending { it.growthRate }
            .thenByDescending { it.currentYield }
    )

    val top10 = ranked.take(10)

    println()

    println("  [4/4] Вывод результатов...")
    println()

    println("=================================================================")
    println("  ТОП-10 АКЦИЙ С ЛУЧШЕЙ ДИВИДЕНДНОЙ ИСТОРИЕЙ")
    println("=================================================================")
    println()

    println("  №  | Тикер   | Цена      | Доходн. | Рост/год | Лет подряд | Прогноз 5л | Прогноз дох.")
    println("  ---|---------|-----------|---------|----------|------------|------------|-------------")

    for ((idx, hist) in top10.withIndex()) {
        println("  ${idx + 1}. | ${hist.ticker.padEnd(7)} | ${formatCurrency(hist.currentPrice).padStart(9)} | ${String.format("%.1f%%", hist.currentYield).padStart(7)} | ${String.format("%+.1f%%", hist.growthRate).padStart(8)} | ${hist.consecutiveYears.toString().padStart(10)} | ${formatCurrency(hist.forecast5y).padStart(10)} | ${String.format("%.1f%%", hist.forecastYield).padStart(11)}")
    }

    println()

    for ((idx, hist) in top10.withIndex()) {
        println("=================================================================")
        println("  ${idx + 1}. ${hist.ticker} — ${hist.name}")
        println("=================================================================")
        println()
        println("  Текущая цена: ${formatCurrency(hist.currentPrice)}")
        println("  Текущая доходность: ${String.format("%.2f%%", hist.currentYield)}")
        println("  Средняя доходность за 5 лет: ${String.format("%.2f%%", hist.averageYield5y)}")
        println("  Рост дивидендов (CAGR): ${String.format("%+.1f%%", hist.growthRate)}")
        println("  Подряд лет выплат: ${hist.consecutiveYears}")
        println()
        println("  История выплат:")
        println("  Год  | Сумма      | Выплат")
        println("  -----|------------|--------")
        for (yd in hist.yearlyDividends) {
            println("  ${yd.year} | ${formatCurrency(yd.totalAmount).padStart(10)} | ${yd.paymentsCount}")
        }
        println()
        println("  Всего выплачено за 5 лет: ${formatCurrency(hist.totalPaid5y)}")
        println()
        println("  Прогноз на 5 лет (на основе роста):")
        println("  ├─ Годовая сумма: ${formatCurrency(hist.forecast5y / 5)}")
        println("  ├─ За 5 лет: ${formatCurrency(hist.forecast5y)}")
        println("  └─ Прогнозная доходность: ${String.format("%.2f%%", hist.forecastYield)}")
        println()
    }

    println("=================================================================")
    println("  ОГРАНИЧЕНИЯ")
    println("=================================================================")
    println("  - Прогноз основан на экстраполяции历史ических данных (CAGR)")
    println("  - Дивидендная политика может измениться")
    println("  - Не является инвестиционной рекомендацией")
}

main()
