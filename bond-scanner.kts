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
    val maxDaysToMaturity: Int = 730  // Макс. дней до погашения (2 года)
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
}

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
        maxDaysToMaturity = maxDays
    )
}

// Main
fun main(args: Array<String>) {
    val config = parseArgs(args)
    val scanner = BondScanner()
    val bonds = scanner.scan(config)
    printResults(bonds, config)
}

main(args)
