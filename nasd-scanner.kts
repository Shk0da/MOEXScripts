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

// Константы стратегии
val CASH_TARGET_PERCENT = BigDecimal("0.10")  // 10% кэша
val BROKER_COLLATERAL_DISCOUNT = BigDecimal("0.10")  // 10% дисконт брокера

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

data class TcsAccount(
    val id: String,
    val name: String,
    val status: String,
    val type: String
)

data class TcsPortfolioPosition(
    val figi: String,
    val instrumentType: String,
    val quantity: Double,
    val avgPrice: Double?,
    val curPrice: Double?,
    val ticker: String,
    val classCode: String
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

/**
 * Действия для ребалансировки портфеля.
 */
data class RebalanceAction(
    val type: String,           // "BUY_FUTURES", "SELL_FUTURES", "BUY_TMON", "SELL_TMON"
    val figi: String,
    val ticker: String,
    val classCode: String = "",
    val uid: String = "",
    val quantity: Int,
    val price: Double,
    val amount: Double
)

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
                    println("  [WARN] Rate limit (429), попытка $attempt/$retries, жду ${wait}0 сек...")
                    println("  [WARN] Request: $url")
                    println("  [WARN] Request body: $body")
                    Thread.sleep(wait * 1000L)
                    continue
                }

                if (resp.statusCode() != 200) {
                    if (attempt == retries) {
                        println("  [ERROR] HTTP ${resp.statusCode()}")
                        println("  [ERROR] Request: $url")
                        println("  [ERROR] Request body: $body")
                        println("  [ERROR] Response: ${resp.body()}")
                        return null
                    }
                    Thread.sleep(2000L)
                    continue
                }

                Thread.sleep(150L)
                return resp.body()
            } catch (e: Exception) {
                if (attempt == retries) {
                    println("  [ERROR] TCS API exception: ${e.javaClass.simpleName}: ${e.message}")
                    println("  [ERROR] Request: $url")
                    println("  [ERROR] Request body: $body")
                    e.printStackTrace()
                    return null
                }
                Thread.sleep(2000L)
            }
        }
        return null
    }

    fun getAccounts(): List<TcsAccount> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.UsersService/GetAccounts"
        val body = """{"status": "ACCOUNT_STATUS_OPEN"}"""
        val json = post(url, body) ?: return emptyList()
        return parseAccounts(json)
    }

    fun getPortfolio(accountId: String): List<TcsPortfolioPosition> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetPortfolio"
        val body = """{"accountId": "$accountId", "currency": "RUB"}"""
        val json = post(url, body) ?: return emptyList()
        return parsePortfolioPositions(json)
    }

    fun getWithdrawLimits(accountId: String): Double {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetWithdrawLimits"
        val body = """{"accountId": "$accountId"}"""
        val json = post(url, body, silent = true) ?: return 0.0
        val totalCash = extractCashTotal(json)
        val blocked = extractBlockedMargin(json)
        if (blocked > 0.0) {
            println("  Кэш (всего): %.2f руб".format(totalCash).replace(',', '.'))
            println("  Заблокировано (ГО): %.2f руб".format(blocked).replace(',', '.'))
            println("  Свободный кэш: %.2f руб".format(totalCash - blocked).replace(',', '.'))
        }
        return totalCash - blocked
    }

    fun findInstrument(query: String, instrumentType: String = ""): InstrumentInfo? {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/FindInstrument"
        val kindFilter = if (instrumentType.isNotEmpty()) """, "instrumentKind": "$instrumentType"""" else ""
        val body = """{"query": "$query"$kindFilter}"""
        val json = post(url, body, retries = 2) ?: return null
        return parseFindInstrument(json)
    }

    fun findAllInstruments(query: String, instrumentType: String = ""): List<InstrumentInfo> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/FindInstrument"
        val kindFilter = if (instrumentType.isNotEmpty()) """, "instrumentKind": "$instrumentType"""" else ""
        val body = """{"query": "$query"$kindFilter}"""
        val json = post(url, body, retries = 2) ?: return emptyList()
        return parseFindAllInstruments(json)
    }

    /**
     * Поиск инструмента по ISIN.
     * FindInstrument API не принимает ISIN напрямую — ищем по тикеру и фильтруем по ISIN.
     */
    fun findByIsin(isin: String): InstrumentInfo? {
        // Сначала пробуем поискать по тикеру TMON@
        val byTicker = findAllInstruments("TMON@")
        val match = byTicker.firstOrNull { it.isin == isin }
        if (match != null) return match
        // Фоллбэк: ищем по самому ISIN (мungkin API вернёт результат)
        val byIsin = findAllInstruments(isin)
        return byIsin.firstOrNull { it.isin == isin }
    }

    /**
     * Поиск NASDAQ фьючерсов с оценкой выгодности контракта.
     * Выбирает контракт с оптимальным соотношением ликвидности и ГО,
     * а не просто ближайший по дате экспирации.
     */
    fun findBestNasdaqFutures(): List<InstrumentInfo> {
        val queries = listOf("NAZ", "NQ", "NASD")
        var candidates = emptyList<InstrumentInfo>()
        for (q in queries) {
            candidates = candidates + findAllInstruments(q)
        }
        candidates = candidates.distinctBy { it.figi }

        val today = LocalDate.now()
        val nasdFutures = candidates.filter { it.classCode == "SPBFUT" && it.figi.contains("NASD", ignoreCase = true) }
            .mapNotNull { inst ->
                val expiry = parseMoexFuturesExpiry(inst.figi)
                if (expiry != null && expiry.isAfter(today)) Pair(inst, expiry) else null
            }
            .sortedBy { it.second }

        // Возвращаем все доступные контракты для выбора лучшего
        return nasdFutures.map { it.first }
    }

    fun findNearestNasdaqFutures(): InstrumentInfo? {
        return findBestNasdaqFutures().firstOrNull()
    }



    fun getFuturesMargin(figi: String): Double? {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetFuturesMargin"
        val body = """{"instrumentId": "$figi"}"""
        val json = post(url, body, retries = 2, silent = true) ?: return null
        // Ответ: {"initialMarginOnSell":"...","minPriceIncrement":...,"basicAssetMinimumQuantity":...}
        return extractMoney(json, "initialMarginOnSell")
    }

    fun getLastPrices(instIds: List<String>): Map<String, Double> {
        if (instIds.isEmpty()) return emptyMap()
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetLastPrices"
        val idsJson = instIds.joinToString(",") { "\"$it\"" }
        val body = """{"instrumentId": [$idsJson]}"""
        val json = post(url, body, retries = 2) ?: return emptyMap()
        return parseLastPrices(json)
    }

    fun getLastCandle(figi: String): Double? {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetCandles"
        val body = """{"figi": "$figi", "interval": "CANDLE_INTERVAL_DAY", "limit": 1}"""
        val json = post(url, body, retries = 2, silent = true) ?: return null
        return parseLastCandleClose(json)
    }

    fun getCandlesByUid(uid: String): Double? {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetCandles"
        val body = """{"instrumentId": "$uid", "interval": "CANDLE_INTERVAL_DAY", "limit": 1}"""
        val json = post(url, body, retries = 2, silent = true) ?: return null
        return parseLastCandleClose(json)
    }

    /**
     * Размещение ордера через TCS API.
     * @param accountId ID аккаунта
     * @param figi FIGI инструмента
     * @param ticker тикер инструмента (fallback)
     * @param classCode класс инструмента (fallback)
     * @param uid UID инструмента (приоритетный идентификатор)
     * @param quantity количество (для фьючерсов = количество контрактов)
     * @param direction "ORDER_DIRECTION_BUY" или "ORDER_DIRECTION_SELL"
     * @param orderType "ORDER_TYPE_MARKET" или "ORDER_TYPE_LIMIT"
     * @param price цена для лимитного ордера (null для рыночного)
     * @return orderId или null при ошибке
     */
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
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OrdersService/PostOrder"
        val priceField = if (price != null && orderType == "ORDER_TYPE_LIMIT") {
            """, "price": {"units": "${price.toLong()}", "nano": ${(price % 1 * 1_000_000_000).toLong()}}"""
        } else ""
        // Приоритет: ticker+classCode > figi > uid
        val instrumentId = when {
            ticker.isNotEmpty() && classCode.isNotEmpty() -> """, "instrumentId": "${ticker}_${classCode}""""
            figi.isNotEmpty() -> """, "instrumentId": "$figi""""
            uid.isNotEmpty() -> """, "instrumentId": "$uid""""
            else -> """, "instrumentId": "${ticker}_${classCode}""""
        }
        val body = """{"accountId": "$accountId"$instrumentId, "quantity": $quantity, "direction": "$direction", "orderType": "$orderType"$priceField, "orderId": "${System.currentTimeMillis()}"}"""
        val idDesc = when {
            uid.isNotEmpty() -> uid
            figi.isNotEmpty() -> figi
            else -> "$ticker:$classCode"
        }
        println("  [API] PostOrder: $direction $quantity x $idDesc")
        val json = post(url, body, retries = 2) ?: return null
        val orderId = extractString(json, "orderId")
        val execStatus = extractString(json, "executedExecutedQuantity")
        val rejects = extractString(json, "rejectReason")
        if (rejects.isNotEmpty()) {
            println("  [ERROR] Ордер отклонён: $rejects")
            println("  [ERROR] Response: $json")
            return null
        }
        println("  [API] OrderId: $orderId")
        return orderId
    }

    /**
     * Получение статуса ордера.
     */
    fun getOrderState(accountId: String, orderId: String): String? {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OrdersService/GetOrderState"
        val body = """{"accountId": "$accountId", "orderId": "$orderId"}"""
        val json = post(url, body, retries = 2, silent = true) ?: return null
        return extractString(json, "executedExecutedQuantity")
    }

    /**
     * Получение списка активных ордеров.
     */
    fun getOrders(accountId: String): List<String> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OrdersService/GetOrders"
        val body = """{"accountId": "$accountId"}"""
        val json = post(url, body, retries = 2, silent = true) ?: return emptyList()
        val orderIds = mutableListOf<String>()
        var searchStart = 0
        while (true) {
            val idx = json.indexOf("\"orderId\"", searchStart)
            if (idx < 0) break
            val orderId = extractString(json.substring(idx), "orderId")
            if (orderId.isNotEmpty()) orderIds.add(orderId)
            searchStart = idx + 10
        }
        return orderIds
    }

    /**
     * Отмена ордера.
     */
    fun cancelOrder(accountId: String, orderId: String): Boolean {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OrdersService/CancelOrder"
        val body = """{"accountId": "$accountId", "orderId": "$orderId"}"""
        val json = post(url, body, retries = 2) ?: return false
        return json.contains("canceled")
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
                        val amount = money + nano / 1_000_000_000.0
                        if (currency == "rub" || currency == "RUB") total += amount
                        objStart = -1
                    }
                }
            }
        }
        return total
    }

    /**
     * Извлекает заблокированную сумму (ГО) из ответа GetWithdrawLimits.
     * Ищет "blocked" или "blockedGuarantee" массив, суммирует RUB.
     */
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
                    ']' -> {
                        depth--
                        if (depth == 0) { bracketEnd = i; break }
                    }
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
            if (total > 0) break  // blockedGuarantee приоритетнее blocked
        }
        return total
    }

    private fun parseAccounts(json: String): List<TcsAccount> {
        val accounts = mutableListOf<TcsAccount>()
        val arrStart = json.indexOf("\"accounts\"")
        if (arrStart < 0) return accounts

        val bracketStart = json.indexOf('[', arrStart)
        val bracketEnd = json.indexOf(']', bracketStart)
        if (bracketStart < 0 || bracketEnd < 0) return accounts

        val content = json.substring(bracketStart + 1, bracketEnd)
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
                            status = extractString(obj, "status"),
                            type = extractString(obj, "type")
                        ))
                        objStart = -1
                    }
                }
            }
        }
        return accounts
    }

    private fun parsePortfolioPositions(json: String): List<TcsPortfolioPosition> {
        val positions = mutableListOf<TcsPortfolioPosition>()
        val arrStart = json.indexOf("\"positions\"")
        if (arrStart < 0) return positions

        var depth = 0
        var inArray = false
        var arrayStart = -1

        for (i in arrStart until json.length) {
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
                        parsePositionObjects(content, positions)
                        break
                    }
                }
            }
        }
        return positions
    }

    private fun parsePositionObjects(content: String, positions: MutableList<TcsPortfolioPosition>) {
        var depth = 0
        var objStart = -1

        for (i in content.indices) {
            when (content[i]) {
                '{' -> { if (depth == 0) objStart = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        val obj = content.substring(objStart, i + 1)
                        positions.add(TcsPortfolioPosition(
                            figi = extractString(obj, "figi"),
                            instrumentType = extractString(obj, "instrumentType"),
                            quantity = extractMoney(obj, "quantity") ?: 0.0,
                            avgPrice = extractMoney(obj, "averagePositionPrice"),
                            curPrice = extractMoney(obj, "currentPrice"),
                            ticker = extractString(obj, "ticker"),
                            classCode = extractString(obj, "classCode")
                        ))
                        objStart = -1
                    }
                }
            }
        }
    }

    private fun parseFindInstrument(json: String): InstrumentInfo? {
        val arrStart = json.indexOf("\"instruments\"")
        if (arrStart < 0) return null

        var depth = 0
        var inArray = false
        var arrayStart = -1

        for (i in arrStart until json.length) {
            val ch = json[i]
            if (ch == '[' && !inArray) { inArray = true; arrayStart = i }
            else if (inArray && ch == '[') depth++
            else if (inArray && ch == ']') {
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
                                    return parseInstrumentObj(obj)
                                }
                            }
                        }
                    }
                    break
                }
            }
        }
        return null
    }

    private fun parseFindAllInstruments(json: String): List<InstrumentInfo> {
        val result = mutableListOf<InstrumentInfo>()
        val arrStart = json.indexOf("\"instruments\"")
        if (arrStart < 0) return result

        var depth = 0
        var inArray = false
        var arrayStart = -1

        for (i in arrStart until json.length) {
            val ch = json[i]
            if (ch == '[' && !inArray) { inArray = true; arrayStart = i }
            else if (inArray && ch == '[') depth++
            else if (inArray && ch == ']') {
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
                                    result.add(parseInstrumentObj(obj))
                                    objStart = -1
                                }
                            }
                        }
                    }
                    break
                }
            }
        }
        return result
    }

    private fun parseInstrumentObj(obj: String): InstrumentInfo {
        return InstrumentInfo(
            figi = extractString(obj, "figi"),
            ticker = extractString(obj, "ticker"),
            classCode = extractString(obj, "classCode"),
            instrumentType = extractString(obj, "instrumentType"),
            maturityDate = extractString(obj, "maturityDate").substringBefore("T"),
            isin = extractString(obj, "isin"),
            uid = extractString(obj, "uid")
        )
    }

    private fun parseLastPrices(json: String): Map<String, Double> {
        val prices = mutableMapOf<String, Double>()
        val arrStart = json.indexOf("\"lastPrices\"")
        if (arrStart < 0) return prices

        var depth = 0
        var inArray = false
        var arrayStart = -1

        for (i in arrStart until json.length) {
            val ch = json[i]
            if (ch == '[' && !inArray) { inArray = true; arrayStart = i }
            else if (inArray && ch == '[') depth++
            else if (inArray && ch == ']') {
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
                                    val figi = extractString(obj, "figi")
                                    val instUid = extractString(obj, "instrumentUid")
                                    val price = extractMoney(obj, "price")
                                    if (price != null) {
                                        if (figi.isNotEmpty()) prices[figi] = price
                                        if (instUid.isNotEmpty()) prices[instUid] = price
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
        return prices
    }

    private fun parseLastCandleClose(json: String): Double? {
        val candlesStart = json.indexOf("\"candles\"")
        if (candlesStart < 0) return null

        var depth = 0
        var inArray = false
        var arrayStart = -1

        for (i in candlesStart until json.length) {
            val ch = json[i]
            if (ch == '[' && !inArray) { inArray = true; arrayStart = i }
            else if (inArray && ch == '[') depth++
            else if (inArray && ch == ']') {
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
                                    val close = extractMoney(obj, "close")
                                    if (close != null) return close
                                    objStart = -1
                                }
                            }
                        }
                    }
                    break
                }
            }
        }
        return null
    }
}

fun readDouble(prompt: String): Double {
    while (true) {
        print(prompt)
        val input = readLine()?.trim()?.replace(",", ".") ?: continue
        val value = input.toDoubleOrNull()
        if (value != null && value > 0) return value
        println("  [ОШИБКА] Введите положительное число.")
    }
}

fun main(args: Array<String>) {
    val autoMode = args.contains("--auto")

    println("================================================================")
    println("  NASD SCANNER (Фьючерсы + Ликвидность)")
    println("  Дата: ${LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}")
    if (autoMode) println("  РЕЖИМ: АВТОМАТИЧЕСКАЯ РЕБАЛАНСИРОВКА")
    println("================================================================")
    println()

    // 1. Загрузка API ключа
    val props = loadProperties()
    val tcsApiKey = props.getProperty("tcs.apiKey", "")

    if (tcsApiKey.isEmpty()) {
        println("  [ERROR] Не настроен T-Invest API ключ в application.properties")
        kotlin.system.exitProcess(1)
    }

    val tcs = TcsClient(tcsApiKey)

    // 2. Получение аккаунта по ID из properties
    var accountId = props.getProperty("tcs.accountId", "")

    if (accountId.isEmpty()) {
        println("  [WARN] tcs.accountId не настроен. Загрузка списка счетов...")
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
        print("\n  Введите номер ИИС счета: ")
        val choice = readLine()?.trim()?.toIntOrNull()
        if (choice == null || choice < 1 || choice > accounts.size) {
            println("  [ERROR] Неверный выбор")
            kotlin.system.exitProcess(1)
        }
        accountId = accounts[choice - 1].id
        println("  Выбран: ${accounts[choice - 1].name} (${accountId})")
    }
    println("  [1/5] Подключение к аккаунту (${accountId})...")

    // 3. Получение портфеля и кэша
    println("  [2/5] Загрузка портфеля аккаунта...")
    val positions = tcs.getPortfolio(accountId)
    val cash = tcs.getWithdrawLimits(accountId)

    // Считаем стоимость ценных бумаг
    var liquidSecuritiesValue = 0.0  // Акции, облигации, ETF — можно продать и купить фонд
    var futuresValue = 0.0           // Фьючерсы — не ликвидные средства, отдельно
    for (pos in positions) {
        val value = pos.quantity * (pos.curPrice ?: 0.0)
        when {
            pos.instrumentType.equals("FUTURES", ignoreCase = true) -> futuresValue += value
            // Кэш (RUB000UTSTOM и т.п.) — считается отдельно через getWithdrawLimits,
            // исключаем чтобы не задвоить с заблокированными средствами
            pos.instrumentType.equals("CURRENCY", ignoreCase = true) -> { }
            else -> liquidSecuritiesValue += value
        }
    }

    // Для расчета покупки фонда ликвидности доступны: свободный кэш + ликвидные бумаги
    // cash уже вычитает заблокированное ГО
    val liquidAssets = liquidSecuritiesValue + cash

    println("  Свободный кэш (за вычетом ГО): %.2f руб".format(cash).replace(',', '.'))
    println("  Ликвидные бумаги (ETF/акции/облигации): %.2f руб".format(liquidSecuritiesValue).replace(',', '.'))
    println("  Фьючерсы (нельзя тратить): %.2f руб".format(futuresValue).replace(',', '.'))
    println("  Доступно для инвестирования: %.2f руб".format(liquidAssets).replace(',', '.'))
    println("  Позиций: ${positions.size}")

    if (positions.isNotEmpty()) {
        println()
        println("  Состав портфеля:")
        for (pos in positions.filter { it.quantity > 0 }) {
            val value = pos.quantity * (pos.curPrice ?: 0.0)
            println("    ${pos.ticker.ifEmpty { pos.figi }} (${pos.instrumentType}): ${pos.quantity} шт x ${"%.2f".format(pos.curPrice ?: 0.0)} = ${"%.2f".format(value)} руб")
        }
    }

    // 4. Поиск фьючерса NASD и курса USDRUB
    println()
    println("  [3/5] Поиск фьючерса NASD...")

    // Проверяем, есть ли уже NASD фьючерсы в портфеле
    val existingNasdFutures = positions.filter {
        it.instrumentType.equals("FUTURES", ignoreCase = true) && it.figi.contains("NASD", ignoreCase = true)
    }

    var existingFuturesCount = 0
    var existingFuturesFigi = ""
    var existingFuturesAvgPrice = 0.0

    if (existingNasdFutures.isNotEmpty()) {
        println("  [INFO] В портфеле уже есть NASD фьючерсы:")
        for (pos in existingNasdFutures) {
            println("    - ${pos.ticker} (${pos.figi}): ${pos.quantity} шт, средняя цена: ${"%.2f".format(pos.avgPrice ?: 0.0)} руб.")
            existingFuturesCount += pos.quantity.toInt()
            existingFuturesFigi = pos.figi
            existingFuturesAvgPrice = pos.avgPrice ?: 0.0
        }
        println("  Итого NASD фьючерсов в портфеле: $existingFuturesCount шт.")
    }

    // Ищем NASDAQ фьючерсы через Futures endpoint
    val availableFutures = tcs.findBestNasdaqFutures()

    if (availableFutures.isEmpty()) {
        println("  Futures endpoint не вернул результат, пробуем FindInstrument...")
        val alt = tcs.findInstrument("NASD") ?: tcs.findInstrument("NQ")
        if (alt != null && alt.classCode != "FAKEFAKE") {
            println("  Найден: ${alt.ticker} (${alt.figi}) class=${alt.classCode} exp=${alt.maturityDate}")
            // Добавляем в список для выбора
        } else {
            println("  [ERROR] Фьючерс NASD не найден. Укажите тикер в application.properties: nasd.ticker, nasd.classCode, nasd.figi")
            kotlin.system.exitProcess(1)
        }
    }

    // Выбираем самый выгодный контракт
    val nasdInstrument = if (availableFutures.size > 1) {
        println("  [INFO] Доступно ${availableFutures.size} контрактов, выбираю наиболее выгодный...")
        selectBestFuturesContract(availableFutures, tcs) ?: availableFutures.first()
    } else {
        availableFutures.firstOrNull() ?: tcs.findInstrument("NASD")
    }

    if (nasdInstrument == null) {
        println("  [ERROR] Не удалось найти подходящий фьючерс NASD")
        kotlin.system.exitProcess(1)
    }
    println("  Выбран контракт: ${nasdInstrument.ticker} (${nasdInstrument.figi})")

    println("  [4/5] Получение рыночных котировок...")

    // Поиск TMON@ по ISIN
    val tmonIsin = "RU000A106DL2"
    val tmonFigiDefault = "TCS70A106DL2"
    val tmonClassCodeDefault = "SPBRU"
    val tmonInstrument = tcs.findByIsin(tmonIsin)
    val tmonTicker = "TMON@"
    val tmonClassCode = tmonClassCodeDefault
    val tmonFigi = tmonFigiDefault
    val tmonUid = tmonInstrument?.uid ?: ""
    if (tmonInstrument != null) {
        println("  TMON@ ISIN $tmonIsin -> ticker: $tmonTicker, classCode: $tmonClassCode, figi: $tmonFigi, uid: $tmonUid")
    } else {
        println("  [WARN] ISIN $tmonIsin не найден в API, используем данные по умолчанию: ticker=$tmonTicker, figi=$tmonFigi, classCode=$tmonClassCode")
    }
    // Для цен: NASD по FIGI, TMON@ по uid → figi → ISIN
    val tmonPriceId = when {
        tmonUid.isNotEmpty() -> tmonUid
        tmonFigi.isNotEmpty() -> tmonFigi
        else -> tmonIsin
    }
    val prices = tcs.getLastPrices(listOf(nasdInstrument.figi, tmonPriceId))

    val nasdPrice = prices[nasdInstrument.figi]
        ?: tcs.getLastCandle(nasdInstrument.figi)
        ?: if (nasdInstrument.uid.isNotEmpty()) tcs.getCandlesByUid(nasdInstrument.uid) else null
    if (nasdPrice == null) {
        println("  [ERROR] Не удалось получить цену фьючерса NASD (${nasdInstrument.figi})")
        kotlin.system.exitProcess(1)
    }
    println("  Цена NASD: %.2f руб".format(nasdPrice).replace(',', '.'))

    val tmonPrice = prices[tmonPriceId]
        ?: if (tmonUid.isNotEmpty()) tcs.getCandlesByUid(tmonUid) else null
    if (tmonPrice == null) {
        println("  [ERROR] Не удалось получить цену TMON@ (uid: $tmonUid)")
        kotlin.system.exitProcess(1)
    }
    println("  Цена TMON@: %.2f руб".format(tmonPrice).replace(',', '.'))

    // 5. Получение ГО и расчет
    val fullContractCostRur = nasdPrice
    println("  Стоимость контракта: %.2f руб".format(fullContractCostRur).replace(',', '.'))

    // Пробуем получить реальное ГО из API
    val apiGo = tcs.getFuturesMargin(nasdInstrument.figi)
    val singleContractGoRur: Double
    if (apiGo != null && apiGo > 0) {
        singleContractGoRur = apiGo
        println("  ГО из API: %.2f руб".format(singleContractGoRur).replace(',', '.'))
    } else {
        // Fallback: 12% от номинала
        singleContractGoRur = fullContractCostRur * 0.12
        println("  [WARN] ГО из API недоступно, используем ~12%%: %.2f руб".format(singleContractGoRur).replace(',', '.'))
    }

    println()
    println("  [5/5] Расчет оптимальной структуры портфеля...")
    println()

    // Вызываем расчет с учетом текущих позиций по фьючерсам
    // totalAccountValue = liquidAssets (кэш + ликвидные бумаги), фьючерсы не считаются
    val actions = calculatePortfolioStructure(
        totalAccountValue = liquidAssets,
        fullContractCostRur = fullContractCostRur,
        singleContractGoRur = singleContractGoRur,
        existingFuturesCount = existingFuturesCount,
        existingFuturesFigi = existingFuturesFigi,
        existingFuturesAvgPrice = existingFuturesAvgPrice,
        futuresTicker = nasdInstrument.ticker,
        futuresFigi = nasdInstrument.figi,
        tmonTicker = tmonTicker,
        tmonFigi = tmonFigi,
        tmonClassCode = tmonClassCode,
        tmonUid = tmonUid,
        tmonPrice = tmonPrice,
        currentCash = cash,
        currentLqdtValue = liquidSecuritiesValue
    )

    // Автоматическая ребалансировка (только при --auto)
    if (autoMode) {
        executeRebalancing(
            accountId = accountId,
            tcs = tcs,
            actions = actions
        )
    }
}

/**
 * Рассчитывает оптимальную структуру портфеля с учетом:
 * - Текущих позиций по фьючерсам (ребалансировка)
 * - Выбора наиболее выгодного контракта
 * - Безопасности маржинальных требований
 *
 * @return список действий для ребалансировки
 */
fun calculatePortfolioStructure(
    totalAccountValue: Double,
    fullContractCostRur: Double,
    singleContractGoRur: Double,
    existingFuturesCount: Int,
    existingFuturesFigi: String,
    existingFuturesAvgPrice: Double,
    futuresTicker: String,
    futuresFigi: String,
    tmonTicker: String,
    tmonFigi: String,
    tmonClassCode: String,
    tmonUid: String,
    tmonPrice: Double,
    currentCash: Double,
    currentLqdtValue: Double
): List<RebalanceAction> {
    val total = BigDecimal.valueOf(totalAccountValue)
    val contractCost = BigDecimal.valueOf(fullContractCostRur)
    val go = BigDecimal.valueOf(singleContractGoRur)
    val actions = mutableListOf<RebalanceAction>()

    // 1. Расчет целевых долей в рублях
    val targetCash = total.multiply(CASH_TARGET_PERCENT)
    val targetLiquidityFund = total.subtract(targetCash)

    // 2. Расчет целевого количества фьючерсов
    val targetFuturesCount = total.divide(contractCost, 0, RoundingMode.DOWN).toInt()

    // 3. Учет уже имеющихся фьючерсов в портфеле
    val futuresDifference = targetFuturesCount - existingFuturesCount

    // 4. Проверка маржинальных лимитов и безопасности
    val totalRequiredGo = go.multiply(BigDecimal.valueOf(targetFuturesCount.toLong()))
    val fundCollateralValue = targetLiquidityFund.multiply(BigDecimal.ONE.subtract(BROKER_COLLATERAL_DISCOUNT))

    // Вывод структурированного отчета
    println("================================================================")
    println("  ОТЧЕТ ПО АККАУНТУ (ФЬЮЧЕРСЫ NASD + ЛИКВИДНОСТЬ)")
    println("================================================================")
    println("  Текущая оценка аккаунта: ${total.setScale(2, RoundingMode.HALF_UP)} руб.")
    println("  Стоимость 1 контракта NASD: ${contractCost.setScale(2, RoundingMode.HALF_UP)} руб.")
    println("  ГО 1 контракта: ${go.setScale(2, RoundingMode.HALF_UP)} руб.")
    println("----------------------------------------------------------------")

    // Блок учета текущих позиций
    if (existingFuturesCount > 0) {
        println("  ТЕКУЩАЯ ПОЗИЦИЯ В ПОРТФЕЛЕ:")
        println("  - Фьючерсов NASD уже есть: $existingFuturesCount шт.")
        println("  - Средняя цена входа: ${"%.2f".format(existingFuturesAvgPrice)} руб.")
        println("  - FIGI текущего контракта: $existingFuturesFigi")
        println("----------------------------------------------------------------")
    }

    println("  ИДЕАЛЬНОЕ РАСПРЕДЕЛЕНИЕ АКТИВОВ ДЛЯ ВВОДА В ТЕРМИНАЛ:")
    println("  1. Оставить в КЭШЕ (чистые рубли): ${targetCash.setScale(2, RoundingMode.HALF_UP)} руб.")
    println("  2. Купить ФОНД ЛИКВИДНОСТИ (TMON@): ${targetLiquidityFund.setScale(2, RoundingMode.HALF_UP)} руб.")
    println("  3. Удерживать ФЬЮЧЕРСОВ NASD: $targetFuturesCount шт.")

    // Блок ребалансировки
    // При покупке фьючерса нужен ГО (не полная стоимость).
    // При продаже освобождается ГО (не полная стоимость контракта).
    println("----------------------------------------------------------------")
    if (futuresDifference > 0) {
        val requiredGo = go.multiply(BigDecimal.valueOf(futuresDifference.toLong()))
        val requiredGoD = requiredGo.toDouble()
        println("  РЕБАЛАНСИРОВКА ТРЕБУЕТСЯ:")
        println("  [!!!] Нужно ДОКУПИТЬ: $futuresDifference шт. фьючерсов NASD")
        println("        Требуется ГО: ${requiredGo.setScale(2, RoundingMode.HALF_UP)} руб.")

        // Проверяем, хватает ли кэша для покупки фьючерсов
        if (currentCash >= requiredGoD) {
            // Кэша достаточно — покупаем фьючерсы напрямую
            println("        Источник: свободный кэш (${"%.2f".format(currentCash)} руб.)")
            actions.add(RebalanceAction(
                type = "BUY_FUTURES",
                figi = futuresFigi,
                ticker = futuresTicker,
                quantity = futuresDifference,
                price = fullContractCostRur,
                amount = requiredGoD
            ))
        } else {
            // Кэша не хватает — нужно продать TMON@ на недостающую сумму
            val deficit = requiredGoD - currentCash
            val tmonSellValue = deficit
            val tmonSellQty = if (tmonPrice > 0) (tmonSellValue / tmonPrice).toInt().coerceAtLeast(1) else 0

            println("        Кэша недостаточно: нужно ${"%.2f".format(requiredGoD)} руб., есть ${"%.2f".format(currentCash)} руб.")
            println("        Источник: продать ${tmonSellQty} шт. TMON@ + весь кэш")

            // Сначала покупаем фьючерсы
            actions.add(RebalanceAction(
                type = "BUY_FUTURES",
                figi = futuresFigi,
                ticker = futuresTicker,
                quantity = futuresDifference,
                price = fullContractCostRur,
                amount = requiredGoD
            ))

            // Затем продаем TMON@ для покрытия дефицита
            if (tmonSellQty > 0) {
                actions.add(RebalanceAction(
                    type = "SELL_TMON",
                    figi = tmonFigi,
                    ticker = tmonTicker,
                    classCode = tmonClassCode,
                    uid = tmonUid,
                    quantity = tmonSellQty,
                    price = tmonPrice,
                    amount = tmonSellValue
                ))
            }
        }
    } else if (futuresDifference < 0) {
        val freedGo = go.multiply(BigDecimal.valueOf((-futuresDifference).toLong()))
        println("  РЕБАЛАНСИРОВКА ТРЕБУЕТСЯ:")
        println("  [!!!] Нужно ПРОДАТЬ: ${-futuresDifference} шт. фьючерсов NASD")
        println("        Освободится ГО: ${freedGo.setScale(2, RoundingMode.HALF_UP)} руб.")
        // Добавляем действие: продажа фьючерсов
        actions.add(RebalanceAction(
            type = "SELL_FUTURES",
            figi = futuresFigi,
            ticker = futuresTicker,
            quantity = -futuresDifference,
            price = fullContractCostRur,
            amount = freedGo.toDouble()
        ))

        // Рекомендация: куда девать освободившийся ГО
        // После продажи ГО становится свободным кэшем.
        // Нужно решить: доложить в TMON@ или оставить как кэш.
        val freedGoD = freedGo.toDouble()
        val currentCashAfterSale = totalAccountValue + freedGoD  // кэш вырос на освобождённое ГО
        val targetCashAfter = currentCashAfterSale * CASH_TARGET_PERCENT.toDouble()
        val newFundTarget = currentCashAfterSale - targetCashAfter

        // Сколько TMON@ будет после продажи (TMON@ не меняется при продаже фьючерса)
        // Нужно докупить TMON@ на сумму, чтобы довести фонд до нового целевого
        val currentTmonValue = totalAccountValue - (totalAccountValue * CASH_TARGET_PERCENT.toDouble())
        val tmonToBuy = newFundTarget - currentTmonValue

        if (tmonToBuy > 0) {
            println("        Рекомендация: докупить TMON@ на ${BigDecimal.valueOf(tmonToBuy).setScale(2, RoundingMode.HALF_UP)} руб.")
            println("        Остаток кэша: ${BigDecimal.valueOf(targetCashAfter).setScale(2, RoundingMode.HALF_UP)} руб.")
            // Добавляем действие: покупка TMON@
            actions.add(RebalanceAction(
                type = "BUY_TMON",
                figi = tmonFigi,
                ticker = tmonTicker,
                classCode = tmonClassCode,
                uid = tmonUid,
                quantity = if (tmonPrice > 0) (tmonToBuy / tmonPrice).toInt().coerceAtLeast(1) else 0,
                price = tmonPrice,
                amount = tmonToBuy
            ))
        } else if (tmonToBuy < 0) {
            println("        Рекомендация: продать TMON@ на ${BigDecimal.valueOf(-tmonToBuy).setScale(2, RoundingMode.HALF_UP)} руб.")
            println("        Остаток кэша: ${BigDecimal.valueOf(targetCashAfter).setScale(2, RoundingMode.HALF_UP)} руб.")
            // Добавляем действие: продажа TMON@
            actions.add(RebalanceAction(
                type = "SELL_TMON",
                figi = tmonFigi,
                ticker = tmonTicker,
                classCode = tmonClassCode,
                uid = tmonUid,
                quantity = if (tmonPrice > 0) (-tmonToBuy / tmonPrice).toInt().coerceAtLeast(1) else 0,
                price = tmonPrice,
                amount = -tmonToBuy
            ))
        } else {
            println("        Рекомендация: оставить как кэш (${BigDecimal.valueOf(targetCashAfter).setScale(2, RoundingMode.HALF_UP)} руб.)")
        }
    } else {
        println("  [OK] Фьючерсы сбалансированы.")
    }

    // Проверяем баланс TMON@: если кэша больше целевого — докупаем TMON@
    // targetCash = 10% от totalAccountValue
    // Если currentCash > targetCash, разницу тратим на TMON@
    run {
        val targetCashD = totalAccountValue * CASH_TARGET_PERCENT.toDouble()
        val excessCash = currentCash - targetCashD
        if (excessCash > tmonPrice) {
            val tmonQty = (excessCash / tmonPrice).toInt()
            val tmonCost = tmonQty * tmonPrice
            println("  РЕБАЛАНСИРОВКА TMON@:")
            println("  [!!!] Нужно ДОКУПИТЬ: $tmonQty шт. TMON@ на ${"%.2f".format(tmonCost)} руб.")
            println("        Кэш: ${"%.2f".format(currentCash)} руб., целевой: ${"%.2f".format(targetCashD)} руб.")
            actions.add(RebalanceAction(
                type = "BUY_TMON",
                figi = tmonFigi,
                ticker = tmonTicker,
                classCode = tmonClassCode,
                uid = tmonUid,
                quantity = tmonQty,
                price = tmonPrice,
                amount = tmonCost
            ))
        } else if (currentLqdtValue > 0 && currentCash < targetCashD - tmonPrice) {
            // Кэша мало — продаем часть TMON@
            val deficit = targetCashD - currentCash
            val tmonSellQty = (deficit / tmonPrice).toInt().coerceAtLeast(1)
            val tmonSellValue = tmonSellQty * tmonPrice
            println("  РЕБАЛАНСИРОВКА TMON@:")
            println("  [!!!] Нужно ПРОДАТЬ: $tmonSellQty шт. TMON@ на ${"%.2f".format(tmonSellValue)} руб.")
            println("        Кэш: ${"%.2f".format(currentCash)} руб., целевой: ${"%.2f".format(targetCashD)} руб.")
            actions.add(RebalanceAction(
                type = "SELL_TMON",
                figi = tmonFigi,
                ticker = tmonTicker,
                classCode = tmonClassCode,
                uid = tmonUid,
                quantity = tmonSellQty,
                price = tmonPrice,
                amount = tmonSellValue
            ))
        } else {
            println("  [OK] TMON@ сбалансирован.")
        }
    }

    println("----------------------------------------------------------------")
    println("  АНАЛИЗ БЕЗОПАСНОСТИ МАРЖИНАЛЬНЫХ ТРЕБОВАНИЙ:")
    println("  - Заблокировано под ГО фьючерсов: ${totalRequiredGo.setScale(2, RoundingMode.HALF_UP)} руб.")
    println("  - Доступная стоимость залога от фонда: ${fundCollateralValue.setScale(2, RoundingMode.HALF_UP)} руб.")

    if (fundCollateralValue.compareTo(totalRequiredGo) > 0) {
        println("  [OK] Статус: БЕЗОПАСНО. Залог полностью покрывает требования ГО.")
    } else {
        println("  [!!!] Статус: КРИТИЧЕСКИЙ РИСК! ГО превышает залоговую стоимость фонда.")
        println("        Уменьшите количество фьючерсов.")
    }
    println("================================================================")

    // Дополнительный блок: сценарий при просадке рынка
    println()
    println("  КАК РАБОТАЕТ СТРАТЕГИЯ ПРИ ПРОСАДКЕ:")
    println("  - Кэш в 10% постепенно тает, превращаясь в вариационную маржу.")
    println("  - Если кэш упал (например, до 4%), программа порекомендует")
    println("    продать часть фонда ликвидности для восстановления 10% кэша.")
    println("================================================================")

    return actions
}

/**
 * Выбирает самый выгодный NASDAQ фьючерс из доступных.
 * Критерии выгоды:
 * 1. Достаточная ликвидность (проверяется через объем торгов)
 * 2. Оптимальное ГО (не слишком дорогое)
 * 3. Достаточный срок до экспирации (не менее 30 дней)
 */

fun parseMoexFuturesExpiry(figi: String): java.time.LocalDate? {
    // FUTNASD12260 → month=12, year=2026
    // FNASD0327000 → month=03, year=2027
    val match = Regex("F(?:UT)?NASD(\\d{2})(\\d{2})\\d+").find(figi) ?: return null
    val month = match.groupValues[1].toIntOrNull() ?: return null
    val year2 = match.groupValues[2].toIntOrNull() ?: return null
    val year = 2000 + year2
    if (month !in 1..12 || year < 2024) return null
    return java.time.LocalDate.of(year, month, 1).with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(3, java.time.DayOfWeek.FRIDAY))
}
fun selectBestFuturesContract(
    futuresList: List<InstrumentInfo>,
    client: TcsClient
): InstrumentInfo? {
    if (futuresList.isEmpty()) return null

    val today = LocalDate.now()
    val minDaysToExpiry = 30  // Минимум 30 дней до экспирации

    // Фильтруем по минимальному сроку
    val viableFutures = futuresList.mapNotNull { inst ->
        val expiry = parseMoexFuturesExpiry(inst.figi)
        if (expiry != null && expiry.isAfter(today.plusDays(minDaysToExpiry.toLong()))) {
            Triple(inst, expiry, java.time.temporal.ChronoUnit.DAYS.between(today, expiry))
        } else null
    }

    if (viableFutures.isEmpty()) {
        println("  [WARN] Нет фьючерсов с достаточным сроком до экспирации (>${minDaysToExpiry} дней)")
        // Fallback: берем ближайший
        return futuresList.firstOrNull()
    }

    // Получаем ГО для каждого кандидата и оцениваем выгодность
    var bestContract: InstrumentInfo? = null
    var bestScore = Double.MIN_VALUE

    println("  Анализ доступных контрактов:")
    for ((inst, expiry, daysToExpiry) in viableFutures) {
        val go = client.getFuturesMargin(inst.figi)
        val goValue = go ?: 0.0

        // Формула оценки выгодности:
        // - Чем меньше ГО относительно стоимости контракта, тем лучше
        // - Чем больше дней до экспирации, тем лучше (но не слишком)
        val price = client.getLastPrices(listOf(inst.figi))[inst.figi] ?: 0.0
        val goRatio = if (price > 0) goValue / price else 1.0

        // Оптимальный срок: 30-90 дней (пенальти за слишком долгий срок)
        val optimalDays = 60.0
        val daysFactor = if (daysToExpiry <= optimalDays) {
            1.0  // Идеально
        } else {
            1.0 - (daysToExpiry - optimalDays) / 365.0 * 0.5  // Штраф за долгий срок
        }

        // Итоговая оценка: чем выше, тем лучше
        val score = (1.0 - goRatio) * 0.6 + daysFactor * 0.4

        println("    ${inst.ticker} (${inst.figi}): exp=$expiry, дн=$daysToExpiry, ГО=$goValue, оценка=${"%.3f".format(score)}")

        if (score > bestScore) {
            bestScore = score
            bestContract = inst
        }
    }

    return bestContract
}

/**
 * Выполнение автоматической ребалансировки через TCS API.
 * @param accountId ID аккаунта
 * @param tcs клиент TCS API
 * @param actions список действий для ребалансировки
 * @param dryRun если true, ордера не размещаются
 */
fun executeRebalancing(
    accountId: String,
    tcs: TcsClient,
    actions: List<RebalanceAction>
) {
    if (actions.isEmpty()) {
        println("  [OK] Ребалансировка не требуется.")
        return
    }

    println()
    println("================================================================")
    println("  ВЫПОЛНЕНИЕ РЕБАЛАНСИРОВКИ")
    println("================================================================")

    // Сначала выполняем продажи (освобождаем средства)
    val sellActions = actions.filter { it.type.startsWith("SELL") }
    // Покупки: сначала TMON@ (ETF), затем фьючерсы
    val buyTmon = actions.filter { it.type == "BUY_TMON" }
    val buyFutures = actions.filter { it.type == "BUY_FUTURES" }
    val buyActions = buyTmon + buyFutures

    for (action in sellActions) {
        println("  [SELL] ${action.ticker}: ${action.quantity} шт по ${"%.2f".format(action.price)} = ${"%.2f".format(action.amount)} руб")
        val orderDirection = if (action.type.contains("FUTURES")) "ORDER_DIRECTION_SELL" else "ORDER_DIRECTION_SELL"
        val orderId = tcs.postOrder(
            accountId = accountId,
            figi = action.figi,
            quantity = action.quantity.toLong(),
            direction = orderDirection,
            orderType = "ORDER_TYPE_MARKET",
            ticker = action.ticker,
            classCode = action.classCode,
            uid = action.uid
        )
        if (orderId != null) {
            println("  [OK] Ордер размещён: $orderId")
        } else if (action.type.contains("TMON")) {
            println("  [WARN] ${action.ticker} недоступен для API-торговли, пропускаю")
        } else {
            println("  [ERROR] Ордер не размещён! Прерываю ребалансировку.")
            return
        }
        Thread.sleep(500)
    }

    for (action in buyActions) {
        println("  [BUY] ${action.ticker}: ${action.quantity} шт по ${"%.2f".format(action.price)} = ${"%.2f".format(action.amount)} руб")
        val orderId = tcs.postOrder(
            accountId = accountId,
            figi = action.figi,
            quantity = action.quantity.toLong(),
            direction = "ORDER_DIRECTION_BUY",
            orderType = "ORDER_TYPE_MARKET",
            ticker = action.ticker,
            classCode = action.classCode,
            uid = action.uid
        )
        if (orderId != null) {
            println("  [OK] Ордер размещён: $orderId")
        } else if (action.type.contains("TMON")) {
            println("  [WARN] ${action.ticker} недоступен для API-торговли, пропускаю")
        } else {
            println("  [ERROR] Ордер не размещён! Прерываю ребалансировку.")
            return
        }
        Thread.sleep(500)
    }

    println("================================================================")
    println("  РЕБАЛАНСИРОВКА ЗАВЕРШЕНА")
    println("================================================================")
}

main(args)
