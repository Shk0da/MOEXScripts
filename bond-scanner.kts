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
    val minRiskLevel: String = "RISK_LEVEL_LOW",  // Минимальный рейтинг
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
    val couponAmount: Double,    // Купон на 1 облигацию (из DoHod)
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
    val dohodQuality: Double = 0.0,  // Качество эмитента по DoHod (0-10)
    val dohodRating: String = "",    // Рейтинг DoHod
    val dohodCoupon: Double = 0.0    // Купон из DoHod (рубли)
)

// Рейтинг облигаций (от лучшего к худшему)
val RISK_LEVELS = mapOf(
    "RISK_LEVEL_LOW" to 1,
    "RISK_LEVEL_MODERATE" to 2,
    "RISK_LEVEL_HIGH" to 3,
    "RISK_LEVEL_VERY_HIGH" to 4
)

// Маппинг на буквенные рейтинги
val RISK_TO_RATING = mapOf(
    "RISK_LEVEL_LOW" to "AAA-BBB",
    "RISK_LEVEL_MODERATE" to "BB-B",
    "RISK_LEVEL_HIGH" to "CCC и ниже",
    "RISK_LEVEL_VERY_HIGH" to "D"
)

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

// Клиент для DoHod.ru API (аналитика эмитентов)
class DohodClient {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .sslContext(createTrustAllSSLContext())
        .build()

    // Получить качество эмитента и рейтинг
    fun getIssuerQuality(isin: String): IssuerQuality? {
        return try {
            val url = "https://analytics.dohod.ru/bond/$isin"
            val resp = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            
            if (resp.statusCode() != 200) {
                return null
            }
            
            parseDohodHtml(resp.body(), isin)
        } catch (e: Exception) {
            println("  [WARN] DoHod ошибка для $isin: ${e.message}")
            null
        }
    }

    data class IssuerQuality(
        val quality: Double,      // Качество эмитента (0-10)
        val rating: String,       // Кредитный рейтинг (AAA, BB, и т.д.)
        val liquidity: Double,    // Ликвидность (0-100)
        val coupon: Double = 0.0  // Размер купона в рублях
    )

    private fun parseDohodHtml(html: String, isin: String): IssuerQuality? {
        // Ищем все доступные рейтинги с приоритетами
        // 1. Внутренний рейтинг ДОХОДЪ
        // 2. АКРА
        // 3. Эксперт РА
        // 4. НКР
        // 5. Другие агентства
        
        var rating = ""
        var quality = 0.0
        
        // Паттерны для разных источников рейтингов (в порядке приоритета)
        val ratingPatterns = listOf(
            // Внутренний рейтинг ДОХОДЪ: "BB (6 из 10)"
            Regex("""Внутренний рейтинг ДОХОДЪ\s*</p>[^<]*<[^>]*>\s*([A-Z+-]+)\s*\(\s*(\d+)\s*из\s*(\d+)"""),
            // АКРА: "АКРА - A-(RU) (6 из 10)"
            Regex("""АКРА\s*-\s*([A-Z+-]+(?:\s*\(RU\))?)\s*\(\s*(\d+)\s*из\s*(\d+)"""),
            // Эксперт РА
            Regex("""Эксперт\s*РА\s*-\s*([A-Z+-]+)\s*\(\s*(\d+)\s*из\s*(\d+)"""),
            // НКР
            Regex("""НКР\s*-\s*([A-Z+-]+)\s*\(\s*(\d+)\s*из\s*(\d+)"""),
            // Общий паттерн: "A (6 из 10)" или "BBB (5 из 10)"
            Regex("""([A-Z+-]{2,3})\s*\(\s*(\d+)\s*из\s*(\d+)""")
        )
        
        for (pattern in ratingPatterns) {
            val match = pattern.find(html)
            if (match != null) {
                rating = match.groupValues[1].replace("(RU)", "").trim()
                quality = match.groupValues[2].toDoubleOrNull() ?: 0.0
                val maxQuality = match.groupValues[3].toDoubleOrNull() ?: 10.0
                // Нормализуем к шкале 0-10
                quality = if (maxQuality > 0) quality * 10 / maxQuality else 0.0
                break  // Берем первый найденный (наивысший приоритет)
            }
        }
        
        // Ликвидность ищем отдельно
        val liquidityPattern = Regex("""Коэф\.\s*ликвидности\s*([0-9.]+)\s*из\s*100""")
        val liquidityMatch = liquidityPattern.find(html)
        val liquidity = liquidityMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        
        // Купон ищем в формате "35.40 RUB" или "Купон 35.40"
        val couponPattern = Regex("""Купон[^0-9]*([0-9.]+)\s*RUB""")
        val couponMatch = couponPattern.find(html)
        val coupon = couponMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        
        if (rating.isEmpty() && quality <= 0) {
            return null
        }

        return IssuerQuality(quality, rating, liquidity, coupon)
    }
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
        val couponQuantityPerYear: Int,
        val maturityDate: String,
        val nominal: Double,
        val riskLevel: String,
        val sector: String,
        val amortizationFlag: Boolean
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
            couponQuantityPerYear = extractInt(json, "couponQuantityPerYear"),
            maturityDate = extractString(json, "maturityDate").substringBefore("T"),
            nominal = extractNominal(json),
            riskLevel = extractString(json, "riskLevel").ifEmpty { "RISK_LEVEL_UNSPECIFIED" },
            sector = extractString(json, "sector"),
            amortizationFlag = extractBoolean(json, "amortizationFlag")
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
}

// Сканер облигаций
class BondScanner {
    private val client = TcsBondClient()
    private val dohodClient = DohodClient()

    fun scan(config: BondConfig): List<BondData> {
        println("=== Сканер облигаций ===")
        println("  Параметры:")
        println("    Целевая доходность: ${config.targetYield}%")
        println("    Частота купона: ${config.couponFrequency}/год")
        println("    Без амортизации: ${config.noAmortization}")
        println("    Мин. рейтинг: ${RISK_TO_RATING[config.minRiskLevel] ?: config.minRiskLevel}")
        println("    Срок до погашения: ${config.minDaysToMaturity}-${config.maxDaysToMaturity} дн.")
        println()

        // Загрузка облигаций
        println("  Загрузка облигаций...")
        val allBonds = client.getAllBonds()
        println("  Найдено: ${allBonds.size}")

        // Фильтрация по критериям
        val filtered = allBonds.filter { bond ->
            bond.couponQuantityPerYear == config.couponFrequency &&
            (!config.noAmortization || !bond.amortizationFlag) &&
            isRiskLevelAcceptable(bond.riskLevel, config.minRiskLevel) &&
            bond.sector != "government"  // Исключаем гособлигации (низкая доходность)
        }
        println("  После фильтрации: ${filtered.size}")

        // Получение цен
        println("  Получение цен...")
        val instrumentIds = filtered.map { "${it.ticker}_${it.classCode}" }
        val prices = client.getLastPrices(instrumentIds)

        // Получение данных DoHod (качество эмитента)
        println("  Получение данных DoHod.ru...")
        val dohodData = filtered.associateWith { bond ->
            dohodClient.getIssuerQuality(bond.ticker)
        }

        // Расчет доходностей
        val bondsWithData = filtered.mapNotNull { bond ->
            val price = prices[bond.ticker]
            if (price == null || price <= 0) {
                println("  [WARN] Нет цены для ${bond.ticker}")
                return@mapNotNull null
            }

            // Данные DoHod
            val dohod = dohodData[bond]
            val dohodQuality = dohod?.quality ?: 0.0
            val dohodRating = dohod?.rating ?: ""
            val dohodCoupon = dohod?.coupon ?: 0.0

            // Используем купон из DoHod (если есть), иначе из API
            val avgCoupon = if (dohodCoupon > 0) {
                dohodCoupon
            } else {
                // Получение купонов из API
                val coupons = client.getBondCoupons(bond.figi)
                val futureCoupons = coupons.filter { 
                    it.couponDate > LocalDate.now().toString() &&
                    it.currency == "rub" &&
                    (it.couponType == "COUPON_TYPE_FIX" || it.couponType == "COUPON_TYPE_FIXED")
                }.take(config.couponFrequency * 2)
                
                if (futureCoupons.isNotEmpty()) {
                    futureCoupons.map { it.payOneBond }.average()
                } else {
                    bond.nominal * 0.12 / config.couponFrequency
                }
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
                dohodQuality = dohodQuality,
                dohodRating = dohodRating,
                dohodCoupon = dohodCoupon
            )
        }

        // Сортировка по доходности к погашению
        val sorted = bondsWithData.sortedByDescending { it.ytm }.take(config.maxBondsCount)

        println("  Отобрано: ${sorted.size}")
        println()

        return sorted
    }

    private fun isRiskLevelAcceptable(riskLevel: String, minRiskLevel: String): Boolean {
        val riskValue = RISK_LEVELS[riskLevel] ?: Int.MAX_VALUE
        val minRiskValue = RISK_LEVELS[minRiskLevel] ?: Int.MAX_VALUE
        return riskValue <= minRiskValue
    }
}

// Печать результатов
fun printResults(bonds: List<BondData>, config: BondConfig) {
    println("======================================================================================================================================================")
    println("  ТОП-${config.maxBondsCount} ОБЛИГАЦИЙ ПО ДОХОДНОСТИ  |  Частота купона: ${config.couponFrequency}/год  |  Без амортизации: ${config.noAmortization}")
    println("======================================================================================================================================================")
    
    if (bonds.isEmpty()) {
        println("  Нет облигаций, соответствующих критериям")
        return
    }

    println()
    println("#    | Тикер        | Название                  |  Цена  |  Купон   | Купон% |  YTM%  | Дней  |  Рейтинг   | Качество |    Сектор")
    println("---------------------------------------------------------------------------------------------------------------------------------------------")
    
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
        
        // Показываем рейтинг DoHod (AAA, AA+ и т.д.)
        val ratingDisplay = if (bond.dohodRating.isNotEmpty()) {
            bond.dohodRating
        } else {
            "н/д"
        }
        
        // Показываем качество эмитента (0-10)
        val qualityDisplay = if (bond.dohodQuality > 0) {
            "${bond.dohodQuality}/10"
        } else {
            "н/д"
        }
        
        println("%-4d | %-12s | %-25s | %5.2f%% | %6.2f руб | %5.1f%% | %5.1f%% | %5d  | %-10s | %-8s | %-12s".format(
            index + 1,
            bond.ticker,
            bond.name.take(25),
            bond.price,
            bond.couponAmount,
            bond.currentYield,
            bond.ytm,
            bond.daysToMaturity,
            ratingDisplay,
            qualityDisplay,
            sectorShort
        ))
    }
    
    println("--------------------------------------------------------------------------------------------------------------------------------------------")
    println()
    println("  Примечание: DoHod - данные с analytics.dohod.ru (качество эмитента и рейтинг)")
    
    // Итоговая статистика
    val avgYtm = bonds.map { it.ytm }.average()
    val avgDays = bonds.map { it.daysToMaturity }.average().toInt()
    val totalCoupons = bonds.sumOf { it.totalCoupons }
    
    println()
    println("  Статистика:")
    println("    Средняя YTM: %.2f%%".format(avgYtm))
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
    var minRisk = "RISK_LEVEL_LOW"
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
            args[i] == "--min-risk" && i + 1 < args.size -> {
                minRisk = args[i + 1]
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
                println("  --min-risk <level>       Мин. уровень риска (по умолчанию: RISK_LEVEL_LOW)")
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
