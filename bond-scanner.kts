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
import java.time.temporal.ChronoUnit
import java.util.Properties
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// Загрузка конфигурации из application.properties
fun loadProperties(): Properties {
    val props = Properties()
    try {
        val scriptPath = object {}.javaClass.classLoader.getResource("application.properties")?.path
            ?: "${System.getProperty("user.dir")}/application.properties"
        props.load(FileInputStream(scriptPath))
    } catch (e: Exception) {
        println("  [WARN] Не удалось загрузить application.properties: ${e.message}")
    }
    return props
}

val tcsApiKey = loadProperties().getProperty("tcs.apiKey", "")

// Конфигурация сканера облигаций
data class BondConfig(
    val targetYield: Double = 16.0,  // Целевая доходность, % годовых
    val couponFrequency: Int = 12,   // Частота купона (12 = ежемесячно)
    val noAmortization: Boolean = true,  // Без амортизации
    val excludeFloating: Boolean = true,  // Исключить флоатеры
    val excludeQualInvestorOnly: Boolean = true,  // Исключить бумаги только для квал. инвесторов
    val minRiskLevel: String = "RISK_LEVEL_MODERATE",  // Макс. риск: надежные + средние
    val maxBondsCount: Int = 10,     // Топ-N облигаций
    val brokerCommission: Double = 0.0005,  // 0.05% комиссия брокера
    val capital: Double = 100_000.0,  // Капитал для инвестиций
    val minDaysToMaturity: Int = 90,  // Мин. дней до погашения
    val maxDaysToMaturity: Int = 730, // Макс. дней до погашения (2 года)
    val autoMode: Boolean = false,   // Автоматическая покупка
    val dryRun: Boolean = false,     // Режим dry run (без реальных сделок)
    val accountId: String = "",      // ID аккаунта для торговли
    val autoBuyCount: Int = 5,       // Кол-во облигаций для покупки в auto-режиме
    val autoBuyAmount: Double = 0.0  // Сумма на покупку (0 = весь свободный кэш)
)

// Данные облигации
data class BondData(
    val ticker: String,
    val name: String,
    val figi: String,
    val classCode: String,
    val price: Double,           // Текущая цена (% от номинала)
    val nominal: Double,         // Номинал
    val couponAmount: Double,    // Купон на 1 облигацию (из TCS API)
    val couponFrequency: Int,    // Купонов в год
    val maturityDate: LocalDate, // Дата погашения
    val daysToMaturity: Int,     // Дней до погашения
    val currentYield: Double,    // Текущая доходность
    val ytm: Double,            // Доходность к погашению
    val riskLevel: String,       // Уровень риска
    val sector: String,          // Сектор эмитента
    val amortizationFlag: Boolean,  // Есть ли амортизация
    val totalCoupons: Double,    // Всего купонных выплат
    val totalProfit: Double,     // Общая прибыль (купон + разница к номиналу)
    val commission: Double,      // Комиссия брокера
    val netProfit: Double,       // Чистая прибыль
    val netYield: Double,        // Чистая доходность
    val assetUid: String = "",
    val financialScore: Int = 0,       // 0 = н/д, 1-5
    val financialScoreLabel: String = "",
    val fundamentals: IssuerFundamentals? = null
)

data class IssuerFundamentals(
    val name: String,
    val debtToEquity: Double,
    val netDebtToEbitda: Double,
    val currentRatio: Double,
    val roe: Double,
    val roic: Double,
    val netMargin: Double,
    val revenueGrowth5y: Double,
    val marketCap: Double
)

// Рейтинг облигаций (от лучшего к худшему)
val RISK_LEVELS = mapOf(
    "RISK_LEVEL_LOW" to 1,
    "RISK_LEVEL_MODERATE" to 2,
    "RISK_LEVEL_HIGH" to 3,
    "RISK_LEVEL_VERY_HIGH" to 4
)

// Маппинг уровня риска T-Invest на рейтинговую шкалу
val RISK_TO_RATING = mapOf(
    "RISK_LEVEL_LOW" to "AAA",
    "RISK_LEVEL_MODERATE" to "AA-BB",
    "RISK_LEVEL_HIGH" to "B-CCC",
    "RISK_LEVEL_VERY_HIGH" to "D"
)

val RISK_LEVEL_ALIASES = mapOf(
    "AAA" to "RISK_LEVEL_LOW",
    "LOW" to "RISK_LEVEL_LOW",
    "MODERATE" to "RISK_LEVEL_MODERATE",
    "HIGH" to "RISK_LEVEL_HIGH",
    "VERY_HIGH" to "RISK_LEVEL_VERY_HIGH"
)

fun normalizeRiskLevel(level: String): String =
    RISK_LEVEL_ALIASES[level.uppercase()] ?: level

fun formatAcceptedRisk(maxRiskLevel: String): String {
    val maxValue = RISK_LEVELS[maxRiskLevel] ?: return maxRiskLevel
    return RISK_LEVELS.entries
        .filter { it.value <= maxValue }
        .sortedBy { it.value }
        .joinToString(", ") { RISK_TO_RATING[it.key] ?: it.key }
}

val FINANCIAL_SCORE_LABELS = mapOf(
    1 to "плохо",
    2 to "слабо",
    3 to "средне",
    4 to "хорошо",
    5 to "отлично"
)

fun financialScoreLabel(score: Int): String =
    if (score in 1..5) FINANCIAL_SCORE_LABELS[score]!! else "н/д"

fun calculateFinancialHealthScore(f: IssuerFundamentals): Int {
    var score = 0.0
    var weight = 0.0

    fun add(metricScore: Double, w: Double) {
        score += metricScore * w
        weight += w
    }

    if (f.debtToEquity in 0.01..500.0) {
        val s = when {
            f.debtToEquity <= 30 -> 100.0
            f.debtToEquity <= 50 -> 80.0
            f.debtToEquity <= 100 -> 60.0
            f.debtToEquity <= 200 -> 40.0
            f.debtToEquity <= 300 -> 20.0
            else -> 10.0
        }
        add(s, 2.0)
    }
    if (f.netDebtToEbitda in 0.01..50.0) {
        val s = when {
            f.netDebtToEbitda <= 1 -> 100.0
            f.netDebtToEbitda <= 2 -> 80.0
            f.netDebtToEbitda <= 3 -> 60.0
            f.netDebtToEbitda <= 5 -> 40.0
            f.netDebtToEbitda <= 8 -> 20.0
            else -> 10.0
        }
        add(s, 2.0)
    }
    if (f.currentRatio in 0.01..20.0) {
        val s = when {
            f.currentRatio >= 2 -> 100.0
            f.currentRatio >= 1.5 -> 80.0
            f.currentRatio >= 1.2 -> 60.0
            f.currentRatio >= 1.0 -> 40.0
            f.currentRatio >= 0.8 -> 20.0
            else -> 10.0
        }
        add(s, 1.5)
    }
    if (f.roe in 0.01..100.0) {
        val s = when {
            f.roe >= 20 -> 100.0
            f.roe >= 15 -> 80.0
            f.roe >= 10 -> 60.0
            f.roe >= 5 -> 40.0
            else -> 20.0
        }
        add(s, 1.0)
    }
    if (f.roic in 0.01..100.0) {
        val s = when {
            f.roic >= 15 -> 100.0
            f.roic >= 10 -> 80.0
            f.roic >= 7 -> 60.0
            f.roic >= 4 -> 40.0
            else -> 20.0
        }
        add(s, 1.0)
    }
    if (f.netMargin in 0.01..100.0) {
        val s = when {
            f.netMargin >= 20 -> 100.0
            f.netMargin >= 15 -> 80.0
            f.netMargin >= 10 -> 60.0
            f.netMargin >= 5 -> 40.0
            else -> 20.0
        }
        add(s, 1.0)
    }
    if (f.revenueGrowth5y > 0) {
        val s = when {
            f.revenueGrowth5y >= 15 -> 100.0
            f.revenueGrowth5y >= 10 -> 80.0
            f.revenueGrowth5y >= 5 -> 60.0
            f.revenueGrowth5y >= 2 -> 40.0
            else -> 20.0
        }
        add(s, 0.5)
    }

    if (weight <= 0) return 0
    val avg = score / weight
    return when {
        avg < 20 -> 1
        avg < 40 -> 2
        avg < 60 -> 3
        avg < 80 -> 4
        else -> 5
    }
}

fun findIssuerAssetUid(bondName: String, shareAssets: List<TcsBondClient.ShareAssetRef>): String {
    val normalizedBond = bondName
        .replace(Regex("""\s+\d+Р?-\d+.*$"""), "")
        .replace(Regex("""\s+БО-.*$"""), "")
        .replace(Regex("""\s+облигаци.*$""", RegexOption.IGNORE_CASE), "")
        .trim()
    if (normalizedBond.length < 3) return ""

    shareAssets
        .sortedByDescending { it.name.length }
        .forEach { asset ->
            if (normalizedBond.contains(asset.name, ignoreCase = true) ||
                asset.name.contains(normalizedBond, ignoreCase = true)) {
                return asset.assetUid
            }
        }
    return ""
}

// SSL context, доверяющий всем сертификатам (необходимо для российских CA)
fun createTrustAllSSLContext(): SSLContext {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    return sslContext
}

class TcsBondClient {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .sslContext(createTrustAllSSLContext())
        .build()

    // Получить список открытых счетов
    fun getAccounts(): List<TcsAccount> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.UsersService/GetAccounts"
        val body = """{"status": "ACCOUNT_STATUS_OPEN"}"""
        val json = postJson(url, body) ?: return emptyList()
        return parseAccounts(json)
    }

    // Получить свободные средства
    fun getWithdrawLimits(accountId: String): Double {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetWithdrawLimits"
        val body = """{"accountId": "$accountId"}"""
        val json = postJson(url, body, retries = 2) ?: return 0.0
        val totalCash = extractCashTotal(json)
        val blocked = extractBlockedMargin(json)
        return totalCash - blocked
    }

    // Разместить ордер на покупку облигации
    fun postOrder(
        accountId: String,
        figi: String,
        quantity: Long,
        direction: String,
        orderType: String = "ORDER_TYPE_MARKET",
        price: Double? = null,
        ticker: String = "",
        classCode: String = ""
    ): String? {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OrdersService/PostOrder"
        val priceField = if (price != null && orderType == "ORDER_TYPE_LIMIT") {
            """, "price": {"units": "${price.toLong()}", "nano": ${(price % 1 * 1_000_000_000).toLong()}}"""
        } else ""
        val instrumentId = if (ticker.isNotEmpty() && classCode.isNotEmpty()) {
            """, "instrumentId": "${ticker}_${classCode}""""
        } else {
            """, "instrumentId": "$figi""""
        }
        val body = """{"accountId": "$accountId"$instrumentId, "quantity": $quantity, "direction": "$direction", "orderType": "$orderType"$priceField, "orderId": "${System.currentTimeMillis()}"}"""
        println("  [API] PostOrder: $direction $quantity x ${ticker.ifEmpty { figi }}")
        val json = postJson(url, body, retries = 2) ?: return null
        val orderId = extractString(json, "orderId")
        val rejects = extractString(json, "rejectReason")
        if (rejects.isNotEmpty()) {
            println("  [ERROR] Ордер отклонён: $rejects")
            println("  [ERROR] Response: $json")
            return null
        }
        println("  [API] OrderId: $orderId")
        return orderId
    }

    // Парсинг счетов
    private fun parseAccounts(json: String): List<TcsAccount> {
        val accounts = mutableListOf<TcsAccount>()
        val arrStart = json.indexOf("\"accounts\"")
        if (arrStart < 0) return accounts
        val bStart = json.indexOf('[', arrStart)
        val bEnd = json.indexOf(']', bStart)
        if (bStart < 0 || bEnd < 0) return accounts
        val content = json.substring(bStart + 1, bEnd)
        var depth = 0
        var objStart = -1
        for (i in content.indices) {
            when (content[i]) {
                '{' -> { if (depth == 0) objStart = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        val obj = content.substring(objStart, i + 1)
                        accounts.add(TcsAccount(
                            id = extractString(obj, "id"),
                            name = extractString(obj, "name"),
                            type = extractString(obj, "type")
                        ))
                        objStart = -1
                    }
                }
            }
        }
        return accounts
    }

    // Извлечение общей суммы кэша
    private fun extractCashTotal(json: String): Double {
        val moneyArr = json.indexOf("\"money\"")
        if (moneyArr < 0) return 0.0
        val bracketStart = json.indexOf('[', moneyArr)
        val bracketEnd = json.indexOf(']', bracketStart)
        if (bracketStart < 0 || bracketEnd < 0) return 0.0
        val content = json.substring(bracketStart + 1, bracketEnd)
        var depth = 0
        var objStart = -1
        var total = 0.0
        for (i in content.indices) {
            when (content[i]) {
                '{' -> { if (depth == 0) objStart = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        val obj = content.substring(objStart, i + 1)
                        val currency = extractString(obj, "currency")
                        val money = extractMoney(obj, "units") ?: 0.0
                        val nano = Regex("\"nano\"\\s*:\\s*(\\d+)").find(obj)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        val amount = money + nano / 1_000_000_000.0
                        if (currency == "rub" || currency == "RUB") total += amount
                        objStart = -1
                    }
                }
            }
        }
        return total
    }

    // Извлечение заблокированной суммы (ГО)
    private fun extractBlockedMargin(json: String): Double {
        var total = 0.0
        for (key in listOf("blockedGuarantee", "blocked")) {
            val arr = json.indexOf("\"$key\"")
            if (arr < 0) continue
            var bracketStart = -1
            for (i in arr until json.length) {
                if (json[i] == '[') { bracketStart = i; break }
            }
            if (bracketStart < 0) continue
            var bracketEnd = -1
            var depth = 0
            for (i in bracketStart until json.length) {
                when (json[i]) {
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { bracketEnd = i; break } }
                }
            }
            if (bracketEnd < 0) continue
            val content = json.substring(bracketStart + 1, bracketEnd)
            var objDepth = 0
            var objStart = -1
            for (i in content.indices) {
                when (content[i]) {
                    '{' -> { if (objDepth == 0) objStart = i; objDepth++ }
                    '}' -> {
                        objDepth--
                        if (objDepth == 0 && objStart >= 0) {
                            val obj = content.substring(objStart, i + 1)
                            val currency = extractString(obj, "currency")
                            val units = extractMoney(obj, "units") ?: 0.0
                            val nano = Regex("\"nano\"\\s*:\\s*(\\d+)").find(obj)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                            val amount = units + nano / 1_000_000_000.0
                            if (currency == "rub" || currency == "RUB") total += amount
                            objStart = -1
                        }
                    }
                }
            }
            if (total > 0) break
        }
        return total
    }

    // Извлечение денежного значения из JSON
    private fun extractMoney(json: String, key: String): Double? {
        val start = json.indexOf("\"$key\"")
        if (start < 0) return null
        val sub = json.substring(start)
        val units = Regex("\"units\"\\s*:\\s*\"?(\\d+)\"?").find(sub)
            ?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val nano = Regex("\"nano\"\\s*:\\s*(\\d+)").find(sub)
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return units + nano / 1_000_000_000.0
    }

    // Получить список всех облигаций
    fun getAllBonds(): List<BondInstrument> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/Bonds"
        val requestBody = """{"instrumentStatus": "INSTRUMENT_STATUS_BASE"}"""
        
        // Retry при rate limit
        for (attempt in 1..3) {
            try {
                val resp = http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer $tcsApiKey")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                
                if (resp.statusCode() == 429) {
                    println("  [WARN] Rate limit (429), попытка $attempt/3, жду 5 сек...")
                    Thread.sleep(5000L)
                    continue
                }
                
                if (resp.statusCode() != 200) {
                    throw Exception("TCS API HTTP ${resp.statusCode()}")
                }
                
                return parseBondsJson(resp.body())
            } catch (e: Exception) {
                if (attempt == 3) throw e
                Thread.sleep(3000L)
            }
        }
        
        throw Exception("TCS API: все попытки исчерпаны")
    }

    // Получить цены последних сделок
    fun getLastPrices(instrumentIds: List<String>): Map<String, Double> {
        if (instrumentIds.isEmpty()) return emptyMap()
        
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetLastPrices"
        val idsJson = instrumentIds.joinToString(",") { "\"$it\"" }
        val requestBody = """{"instrumentId": [$idsJson]}"""
        
        return try {
            val resp = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer $tcsApiKey")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            
            if (resp.statusCode() != 200) {
                println("  [WARN] Цены: HTTP ${resp.statusCode()}")
                return emptyMap()
            }
            
            parsePricesJson(resp.body())
        } catch (e: Exception) {
            println("  [WARN] Ошибка получения цен: ${e.message}")
            emptyMap()
        }
    }

    // Получить купоны по облигации
    fun getBondCoupons(figi: String): List<CouponEvent> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetBondCoupons"
        val requestBody = """{"figi": "$figi"}"""
        
        return try {
            val resp = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer $tcsApiKey")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            
            if (resp.statusCode() != 200) {
                return emptyList()
            }
            
            parseCouponsJson(resp.body())
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class BondInstrument(
        val ticker: String,
        val name: String,
        val figi: String,
        val classCode: String,
        val assetUid: String,
        val couponQuantityPerYear: Int,
        val maturityDate: String,
        val nominal: Double,
        val riskLevel: String,
        val sector: String,
        val amortizationFlag: Boolean,
        val floatingCouponFlag: Boolean,
        val forQualInvestorFlag: Boolean
    )

    data class ShareAssetRef(
        val assetUid: String,
        val name: String,
        val ticker: String
    )

    data class CouponEvent(
        val couponDate: String,
        val payOneBond: Double,
        val couponType: String,
        val currency: String = "rub"
    )

    private fun parseBondsJson(json: String): List<BondInstrument> {
        val instruments = mutableListOf<BondInstrument>()
        val instrumentsStart = json.indexOf("\"instruments\"")
        if (instrumentsStart < 0) return instruments
        
        var depth = 0
        var inArray = false
        var arrayStart = -1
        
        for (i in instrumentsStart until json.length) {
            val ch = json[i]
            if (ch == '[' && !inArray) {
                inArray = true
                arrayStart = i
            } else if (inArray) {
                if (ch == '[') depth++
                if (ch == ']') {
                    depth--
                    if (depth < 0) {
                        val arrayContent = json.substring(arrayStart + 1, i)
                        parseBondObjects(arrayContent, instruments)
                        break
                    }
                }
            }
        }
        
        return instruments
    }

    private fun parseBondObjects(content: String, instruments: MutableList<BondInstrument>) {
        var depth = 0
        var objStart = -1
        
        for (i in content.indices) {
            when (content[i]) {
                '{' -> {
                    if (depth == 0) objStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        val obj = content.substring(objStart, i + 1)
                        instruments.add(parseSingleBond(obj))
                        objStart = -1
                    }
                }
            }
        }
    }

    private fun parseSingleBond(json: String): BondInstrument {
        return BondInstrument(
            ticker = extractString(json, "ticker"),
            name = extractString(json, "name"),
            figi = extractString(json, "figi"),
            classCode = extractString(json, "classCode"),
            assetUid = extractString(json, "assetUid"),
            couponQuantityPerYear = extractInt(json, "couponQuantityPerYear"),
            maturityDate = extractString(json, "maturityDate").substringBefore("T"),
            nominal = extractNominal(json),
            riskLevel = extractString(json, "riskLevel").ifEmpty { "RISK_LEVEL_UNSPECIFIED" },
            sector = extractString(json, "sector"),
            amortizationFlag = extractBoolean(json, "amortizationFlag"),
            floatingCouponFlag = extractBoolean(json, "floatingCouponFlag"),
            forQualInvestorFlag = extractBoolean(json, "forQualInvestorFlag")
        )
    }

    private fun parsePricesJson(json: String): Map<String, Double> {
        val prices = mutableMapOf<String, Double>()
        val pricesStart = json.indexOf("\"lastPrices\"")
        if (pricesStart < 0) return prices
        
        var depth = 0
        var inArray = false
        var arrayStart = -1
        
        for (i in pricesStart until json.length) {
            val ch = json[i]
            if (ch == '[' && !inArray) {
                inArray = true
                arrayStart = i
            } else if (inArray) {
                if (ch == '[') depth++
                if (ch == ']') {
                    depth--
                    if (depth < 0) {
                        val arrayContent = json.substring(arrayStart + 1, i)
                        parsePriceObjects(arrayContent, prices)
                        break
                    }
                }
            }
        }
        
        return prices
    }

    private fun parsePriceObjects(content: String, prices: MutableMap<String, Double>) {
        var depth = 0
        var objStart = -1
        
        for (i in content.indices) {
            when (content[i]) {
                '{' -> {
                    if (depth == 0) objStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        val obj = content.substring(objStart, i + 1)
                        val ticker = extractString(obj, "ticker")
                        val price = extractPrice(obj)
                        if (ticker.isNotEmpty() && price > 0) {
                            prices[ticker] = price
                        }
                        objStart = -1
                    }
                }
            }
        }
    }

    private fun parseCouponsJson(json: String): List<CouponEvent> {
        val events = mutableListOf<CouponEvent>()
        val eventsStart = json.indexOf("\"events\"")
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
                        val arrayContent = json.substring(arrayStart + 1, i)
                        parseCouponObjects(arrayContent, events)
                        break
                    }
                }
            }
        }
        
        return events
    }

    private fun parseCouponObjects(content: String, events: MutableList<CouponEvent>) {
        var depth = 0
        var objStart = -1
        
        for (i in content.indices) {
            when (content[i]) {
                '{' -> {
                    if (depth == 0) objStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        val obj = content.substring(objStart, i + 1)
                        events.add(CouponEvent(
                            couponDate = extractString(obj, "couponDate"),
                            payOneBond = extractCouponPayment(obj),
                            couponType = extractString(obj, "couponType"),
                            currency = extractCouponCurrency(obj)
                        ))
                        objStart = -1
                    }
                }
            }
        }
    }

    private fun extractCouponCurrency(json: String): String {
        val payStart = json.indexOf("\"payOneBond\"")
        if (payStart < 0) return "rub"
        
        val currencyPattern = "\"currency\"\\s*:\\s*\"([^\"]*)\""
        val match = Regex(currencyPattern).find(json.substring(payStart))
        return match?.groupValues?.get(1) ?: "rub"
    }

    private fun extractString(json: String, key: String): String {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\""
        val match = Regex(pattern).find(json)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun extractInt(json: String, key: String): Int {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)"
        val match = Regex(pattern).find(json)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractBoolean(json: String, key: String): Boolean {
        val pattern = "\"$key\"\\s*:\\s*(true|false)"
        val match = Regex(pattern).find(json)
        return match?.groupValues?.get(1) == "true"
    }

    private fun extractNominal(json: String): Double {
        val nominalStart = json.indexOf("\"nominal\"")
        if (nominalStart < 0) return 1000.0
        
        val unitsPattern = "\"units\"\\s*:\\s*\"?(\\d+)\"?"
        val nanoPattern = "\"nano\"\\s*:\\s*(\\d+)"
        
        val unitsMatch = Regex(unitsPattern).find(json.substring(nominalStart))
        val nanoMatch = Regex(nanoPattern).find(json.substring(nominalStart))
        
        val units = unitsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val nano = nanoMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        
        return units + nano / 1_000_000_000.0
    }

    private fun extractPrice(json: String): Double {
        val priceStart = json.indexOf("\"price\"")
        if (priceStart < 0) return 0.0
        
        val unitsPattern = "\"units\"\\s*:\\s*\"?(\\d+)\"?"
        val nanoPattern = "\"nano\"\\s*:\\s*(\\d+)"
        
        val unitsMatch = Regex(unitsPattern).find(json.substring(priceStart))
        val nanoMatch = Regex(nanoPattern).find(json.substring(priceStart))
        
        val units = unitsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val nano = nanoMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        
        return units + nano / 1_000_000_000.0
    }

    private fun extractCouponPayment(json: String): Double {
        val payStart = json.indexOf("\"payOneBond\"")
        if (payStart < 0) return 0.0
        
        val unitsPattern = "\"units\"\\s*:\\s*\"?(\\d+)\"?"
        val nanoPattern = "\"nano\"\\s*:\\s*(\\d+)"
        
        val substring = json.substring(payStart)
        val unitsMatch = Regex(unitsPattern).find(substring)
        val nanoMatch = Regex(nanoPattern).find(substring)
        
        val units = unitsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val nano = nanoMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        
        return units + nano / 1_000_000_000.0
    }

    private fun extractDouble(json: String, key: String): Double {
        val pattern = "\"$key\"\\s*:\\s*([0-9.]+(?:[eE][+-]?\\d+)?)"
        val match = Regex(pattern).find(json)
        return match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    private fun postJson(url: String, body: String, retries: Int = 3): String {
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
                    Thread.sleep(5000L)
                    continue
                }
                if (resp.statusCode() != 200) {
                    throw Exception("HTTP ${resp.statusCode()}")
                }
                return resp.body()
            } catch (e: Exception) {
                if (attempt == retries) throw e
                Thread.sleep(3000L)
            }
        }
        throw Exception("TCS API: все попытки исчерпаны")
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

    fun getShareAssets(): List<ShareAssetRef> {
        return try {
            val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetAssets"
            val resp = postJson(url, """{"instrumentType":"INSTRUMENT_TYPE_SHARE"}""")
            val assets = extractNestedArray(resp, "assets")
            val refs = mutableListOf<ShareAssetRef>()
            for (asset in assets) {
                val assetUid = extractString(asset, "uid")
                val assetName = extractString(asset, "name")
                if (assetUid.isEmpty()) continue
                val instruments = extractNestedArray(asset, "instruments")
                val ticker = instruments.firstOrNull()?.let { extractString(it, "ticker") } ?: ""
                refs.add(ShareAssetRef(assetUid, assetName, ticker))
            }
            refs
        } catch (e: Exception) {
            println("  [WARN] GetAssets: ${e.message}")
            emptyList()
        }
    }

    fun getAssetFundamentals(assetIds: List<String>): Map<String, IssuerFundamentals> {
        if (assetIds.isEmpty()) return emptyMap()
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetAssetFundamentals"
        val result = mutableMapOf<String, IssuerFundamentals>()
        assetIds.chunked(100).forEach { chunk ->
            try {
                val idsJson = chunk.joinToString(",") { "\"$it\"" }
                val resp = postJson(url, """{"assets":[$idsJson]}""")
                for (item in extractNestedArray(resp, "fundamentals")) {
                    val assetUid = extractString(item, "assetUid")
                    if (assetUid.isEmpty()) continue
                    result[assetUid] = IssuerFundamentals(
                        name = extractString(item, "name"),
                        debtToEquity = extractDouble(item, "totalDebtToEquityMrq"),
                        netDebtToEbitda = extractDouble(item, "netDebtToEbitda"),
                        currentRatio = extractDouble(item, "currentRatioMrq"),
                        roe = extractDouble(item, "roe"),
                        roic = extractDouble(item, "roic"),
                        netMargin = extractDouble(item, "netMarginMrq"),
                        revenueGrowth5y = extractDouble(item, "fiveYearAnnualRevenueGrowthRate"),
                        marketCap = extractDouble(item, "marketCapitalization")
                    )
                }
            } catch (e: Exception) {
                println("  [WARN] GetAssetFundamentals: ${e.message}")
            }
        }
        return result
    }

    // Данные по аккаунту
    data class TcsAccount(val id: String, val name: String, val type: String)
}

// Клиент Finam API для торговли
class FinamClient {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .sslContext(createTrustAllSSLContext())
        .build()
    private var jwtToken: String? = null

    fun authenticate(secret: String): String {
        val url = "https://api.finam.ru/v1/sessions"
        val body = """{"secret": "$secret"}"""
        val resp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (resp.statusCode() != 200) {
            throw Exception("Finam auth failed: HTTP ${resp.statusCode()} ${resp.body()}")
        }
        val token = extractJsonString(resp.body(), "token")
        jwtToken = token
        return token
    }

    fun getAccount(accountId: String): String {
        val token = jwtToken ?: throw Exception("Not authenticated")
        val url = "https://api.finam.ru/v1/accounts/$accountId"
        val resp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer $token")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (resp.statusCode() != 200) {
            throw Exception("Finam get account failed: HTTP ${resp.statusCode()}")
        }
        return resp.body()
    }

    // Получить стакан по инструменту
    fun getOrderBook(symbol: String): FinamOrderBook? {
        val token = jwtToken ?: return null
        val url = "https://api.finam.ru/v1/instruments/$symbol/orderbook"
        return try {
            val resp = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (resp.statusCode() != 200) {
                println("  [WARN] OrderBook $symbol: HTTP ${resp.statusCode()}")
                return null
            }
            parseOrderBook(resp.body())
        } catch (e: Exception) {
            println("  [WARN] OrderBook $symbol: ${e.message}")
            null
        }
    }

    // Разместить лимитный ордер на покупку
    fun placeLimitBuyOrder(accountId: String, symbol: String, quantity: String, price: String): String? {
        val token = jwtToken ?: throw Exception("Not authenticated")
        val url = "https://api.finam.ru/v1/accounts/$accountId/orders"
        // Формируем JSON без переносов строк для надежности
        val body = "{\"symbol\":\"$symbol\",\"quantity\":{\"value\":\"$quantity\"},\"side\":\"SIDE_BUY\",\"type\":\"ORDER_TYPE_LIMIT\",\"time_in_force\":\"TIME_IN_FORCE_DAY\",\"limit_price\":{\"value\":\"$price\"}}"
        println("  [Finam] BUY $quantity x $symbol @ $price")
        return try {
            val resp = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (resp.statusCode() != 200) {
                println("  [ERROR] PlaceOrder: HTTP ${resp.statusCode()} ${resp.body()}")
                return null
            }
            val orderId = extractJsonString(resp.body(), "orderId")
            println("  [Finam] OrderId: $orderId")
            orderId
        } catch (e: Exception) {
            println("  [ERROR] PlaceOrder: ${e.message}")
            null
        }
    }

    private fun extractJsonString(json: String, key: String): String {
        val match = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun parseOrderBook(json: String): FinamOrderBook? {
        val bids = mutableListOf<FinamOrderBookEntry>()
        val asks = mutableListOf<FinamOrderBookEntry>()

        // Finam API формат: {"orderbook": {"rows": [{"price": {"value": "87.67"}, "sell_size": {"value": "82.0"}, ...}]}}
        // Ищем массив rows
        val rowsStart = json.indexOf("\"rows\"")
        if (rowsStart < 0) return FinamOrderBook(bids, asks)

        // Находим начало массива [
        var depth = 0
        var inArray = false
        var arrayStart = -1

        for (i in rowsStart until json.length) {
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
                        parseOrderBookRows(content, bids, asks)
                        break
                    }
                }
            }
        }

        return FinamOrderBook(bids, asks)
    }

    private fun parseOrderBookRows(content: String, bids: MutableList<FinamOrderBookEntry>, asks: MutableList<FinamOrderBookEntry>) {
        var depth = 0
        var objStart = -1

        for (i in content.indices) {
            when (content[i]) {
                '{' -> { if (depth == 0) objStart = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        val obj = content.substring(objStart, i + 1)
                        // Извлекаем цену из вложенного объекта {"value": "87.67"}
                        val price = extractNestedValue(obj, "price")
                        // Для ask: sell_size, для bid: buy_size
                        val sellSize = extractNestedValue(obj, "sell_size")
                        val buySize = extractNestedValue(obj, "buy_size")

                        if (price.isNotEmpty()) {
                            val priceVal = price.toDoubleOrNull() ?: 0.0
                            if (sellSize.isNotEmpty() && sellSize.toDoubleOrNull() ?: 0.0 > 0) {
                                asks.add(FinamOrderBookEntry(priceVal, sellSize.toDoubleOrNull() ?: 0.0))
                            }
                            if (buySize.isNotEmpty() && buySize.toDoubleOrNull() ?: 0.0 > 0) {
                                bids.add(FinamOrderBookEntry(priceVal, buySize.toDoubleOrNull() ?: 0.0))
                            }
                        }
                        objStart = -1
                    }
                }
            }
        }
    }

    // Извлечение значения из вложенного объекта: "key": {"value": "87.67"}
    private fun extractNestedValue(json: String, key: String): String {
        val keyIdx = json.indexOf("\"$key\"")
        if (keyIdx < 0) return ""
        val sub = json.substring(keyIdx)
        val valueMatch = Regex("\"value\"\\s*:\\s*\"([^\"]+)\"").find(sub)
        return valueMatch?.groupValues?.get(1) ?: ""
    }
}

data class FinamOrderBookEntry(val price: Double, val quantity: Double)
data class FinamOrderBook(val bids: List<FinamOrderBookEntry>, val asks: List<FinamOrderBookEntry>)

// Сканер облигаций
class BondScanner {
    private val client = TcsBondClient()

    fun scan(config: BondConfig): List<BondData> {
        println("=== Сканер облигаций ===")
        println("  Параметры:")
        println("    Целевая доходность: ${config.targetYield}%")
        println("    Частота купона: ${config.couponFrequency}/год")
        println("    Без амортизации: ${config.noAmortization}")
        println("    Без флоатеров: ${config.excludeFloating}")
        println("    Без бумаг для квал. инвесторов: ${config.excludeQualInvestorOnly}")
        println("    Риск (T-Invest): ${formatAcceptedRisk(config.minRiskLevel)}")
        println("    Срок до погашения: ${config.minDaysToMaturity}-${config.maxDaysToMaturity} дн.")
        println()

        // Загрузка облигаций
        println("  Загрузка облигаций...")
        val allBonds = client.getAllBonds()
        println("  Найдено: ${allBonds.size}")

        val forNonQual = allBonds.count { !it.forQualInvestorFlag }
        val nonFloating = allBonds.count { !it.floatingCouponFlag }
        println("  Доступно неквалифицированным: $forNonQual, без флоатеров: $nonFloating")

        // Фильтрация по критериям
        val filtered = allBonds.filter { bond ->
            bond.couponQuantityPerYear == config.couponFrequency &&
            (!config.noAmortization || !bond.amortizationFlag) &&
            (!config.excludeFloating || !bond.floatingCouponFlag) &&
            (!config.excludeQualInvestorOnly || !bond.forQualInvestorFlag) &&
            isRiskLevelAcceptable(bond.riskLevel, config.minRiskLevel) &&
            bond.sector != "government"  // Исключаем гособлигации (низкая доходность)
        }
        println("  После фильтрации: ${filtered.size}")

        // Получение цен
        println("  Получение цен...")
        val instrumentIds = filtered.map { "${it.ticker}_${it.classCode}" }
        val prices = client.getLastPrices(instrumentIds)

        // Расчет доходностей
        val bondsWithData = filtered.mapNotNull { bond ->
            val price = prices[bond.ticker]
            if (price == null || price <= 0) {
                println("  [WARN] Нет цены для ${bond.ticker}")
                return@mapNotNull null
            }

            val coupons = client.getBondCoupons(bond.figi)
            val futureCoupons = coupons.filter {
                it.couponDate > LocalDate.now().toString() &&
                it.currency == "rub" &&
                isAcceptableCouponType(it.couponType, config.excludeFloating)
            }.take(config.couponFrequency * 2)

            if (config.excludeFloating && futureCoupons.isEmpty() && coupons.any {
                it.couponDate > LocalDate.now().toString() && isFloatingCouponType(it.couponType)
            }) {
                return@mapNotNull null
            }

            val avgCoupon = if (futureCoupons.isNotEmpty()) {
                if (config.excludeFloating) {
                    futureCoupons.map { it.payOneBond }.average()
                } else {
                    futureCoupons.minByOrNull { it.couponDate }!!.payOneBond
                }
            } else {
                bond.nominal * 0.12 / config.couponFrequency
            }

            val maturityDate = LocalDate.parse(bond.maturityDate)
            val daysToMaturity = ChronoUnit.DAYS.between(LocalDate.now(), maturityDate).toInt()
            
            // Фильтрация по сроку до погашения
            if (daysToMaturity < config.minDaysToMaturity || daysToMaturity > config.maxDaysToMaturity) {
                return@mapNotNull null
            }

            // Расчет доходностей
            // Цена в процентах от номинала, переводим в рубли
            val priceInRubles = price * bond.nominal / 100.0
            val annualCoupon = avgCoupon * config.couponFrequency
            val currentYield = annualCoupon / priceInRubles * 100
            
            // YTM (упрощенно): (купонный доход + разница к номиналу) / цена / годы
            val yearsToMaturity = daysToMaturity / 365.0
            val priceDiff = bond.nominal - priceInRubles
            val totalCouponIncome = annualCoupon * yearsToMaturity
            val totalReturn = totalCouponIncome + priceDiff
            val ytm = (totalReturn / priceInRubles / yearsToMaturity) * 100

            BondData(
                ticker = bond.ticker,
                name = bond.name,
                figi = bond.figi,
                classCode = bond.classCode,
                price = price,
                nominal = bond.nominal,
                couponAmount = avgCoupon,
                couponFrequency = bond.couponQuantityPerYear,
                maturityDate = maturityDate,
                daysToMaturity = daysToMaturity,
                currentYield = currentYield,
                ytm = ytm,
                riskLevel = bond.riskLevel,
                sector = bond.sector,
                amortizationFlag = bond.amortizationFlag,
                totalCoupons = totalCouponIncome,
                totalProfit = totalCouponIncome + priceDiff,
                commission = priceInRubles * config.brokerCommission,
                netProfit = totalCouponIncome + priceDiff - (priceInRubles * config.brokerCommission),
                netYield = ((totalCouponIncome + priceDiff - (priceInRubles * config.brokerCommission)) / priceInRubles / yearsToMaturity * 100),
                assetUid = bond.assetUid
            )
        }

        // Сортировка: фиксированный купон — по YTM, флоатеры — по размеру купона
        val sorted = if (config.excludeFloating) {
            bondsWithData.sortedByDescending { it.ytm }
        } else {
            bondsWithData.sortedByDescending { it.couponAmount }
        }.take(config.maxBondsCount)

        val sortMode = if (config.excludeFloating) "YTM" else "купон"
        println("  Отобрано: ${sorted.size} (сортировка по $sortMode)")

        println("  Фундаментальный анализ эмитентов...")
        val enriched = enrichWithFundamentals(sorted)

        val withScore = enriched.count { it.financialScore > 0 }
        println("  Оценка эмитентов: $withScore/${enriched.size}")
        println()

        return enriched
    }

    private fun enrichWithFundamentals(bonds: List<BondData>): List<BondData> {
        if (bonds.isEmpty()) return bonds

        val shareAssets = client.getShareAssets()
        val resolvedUids = bonds.associate { bond ->
            bond.ticker to resolveAssetUid(bond, shareAssets)
        }

        var fundamentals = client.getAssetFundamentals(resolvedUids.values.filter { it.isNotEmpty() }.distinct())

        // Повторный поиск по акциям, если по assetUid облигации нет данных
        val missing = bonds.filter { fundamentals[resolvedUids[it.ticker]] == null }
        if (missing.isNotEmpty() && shareAssets.isNotEmpty()) {
            val fallbackUids = missing.mapNotNull { bond ->
                val fallback = findIssuerAssetUid(bond.name, shareAssets)
                if (fallback.isNotEmpty() && fallback != resolvedUids[bond.ticker]) bond.ticker to fallback else null
            }.toMap()
            if (fallbackUids.isNotEmpty()) {
                val extra = client.getAssetFundamentals(fallbackUids.values.distinct())
                fundamentals = fundamentals + extra
                return bonds.map { bond ->
                    val primaryUid = resolvedUids[bond.ticker] ?: ""
                    val assetUid = fallbackUids[bond.ticker] ?: primaryUid
                    val fund = fundamentals[assetUid] ?: fundamentals[primaryUid]
                    toBondWithFundamentals(bond, assetUid, fund)
                }
            }
        }

        return bonds.map { bond ->
            val assetUid = resolvedUids[bond.ticker] ?: ""
            toBondWithFundamentals(bond, assetUid, fundamentals[assetUid])
        }
    }

    private fun toBondWithFundamentals(bond: BondData, assetUid: String, fund: IssuerFundamentals?): BondData {
        if (fund == null) return bond.copy(assetUid = assetUid)
        val score = calculateFinancialHealthScore(fund)
        return bond.copy(
            assetUid = assetUid,
            financialScore = score,
            financialScoreLabel = financialScoreLabel(score),
            fundamentals = fund
        )
    }

    private fun resolveAssetUid(bond: BondData, shareAssets: List<TcsBondClient.ShareAssetRef>): String {
        if (bond.assetUid.isNotEmpty()) return bond.assetUid
        return findIssuerAssetUid(bond.name, shareAssets)
    }

    private fun isRiskLevelAcceptable(riskLevel: String, minRiskLevel: String): Boolean {
        val riskValue = RISK_LEVELS[riskLevel] ?: Int.MAX_VALUE
        val minRiskValue = RISK_LEVELS[minRiskLevel] ?: Int.MAX_VALUE
        return riskValue <= minRiskValue
    }

    private fun isFixedCouponType(couponType: String): Boolean =
        couponType in setOf("COUPON_TYPE_FIX", "COUPON_TYPE_FIXED", "COUPON_TYPE_CONSTANT")

    private fun isFloatingCouponType(couponType: String): Boolean =
        couponType in setOf("COUPON_TYPE_FLOATING", "COUPON_TYPE_VARIABLE")

    private fun isAcceptableCouponType(couponType: String, excludeFloating: Boolean): Boolean =
        isFixedCouponType(couponType) || (!excludeFloating && isFloatingCouponType(couponType))
}

// Печать результатов
fun printResults(bonds: List<BondData>, config: BondConfig) {
    val sortLabel = if (config.excludeFloating) "ДОХОДНОСТИ" else "КУПОНУ"
    println("======================================================================================================================================================")
    println("  ТОП-${config.maxBondsCount} ОБЛИГАЦИЙ ПО $sortLabel  |  Частота купона: ${config.couponFrequency}/год  |  Без амортизации: ${config.noAmortization}  |  Без флоатеров: ${config.excludeFloating}  |  Без квал.: ${config.excludeQualInvestorOnly}")
    println("======================================================================================================================================================")
    
    if (bonds.isEmpty()) {
        println("  Нет облигаций, соответствующих критериям")
        return
    }

    println()
    println("#    | Тикер        | Название                  |  Цена  |  Купон   | Купон% |  YTM%  | Дней  |    Риск    |  Фин.  |    Сектор")
    println("--------------------------------------------------------------------------------------------------------------------------------------------------")
    
    bonds.forEachIndexed { index, bond ->
        val sectorShort = when (bond.sector) {
            "corporate" -> "Корпоративный"
            "bank" -> "Банковский"
            "government" -> "Гос. облигации"
            "utilities" -> "Коммунальщики"
            "energy" -> "Энергетика"
            "materials" -> "Материалы"
            "financial" -> "Финансы"
            "consumer" -> "Потребительский"
            "it" -> "IT"
            "industrials" -> "Промышленность"
            else -> bond.sector
        }.take(12)
        
        val riskDisplay = RISK_TO_RATING[bond.riskLevel] ?: bond.riskLevel.removePrefix("RISK_LEVEL_")
        val finDisplay = if (bond.financialScore > 0) {
            "${bond.financialScore} ${bond.financialScoreLabel.take(4)}"
        } else {
            "н/д"
        }
        
        println("%-4d | %-12s | %-25s | %5.2f%% | %6.2f руб | %5.1f%% | %5.1f%% | %5d  | %-10s | %-6s | %-12s".format(
            index + 1,
            bond.ticker,
            bond.name.take(25),
            bond.price,
            bond.couponAmount,
            bond.currentYield,
            bond.ytm,
            bond.daysToMaturity,
            riskDisplay,
            finDisplay,
            sectorShort
        ))
    }
    
    println("--------------------------------------------------------------------------------------------------------------------------------------------------")
    println()
    println("  Примечание: риск — T-Invest (${formatAcceptedRisk(config.minRiskLevel)}); фин. — оценка эмитента 1-5 (плохо → отлично)")

    val bondsWithFundamentals = bonds.filter { it.fundamentals != null }
    if (bondsWithFundamentals.isNotEmpty()) {
        println()
        println("  Фундаментальный анализ эмитентов (топ-${bonds.size}):")
        bondsWithFundamentals.forEach { bond ->
            val f = bond.fundamentals!!
            println()
            println("  ${bond.ticker} — ${bond.financialScore}/5 (${bond.financialScoreLabel})")
            if (f.name.isNotEmpty()) println("    Эмитент: ${f.name}")
            val metrics = listOfNotNull(
                if (f.debtToEquity > 0) "D/E: %.1f%%".format(f.debtToEquity) else null,
                if (f.netDebtToEbitda > 0) "NetDebt/EBITDA: %.2f".format(f.netDebtToEbitda) else null,
                if (f.currentRatio > 0) "Тек.ликв: %.2f".format(f.currentRatio) else null,
                if (f.roe > 0) "ROE: %.1f%%".format(f.roe) else null,
                if (f.roic > 0) "ROIC: %.1f%%".format(f.roic) else null,
                if (f.netMargin > 0) "Маржа: %.1f%%".format(f.netMargin) else null,
                if (f.revenueGrowth5y > 0) "Рост выручки 5л: %.1f%%".format(f.revenueGrowth5y) else null
            )
            if (metrics.isNotEmpty()) {
                println("    ${metrics.joinToString(" | ")}")
            }
        }
    }
    
    // Итоговая статистика
    val avgYtm = bonds.map { it.ytm }.average()
    val avgCoupon = bonds.map { it.couponAmount }.average()
    val avgDays = bonds.map { it.daysToMaturity }.average().toInt()
    val totalCoupons = bonds.sumOf { it.totalCoupons }
    
    println()
    println("  Статистика:")
    if (config.excludeFloating) {
        println("    Средняя YTM: %.2f%%".format(avgYtm))
    } else {
        println("    Средний купон: %.2f руб".format(avgCoupon))
    }
    val scored = bonds.filter { it.financialScore > 0 }
    if (scored.isNotEmpty()) {
        val avgFin = scored.map { it.financialScore }.average()
        println("    Средняя оценка эмитентов: %.1f/5".format(avgFin))
    }
    println("    Средний срок: $avgDays дн.")
    println("    Суммарные купоны (на 1 облиг): %.2f руб".format(totalCoupons / bonds.size))
    println()
    println("  Рекомендуемая стратегия:")
    println("    - Диверсифицировать портфель (5-10 облигаций)")
    println("    - Реинвестировать купоны")
    println("    - Учитывать риск дефолта (особенно для high-yield)")
    println()
    
    // Расчет для заданного капитала
    if (config.capital > 0) {
        println("  Пример портфеля на %.0f руб:".format(config.capital))
        val perBond = config.capital / config.maxBondsCount
        bonds.forEach { bond ->
            // Цена в процентах, переводим в рубли
            val priceInRubles = bond.price * bond.nominal / 100.0
            val quantity = (perBond / priceInRubles).toInt()
            val cost = quantity * priceInRubles
            val monthlyCoupon = quantity * bond.couponAmount
            println("    ${bond.ticker}: $quantity шт. = %.0f руб | Купон/мес: %.2f руб".format(cost, monthlyCoupon))
        }
        val totalMonthly = bonds.sumOf { bond ->
            val priceInRubles = bond.price * bond.nominal / 100.0
            (perBond / priceInRubles).toInt() * bond.couponAmount
        }
        println("    Итого купон/мес: %.2f руб (%.2f%% год.)".format(totalMonthly, totalMonthly * 12 / config.capital * 100))
    }
    
    println("======================================================================================================================================================")
    println("Не является инвестиционной рекомендацией")
    println("======================================================================================================================================================")
}

// Парсинг аргументов командной строки
fun parseArgs(args: Array<String>): BondConfig {
    var targetYield = 16.0
    var couponFreq = 12
    var noAmort = true
    var excludeFloating = true
    var excludeQualOnly = true
    var minRisk = "RISK_LEVEL_MODERATE"
    var maxCount = 10
    var capital = 100_000.0
    var minDays = 90
    var maxDays = 730
    var autoMode = false
    var dryRun = false
    var accountId = ""
    var autoBuyCount = 5
    var autoBuyAmount = 0.0

    var i = 0
    while (i < args.size) {
        when {
            args[i] == "--yield" && i + 1 < args.size -> {
                targetYield = args[i + 1].toDoubleOrNull() ?: targetYield
                i += 2
            }
            args[i] == "--coupon-freq" && i + 1 < args.size -> {
                couponFreq = args[i + 1].toIntOrNull() ?: couponFreq
                i += 2
            }
            args[i] == "--allow-amortization" -> {
                noAmort = false
                i++
            }
            args[i] == "--allow-floating" || args[i] == "-f" -> {
                excludeFloating = false
                i++
            }
            args[i] == "--allow-qual" || args[i] == "-q" -> {
                excludeQualOnly = false
                i++
            }
            args[i] == "--min-risk" && i + 1 < args.size -> {
                minRisk = normalizeRiskLevel(args[i + 1])
                i += 2
            }
            args[i] == "--count" && i + 1 < args.size -> {
                maxCount = args[i + 1].toIntOrNull() ?: maxCount
                i += 2
            }
            args[i] == "--capital" && i + 1 < args.size -> {
                capital = args[i + 1].toDoubleOrNull() ?: capital
                i += 2
            }
            args[i] == "--min-days" && i + 1 < args.size -> {
                minDays = args[i + 1].toIntOrNull() ?: minDays
                i += 2
            }
            args[i] == "--max-days" && i + 1 < args.size -> {
                maxDays = args[i + 1].toIntOrNull() ?: maxDays
                i += 2
            }
            args[i] == "--auto" -> {
                autoMode = true
                i++
            }
            args[i] == "--dry" -> {
                dryRun = true
                i++
            }
            args[i] == "--account-id" && i + 1 < args.size -> {
                accountId = args[i + 1]
                i += 2
            }
            args[i] == "--auto-count" && i + 1 < args.size -> {
                autoBuyCount = args[i + 1].toIntOrNull() ?: autoBuyCount
                i += 2
            }
            args[i] == "--auto-amount" && i + 1 < args.size -> {
                autoBuyAmount = args[i + 1].toDoubleOrNull() ?: autoBuyAmount
                i += 2
            }
            args[i] == "--help" -> {
                println("Использование: bond-scanner.kts [опции]")
                println("Опции:")
                println("  --yield <value>          Целевая доходность (по умолчанию: 16.0)")
                println("  --coupon-freq <value>    Частота купона в год (по умолчанию: 12)")
                println("  --allow-amortization     Разрешить амортизацию (по умолчанию: false)")
                println("  --allow-floating, -f     Разрешить флоатеры (по умолчанию: false)")
                println("  --allow-qual, -q         Разрешить бумаги для квал. инвесторов (по умолчанию: false)")
                println("  --min-risk <level>       Макс. риск: AAA (только надежные), MODERATE (надежные+средние, по умолчанию)")
                println("  --count <value>          Кол-во облигаций в топе (по умолчанию: 10)")
                println("  --capital <value>        Капитал для инвестиций (по умолчанию: 100000)")
                println("  --min-days <value>       Мин. дней до погашения (по умолчанию: 90)")
                println("  --max-days <value>       Макс. дней до погашения (по умолчанию: 730)")
                println("  --auto                   Автоматическая покупка лучших облигаций через Finam")
                println("  --dry                    Режим dry run (без реальных сделок)")
                println("  --account-id <id>        ID аккаунта Finam (или finam.accountId в properties)")
                println("  --auto-count <value>     Кол-во облигаций для покупки (по умолчанию: 5)")
                println("  --auto-amount <value>    Сумма на покупку (0 = весь свободный кэш)")
                println("  --help                   Показать эту справку")
                System.exit(0)
            }
            else -> i++
        }
    }

    return BondConfig(
        targetYield = targetYield,
        couponFrequency = couponFreq,
        noAmortization = noAmort,
        excludeFloating = excludeFloating,
        excludeQualInvestorOnly = excludeQualOnly,
        minRiskLevel = minRisk,
        maxBondsCount = maxCount,
        capital = capital,
        minDaysToMaturity = minDays,
        maxDaysToMaturity = maxDays,
        autoMode = autoMode,
        dryRun = dryRun,
        accountId = accountId,
        autoBuyCount = autoBuyCount,
        autoBuyAmount = autoBuyAmount
    )
}

// Комбинированный скор облигации (чем выше — тем лучше для покупки)
// Учитывает: риск (вес 0.2), фундаментал (вес 0.3), YTM (вес 0.3), купонную доходность (вес 0.2)
fun calculateBondBuyScore(bond: BondData): Double {
    // Компонент 1: Риск (0-100, чем ниже риск — тем выше скор)
    val riskScore = when (bond.riskLevel) {
        "RISK_LEVEL_LOW" -> 100.0
        "RISK_LEVEL_MODERATE" -> 70.0
        "RISK_LEVEL_HIGH" -> 40.0
        "RISK_LEVEL_VERY_HIGH" -> 10.0
        else -> 50.0
    }

    // Компонент 2: Фундаментал (0-100)
    val fundScore = if (bond.financialScore > 0) bond.financialScore * 20.0 else 50.0

    // Компонент 3: YTM (0-100, нормализуем к диапазону 0-30%)
    val ytmScore = (bond.ytm / 30.0 * 100.0).coerceIn(0.0, 100.0)

    // Компонент 4: Купонная доходность (0-100, нормализуем к диапазону 0-25%)
    val couponScore = (bond.currentYield / 25.0 * 100.0).coerceIn(0.0, 100.0)

    return riskScore * 0.2 + fundScore * 0.3 + ytmScore * 0.3 + couponScore * 0.2
}

// Автоматическая покупка облигаций через Finam API
fun executeAutoBuy(bonds: List<BondData>, config: BondConfig, props: java.util.Properties) {
    println()
    println("================================================================")
    println("  АВТОМАТИЧЕСКАЯ ПОКУПКА ОБЛИГАЦИЙ")
    println("================================================================")

    // Инициализация Finam клиента
    val finamSecret = props.getProperty("finam.apiKey", "")
    if (finamSecret.isEmpty()) {
        println("  [ERROR] Не настроен finam.apiKey в application.properties")
        return
    }

    val finam = FinamClient()
    try {
        println("  [1/4] Авторизация в Finam API...")
        finam.authenticate(finamSecret)
        println("  [OK]")
    } catch (e: Exception) {
        println("  [ERROR] Ошибка авторизации Finam: ${e.message}")
        return
    }

    // Определяем аккаунт
    var accountId = config.accountId
    if (accountId.isEmpty()) {
        accountId = props.getProperty("finam.accountId", "")
    }
    if (accountId.isEmpty()) {
        println("  [ERROR] Не указан --account-id и finam.accountId в properties")
        return
    }
    println("  Аккаунт: $accountId")

    // Получаем информацию об аккаунте и свободные средства
    println("  [2/4] Получение информации об аккаунте...")
    val accountJson = try {
        finam.getAccount(accountId)
    } catch (e: Exception) {
        println("  [ERROR] Ошибка получения аккаунта: ${e.message}")
        return
    }

    // Извлекаем свободные средства (available_cash)
    val freeCash = extractFinamCash(accountJson)
    println("  Свободные средства: ${"%.2f".format(freeCash)} руб.")

    if (freeCash <= 0) {
        println("  [WARN] Нет свободных средств для покупки")
        return
    }

    // Сумма для покупки
    val buyAmount = if (config.autoBuyAmount > 0) {
        minOf(config.autoBuyAmount, freeCash)
    } else {
        freeCash
    }
    println("  Сумма на покупку: ${"%.2f".format(buyAmount)} руб.")

    // Ранжируем облигации по комбинированному скору
    println("  [3/4] Ранжирование облигаций...")
    val ranked = bonds
        .filter { it.price > 0 && it.ytm > 0 }
        .sortedByDescending { calculateBondBuyScore(it) }
        .take(config.autoBuyCount)

    if (ranked.isEmpty()) {
        println("  [WARN] Нет подходящих облигаций для покупки")
        return
    }

    println("  Лучшие облигации для покупки:")
    ranked.forEachIndexed { idx, bond ->
        val score = calculateBondBuyScore(bond)
        println("    ${idx + 1}. ${bond.ticker} — YTM: ${"%.1f".format(bond.ytm)}%, Купон: ${"%.1f".format(bond.currentYield)}%, Риск: ${RISK_TO_RATING[bond.riskLevel] ?: "?"}, Фин: ${bond.financialScore}/5, Скор: ${"%.1f".format(score)}")
    }

    // Распределяем сумму между облигациями
    val perBond = buyAmount / ranked.size
    println()
    println("  [4/4] Размещение ордеров...")
    if (config.dryRun) println("  *** DRY RUN — реальные ордера НЕ размещаются ***")
    println("  На каждую облигацию: ${"%.2f".format(perBond)} руб.")
    println()

    var totalSpent = 0.0
    var ordersPlaced = 0

    for (bond in ranked) {
        // Цена в процентах от номинала, номинал通常 1000 руб
        val pricePerBond = bond.price * bond.nominal / 100.0
        val quantity = (perBond / pricePerBond).toInt()

        if (quantity <= 0) {
            println("  [SKIP] ${bond.ticker}: недостаточно средств на 1 облигацию (${"%.2f".format(pricePerBond)} руб.)")
            continue
        }

        // Формируем символ для Finam: ISIN@MISX (нужно найти ISIN по тикеру)
        // Finam использует формат ticker@MIC, для облигаций это typically RU000A...@MISX
        // Но у нас есть только тикер из T-Invest. Используем тикер класса
        val symbol = "${bond.ticker}@MISX"

        // Получаем стакан для определения лучшей цены покупки (ask)
        val orderBook = finam.getOrderBook(symbol)
        val buyPrice = if (orderBook != null && orderBook.asks.isNotEmpty()) {
            // Берем лучшую цену покупки (самый дешевый ask)
            orderBook.asks.minByOrNull { it.price }?.price ?: bond.price
        } else {
            // Фоллбэк на цену из T-Invest
            bond.price
        }

        // Форматируем цену с точкой (не запятой) для Finam API
        val priceFormatted = String.format(java.util.Locale.US, "%.2f", buyPrice)

        println("  [BUY] ${bond.ticker}: $quantity шт. @ $priceFormatted% (стакан: ${if (orderBook != null) "ask=${orderBook.asks.minByOrNull { it.price }?.price ?: 0.0}" else "нет данных"})")

        if (config.dryRun) {
            // Dry run: только считаем стоимость, не размещаем ордер
            val cost = quantity * buyPrice * bond.nominal / 100.0
            totalSpent += cost
            ordersPlaced++
            println("  [DRY] Ордер пропущен (dry run), стоимость: ${"%.2f".format(cost)} руб.")
        } else {
            // Реальный ордер
            val orderId = finam.placeLimitBuyOrder(
                accountId = accountId,
                symbol = symbol,
                quantity = quantity.toString(),
                price = priceFormatted
            )

            if (orderId != null) {
                val cost = quantity * buyPrice * bond.nominal / 100.0
                totalSpent += cost
                ordersPlaced++
                println("  [OK] Ордер размещён: $orderId, стоимость: ${"%.2f".format(cost)} руб.")
            } else {
                println("  [ERROR] Ордер не размещён для ${bond.ticker}")
            }
        }

        Thread.sleep(200) // Задержка между ордерами
    }

    println()
    println("================================================================")
    println("  ИТОГО АВТОПОКУПКИ")
    println("================================================================")
    println("  Ордеров размещено: $ordersPlaced")
    println("  Потрачено: ${"%.2f".format(totalSpent)} руб.")
    println("  Остаток: ${"%.2f".format(buyAmount - totalSpent)} руб.")
    println("================================================================")
}

// Извлечение значения из вложенного объекта: "key": {"value": "87.67"}
fun extractNestedValue(json: String, key: String): String {
    val keyIdx = json.indexOf("\"$key\"")
    if (keyIdx < 0) return ""
    val sub = json.substring(keyIdx)
    val valueMatch = Regex("\"value\"\\s*:\\s*\"([^\"]+)\"").find(sub)
    return valueMatch?.groupValues?.get(1) ?: ""
}

// Извлечение свободных средств из ответа Finam API
// Finam API /v1/accounts/{id} возвращает:
//   "available_cash": {"value": "380.27"} — доступный кэш для торговли
//   "cash": [...] — массив валютных позиций (не свободные средства!)
fun extractFinamCash(json: String): Double {
    // Приоритет 1: available_cash (свободные средства для торговли)
    val availMatch = Regex("\"available_cash\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*\"([^\"]+)\"").find(json)
    if (availMatch != null) {
        return availMatch.groupValues[1].toDoubleOrNull() ?: 0.0
    }

    // Фоллбэк: ищем первый объект в массиве cash (если available_cash нет)
    val cashStart = json.indexOf("\"cash\"")
    if (cashStart < 0) return 0.0
    val sub = json.substring(cashStart)
    val units = Regex("\"units\"\\s*:\\s*\"?([\\d.]+)\"?").find(sub)
        ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    val nanos = Regex("\"nanos\"\\s*:\\s*(\\d+)").find(sub)
        ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    return units + nanos / 1_000_000_000.0
}

// Main
fun main(args: Array<String>) {
    val config = parseArgs(args)
    val scanner = BondScanner()
    val bonds = scanner.scan(config)
    printResults(bonds, config)

    // Автоматическая покупка в auto-режиме
    if (config.autoMode && bonds.isNotEmpty()) {
        val props = loadProperties()
        executeAutoBuy(bonds, config, props)
    }
}

main(args)
