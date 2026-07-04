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
    val maturityDate: String = ""
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
                    Thread.sleep(wait * 1000L)
                    continue
                }

                if (resp.statusCode() != 200) {
                    if (attempt == retries) {
                        println("  [WARN] HTTP ${resp.statusCode()} (body: ${body.take(60)}...)")
                        return null
                    }
                    Thread.sleep(2000L)
                    continue
                }

                Thread.sleep(150L)
                return resp.body()
            } catch (e: Exception) {
                if (attempt == retries) {
                    println("  [WARN] TCS API error: ${e.message}")
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
        return extractCashTotal(json)
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

    fun findNearestNasdaqFutures(): InstrumentInfo? {
        val queries = listOf("NAZ", "NQ", "NASD")
        var candidates = emptyList<InstrumentInfo>()
        for (q in queries) {
            candidates = candidates + findAllInstruments(q)
        }
        candidates = candidates.distinctBy { it.figi }

        // Фильтруем только MOEX фьючерсы и парсим дату экспирации из figi
        // Формат figi: FUTNASDMMYY0 или FNASDMMYY000
        val today = LocalDate.now()
        val nasdFutures = candidates.filter { it.classCode == "SPBFUT" && it.figi.contains("NASD", ignoreCase = true) }
            .mapNotNull { inst ->
                val expiry = parseMoexFuturesExpiry(inst.figi)
                if (expiry != null && expiry.isAfter(today)) Pair(inst, expiry) else null
            }
            .sortedBy { it.second }

        if (nasdFutures.isNotEmpty()) {
            val best = nasdFutures.first()
            println("  Ближайший: ${best.first.ticker} (${best.first.figi}) exp=${best.second}")
            for ((inst, exp) in nasdFutures) {
                println("    ${inst.ticker} (${inst.figi}) exp=$exp")
            }
            return best.first
        }

        // Fallback: без фильтра по дате
        val anyMoex = candidates.filter { it.classCode == "SPBFUT" && it.figi.contains("NASD", ignoreCase = true) }
        return anyMoex.firstOrNull()
    }

    private fun parseMoexFuturesExpiry(figi: String): java.time.LocalDate? {
        // FUTNASD12260 → month=12, year=2026
        // FNASD0327000 → month=03, year=2027
        val match = Regex("F(?:UT)?NASD(\\d{2})(\\d{2})\\d+").find(figi) ?: return null
        val month = match.groupValues[1].toIntOrNull() ?: return null
        val year2 = match.groupValues[2].toIntOrNull() ?: return null
        val year = 2000 + year2
        if (month !in 1..12 || year < 2024) return null
        // Примерная дата экспирации: 3-я пятница месяца
        return java.time.LocalDate.of(year, month, 1).with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(3, java.time.DayOfWeek.FRIDAY))
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
            maturityDate = extractString(obj, "maturityDate").substringBefore("T")
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
                                    val instId = extractString(obj, "figi").ifEmpty { extractString(obj, "instrumentUid") }
                                    val price = extractMoney(obj, "price")
                                    if (instId.isNotEmpty() && price != null) {
                                        prices[instId] = price
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
    println("================================================================")
    println("  NASD SCANNER (Фьючерсы + Ликвидность)")
    println("  Дата: ${LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}")
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
    var securitiesValue = 0.0
    for (pos in positions) {
        securitiesValue += pos.quantity * (pos.curPrice ?: 0.0)
    }

    val totalAccountValue = securitiesValue + cash

    println("  Ценных бумаг: %.2f руб".format(securitiesValue).replace(',', '.'))
    println("  Кэш: %.2f руб".format(cash).replace(',', '.'))
    println("  ИТОГО: %.2f руб".format(totalAccountValue).replace(',', '.'))
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

    // Ищем NASDAQ фьючерсы через Futures endpoint
    val nasdInstrument = tcs.findNearestNasdaqFutures()

    if (nasdInstrument == null) {
        // Fallback: пробуем FindInstrument
        println("  Futures endpoint не вернул результат, пробуем FindInstrument...")
        val alt = tcs.findInstrument("NASD") ?: tcs.findInstrument("NQ")
        if (alt != null && alt.classCode != "FAKEFAKE") {
            println("  Найден: ${alt.ticker} (${alt.figi}) class=${alt.classCode} exp=${alt.maturityDate}")
        } else {
            println("  [ERROR] Фьючерс NASD не найден. Укажите тикер в application.properties: nasd.ticker, nasd.classCode, nasd.figi")
            kotlin.system.exitProcess(1)
        }
    }
    println("  Контракт: ${nasdInstrument!!.ticker} (${nasdInstrument.figi})")

    println("  [4/5] Получение рыночных котировок...")

    val prices = tcs.getLastPrices(listOf(nasdInstrument.figi))

    val nasdPrice = prices[nasdInstrument.figi] ?: tcs.getLastCandle(nasdInstrument.figi)
    if (nasdPrice == null) {
        println("  [ERROR] Не удалось получить цену фьючерса NASD (${nasdInstrument.figi})")
        kotlin.system.exitProcess(1)
    }
    println("  Цена NASD: %.2f руб".format(nasdPrice).replace(',', '.'))

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
    calculatePortfolioStructure(totalAccountValue, fullContractCostRur, singleContractGoRur)
}

fun calculatePortfolioStructure(
    totalAccountValue: Double,
    fullContractCostRur: Double,
    singleContractGoRur: Double
) {
    val total = BigDecimal.valueOf(totalAccountValue)
    val contractCost = BigDecimal.valueOf(fullContractCostRur)
    val go = BigDecimal.valueOf(singleContractGoRur)

    // 1. Расчет целевых долей в рублях
    val targetCash = total.multiply(CASH_TARGET_PERCENT)
    val targetLiquidityFund = total.subtract(targetCash)

    // 2. Расчет количества фьючерсов строго по правилу 1х (Без плеча)
    val safeFuturesCount = total.divide(contractCost, 0, RoundingMode.DOWN).toInt()

    // 3. Проверка маржинальных лимитов и безопасности
    val totalRequiredGo = go.multiply(BigDecimal.valueOf(safeFuturesCount.toLong()))
    val fundCollateralValue = targetLiquidityFund.multiply(BigDecimal.ONE.subtract(BROKER_COLLATERAL_DISCOUNT))

    // Вывод структурированного отчета
    println("================================================================")
    println("  ОТЧЕТ ПО АККАУНТУ (ФЬЮЧЕРСЫ NASD + ЛИКВИДНОСТЬ)")
    println("================================================================")
    println("  Текущая оценка аккаунта: ${total.setScale(2, RoundingMode.HALF_UP)} руб.")
    println("  Стоимость 1 контракта NASD: ${contractCost.setScale(2, RoundingMode.HALF_UP)} руб.")
    println("  ГО 1 контракта: ${go.setScale(2, RoundingMode.HALF_UP)} руб.")
    println("----------------------------------------------------------------")
    println("  ИДЕАЛЬНОЕ РАСПРЕДЕЛЕНИЕ АКТИВОВ ДЛЯ ВВОДА В ТЕРМИНАЛ:")
    println("  1. Оставить в КЭШЕ (чистые рубли): ${targetCash.setScale(2, RoundingMode.HALF_UP)} руб.")
    println("  2. Купить ФОНД ЛИКВИДНОСТИ (TMON): ${targetLiquidityFund.setScale(2, RoundingMode.HALF_UP)} руб.")
    println("  3. Удерживать ФЬЮЧЕРСОВ NASD: $safeFuturesCount шт.")
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
    println("  - Раз в месяц перезапустите калькулятор с актуальными данными.")
    println("  - Если кэш упал (например, до 4%), программа порекомендует")
    println("    продать часть фонда ликвидности для восстановления 10% кэша.")
    println("================================================================")
}

main(args)
