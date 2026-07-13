#!/usr/bin/env kotlin

import java.io.FileInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Properties
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ─── Вспомогательные функции ───────────────────────────────────────────────

fun loadProperties(): Properties {
    val props = Properties()
    try {
        val path = "${System.getProperty("user.dir")}/application.properties"
        props.load(FileInputStream(path))
    } catch (e: Exception) {
        println("  [WARN] Не удалось загрузить application.properties: ${e.message}")
    }
    return props
}

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

fun createHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(15))
    .sslContext(createTrustAllSSLContext())
    .build()

fun extractString(json: String, key: String): String {
    val match = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)
    return match?.groupValues?.get(1) ?: ""
}

fun extractMoney(json: String, key: String): Double? {
    val start = json.indexOf("\"$key\"")
    if (start < 0) return null
    val sub = json.substring(start)
    val units = Regex("\"units\"\\s*:\\s*\"?(\\d+)\"?").find(sub)
        ?.groupValues?.get(1)?.toLongOrNull() ?: return null
    val nano = Regex("\"nano\"\\s*:\\s*(\\d+)").find(sub)
        ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    return units + nano / 1_000_000_000.0
}

fun extractBalancedBrackets(text: String, openPos: Int): String {
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

fun splitJsonArray(content: String): List<String> {
    val result = mutableListOf<String>()
    var depth = 0
    var inString = false
    val current = StringBuilder()
    for (ch in content) {
        when {
            ch == '"' && !inString -> { inString = true; current.append(ch) }
            ch == '"' && inString -> { inString = false; current.append(ch) }
            ch == '{' && !inString -> { depth++; current.append(ch) }
            ch == '}' && !inString -> {
                depth--; current.append(ch)
                if (depth == 0) { result.add(current.toString()); current.clear() }
            }
            depth > 0 -> current.append(ch)
        }
    }
    return result
}

fun extractJsonArray(json: String, key: String): List<String> {
    val startIndex = json.indexOf("\"$key\"")
    if (startIndex < 0) return emptyList()
    val arrayStart = json.indexOf('[', startIndex)
    if (arrayStart < 0) return emptyList()
    val content = extractBalancedBrackets(json, arrayStart)
    return splitJsonArray(content)
}

fun extractJsonObject(json: String, key: String): String? {
    val startIndex = json.indexOf("\"$key\"")
    if (startIndex < 0) return null
    val colonIndex = json.indexOf(':', startIndex)
    if (colonIndex < 0) return null
    var pos = colonIndex + 1
    while (pos < json.length && json[pos] == ' ') pos++
    if (pos < json.length && json[pos] == '"') {
        val end = json.indexOf('"', pos + 1)
        return if (end > 0) json.substring(pos + 1, end) else null
    }
    return null
}

// ─── Форматирование чисел ──────────────────────────────────────────────────

private val US = java.util.Locale.US

fun fmt(value: Double, decimals: Int = 0): String {
    val pattern = if (decimals > 0) "%,.${decimals}f" else "%,.0f"
    return pattern.format(US, value).replace(',', ' ')
}

// ─── Модели данных ─────────────────────────────────────────────────────────

data class TcsAccount(val id: String, val name: String, val status: String, val type: String)

data class TcsPortfolioPosition(
    val figi: String,
    val instrumentType: String,
    val quantity: Double,
    val avgPrice: Double?,
    val curPrice: Double?,
    val ticker: String,
    val classCode: String,
    val uid: String = ""
)

data class InstrumentInfo(
    val figi: String,
    val ticker: String,
    val classCode: String,
    val instrumentType: String,
    val maturityDate: String = "",
    val isin: String = "",
    val uid: String = ""
)

/** Цель ребалансировки из tcs.rebalance */
data class RebalanceTarget(
    val instrumentType: String,  // STOCK, BOND, ETF, ...
    val ticker: String,
    val weightPercent: Double     // 0..100
)

/** Результат анализа одного инструмента */
data class InstrumentAnalysis(
    val target: RebalanceTarget,
    val instrument: InstrumentInfo?,
    val currentQty: Double,
    val currentPrice: Double,
    val currentValue: Double,
    val targetValue: Double,
    val diffValue: Double,        // положительное = нужно продать, отрицательное = нужно купить
    val diffQty: Int
)

/** Действие для ребалансировки */
data class RebalanceAction(
    val type: String,            // "BUY" или "SELL"
    val ticker: String,
    val figi: String,
    val classCode: String,
    val uid: String,
    val quantity: Int,
    val price: Double,
    val amount: Double
)

// ─── TCS API клиент ────────────────────────────────────────────────────────

class TcsClient(private val apiKey: String) {
    private val http = createHttpClient()

    fun post(url: String, body: String, retries: Int = 3, silent: Boolean = false): String? {
        for (attempt in 1..retries) {
            try {
                val resp = http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer $apiKey")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                if (resp.statusCode() == 429) {
                    val wait = if (attempt < retries) 3L else 0L
                    if (!silent) println("  [WARN] Rate limit (429), попытка $attempt/$retries, жду ${wait}с...")
                    Thread.sleep(wait * 1000L)
                    continue
                }
                if (resp.statusCode() != 200) {
                    if (attempt == retries) {
                        println("  [ERROR] HTTP ${resp.statusCode()} — $url")
                        return null
                    }
                    Thread.sleep(2000L)
                    continue
                }
                Thread.sleep(150L)
                return resp.body()
            } catch (e: Exception) {
                if (attempt == retries) {
                    println("  [ERROR] ${e.javaClass.simpleName}: ${e.message}")
                    return null
                }
                Thread.sleep(2000L)
            }
        }
        return null
    }

    fun getAccounts(): List<TcsAccount> {
        val json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.UsersService/GetAccounts",
            """{"status": "ACCOUNT_STATUS_OPEN"}""") ?: return emptyList()
        val result = mutableListOf<TcsAccount>()
        val accounts = extractJsonArray(json, "accounts")
        for (a in accounts) {
            result.add(TcsAccount(
                id = extractString(a, "id"),
                name = extractString(a, "name"),
                status = extractString(a, "status"),
                type = extractString(a, "type")
            ))
        }
        return result
    }

    fun getPortfolio(accountId: String): List<TcsPortfolioPosition> {
        val json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetPortfolio",
            """{"accountId": "$accountId", "currency": "RUB"}""") ?: return emptyList()
        val result = mutableListOf<TcsPortfolioPosition>()
        val positions = extractJsonArray(json, "positions")
        for (p in positions) {
            val qty = extractMoney(p, "quantity") ?: 0.0
            if (qty == 0.0) continue
            val avgP = extractMoney(p, "averagePositionPrice")
            val curP = extractMoney(p, "currentPrice")
            val moneyQty = extractMoney(p, "quantity") ?: 0.0
            result.add(TcsPortfolioPosition(
                figi = extractString(p, "figi"),
                instrumentType = extractString(p, "instrumentType"),
                quantity = moneyQty,
                avgPrice = avgP,
                curPrice = curP,
                ticker = extractString(p, "ticker"),
                classCode = extractString(p, "classCode"),
                uid = extractString(p, "instrumentUid")
            ))
        }
        return result
    }

    fun getWithdrawLimits(accountId: String): Double {
        val json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetWithdrawLimits",
            """{"accountId": "$accountId"}""", silent = true) ?: return 0.0
        return extractCashTotal(json) - extractBlockedMargin(json)
    }

    fun findInstrument(query: String, instrumentType: String = ""): InstrumentInfo? {
        val kindFilter = if (instrumentType.isNotEmpty()) """, "instrumentKind": "$instrumentType"""" else ""
        val json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/FindInstrument",
            """{"query": "$query"$kindFilter}""", retries = 2) ?: return null
        return parseFindInstrument(json)
    }

    fun findAllInstruments(query: String, instrumentType: String = ""): List<InstrumentInfo> {
        val kindFilter = if (instrumentType.isNotEmpty()) """, "instrumentKind": "$instrumentType"""" else ""
        val json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/FindInstrument",
            """{"query": "$query"$kindFilter}""", retries = 2) ?: return emptyList()
        return parseFindAllInstruments(json)
    }

    fun getOrderBookBestPrices(instrumentId: String): Pair<Double, Double>? {
        val json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetOrderBook",
            """{"depth": 1, "instrumentId": "$instrumentId"}""", retries = 2) ?: return null
        val bids = extractJsonArray(json, "bids")
        val asks = extractJsonArray(json, "asks")
        if (bids.isEmpty() || asks.isEmpty()) return null
        val bestBid = extractMoney(bids[0], "price") ?: 0.0
        val bestAsk = extractMoney(asks[0], "price") ?: 0.0
        if (bestBid <= 0 || bestAsk <= 0) return null
        return Pair(bestBid, bestAsk)
    }

    fun getOrderBookMidPrice(instrumentId: String): Double? {
        val (bid, ask) = getOrderBookBestPrices(instrumentId) ?: return null
        return (bid + ask) / 2.0
    }

    fun getLastPrices(instIds: List<String>): Map<String, Double> {
        if (instIds.isEmpty()) return emptyMap()
        val idsJson = instIds.joinToString(",") { "\"$it\"" }
        val json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetLastPrices",
            """{"instrumentId": [$idsJson]}""", retries = 2) ?: return emptyMap()
        return parseLastPrices(json)
    }

    fun getCandlesByUid(uid: String): Double? {
        val json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetCandles",
            """{"instrumentId": "$uid", "interval": "CANDLE_INTERVAL_DAY", "limit": 1}""", retries = 2, silent = true) ?: return null
        return parseLastCandleClose(json)
    }

    fun postOrder(
        accountId: String,
        figi: String,
        quantity: Long,
        direction: String,
        orderType: String = "ORDER_TYPE_MARKET",
        price: Double? = null,
        ticker: String = "",
        classCode: String = "",
        uid: String = ""
    ): String? {
        val priceField = if (price != null && orderType == "ORDER_TYPE_LIMIT") {
            """, "price": {"units": "${price.toLong()}", "nano": ${(price % 1 * 1_000_000_000).toLong()}}"""
        } else ""
        val instrumentId = when {
            ticker.isNotEmpty() && classCode.isNotEmpty() -> """, "instrumentId": "${ticker}_${classCode}""""
            figi.isNotEmpty() -> """, "instrumentId": "$figi""""
            uid.isNotEmpty() -> """, "instrumentId": "$uid""""
            else -> ""
        }
        if (instrumentId.isEmpty()) return null
        val body = """{"accountId": "$accountId"$instrumentId, "quantity": $quantity, "direction": "$direction", "orderType": "$orderType"$priceField, "orderId": "${System.currentTimeMillis()}"}"""
        println("  [API] PostOrder: $direction $quantity x ${ticker.ifEmpty { figi }}")
        val json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OrdersService/PostOrder",
            body, retries = 2) ?: return null
        val orderId = extractString(json, "orderId")
        val rejects = extractString(json, "rejectReason")
        if (rejects.isNotEmpty()) {
            println("  [ERROR] Ордер отклонён: $rejects")
            return null
        }
        return orderId
    }

    // ─── Парсинг ответов ───────────────────────────────────────────────

    private fun parseAccounts(json: String): List<TcsAccount> {
        val result = mutableListOf<TcsAccount>()
        for (a in extractJsonArray(json, "accounts")) {
            result.add(TcsAccount(
                id = extractString(a, "id"),
                name = extractString(a, "name"),
                status = extractString(a, "status"),
                type = extractString(a, "type")
            ))
        }
        return result
    }

    private fun parseFindInstrument(json: String): InstrumentInfo? {
        val instruments = extractJsonArray(json, "instruments")
        if (instruments.isEmpty()) return null
        val inst = instruments.first()
        return InstrumentInfo(
            figi = extractString(inst, "figi"),
            ticker = extractString(inst, "ticker"),
            classCode = extractString(inst, "classCode"),
            instrumentType = extractString(inst, "instrumentType"),
            maturityDate = extractString(inst, "maturityDate"),
            isin = extractString(inst, "isin"),
            uid = extractString(inst, "uid")
        )
    }

    private fun parseFindAllInstruments(json: String): List<InstrumentInfo> {
        val instruments = extractJsonArray(json, "instruments")
        return instruments.map { inst ->
            InstrumentInfo(
                figi = extractString(inst, "figi"),
                ticker = extractString(inst, "ticker"),
                classCode = extractString(inst, "classCode"),
                instrumentType = extractString(inst, "instrumentType"),
                maturityDate = extractString(inst, "maturityDate"),
                isin = extractString(inst, "isin"),
                uid = extractString(inst, "uid")
            )
        }
    }

    private fun parseLastPrices(json: String): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        val prices = extractJsonArray(json, "lastPrices")
        for (p in prices) {
            val figi = extractString(p, "figi")
            val uid = extractString(p, "uid")
            val price = extractMoney(p, "price") ?: continue
            val key = uid.ifEmpty { figi }
            if (key.isNotEmpty()) result[key] = price
        }
        return result
    }

    private fun parseLastCandleClose(json: String): Double? {
        val candles = extractJsonArray(json, "candles")
        if (candles.isEmpty()) return null
        val candle = candles[0]
        val close = extractMoney(candle, "close")
        return close
    }

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
                        if (currency == "rub" || currency == "RUB") total += money + nano / 1_000_000_000.0
                        objStart = -1
                    }
                }
            }
        }
        return total
    }

    private fun extractBlockedMargin(json: String): Double {
        var total = 0.0
        for (key in listOf("blockedGuarantee", "blocked")) {
            val arr = json.indexOf("\"$key\"")
            if (arr < 0) continue
            var bracketStart = -1
            for (i in arr until json.length) { if (json[i] == '[') { bracketStart = i; break } }
            if (bracketStart < 0) continue
            var bracketEnd = -1; var depth = 0
            for (i in bracketStart until json.length) {
                when (json[i]) {
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { bracketEnd = i; break } }
                }
            }
            if (bracketEnd < 0) continue
            val content = json.substring(bracketStart + 1, bracketEnd)
            var objDepth = 0; var objStart = -1
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
                            if (currency == "rub" || currency == "RUB") total += units + nano / 1_000_000_000.0
                            objStart = -1
                        }
                    }
                }
            }
        }
        return total
    }
}

// ─── Парсинг конфигурации rebalance ────────────────────────────────────────

fun parseRebalanceConfig(config: String): List<RebalanceTarget> {
    val result = mutableListOf<RebalanceTarget>()
    val entries = config.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    for (entry in entries) {
        val parts = entry.split(":")
        if (parts.size != 3) {
            println("  [WARN] Неверный формат rebalance: '$entry' (ожидается TYPE:TICKER:WEIGHT)")
            continue
        }
        val type = parts[0].trim().uppercase()
        val ticker = parts[1].trim().uppercase()
        val weight = parts[2].trim().toDoubleOrNull()
        if (weight == null || weight <= 0) {
            println("  [WARN] Неверный вес для $ticker: '${parts[2]}'")
            continue
        }
        result.add(RebalanceTarget(type, ticker, weight))
    }
    return result
}

// ─── Логика ребалансировки ─────────────────────────────────────────────────

fun calculateRebalance(
    targets: List<RebalanceTarget>,
    positions: List<TcsPortfolioPosition>,
    cash: Double,
    tcs: TcsClient,
    tmonInstrument: InstrumentInfo?
): Pair<List<InstrumentAnalysis>, List<RebalanceAction>> {

    // TMON@常量
    val tmonFigiDefault = "TCS70A106DL2"
    val tmonClassCodeDefault = "SPBRU"
    val tmonIsin = "RU000A106DL2"

    // Поиск TMON@ в портфеле
    val tmonPosition = positions.firstOrNull {
        it.figi == (tmonInstrument?.figi ?: tmonFigiDefault) ||
        it.ticker.equals("TMON@", ignoreCase = true) ||
        it.instrumentType.equals("ETF", ignoreCase = true) && it.figi.contains("TMON", ignoreCase = true)
    }

    // Стоимость всех бумаг в портфеле (включая TMON@)
    var totalSecuritiesValue = 0.0
    var cashInPortfolio = 0.0
    for (pos in positions) {
        val value = pos.quantity * (pos.curPrice ?: 0.0)
        when {
            pos.instrumentType.equals("CURRENCY", ignoreCase = true) -> cashInPortfolio += value
            pos.instrumentType.equals("FUTURES", ignoreCase = true) -> { }
            else -> totalSecuritiesValue += value
        }
    }

    // Общая стоимость портфеля = ценные бумаги + кэш из портфеля
    val totalPortfolioValue = totalSecuritiesValue + cashInPortfolio
    val totalWeight = targets.sumOf { it.weightPercent }

    println("  Общая стоимость портфеля: ${fmt(totalPortfolioValue, 2)} руб.")
    println("  Кэш в портфеле: ${fmt(cashInPortfolio, 2)} руб.")
    println("  Ценные бумаги: ${fmt(totalSecuritiesValue, 2)} руб.")
    println("  Суммарный вес целей: ${"%.1f".format(totalWeight)}%")

    if (totalWeight > 100) {
        println("  [ERROR] Суммарный вес целей превышает 100%!")
        return Pair(emptyList(), emptyList())
    }

    // Ценовой запрос: собираем instrumentId для стакана
    val analyses = mutableListOf<InstrumentAnalysis>()
    val instrumentMap = mutableMapOf<String, InstrumentInfo>()  // ticker -> instrument info

    println()
    println("  Поиск инструментов в API и получение цен из стакана...")
    println()

    for (target in targets) {
        // Ищем все варианты инструмента и выбираем подходящий
        val allInstruments = tcs.findAllInstruments(target.ticker)
        if (allInstruments.isEmpty()) {
            println("  [WARN] Инструмент ${target.ticker} (${target.instrumentType}) не найден в API")
            analyses.add(InstrumentAnalysis(
                target = target, instrument = null,
                currentQty = 0.0, currentPrice = 0.0, currentValue = 0.0,
                targetValue = totalPortfolioValue * target.weightPercent / 100.0,
                diffValue = 0.0, diffQty = 0
            ))
            continue
        }

        // Приоритет classCode для торгуемых инструментов:
        // TQBR — основной режим MOEX (акции)
        // SPBRU — SPB (ETF вроде TMON@)
        val preferredClassCodes = listOf("TQBR", "SPBRU")
        val instrument = allInstruments.firstOrNull { it.classCode in preferredClassCodes }
            ?: allInstruments.first()

        instrumentMap[target.ticker] = instrument
        println("  ${target.ticker}: classCode=${instrument.classCode}, figi=${instrument.figi}")

        // Определяем instrumentId для стакана: ticker_classCode
        val instrumentId = "${instrument.ticker}_${instrument.classCode}"

        // Получаем цену из стакана
        val midPrice = tcs.getOrderBookMidPrice(instrumentId)
        val price = if (midPrice != null) {
            println("    ${target.ticker}: стакан mid = ${"%.2f".format(midPrice)} руб.")
            midPrice
        } else {
            // Фоллбэк на последнюю цену
            val lastPrices = tcs.getLastPrices(listOf(instrumentId))
            val fallback = lastPrices[instrumentId]
            if (fallback != null) {
                println("    ${target.ticker}: последняя цена = ${"%.2f".format(fallback)} руб. (стакан недоступен)")
                fallback
            } else {
                // Фоллбэк на свечи
                val candlePrice = if (instrument.uid.isNotEmpty()) tcs.getCandlesByUid(instrument.uid) else null
                if (candlePrice != null) {
                    println("    ${target.ticker}: цена свечи = ${"%.2f".format(candlePrice)} руб. (стакан недоступен)")
                    candlePrice
                } else {
                    println("    ${target.ticker}: [WARN] цена недоступна!")
                    0.0
                }
            }
        }

        // Текущая позиция в портфеле
        val pos = positions.firstOrNull {
            it.ticker.equals(target.ticker, ignoreCase = true) ||
            it.figi == instrument.figi
        }
        val currentQty = pos?.quantity ?: 0.0
        val currentPriceForVal = pos?.curPrice ?: price
        val currentValue = currentQty * currentPriceForVal
        val targetValue = totalPortfolioValue * target.weightPercent / 100.0
        val diffValue = currentValue - targetValue

        // Количество лотов для покупки/продажи
        val diffQty = if (price > 0) {
            (diffValue / price).toInt()  // положительное = продать, отрицательное = купить
        } else 0

        analyses.add(InstrumentAnalysis(
            target = target,
            instrument = instrument,
            currentQty = currentQty,
            currentPrice = price,
            currentValue = currentValue,
            targetValue = targetValue,
            diffValue = diffValue,
            diffQty = diffQty
        ))
    }

    // ─── Расчет TMON@ для остатка кэша ────────────────────────────────
    val tmonTargetWeight = 100.0 - totalWeight
    val tmonTargetValue = totalPortfolioValue * tmonTargetWeight / 100.0
    val tmonPositionValue = tmonPosition?.let { it.quantity * (it.curPrice ?: 0.0) } ?: 0.0
    val tmonFigi = tmonInstrument?.figi ?: tmonFigiDefault
    val tmonClassCode = tmonInstrument?.classCode ?: tmonClassCodeDefault
    val tmonUid = tmonInstrument?.uid ?: ""

    val tmonInstrumentId = when {
        tmonUid.isNotEmpty() -> tmonUid
        tmonFigi.isNotEmpty() -> tmonFigi
        else -> "TMON@_SPBRU"
    }
    val tmonPrice = tcs.getOrderBookMidPrice(tmonInstrumentId)
        ?: tcs.getLastPrices(listOf(tmonInstrumentId))[tmonInstrumentId]
        ?: (if (tmonUid.isNotEmpty()) tcs.getCandlesByUid(tmonUid) else null)
        ?: 0.0

    if (tmonTargetWeight > 0) {
        println("    TMON@: стакан mid = ${"%.2f".format(tmonPrice)} руб." +
            if (tmonPrice <= 0) " [WARN] цена недоступна!" else "")
    }

    val tmonDiffValue = tmonPositionValue - tmonTargetValue
    val tmonDiffQty = if (tmonPrice > 0) (tmonDiffValue / tmonPrice).toInt() else 0

    // ─── Формирование действий ─────────────────────────────────────────
    // Правило: бумаги НЕ продаются. Для покупок продается TMON@ (денежный буфер).
    // TMON@ = "свободные средства". Избыток кэша идёт в TMON@, недостаток покрывается продажей TMON@.

    val actions = mutableListOf<RebalanceAction>()

    // 1. Собираем ВСЕ покупки бумаг (кроме TMON@) — это недостающие доли
    var totalBuyAmount = 0.0
    for (a in analyses) {
        if (a.diffQty < 0 && a.instrument != null) {
            val amount = (-a.diffQty) * a.currentPrice
            totalBuyAmount += amount
            actions.add(RebalanceAction(
                type = "BUY",
                ticker = a.target.ticker,
                figi = a.instrument.figi,
                classCode = a.instrument.classCode,
                uid = a.instrument.uid,
                quantity = -a.diffQty,
                price = a.currentPrice,
                amount = amount
            ))
        }
    }

    // 2. Определяем, хватает ли кэша на покупки. Если нет — продаем TMON@.
    //    Если кэша больше чем нужно — докупаем TMON@.
    val availableCash = cash  // свободный кэш в рублях

    if (totalBuyAmount > 0) {
        if (availableCash >= totalBuyAmount) {
            // Кэша хватает — покупки идут из кэша, TMON@ не трогаем
            println("  [INFO] Кэша достаточно (${fmt(availableCash)} р.)" +
                " для покупок на ${fmt(totalBuyAmount)} р.")
        } else {
            // Кэша не хватает — продаем TMON@ на недостающую сумму
            val deficit = totalBuyAmount - availableCash
            val tmonSellQty = if (tmonPrice > 0) (deficit / tmonPrice).toInt().coerceAtLeast(1) else 0
            val tmonSellAmount = tmonSellQty * tmonPrice

            if (tmonSellQty > 0 && tmonSellAmount > 0) {
                println("  [INFO] Кэша недостаточно: нужно ${fmt(totalBuyAmount)} р.," +
                    " есть ${fmt(availableCash)} р.")
                println("  [INFO] Продаем ${tmonSellQty} шт. TMON@ на ${fmt(tmonSellAmount)} р.")
                actions.add(RebalanceAction(
                    type = "SELL", ticker = "TMON@", figi = tmonFigi,
                    classCode = tmonClassCode, uid = tmonUid,
                    quantity = tmonSellQty, price = tmonPrice,
                    amount = tmonSellAmount
                ))
            }
        }
    }

    // 3. Если бумаги избыточны (diffQty > 0) — НЕ продаём их, просто показываем
    for (a in analyses) {
        if (a.diffQty > 0 && a.instrument != null) {
            println("  [INFO] ${a.target.ticker}: избыточная доля +${fmt(a.diffValue)} р." +
                " (${a.currentQty.toInt()} шт). Бумаги не продаются — остаются в портфеле.")
        }
    }

    // 4. Если кэша осталось после покупок — докупаем TMON@
    val totalSellAmount = actions.filter { it.type == "SELL" }.sumOf { it.amount }
    val cashAfterBuysAndSells = availableCash + totalSellAmount - totalBuyAmount
    if (cashAfterBuysAndSells > tmonPrice && tmonPrice > 0) {
        val tmonBuyQty = (cashAfterBuysAndSells / tmonPrice).toInt()
        val tmonBuyAmount = tmonBuyQty * tmonPrice
        if (tmonBuyQty > 0) {
            println("  [INFO] Остаток кэша ${fmt(cashAfterBuysAndSells)} р." +
                " → докупаем ${tmonBuyQty} шт. TMON@")
            actions.add(RebalanceAction(
                type = "BUY", ticker = "TMON@", figi = tmonFigi,
                classCode = tmonClassCode, uid = tmonUid,
                quantity = tmonBuyQty, price = tmonPrice,
                amount = tmonBuyAmount
            ))
        }
    }

    // Добавляем информацию о TMON@ в analyses для вывода
    if (tmonTargetWeight > 0) {
        analyses.add(InstrumentAnalysis(
            target = RebalanceTarget("ETF", "TMON@", tmonTargetWeight),
            instrument = tmonInstrument,
            currentQty = tmonPosition?.quantity ?: 0.0,
            currentPrice = tmonPrice,
            currentValue = tmonPositionValue,
            targetValue = tmonTargetValue,
            diffValue = tmonDiffValue,
            diffQty = tmonDiffQty
        ))
    }

    return Pair(analyses, actions)
}

// ─── Вывод отчета ──────────────────────────────────────────────────────────

fun printRebalanceReport(analyses: List<InstrumentAnalysis>, actions: List<RebalanceAction>) {
    println()
    println("================================================================")
    println("  ОТЧЕТ ПО РЕБАЛАНСИРОВКЕ ПОРТФЕЛЯ")
    println("================================================================")
    println()

    // Таблица текущего состояния и целей
    println("  %-8s  %-10s  %15s  %15s  %15s  %12s  %6s".format(
        "ТИП", "ТИКЕР", "ТЕКУЩАЯ", "ЦЕЛЕВАЯ", "РАЗНИЦА", "ЦЕНА", "ВЕС%"))
    println("  " + "-".repeat(80))

    for (a in analyses) {
        val sign = if (a.diffValue > 0) "+" else ""
        println("  %-8s  %-10s  %15s  %15s  %s%14s  %12s  %5.1f%%".format(
            a.target.instrumentType,
            a.target.ticker,
            "${fmt(a.currentValue)} р.",
            "${fmt(a.targetValue)} р.",
            sign,
            "${fmt(a.diffValue)} р.",
            if (a.currentPrice > 0) "${fmt(a.currentPrice, 2)} р." else "—",
            a.target.weightPercent
        ))
    }
    println()

    // Итого
    val totalCurrent = analyses.sumOf { it.currentValue }
    val totalTarget = analyses.sumOf { it.targetValue }
    println("  ИТОГО:  текущая=${fmt(totalCurrent)} р." +
        "  целевая=${fmt(totalTarget)} р.")

    // Действия
    if (actions.isEmpty()) {
        println()
        println("  [OK] Портфель сбалансирован. Ребалансировка не требуется.")
    } else {
        println()
        println("================================================================")
        println("  ДЕЙСТВИЯ ДЛЯ РЕБАЛАНСИРОВКИ")
        println("================================================================")
        println()

        val sells = actions.filter { it.type == "SELL" }
        val buys = actions.filter { it.type == "BUY" }

        if (sells.isNotEmpty()) {
            println("  ПРОДАЖА:")
            for (a in sells) {
                println("    %-6s  %-10s  %5d шт  x  %12s р.  =  %12s р.".format(
                    a.type, a.ticker, a.quantity,
                    "${fmt(a.price, 2)}",
                    "${fmt(a.amount)}"
                ))
            }
            println("  Итого продажа: ${fmt(sells.sumOf { it.amount })} руб.")
            println()
        }

        if (buys.isNotEmpty()) {
            println("  ПОКУПКА:")
            for (a in buys) {
                println("    %-6s  %-10s  %5d шт  x  %12s р.  =  %12s р.".format(
                    a.type, a.ticker, a.quantity,
                    "${fmt(a.price, 2)}",
                    "${fmt(a.amount)}"
                ))
            }
            println("  Итого покупка: ${fmt(buys.sumOf { it.amount })} руб.")
        }

        println()
        println("  [!] Бумаги не продаются. Для покупок продается TMON@ (денежный буфер)")
        println("================================================================")
    }
}

// ─── Автоматическое выполнение ─────────────────────────────────────────────

fun executeRebalancing(
    accountId: String,
    tcs: TcsClient,
    actions: List<RebalanceAction>
) {
    if (actions.isEmpty()) return

    println()
    println("================================================================")
    println("  ВЫПОЛНЕНИЕ РЕБАЛАНСИРОВКИ")
    println("================================================================")

    // Сначала продажи
    val sells = actions.filter { it.type == "SELL" }
    for (a in sells) {
        println("  [SELL] ${a.ticker}: ${a.quantity} шт по ${fmt(a.price, 2)} р.")
        val orderId = tcs.postOrder(
            accountId = accountId, figi = a.figi, quantity = a.quantity.toLong(),
            direction = "ORDER_DIRECTION_SELL", orderType = "ORDER_TYPE_MARKET",
            ticker = a.ticker, classCode = a.classCode, uid = a.uid
        )
        if (orderId != null) println("  [OK] Ордер: $orderId")
        else println("  [WARN] Не удалось разместить ордер на продажу ${a.ticker}")
        Thread.sleep(500)
    }

    // Затем покупки
    val buys = actions.filter { it.type == "BUY" }
    for (a in buys) {
        println("  [BUY] ${a.ticker}: ${a.quantity} шт по ${fmt(a.price, 2)} р.")
        val orderId = tcs.postOrder(
            accountId = accountId, figi = a.figi, quantity = a.quantity.toLong(),
            direction = "ORDER_DIRECTION_BUY", orderType = "ORDER_TYPE_MARKET",
            ticker = a.ticker, classCode = a.classCode, uid = a.uid
        )
        if (orderId != null) println("  [OK] Ордер: $orderId")
        else println("  [WARN] Не удалось разместить ордер на покупку ${a.ticker}")
        Thread.sleep(500)
    }

    println("  [OK] Ребалансировка завершена.")
}

// ─── Main ──────────────────────────────────────────────────────────────────

fun main(args: Array<String>) {
    val autoMode = args.contains("--auto")

    // Парсим аргументы командной строки
    var overrideAccountId: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--account" -> { overrideAccountId = args[++i]; i++ }
            "--auto" -> i++
            else -> i++
        }
    }

    println("================================================================")
    println("  REBALANCE SCANNER (Ребалансировка портфеля)")
    println("  Дата: ${LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}")
    if (autoMode) println("  РЕЖИМ: АВТОМАТИЧЕСКАЯ РЕБАЛАНСИРОВКА")
    println("================================================================")
    println()

    // 1. Загрузка конфигурации
    val props = loadProperties()
    val tcsApiKey = props.getProperty("tcs.apiKey", "")
    if (tcsApiKey.isEmpty()) {
        println("  [ERROR] Не настроен T-Invest API ключ в application.properties")
        kotlin.system.exitProcess(1)
    }

    val tcs = TcsClient(tcsApiKey)

    // 2. Определение аккаунта
    var accountId = overrideAccountId ?: props.getProperty("tcs.accountId", "")
    if (accountId.isEmpty()) {
        println("  [WARN] Аккаунт не указан. Загрузка списка счетов...")
        val accounts = tcs.getAccounts()
        if (accounts.isEmpty()) {
            println("  [ERROR] Нет доступных счетов")
            kotlin.system.exitProcess(1)
        }
        println()
        println("  Доступные счета:")
        for ((idx, acc) in accounts.withIndex()) {
            println("    ${idx + 1}. ${acc.name} [${acc.type}] (${acc.id})")
        }
        print("\n  Выберите номер счета: ")
        val choice = readLine()?.trim()?.toIntOrNull()
        if (choice == null || choice < 1 || choice > accounts.size) {
            println("  [ERROR] Неверный выбор")
            kotlin.system.exitProcess(1)
        }
        accountId = accounts[choice - 1].id
        println("  Выбран: ${accounts[choice - 1].name} ($accountId)")
    }
    println("  Аккаунт: $accountId")
    if (overrideAccountId != null) {
        println("  [INFO] Используется аккаунт из параметров командной строки (--account)")
    }

    // 3. Загрузка целей ребалансировки
    val rebalanceConfig = props.getProperty("tcs.rebalance", "")
    if (rebalanceConfig.isEmpty()) {
        println("  [ERROR] tcs.rebalance не настроен в application.properties")
        println("  Формат: TYPE:TICKER:WEIGHT;TYPE:TICKER:WEIGHT;...")
        println("  Пример: STOCK:SBERP:30;STOCK:CHMF:20;STOCK:PHOR:15;STOCK:LSNGP:15;STOCK:SNGSP:20;")
        kotlin.system.exitProcess(1)
    }

    val targets = parseRebalanceConfig(rebalanceConfig)
    if (targets.isEmpty()) {
        println("  [ERROR] Нет валидных целей в tcs.rebalance")
        kotlin.system.exitProcess(1)
    }

    println()
    println("  Цели ребалансировки:")
    for (t in targets) {
        println("    ${t.instrumentType}  ${t.ticker}  →  ${"%.1f".format(t.weightPercent)}%")
    }

    // 4. Загрузка портфеля
    println()
    println("  Загрузка портфеля...")
    val positions = tcs.getPortfolio(accountId)
    val cash = tcs.getWithdrawLimits(accountId)

    println("  Позиций в портфеле: ${positions.size}")
    println("  Свободный кэш: ${fmt(cash, 2)} руб.")

    if (positions.isNotEmpty()) {
        println()
        println("  Состав портфеля:")
        for (pos in positions.filter { it.quantity > 0 }) {
            val value = pos.quantity * (pos.curPrice ?: 0.0)
            println("    ${pos.ticker.ifEmpty { pos.figi }} (${pos.instrumentType}): " +
                "${pos.quantity} шт x ${fmt(pos.curPrice ?: 0.0, 2)} р. " +
                "= ${fmt(value)} р.")
        }
    }

    // 5. Поиск TMON@ (фонд денежного рынка для остатка кэша)
    println()
    println("  Поиск TMON@ (фонд денежного рынка)...")
    val tmonAll = tcs.findAllInstruments("TMON@")
    val tmonInstrument = tmonAll.firstOrNull { it.classCode == "SPBRU" } ?: tmonAll.firstOrNull()
    if (tmonInstrument != null) {
        println("  TMON@: ticker=${tmonInstrument.ticker}, figi=${tmonInstrument.figi}, classCode=${tmonInstrument.classCode}")
    } else {
        println("  [WARN] TMON@ не найден через API, используются значения по умолчанию")
    }

    // 6. Расчет ребалансировки
    println()
    println("  Анализ портфеля и расчет ребалансировки...")
    val (analyses, actions) = calculateRebalance(targets, positions, cash, tcs, tmonInstrument)

    // 7. Вывод отчета
    printRebalanceReport(analyses, actions)

    // 8. Автоматическое выполнение (только при --auto)
    if (autoMode && actions.isNotEmpty()) {
        executeRebalancing(accountId, tcs, actions)
    }
}

main(args)
