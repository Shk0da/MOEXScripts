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
import kotlin.math.max
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import javax.xml.parsers.DocumentBuilderFactory

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

// Конфигурация сканера
data class Config(
    val totalCapital: Double = 100_000.0,
    val positionSize: Double? = null,  // Фиксированный размер позиции (если null - рассчитывается от капитала)
    val brokerCommission: Double = 0.0005,  // 0.05% комиссия за сделку
    val minProfitThreshold: Double = 0.005,
    val overnightRate: Boolean = true,  // Учитывать плату за перенос шорта
    val tickers: List<String> = listOf(
        "SBER", "GAZP", "LKOH", "TATN", "BSPB",
        "NVTK", "ROSN", "VKCO", "T", "YDEX",
        "PLZL", "GMKN", "ALRS", "AFLT", "CHMF",
        "PHOR"
        // MAGN и AFKS исключены - фьючерсы имеют нестандартный масштаб цены
    )
)

// Маппинг тикеров на AssetCode (базовый актив фьючерса)
val TICKER_TO_ASSETCODE = mapOf(
    "SBER" to "SBRF", "GAZP" to "GAZR", "LKOH" to "LKOH",
    "TATN" to "TATN", "BSPB" to "BSPB", "NVTK" to "NOTKM",
    "ROSN" to "ROSN", "MAGN" to "MAGN", "VKCO" to "VKCO",
    "T" to "T", "YDEX" to "YDEX", "PLZL" to "PLZLM",
    "GMKN" to "GMKN", "ALRS" to "ALRS", "AFKS" to "AFKS",
    "AFLT" to "AFLT", "CHMF" to "CHMF", "PHOR" to "PHOR",
    "MTLR" to "MTLR", "SMLT" to "SMLT", "IRAO" to "IRAO",
    "NLMK" to "NLMK", "VTBR" to "VTBR", "MGNT" to "MGNT",
    "RTKM" to "RTKM", "SNGS" to "SNGR", "FEES" to "FEES",
    "HYDR" to "HYDR", "OZON" to "OZON", "LENT" to "LENT",
    "MVID" to "MVID", "RUAL" to "RUAL", "POLY" to "POLY",
    "TATP" to "TATP", "PIKK" to "PIKK", "TRMK" to "TRMK",
    "CBOM" to "CBOM", "MOEX" to "MOEX", "FLOT" to "FLOT",
    "RASP" to "RASP", "ENPG" to "ENPG", "SFIN" to "SFIN"
)

// Результат сканирования - пара акция/фьючерс
data class StockFuture(
    val ticker: String,
    val futureSecId: String,
    val stockPrice: Double,
    val futurePrice: Double,
    val futureLotVol: Int,
    val marginReq: Double,
    val daysToExpiry: Int,
    val lastTradeDate: String,
    val contangoPercent: Double,
    val contangoAbs: Double,
    val annualYield: Double,
    val contractsCount: Int,
    val grossProfit: Double,
    val commissions: Double,
    val marginCost: Double,
    val overnightFee: Double,
    val netProfit: Double,
    val netYield: Double,
    val isProfitable: Boolean,
    val riskLevel: String
)

// Данные по акции
data class StockData(val ticker: String, val price: Double)

// Данные по фьючерсу
data class FutureData(
    val secId: String,
    val assetCode: String,
    val price: Double,      // Цена в пунктах (копейках для индекса/акций)
    val margin: Double,
    val lotVolume: Int,
    val lastTradeDate: String,
    val decimals: Int = 0   // Количество знаков после запятой
)

// XML парсер для MOEX ISS
class XmlParser {
    private val factory = DocumentBuilderFactory.newInstance().apply {
        setNamespaceAware(false)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }

    fun parse(xml: String): Document {
        val builder = factory.newDocumentBuilder()
        return builder.parse(xml.byteInputStream())
    }

    fun getRows(document: Document, dataId: String): List<Element> {
        val dataNodes = document.getElementsByTagName("data")
        for (i in 0 until dataNodes.length) {
            val dataNode = dataNodes.item(i) as Element
            if (dataNode.getAttribute("id") == dataId) {
                val rows = dataNode.getElementsByTagName("row")
                return (0 until rows.length).map { rows.item(it) as Element }
            }
        }
        return emptyList()
    }

    fun getDouble(element: Element, attr: String): Double {
        return element.getAttribute(attr).toDoubleOrNull() ?: 0.0
    }

    fun getInt(element: Element, attr: String): Int {
        return element.getAttribute(attr).toIntOrNull() ?: 0
    }

    fun getString(element: Element, attr: String): String {
        return element.getAttribute(attr)
    }
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

// Клиент для работы с T-Invest API (стакан)
class TcsClient {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .sslContext(createTrustAllSSLContext())
        .build()
    private val parser = XmlParser()

    // Получение цены из стакана (средняя между bid и ask)
    fun getOrderBookPrice(ticker: String, classCode: String = "TQBR"): StockData? {
        if (tcsApiKey.isBlank()) return null
        
        return try {
            val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetOrderBook"
            val requestBody = """{"depth": 1, "instrumentId": "${ticker}_${classCode}"}"""
            
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
                println("  [$ticker] TCS API HTTP ${resp.statusCode()}")
                return null
            }
            
            // Парсим JSON ответ
            val json = resp.body()
            val bids = extractJsonArray(json, "bids")
            val asks = extractJsonArray(json, "asks")
            
            if (bids.isEmpty() || asks.isEmpty()) return null
            
            val bestBid = extractJsonObject(bids[0], "price")?.toDoubleOrNull() ?: 0.0
            val bestAsk = extractJsonObject(asks[0], "price")?.toDoubleOrNull() ?: 0.0
            
            if (bestBid <= 0 || bestAsk <= 0) return null
            
            // Средняя цена между лучшей покупкой и продажей
            val midPrice = (bestBid + bestAsk) / 2.0
            println("  [$ticker] Стакан: bid=$bestBid, ask=$bestAsk, mid=$midPrice")
            StockData(ticker, midPrice)
        } catch (e: Exception) {
            println("  [$ticker] TCS ошибка: ${e.message}")
            null
        }
    }

    // Вспомогательные функции для парсинга JSON
    private fun extractJsonArray(json: String, key: String): List<String> {
        val startIndex = json.indexOf("\"$key\"")
        if (startIndex < 0) return emptyList()
        val arrayStart = json.indexOf('[', startIndex)
        if (arrayStart < 0) return emptyList()
        val content = extractBalancedBrackets(json, arrayStart)
        return splitJsonArray(content)
    }

    private fun extractJsonObject(json: String, key: String): String? {
        val startIndex = json.indexOf("\"$key\"")
        if (startIndex < 0) return null
        val colonIndex = json.indexOf(':', startIndex)
        if (colonIndex < 0) return null
        val valueStart = colonIndex + 1
        while (valueStart < json.length && (json[valueStart] == ' ' || json[valueStart] == '"')) {
            if (json[valueStart] == '"') {
                val valueEnd = json.indexOf('"', valueStart + 1)
                return if (valueEnd > 0) json.substring(valueStart + 1, valueEnd) else null
            }
        }
        return null
    }

    private fun extractBalancedBrackets(text: String, openPos: Int): String {
        if (openPos >= text.length || text[openPos] != '[') return ""
        var depth = 0
        var inString = false
        var i = openPos
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (ch == '\\') { i += 2; continue }
                if (ch == '"') inString = false
            } else {
                when (ch) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return text.substring(openPos + 1, i)
                    }
                }
            }
            i++
        }
        return text.substring(openPos + 1)
    }

    private fun splitJsonArray(content: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var inString = false
        val current = StringBuilder()
        
        for (ch in content) {
            when {
                ch == '"' && !inString -> inString = true
                ch == '"' && inString -> inString = false
                ch == '{' && !inString -> {
                    depth++
                    current.append(ch)
                }
                ch == '}' && !inString -> {
                    depth--
                    current.append(ch)
                    if (depth == 0) {
                        result.add(current.toString())
                        current.clear()
                    }
                }
                depth > 0 -> current.append(ch)
            }
        }
        return result
    }
}

// Клиент для работы с MOEX ISS API
class MoexClient {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .sslContext(createTrustAllSSLContext())
        .build()
    private val parser = XmlParser()
    private val tcsClient = TcsClient()

    // Получение цены акции по тикеру (с приоритетом T-Invest API)
    fun getStockPrice(ticker: String, useOrderBook: Boolean = true): StockData? {
        // Сначала пробуем получить цену из стакана T-Invest
        if (useOrderBook && tcsApiKey.isNotBlank()) {
            tcsClient.getOrderBookPrice(ticker)?.let { return it }
        }
        
        // Фоллбэк на MOEX ISS
        return try {
            val url = "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities.xml?securities=$ticker"
            val resp = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (resp.statusCode() != 200) {
                println("  [$ticker] MOEX HTTP ${resp.statusCode()}")
                return null
            }
            val doc = parser.parse(resp.body())
            val rows = parser.getRows(doc, "marketdata")
            if (rows.isEmpty()) return null
            
            val lastPrice = rows[0].getAttribute("LAST").toDoubleOrNull() ?: return null
            if (lastPrice <= 0) return null
            
            StockData(ticker, lastPrice)
        } catch (e: Exception) {
            println("  [$ticker] ошибка: ${e.message}")
            null
        }
    }

    // Получение всех фьючерсов с ценами
    fun getAllFutures(): Map<String, FutureData> = try {
        // Получаем список всех фьючерсов
        val url = "https://iss.moex.com/iss/engines/futures/markets/forts/securities.xml"
        val resp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (resp.statusCode() != 200) return emptyMap()
        
        val doc = parser.parse(resp.body())
        val secRows = parser.getRows(doc, "securities")
        
        val futures = mutableMapOf<String, FutureData>()
        for (row in secRows) {
            val secId = parser.getString(row, "SECID")
            val assetCode = parser.getString(row, "ASSETCODE")
            val lotVolume = parser.getInt(row, "LOTVOLUME").takeIf { it > 0 } ?: 1
            val lastTradeDate = parser.getString(row, "LASTTRADEDATE")
            val initialMargin = parser.getDouble(row, "INITIALMARGIN")
            
            // PREVSETTLEPRICE - расчетная цена в пунктах, DECIMALS определяет масштаб
            val prevSettlePrice = parser.getDouble(row, "PREVSETTLEPRICE")
            val decimals = parser.getInt(row, "DECIMALS")
            
            // Конвертируем цену из пунктов в рубли за 1 акцию базового актива
            // DECIMALS=0: цена в копейках (для акций)
            // DECIMALS=1: цена с 1 знаком (для индексов)  
            // DECIMALS=2: цена в рублях (для валюты)
            val pricePerShare = when (decimals) {
                0 -> prevSettlePrice / 100.0  // Акции: 32393 -> 323.93 руб/акцию
                1 -> prevSettlePrice / 10.0   // Индексы
                2 -> prevSettlePrice          // Валюта
                else -> prevSettlePrice / Math.pow(10.0, decimals.toDouble())
            }
            
            // Пропускаем неактивные фьючерсы
            if (pricePerShare > 0 && lastTradeDate.isNotEmpty() && lastTradeDate != "2100-01-01") {
                futures[secId] = FutureData(
                    secId = secId,
                    assetCode = assetCode,
                    price = pricePerShare,  // Цена за 1 акцию базового актива
                    margin = initialMargin,
                    lotVolume = lotVolume,
                    lastTradeDate = lastTradeDate,
                    decimals = 2
                )
            }
        }
        futures
    } catch (e: Exception) {
        println("  [FUTURES] ошибка: ${e.message}")
        emptyMap()
    }
}

// Основной сканер арбитражных возможностей
class ArbitrageScanner(private val config: Config) {
    private val moex = MoexClient()
    private val used = mutableSetOf<String>()
    var useOrderBook: Boolean = true  // Использовать стакан T-Invest API

    // Расчет платы за перенос шорт-позиции через ночь (овернайт)
    // Тарифы: https://bcs.ru/comissions/for-all/short
    private fun calculateOvernightFee(positionValue: Double, daysToExpiry: Int): Double {
        if (!config.overnightRate) return 0.0
        
        // Базовая ставка в день в зависимости от размера позиции
        val dailyRate = when {
            positionValue <= 5_000 -> 5.0
            positionValue <= 50_000 -> 35.0
            positionValue <= 100_000 -> 70.0
            positionValue <= 250_000 -> 175.0
            positionValue <= 500_000 -> 340.0
            positionValue <= 1_000_000 -> 680.0
            positionValue <= 2_500_000 -> 1_700.0
            positionValue <= 5_000_000 -> 3_400.0
            positionValue <= 10_000_000 -> 6_800.0
            positionValue <= 25_000_000 -> 0.00066  // 0.066% в день
            positionValue <= 50_000_000 -> 0.00063  // 0.063% в день
            else -> 0.00055  // 0.055% в день
        }
        
        // Рассчитываем плату за все дни до экспирации
        val feePerDay = if (dailyRate < 1.0) {
            // Процентная ставка
            positionValue * dailyRate
        } else {
            // Фиксированная сумма
            dailyRate
        }
        
        return feePerDay * daysToExpiry
    }

    fun scan(): List<StockFuture> {
        println("=== Загрузка данных с MOEX ISS ===")
        print("  Фьючерсы...")
        val allFutures = moex.getAllFutures()
        println(" ${allFutures.size}")
        
        // Группируем фьючерсы по базовому активу
        val activeFutures = allFutures.values.groupBy { it.assetCode }
        println("  Серий: ${activeFutures.size}")
        
        val assetCodes = activeFutures.keys.toSet()
        val tickerToAsset = mutableMapOf<String, String>()
        
        // Сопоставляем тикеры с AssetCode
        for (t in config.tickers) {
            val m = TICKER_TO_ASSETCODE[t]
            if (m != null && m in assetCodes) tickerToAsset[t] = m
            else if (t in assetCodes) tickerToAsset[t] = t
        }
        
        val opps = mutableListOf<StockFuture>()
        used.clear()
        
        for (t in config.tickers) {
            val ac = tickerToAsset[t] ?: continue
            if (ac in used) continue
            used.add(ac)
            
            print("  $t ($ac): ")
            val stock = moex.getStockPrice(t, useOrderBook) ?: continue
            println("${"%.2f".format(stock.price)} руб.")
            
            val tf = activeFutures[ac] ?: continue
            print("  $t: ГО...")
            
            for (f in tf) {
                analyzePair(t, stock, f)?.let { opps.add(it) }
            }
            println(" ок")
        }
        
        println("=== Готово: ${opps.size} пар ===\n")
        return opps.sortedByDescending { it.netYield }
    }

    // Анализ пары акция/фьючерс
    private fun analyzePair(ticker: String, stock: StockData, future: FutureData): StockFuture? {
        val sharesPerContract = future.lotVolume
        
        // future.price - цена за 1 акцию базового актива в рублях
        val futurePricePerShare = future.price
        val stockCost = stock.price * sharesPerContract
        val futPrice = futurePricePerShare * sharesPerContract // Цена фьючерсного контракта в рублях
        val contangoAbs = futPrice - stockCost
        val contangoPct = if (stockCost > 0) (contangoAbs / stockCost) * 100 else 0.0
        
        val today = LocalDate.now()
        val expiry = try { 
            LocalDate.parse(future.lastTradeDate) 
        } catch (_: Exception) { 
            null 
        }
        val dte = max(1, expiry?.let { ChronoUnit.DAYS.between(today, it).toInt() } ?: 90)
        
        val annualYield = (contangoPct / dte) * 365
        
        // Расчет позиции
        val cnt = if (config.positionSize != null) {
            // Фиксированный размер позиции
            max(1, (config.positionSize / stockCost).toInt())
        } else {
            // Распределяем капитал между всеми позициями
            val cc = used.size.coerceAtLeast(1)
            val posSize = config.totalCapital / cc
            max(1, (posSize / stockCost).toInt())
        }
        
        // Валовая прибыль
        val gross = contangoAbs * cnt
        
        // Комиссии брокера (покупка акции + продажа фьючерса + закрытие)
        // 0.05% за каждую сделку, всего 4 операции: купить акцию, продать фьючерс, продать акцию, купить фьючерс
        val comm = (stockCost + futPrice) * 2 * cnt * config.brokerCommission
        
        // Стоимость финансирования ГО (ставка ~16% годовых)
        val marginRate = 0.16 / 365 * dte
        val mcost = future.margin * cnt * marginRate
        
        // Плата за перенос шорт-позиции через ночь (овернайт)
        // Шорт по фьючерсу = позиция на сумму futPrice * cnt
        val overnightFee = calculateOvernightFee(futPrice * cnt, dte)
        
        // Чистая прибыль
        val net = gross - comm - mcost - overnightFee
        val cap = stockCost * cnt + future.margin * cnt
        val ny = if (cap > 0) (net / cap) * 100 else 0.0
        
        val ok = ny >= config.minProfitThreshold * 100
        val risk = when {
            dte <= 7 -> "Критический"
            dte <= 30 -> "Низкий"
            dte <= 90 -> "Средний"
            else -> "Высокий"
        }
        
        return StockFuture(
            ticker = ticker,
            futureSecId = future.secId,
            stockPrice = stock.price,
            futurePrice = futurePricePerShare,
            futureLotVol = sharesPerContract,
            marginReq = future.margin,
            daysToExpiry = dte,
            lastTradeDate = future.lastTradeDate,
            contangoPercent = contangoPct,
            contangoAbs = contangoAbs,
            annualYield = annualYield,
            contractsCount = cnt,
            grossProfit = gross,
            commissions = comm,
            marginCost = mcost,
            overnightFee = overnightFee,
            netProfit = net,
            netYield = ny,
            isProfitable = ok,
            riskLevel = risk
        )
    }

    // Вывод отчета в виде таблицы
    fun printReport(opps: List<StockFuture>) {
        val profit = opps.filter { it.isProfitable }
        
        println("=".repeat(140))
        println("  АРБИТРАЖНЫЙ СКАНЕР: Акция vs Фьючерс (MOEX)  |  Дата: ${LocalDate.now()}  |  Капитал: ${config.totalCapital.toInt().f()} руб.")
        println("=".repeat(140))
        
        if (profit.isEmpty()) {
            println("Нет прибыльных пар")
            println("-".repeat(140))
        } else {
            println("Прибыльных пар: ${profit.size}")
            println("-".repeat(140))
        }
        
        println("%-4s | %-6s | %-8s | %-10s | %-8s | %-10s | %-10s | %-10s | %-12s | %-12s | %-7s | %-9s | %-9s | %-9s | %-9s | %-9s | %-8s | %-8s"
            .format("#", "Тикер", "Фьючерс", "Экспир.", "Дней", "Акции", "Фьючерс", "Цена акции", "Стоимость", "Стоимость ф.", "Конт.%", "Прибыль", "Комиссия", "ГО", "Овернайт", "Доходн%", "Год%", "Риск"))
        println("-".repeat(225))
        
        val displayList = if (profit.isEmpty()) opps.take(20) else profit.take(20)
        displayList.forEachIndexed { i, o ->
            val sharesCount = o.contractsCount * o.futureLotVol
            val stockPositionValue = o.stockPrice * sharesCount
            val futurePositionValue = o.futurePrice * o.futureLotVol * o.contractsCount
            println("%-4d | %-6s | %-8s | %-10s | %-8d | %-10s | %-10s | %-10s | %-12s | %-12s | %-7.1f | %-9s | %-9s | %-9s | %-9s | %-9.2f | %-8.1f | %-8s"
                .format(
                    i+1,
                    o.ticker,
                    o.futureSecId,
                    o.lastTradeDate,
                    o.daysToExpiry,
                    "+${sharesCount.toInt().f()}",
                    "-${o.contractsCount.toInt().f()}",
                    "${o.stockPrice.toInt().f()} ₽",
                    "${stockPositionValue.toInt().f()} ₽",
                    "${futurePositionValue.toInt().f()} ₽",
                    o.contangoPercent,
                    o.netProfit.toInt().f(),
                    o.commissions.toInt().f(),
                    o.marginCost.toInt().f(),
                    o.overnightFee.toInt().f(),
                    o.netYield,
                    o.annualYield,
                    o.riskLevel
                ))
        }
        
        println("-".repeat(140))
        
        if (profit.isNotEmpty()) {
            val top = profit.take(10)
            val ts = top.sumOf { it.stockPrice * it.futureLotVol * it.contractsCount }
            val tm = top.sumOf { it.marginReq * it.contractsCount }
            val tp = top.sumOf { it.netProfit }
            val totalComm = top.sumOf { it.commissions }
            val totalMargin = top.sumOf { it.marginCost }
            val totalOvernight = top.sumOf { it.overnightFee }
            val totalShares = top.sumOf { it.contractsCount * it.futureLotVol }
            val totalContracts = top.sumOf { it.contractsCount }
            
            val avgStockPrice = if (totalShares > 0) ts / totalShares else 0.0
            val totalFutureValue = top.sumOf { it.futurePrice * it.futureLotVol * it.contractsCount }
            println("ИТОГО (топ-10): Купить акций: ${totalShares.toInt().f()} на ${ts.toInt().f()} ₽ | Продать фьючерсов: ${totalContracts} на ${totalFutureValue.toInt().f()} ₽ | Ср.цена: ${avgStockPrice.toInt().f()} ₽ | ГО: ${tm.toInt().f()} | Всего: ${(ts+tm).toInt().f()} руб.")
            println("         Издержки: Комиссия: ${totalComm.toInt().f()} | ГО: ${totalMargin.toInt().f()} | Овернайт: ${totalOvernight.toInt().f()} руб.")
            println("         Прибыль: ${tp.toInt().f()} руб. | Доходность: ${"%.2f".format(tp/(ts+tm)*100)}%")
        }
        
        println("=".repeat(140))
        println("Не является инвестиционной рекомендацией")
        println("=".repeat(140))
    }

    private fun Int.f(): String = toString().reversed().chunked(3).joinToString(" ").reversed()
}

// Точка входа
fun main(args: Array<String>) {
    var capital = 100_000.0
    var positionSize: Double? = null
    var commission = 0.05
    var tickers: List<String>? = null
    var overnightRate = true
    var useOrderBook = true  // Использовать стакан T-Invest API
    
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--capital" -> capital = args[++i].toDouble()
            "--position-size" -> positionSize = args[++i].toDouble()
            "--commission" -> commission = args[++i].toDouble()
            "--tickers" -> tickers = args[++i].split(",").map { it.trim().uppercase() }
            "--no-overnight" -> overnightRate = false
            "--no-orderbook" -> useOrderBook = false
        }
        i++
    }
    
    val cfg = Config(
        totalCapital = capital,
        positionSize = positionSize,
        brokerCommission = commission / 100,
        overnightRate = overnightRate,
        tickers = tickers ?: Config().tickers
    )
    val sc = ArbitrageScanner(cfg)
    sc.useOrderBook = useOrderBook
    val opps = sc.scan()
    sc.printReport(opps)
}

main(args)
