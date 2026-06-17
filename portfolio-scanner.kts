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
import java.time.YearMonth
import java.time.format.DateTimeFormatter
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
        println("  [WARN] Не удалось загрузить application.properties: ${e.message}")
    }
    return props
}

val props = loadProperties()
val tcsApiKey = props.getProperty("tcs.apiKey", "")
val finamSecret = props.getProperty("finam.apiKey", "")
val finamAccountId = props.getProperty("finam.accountId", "")

class FinamClient {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .sslContext(createTrustAllSSLContext())
        .build()
    private var jwtToken: String? = null

    fun authenticate(): String {
        val url = "https://api.finam.ru/v1/sessions"
        val body = """{"secret": "$finamSecret"}"""

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

        val token = extractString(resp.body(), "token")
        jwtToken = token
        return token
    }

    fun getTokenDetails(): String {
        val token = jwtToken ?: throw Exception("Not authenticated")
        val url = "https://api.finam.ru/v1/sessions/details"
        val body = """{"token": "$token"}"""

        val resp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        if (resp.statusCode() != 200) {
            throw Exception("Finam token details failed: HTTP ${resp.statusCode()} ${resp.body()}")
        }

        return resp.body()
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
            throw Exception("Finam get account failed: HTTP ${resp.statusCode()} ${resp.body()}")
        }

        return resp.body()
    }

    fun getFutureDividends(symbol: String): List<FinamDividendEvent> {
        val token = jwtToken ?: throw Exception("Not authenticated")
        val url = "https://api.finam.ru/v1/future-dividends?symbol=$symbol&sort_direction=asc&limit=50"

        return try {
            val resp = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )

            if (resp.statusCode() != 200) return emptyList()

            parseFinamDividends(resp.body())
        } catch (e: Exception) {
            println("  [WARN] Finam future-dividends error: ${e.message}")
            emptyList()
        }
    }
}

class TcsClient {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .sslContext(createTrustAllSSLContext())
        .build()

    data class BondInstrument(
        val ticker: String,
        val name: String,
        val figi: String,
        val classCode: String,
        val nominal: Double,
        val sector: String,
        val couponQuantityPerYear: Int,
        val maturityDate: String
    )

    data class CouponEvent(
        val couponDate: String,
        val payOneBond: Double,
        val couponType: String,
        val currency: String
    )

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
        val expectedYield: Double?,
        val currentNkd: Double?,
        val ticker: String,
        val classCode: String
    )

    data class DividendEvent(
        val dividendDate: String,
        val dividendAmount: Double,
        val currency: String
    )

    data class InstrumentInfo(
        val ticker: String,
        val figi: String,
        val name: String,
        val instrumentType: String,
        val classCode: String
    )

    fun post(url: String, body: String, retries: Int = 3, silent: Boolean = false): String? {
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
                    val wait = if (attempt < retries) 3L else 0L
                    println("  [WARN] Rate limit (429), попытка $attempt/$retries, жду ${wait}0 сек...")
                    Thread.sleep(wait * 1000L)
                    continue
                }

                if (resp.statusCode() != 200) {
                    if (attempt == retries) {
                        val method = url.substringAfterLast("/")
                        if (!silent) println("  [WARN] $method: HTTP ${resp.statusCode()} (body: ${body.take(60)}...)")
                        return null
                    }
                    Thread.sleep(2000L)
                    continue
                }

                // Small delay to avoid rate limiting on sequential calls
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

    fun getWithdrawLimits(accountId: String): Double {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetWithdrawLimits"
        val body = """{"accountId": "$accountId"}"""
        val json = post(url, body, silent = true) ?: return 0.0
        return extractCashTotal(json)
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
                        val money = extractMoney(obj, "units")
                        val nano = Regex("\"nano\"\\s*:\\s*(\\d+)").find(obj)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        val amount = (money ?: 0.0) + nano / 1_000_000_000.0
                        if (currency == "rub" || currency == "RUB") total += amount
                        objStart = -1
                    }
                }
            }
        }
        return total
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

    fun getAllBonds(): List<BondInstrument> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/Bonds"
        val body = """{"instrumentStatus": "INSTRUMENT_STATUS_BASE"}"""

        for (attempt in 1..3) {
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
                    println("  [WARN] Rate limit (429) getAllBonds, попытка $attempt/3, жду 5 сек...")
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

    fun getBondCoupons(figi: String): List<CouponEvent> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetBondCoupons"
        val body = """{"figi": "$figi"}"""

        val json = post(url, body) ?: return emptyList()
        return parseCouponsJson(json)
    }

    fun getDividends(instrumentId: String): List<DividendEvent> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetDividends"
        val body = """{"instrumentId": "$instrumentId"}"""

        val json = post(url, body, retries = 2) ?: return emptyList()
        return parseDividendsJson(json)
    }

    fun findInstrument(query: String, kind: String = "", classCode: String = ""): InstrumentInfo? {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/FindInstrument"
        val kindFilter = if (kind.isNotEmpty()) """, "instrumentKind": "$kind"""" else ""
        val body = """{"query": "$query"$kindFilter}"""
        val json = post(url, body, retries = 2) ?: return null
        return parseFindInstrument(json, query, kind, classCode)
    }

    fun findBondByTicker(ticker: String, bonds: List<BondInstrument>): BondInstrument? {
        val candidates = bonds.filter { it.ticker == ticker || it.figi == ticker }
        if (candidates.isNotEmpty()) return candidates.first()

        return bonds.find { bond ->
            ticker.startsWith(bond.ticker) || bond.ticker.startsWith(ticker)
        }
    }

    // --- JSON parsers ---

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
                        parsePortfolioObjects(content, positions)
                        break
                    }
                }
            }
        }
        return positions
    }

    private fun parsePortfolioObjects(content: String, positions: MutableList<TcsPortfolioPosition>) {
        var depth = 0
        var objStart = -1

        for (i in content.indices) {
            when (content[i]) {
                '{' -> { if (depth == 0) objStart = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        val obj = content.substring(objStart, i + 1)
                        positions.add(parseSinglePortfolioPos(obj))
                        objStart = -1
                    }
                }
            }
        }
    }

    private fun parseSinglePortfolioPos(json: String): TcsPortfolioPosition {
        val figi = extractString(json, "figi")
        val instrumentType = extractString(json, "instrumentType")
        val quantity = extractMoney(json, "quantity") ?: 0.0
        val avgPrice = extractMoney(json, "averagePositionPrice")
        val curPrice = extractMoney(json, "currentPrice")
        val expectedYield = extractMoney(json, "expectedYield")
        val currentNkd = extractMoney(json, "currentNkd")
        val ticker = extractString(json, "ticker")
        val classCode = extractString(json, "classCode")
        return TcsPortfolioPosition(figi, instrumentType, quantity, avgPrice, curPrice, expectedYield, currentNkd, ticker, classCode)
    }

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
                        val content = json.substring(arrayStart + 1, i)
                        parseBondObjects(content, instruments)
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
            nominal = extractBondNominal(json),
            sector = extractString(json, "sector").ifEmpty { "" },
            couponQuantityPerYear = extractInt(json, "couponQuantityPerYear"),
            maturityDate = extractString(json, "maturityDate").substringBefore("T")
        )
    }

    private fun extractBondNominal(json: String): Double {
        val start = json.indexOf("\"nominal\"")
        if (start < 0) return 1000.0
        val units = Regex("\"units\"\\s*:\\s*\"?(\\d+)\"?").find(json.substring(start))
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val nano = Regex("\"nano\"\\s*:\\s*(\\d+)").find(json.substring(start))
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return units + nano / 1_000_000_000.0
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
                                        events.add(CouponEvent(
                                            couponDate = extractString(obj, "couponDate"),
                                            payOneBond = extractMoney(obj, "payOneBond") ?: 0.0,
                                            couponType = extractString(obj, "couponType"),
                                            currency = extractString(obj, "currency").ifEmpty { "rub" }
                                        ))
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

    private fun parseDividendsJson(json: String): List<DividendEvent> {
        val events = mutableListOf<DividendEvent>()
        val eventsStart = json.indexOf("\"dividends\"")
        if (eventsStart < 0) {
            // Check if it's an error response
            val errorMsg = extractString(json, "message")
            if (errorMsg.isNotEmpty()) {
                println("  [WARN] GetDividends: $errorMsg")
            }
            return events
        }

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
                                            val recordDate = extractString(obj, "recordDate").substringBefore("T")
                                            events.add(DividendEvent(
                                                dividendDate = paymentDate.ifEmpty { recordDate },
                                                dividendAmount = amount,
                                                currency = extractString(obj, "currency").ifEmpty { "rub" }
                                            ))
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

    private fun parseFindInstrument(json: String, query: String = "", kind: String = "", classCode: String = ""): InstrumentInfo? {
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
                                    val objType = extractString(obj, "instrumentType")
                                    val objTicker = extractString(obj, "ticker")
                                    val objClassCode = extractString(obj, "classCode")
                                    val typeMatch = kind.isEmpty() || objType == kind.lowercase().removePrefix("instrument_type_")
                                    val tickerMatch = query.isEmpty() || objTicker == query
                                    val ccMatch = classCode.isEmpty() || objClassCode == classCode
                                    if (typeMatch && tickerMatch && ccMatch) {
                                        return InstrumentInfo(
                                            ticker = objTicker,
                                            figi = extractString(obj, "figi"),
                                            name = extractString(obj, "name"),
                                            instrumentType = objType,
                                            classCode = objClassCode
                                        )
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
        return null
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

fun extractString(json: String, key: String): String {
    val match = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)
    return match?.groupValues?.get(1) ?: ""
}

fun extractInt(json: String, key: String): Int {
    val match = Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)
    return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

fun extractDouble(json: String, key: String): Double {
    val match = Regex("\"$key\"\\s*:\\s*\"?([\\d.]+)\"?").find(json)
    return match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
}

fun extractValue(json: String, key: String): String {
    val match = Regex("\"$key\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*\"([^\"]+)\"").find(json)
    return match?.groupValues?.get(1) ?: ""
}

data class FinamPosition(
    val symbol: String,
    val quantity: Double,
    val averagePrice: Double,
    val currentPrice: Double,
    val unrealizedPnl: Double
)

data class UnifiedHolding(
    val figi: String,
    val ticker: String,
    val name: String,
    val instrumentType: String,
    val quantity: Double,
    val avgPrice: Double,
    val curPrice: Double,
    val nominal: Double,
    val sector: String,
    val couponPerYear: Int,
    val maturityDate: String,
    val sources: Set<String>,
    val payments: List<PaymentEvent>
)

data class PaymentEvent(
    val date: String,
    val amountPerUnit: Double,
    val type: String,
    val currency: String
)

data class AccountInfo(val id: String, val name: String)

data class FinamDividendEvent(
    val date: String,
    val amountPerShare: Double,
    val currency: String
)

fun parseFinamPositions(json: String): List<FinamPosition> {
    val positions = mutableListOf<FinamPosition>()
    val posStart = json.indexOf("\"positions\"")
    if (posStart < 0) return positions

    var depth = 0
    var inArray = false
    var arrayStart = -1

    for (i in posStart until json.length) {
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

fun parsePositionObjects(content: String, positions: MutableList<FinamPosition>) {
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
                    positions.add(FinamPosition(
                        symbol = extractString(obj, "symbol"),
                        quantity = extractValue(obj, "quantity").toDoubleOrNull() ?: 0.0,
                        averagePrice = extractValue(obj, "average_price").toDoubleOrNull() ?: 0.0,
                        currentPrice = extractValue(obj, "current_price").toDoubleOrNull() ?: 0.0,
                        unrealizedPnl = extractValue(obj, "unrealized_pnl").toDoubleOrNull() ?: 0.0
                    ))
                    objStart = -1
                }
            }
        }
    }
}

fun extractEquity(json: String): Double {
    return extractValue(json, "equity").toDoubleOrNull() ?: 0.0
}

fun extractCash(json: String): String {
    val cashStart = json.indexOf("\"cash\"")
    if (cashStart < 0) return "0"

    val sub = json.substring(cashStart)
    val units = Regex("\"units\"\\s*:\\s*\"?([\\d.]+)\"?").find(sub)
        ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    val nanos = Regex("\"nanos\"\\s*:\\s*(\\d+)").find(sub)
        ?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    return "%.2f".format(units + nanos / 1_000_000_000.0)
}

fun extractFinamAccounts(json: String): List<AccountInfo> {
    val accounts = mutableListOf<AccountInfo>()
    val keyStart = json.indexOf("\"account_ids\"")
    if (keyStart < 0) return accounts

    val arrayStart = json.indexOf('[', keyStart)
    val arrayEnd = json.indexOf(']', arrayStart)
    if (arrayStart < 0 || arrayEnd < 0) return accounts

    val content = json.substring(arrayStart + 1, arrayEnd)
    val ids = Regex("\"([^\"]+)\"").findAll(content).map { it.groupValues[1] }.toList()

    for (id in ids) {
        accounts.add(AccountInfo(id, ""))
    }
    return accounts
}

fun parseFinamDividends(json: String): List<FinamDividendEvent> {
    val events = mutableListOf<FinamDividendEvent>()
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
                                    val amount = extractMoneyValue(obj, "dividend_amount") ?: extractMoneyValue(obj, "amount") ?: 0.0
                                    val dateStr = extractString(obj, "dividend_date").ifEmpty { extractString(obj, "date") }
                                    val currency = extractString(obj, "currency").ifEmpty { "rub" }
                                    if (dateStr.isNotEmpty() && amount > 0) {
                                        events.add(FinamDividendEvent(
                                            date = dateStr.substringBefore("T"),
                                            amountPerShare = amount,
                                            currency = currency.lowercase()
                                        ))
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

fun extractMoneyValue(json: String, key: String): Double? {
    val start = json.indexOf("\"$key\"")
    if (start < 0) return null
    val sub = json.substring(start)
    val units = Regex("\"units\"\\s*:\\s*\"?(\\d+)\"?").find(sub)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    val nano = Regex("\"nano\"\\s*:\\s*(\\d+)").find(sub)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    return units + nano / 1_000_000_000.0
}

fun extractTickerFromSymbol(symbol: String): String {
    return symbol.substringBefore("@")
}

fun isBondSymbol(symbol: String): Boolean {
    val ticker = extractTickerFromSymbol(symbol)
    return ticker.length >= 10 && ticker.all { it.isDigit() || it.isLetter() }
}

fun sectorShort(sector: String): String = when {
    sector.isEmpty() || sector == "corporate" -> "Корп"
    sector == "government" -> "Гос"
    sector == "bank" -> "Банк"
    sector == "financial" -> "Фин"
    sector == "materials" -> "Мат"
    sector == "energy" -> "Энер"
    sector == "consumer" -> "Потр"
    sector == "it" -> "IT"
    sector == "industrials" -> "Пром"
    else -> sector.take(5)
}

fun typeShort(type: String): String = when {
    type == "share" || type == "stock" -> "Акц"
    type == "bond" -> "Облиг"
    type == "etf" -> "ETF"
    type == "currency" -> "Вал"
    type == "futures" -> "Фьюч"
    else -> type.take(5)
}

fun dateToMonthKey(date: String): String {
    val d = date.substringBefore("T")
    return if (d.length >= 7) d.substring(0, 7) else d
}

// Collect positions from Finam accounts into a map<figi, UnifiedHolding>
fun collectFinamPositions(
    finam: FinamClient,
    tcs: TcsClient,
    allBonds: List<TcsClient.BondInstrument>,
    finamAccounts: List<AccountInfo>
): Pair<Map<String, UnifiedHolding>, Double> {
    val holdings = mutableMapOf<String, UnifiedHolding>()
    var totalEquity = 0.0

    for (acc in finamAccounts) {
        val accountJson = finam.getAccount(acc.id)
        val equity = extractEquity(accountJson)
        totalEquity += equity
        val positions = parseFinamPositions(accountJson)

        for (pos in positions) {
            val ticker = extractTickerFromSymbol(pos.symbol)
            val figiKey = ticker // will be resolved later

            // Try to find bond info
            val bond = tcs.findBondByTicker(ticker, allBonds)
            val nominal = bond?.nominal ?: 1000.0
            val sector = bond?.sector ?: ""
            val couponPerYear = bond?.couponQuantityPerYear ?: 0
            val maturity = bond?.maturityDate ?: ""
            val name = bond?.name ?: ticker
            val figi = bond?.figi ?: ""

            val avgPriceRub = pos.averagePrice * nominal / 100.0
            val curPriceRub = pos.currentPrice * nominal / 100.0

            val existing = holdings[figi.ifEmpty { ticker }]
            if (existing != null) {
                // Merge quantities, recalculate weighted avg price
                val oldQty = existing.quantity
                val newQty = oldQty + pos.quantity
                val newAvgPrice = if (newQty > 0) {
                    (oldQty * existing.avgPrice + pos.quantity * avgPriceRub) / newQty
                } else existing.avgPrice
                holdings[figi.ifEmpty { ticker }] = existing.copy(
                    quantity = newQty,
                    avgPrice = newAvgPrice,
                    sources = existing.sources + "finam"
                )
            } else {
                holdings[figi.ifEmpty { ticker }] = UnifiedHolding(
                    figi = figi,
                    ticker = ticker,
                    name = name,
                    instrumentType = if (bond != null) "bond" else if (isBondSymbol(pos.symbol)) "bond" else "share",
                    quantity = pos.quantity,
                    avgPrice = avgPriceRub,
                    curPrice = curPriceRub,
                    nominal = nominal,
                    sector = sector,
                    couponPerYear = couponPerYear,
                    maturityDate = maturity,
                    sources = setOf("finam"),
                    payments = emptyList()
                )
            }
        }
    }
    return holdings to totalEquity
}

// Collect positions from T-Bank accounts into a map<figi, UnifiedHolding>
fun collectTcsPositions(
    tcs: TcsClient,
    allBonds: List<TcsClient.BondInstrument>,
    tcsAccounts: List<TcsClient.TcsAccount>
): Map<String, UnifiedHolding> {
    val holdings = mutableMapOf<String, UnifiedHolding>()

    for (acc in tcsAccounts) {
        println("    [T-Bank] Счёт: ${acc.name} (${acc.id.take(8)}...)")
        val positions = tcs.getPortfolio(acc.id)
        println("      Позиций: ${positions.size}")

        // Skip non-security positions (currencies, etc.)
        val securities = positions.filter {
            it.instrumentType in setOf("bond", "share", "etf", "futures")
        }

        for (pos in securities) {
            // Find bond info to get ticker, nominal, etc.
            val bond = tcs.findBondByTicker(pos.figi, allBonds)
            val ticker = bond?.ticker ?: pos.figi
            val name = bond?.name ?: (tcs.findInstrument(pos.figi)?.name ?: pos.figi)
            val nominal = bond?.nominal ?: 1000.0
            val sector = bond?.sector ?: ""
            val couponPerYear = bond?.couponQuantityPerYear ?: 0
            val maturity = bond?.maturityDate ?: ""

            val avgPriceRub = pos.avgPrice ?: 0.0
            val curPriceRub = pos.curPrice ?: 0.0

            val existing = holdings[pos.figi]
            if (existing != null) {
                val oldQty = existing.quantity
                val newQty = oldQty + pos.quantity
                val newAvgPrice = if (newQty > 0 && avgPriceRub > 0) {
                    (oldQty * existing.avgPrice + pos.quantity * avgPriceRub) / newQty
                } else existing.avgPrice
                holdings[pos.figi] = existing.copy(
                    quantity = newQty,
                    avgPrice = newAvgPrice,
                    sources = existing.sources + "tcs"
                )
            } else {
                holdings[pos.figi] = UnifiedHolding(
                    figi = pos.figi,
                    ticker = ticker,
                    name = name,
                    instrumentType = pos.instrumentType,
                    quantity = pos.quantity,
                    avgPrice = avgPriceRub,
                    curPrice = curPriceRub,
                    nominal = nominal,
                    sector = sector,
                    couponPerYear = couponPerYear,
                    maturityDate = maturity,
                    sources = setOf("tcs"),
                    payments = emptyList()
                )
            }
        }
    }
    return holdings
}

// Fetch payments (coupons + dividends) for a holding
fun fetchPayments(tcs: TcsClient, finam: FinamClient, holding: UnifiedHolding, finamSymbol: String?): List<PaymentEvent> {
    val payments = mutableListOf<PaymentEvent>()

    // For bonds: try T-Bank GetBondCoupons
    if (holding.instrumentType == "bond" && holding.figi.isNotEmpty()) {
        val coupons = tcs.getBondCoupons(holding.figi)
        val today = LocalDate.now().toString()
        for (c in coupons) {
            val d = c.couponDate.substringBefore("T")
            if (d >= today && c.currency == "rub") {
                payments.add(PaymentEvent(date = d, amountPerUnit = c.payOneBond, type = "coupon", currency = c.currency))
            }
        }
    }

    // For shares/ETF: try T-Bank GetDividends
    if (holding.instrumentType in setOf("share", "etf") && holding.figi.isNotEmpty()) {
        val divs = tcs.getDividends(holding.figi)
        val today = LocalDate.now().toString()
        for (d in divs) {
            if (d.dividendDate >= today && d.currency == "rub") {
                payments.add(PaymentEvent(date = d.dividendDate, amountPerUnit = d.dividendAmount, type = "dividend", currency = d.currency))
            }
        }
    }

    // Fallback: Finam future-dividends for both bonds and shares
    if (payments.isEmpty() && finamSymbol != null) {
        val divEvents = finam.getFutureDividends(finamSymbol)
        val today = LocalDate.now().toString()
        for (d in divEvents) {
            if (d.date >= today && d.currency == "rub") {
                payments.add(PaymentEvent(date = d.date, amountPerUnit = d.amountPerShare, type = "dividend", currency = d.currency))
            }
        }
    }

    return payments.sortedBy { it.date }
}

fun buildMonthlyChart(monthlyPayments: Map<YearMonth, Double>, title: String = "ГРАФИК ОЖИДАЕМЫХ ВЫПЛАТ ПО МЕСЯЦАМ", totalMonths: Int = 12, portfolioCost: Double = 0.0, payingPortfolioCost: Double = 0.0): String {
    if (monthlyPayments.isEmpty()) return "  Нет данных о платежах"

    val now = YearMonth.now()
    val relevantMonths = (0 until totalMonths).map { now.plusMonths(it.toLong()) }

    val maxPayment = relevantMonths.map { monthlyPayments[it] ?: 0.0 }.maxOrNull() ?: 1.0
    val barMaxWidth = 40

    val sb = StringBuilder()
    sb.appendLine()
    sb.appendLine("================================================================")
    sb.appendLine("  $title")
    sb.appendLine("================================================================")
    sb.appendLine()

    val fmt = DateTimeFormatter.ofPattern("MMM yyyy", java.util.Locale("ru"))

    for (month in relevantMonths) {
        val payment = monthlyPayments[month] ?: 0.0
        val label = month.format(fmt).replace(".", "")
        val barLen = if (maxPayment > 0) {
            ((payment / maxPayment) * barMaxWidth).toInt().coerceAtLeast(1)
        } else 1

        val filled = "█".repeat(barLen)
        sb.appendLine("  %-10s │ %s │ %10.2f руб".format(label, filled, payment))
    }

    sb.appendLine()
    val totalPayments = relevantMonths.sumOf { monthlyPayments[it] ?: 0.0 }
    val avgPayment = relevantMonths.map { monthlyPayments[it] ?: 0.0 }.average()
    sb.appendLine("  Всего за 12 мес: %.2f руб".format(totalPayments))
    sb.appendLine("  В среднем в мес: %.2f руб".format(avgPayment))
    if (portfolioCost > 0) {
        val annualYield = totalPayments / portfolioCost * 100
        sb.appendLine("  Средняя доходность: %.2f%% годовых (купон + дивиденд / стоимость портфеля)".format(annualYield))
    }
    if (payingPortfolioCost > 0) {
        val payingYield = totalPayments / payingPortfolioCost * 100
        sb.appendLine("  Средняя доходность (только платящие): %.2f%% годовых".format(payingYield))
    }
    sb.appendLine("================================================================")

    return sb.toString()
}

fun formatDate(d: String): String {
    return if (d.length >= 10) d.substring(8, 10) + "." + d.substring(5, 7) + "." + d.substring(0, 4) else d
}

fun findMatchingBrace(json: String, start: Int): Int {
    if (json[start] != '{') return -1
    var depth = 0
    for (i in start until json.length) {
        when (json[i]) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return i }
        }
    }
    return -1
}

fun main(args: Array<String>) {
    println("=== Портфельный сканер (Finam + T-Invest) ===")
    println()

    if (tcsApiKey.isEmpty()) {
        println("  [ERROR] Не настроен T-Invest API ключ в application.properties")
        kotlin.system.exitProcess(1)
    }

    val finamAvailable = finamSecret.isNotEmpty()

    try {
        val tcs = TcsClient()

        // 1. Finam auth (if available)
        var finam: FinamClient? = null
        var finamAccounts = listOf<AccountInfo>()

        if (finamAvailable) {
            println("  [1/6] Авторизация в Finam API...")
            finam = FinamClient()
            finam.authenticate()
            println("  [OK]")

            println("  [2/6] Получение счетов Finam...")
            val tokenDetails = finam.getTokenDetails()
            finamAccounts = extractFinamAccounts(tokenDetails)
            println("  Найдено счетов: ${finamAccounts.size}")
            if (finamAccountId.isNotEmpty()) {
                finamAccounts = finamAccounts.filter { it.id == finamAccountId }
                println("  (фильтр: только счёт $finamAccountId)")
            }
        } else {
            println("  [1/6] Finam API ключ не указан — работаем только с T-Invest")
            println("  [2/6] Пропущено")
        }

        // 3. Get T-Bank accounts
        println()
        println("  [3/6] Получение счетов T-Invest...")
        val tcsAccounts = tcs.getAccounts()
        println("  Найдено счетов: ${tcsAccounts.size}")
        for (a in tcsAccounts) {
            println("    - ${a.name} (${a.id.take(8)}...) [${a.type}]")
        }

        if (finamAccounts.isEmpty() && tcsAccounts.isEmpty()) {
            println("  [ERROR] Нет доступных счетов ни в одном брокере")
            kotlin.system.exitProcess(1)
        }

        // 4. Load instruments lookup from T-Bank
        println()
        println("  [4/6] Загрузка справочника облигаций T-Invest...")
        val allBonds = tcs.getAllBonds()
        println("  Загружено: ${allBonds.size} облигаций")

        // 5. Collect all positions
        println()
        println("  [5/6] Сбор позиций со всех счетов...")
        println()

        var finamHoldings = mutableMapOf<String, UnifiedHolding>()
        var finamEquity = 0.0
        val finamPositionsByTicker = mutableMapOf<String, FinamPosition>()

        if (finam != null) {
            // Collect Finam positions per account
            for (acc in finamAccounts) {
                val accountJson = finam.getAccount(acc.id)
                val equity = extractEquity(accountJson)
                finamEquity += equity
                val cashTotal = extractCash(accountJson)
                val positions = parseFinamPositions(accountJson)
                println("  [Finam] Счёт ${acc.id}: ${positions.size} позиций, капитал $equity руб, кэш $cashTotal руб")

                for (pos in positions) {
                    val ticker = extractTickerFromSymbol(pos.symbol)
                    finamPositionsByTicker[ticker] = pos
                    val bond = tcs.findBondByTicker(ticker, allBonds)
                    val nominal = bond?.nominal ?: 1000.0
                    val sector = bond?.sector ?: ""
                    val couponPerYear = bond?.couponQuantityPerYear ?: 0
                    val maturity = bond?.maturityDate ?: ""
                    val name = bond?.name ?: ticker
                    val figi = bond?.figi ?: ""

                    val avgPriceRub = pos.averagePrice * nominal / 100.0
                    val curPriceRub = pos.currentPrice * nominal / 100.0
                    val key = figi.ifEmpty { ticker }

                    val existing = finamHoldings[key]
                    if (existing != null) {
                        val oldQty = existing.quantity
                        val newQty = oldQty + pos.quantity
                        val newAvgPrice = if (newQty > 0) {
                            (oldQty * existing.avgPrice + pos.quantity * avgPriceRub) / newQty
                        } else existing.avgPrice
                        finamHoldings[key] = existing.copy(quantity = newQty, avgPrice = newAvgPrice)
                    } else {
                        finamHoldings[key] = UnifiedHolding(
                            figi = figi,
                            ticker = ticker,
                            name = name,
                            instrumentType = if (bond != null) "bond" else "share",
                            quantity = pos.quantity,
                            avgPrice = avgPriceRub,
                            curPrice = curPriceRub,
                            nominal = nominal,
                            sector = sector,
                            couponPerYear = couponPerYear,
                            maturityDate = maturity,
                            sources = setOf("finam"),
                            payments = emptyList()
                        )
                    }
                }
            }
        }

        // Collect T-Bank positions per account
        val tcsHoldings = mutableMapOf<String, UnifiedHolding>()
        val tcsAccountDetails = mutableMapOf<String, Pair<Double, Double>>()

        for (acc in tcsAccounts) {
            val positions = tcs.getPortfolio(acc.id)

            var accountEquity = 0.0
            for (pos in positions) {
                accountEquity += pos.quantity * (pos.curPrice ?: 0.0)
            }

            val accountCash = tcs.getWithdrawLimits(acc.id)
            tcsAccountDetails[acc.id] = Pair(accountEquity, accountCash)

            val cashStr = if (accountCash > 0 || acc.type in setOf("ACCOUNT_TYPE_TINKOFF", "ACCOUNT_TYPE_TINKOFF_IIS"))
                "%.2f".format(accountCash).replace(',', '.')
            else
                "н/д"
            println("  [T-Invest] Счёт ${acc.name} (${acc.id}): ${positions.size} позиций, капитал %.2f руб, кэш $cashStr руб".format(accountEquity).replace(',', '.'))

            val securities = positions.filter {
                it.instrumentType in setOf("bond", "share", "etf")
            }

            for (pos in securities) {
                val posTicker = pos.ticker.ifEmpty { null }
                val ticker: String
                val name: String
                val nominal: Double
                val sector: String
                val couponPerYear: Int
                val maturity: String

                if (pos.instrumentType == "bond") {
                    val bond = tcs.findBondByTicker(posTicker ?: pos.figi, allBonds)
                    ticker = posTicker ?: bond?.ticker ?: pos.figi
                    name = bond?.name ?: posTicker ?: pos.figi
                    nominal = bond?.nominal ?: 1000.0
                    sector = bond?.sector ?: ""
                    couponPerYear = bond?.couponQuantityPerYear ?: 0
                    maturity = bond?.maturityDate ?: ""
                } else {
                    val kindFilter = when (pos.instrumentType) {
                        "share" -> "INSTRUMENT_TYPE_SHARE"
                        "etf" -> "INSTRUMENT_TYPE_ETF"
                        else -> ""
                    }
                    val info = if (posTicker != null) tcs.findInstrument(posTicker, kindFilter, pos.classCode) else null
                    ticker = posTicker ?: info?.ticker ?: pos.figi
                    name = info?.name ?: posTicker ?: pos.figi
                    nominal = 1000.0
                    sector = ""
                    couponPerYear = 0
                    maturity = ""
                }

                val avgPriceRub = pos.avgPrice ?: 0.0
                val curPriceRub = pos.curPrice ?: 0.0

                val existing = tcsHoldings[pos.figi]
                if (existing != null) {
                    val oldQty = existing.quantity
                    val newQty = oldQty + pos.quantity
                    val newAvgPrice = if (newQty > 0 && avgPriceRub > 0) {
                        (oldQty * existing.avgPrice + pos.quantity * avgPriceRub) / newQty
                    } else existing.avgPrice
                    tcsHoldings[pos.figi] = existing.copy(quantity = newQty, avgPrice = newAvgPrice)
                } else {
                    tcsHoldings[pos.figi] = UnifiedHolding(
                        figi = pos.figi,
                        ticker = ticker,
                        name = name,
                        instrumentType = pos.instrumentType,
                        quantity = pos.quantity,
                        avgPrice = avgPriceRub,
                        curPrice = curPriceRub,
                        nominal = nominal,
                        sector = sector,
                        couponPerYear = couponPerYear,
                        maturityDate = maturity,
                        sources = setOf("tcs"),
                        payments = emptyList()
                    )
                }
            }
        }

        // Merge holdings: T-Bank takes priority for figi mapping, Finam for positions not in T-Bank
        val mergedHoldings = mutableMapOf<String, UnifiedHolding>()

        // Add T-Bank holdings first (they have figi)
        for ((key, h) in tcsHoldings) {
            mergedHoldings[key] = h
        }

        // Add/merge Finam holdings
        for ((key, h) in finamHoldings) {
            val existing = mergedHoldings[key]
            if (existing != null) {
                val newQty = existing.quantity + h.quantity
                val newAvgPrice = if (newQty > 0) {
                    (existing.quantity * existing.avgPrice + h.quantity * h.avgPrice) / newQty
                } else existing.avgPrice
                mergedHoldings[key] = existing.copy(
                    quantity = newQty,
                    avgPrice = newAvgPrice,
                    curPrice = if (existing.curPrice > 0) existing.curPrice else h.curPrice,
                    sources = existing.sources + "finam"
                )
            } else {
                mergedHoldings[key] = h
            }
        }

        println()
        println("  Всего позиций (уникальных): ${mergedHoldings.size}")

        // 6. Fetch payments for each holding
        println()
        println("  [6/6] Загрузка будущих купонов и дивидендов...")
        println()

        val today = LocalDate.now()

        for ((key, holding) in mergedHoldings) {
            val payments = mutableListOf<PaymentEvent>()

            // Bonds: try T-Bank GetBondCoupons
            if (holding.instrumentType == "bond" && holding.figi.isNotEmpty()) {
                val coupons = tcs.getBondCoupons(holding.figi)
                for (c in coupons) {
                    val d = c.couponDate.substringBefore("T")
                    if (d >= today.toString() && c.currency == "rub") {
                        payments.add(PaymentEvent(date = d, amountPerUnit = c.payOneBond, type = "coupon", currency = c.currency))
                    }
                }
            }

            // Shares/ETF: try T-Bank GetDividends
            if (holding.instrumentType in setOf("share", "etf") && holding.figi.isNotEmpty()) {
                val divs = tcs.getDividends(holding.figi)
                for (d in divs) {
                    if (d.dividendDate >= today.toString() && d.currency == "rub") {
                        payments.add(PaymentEvent(date = d.dividendDate, amountPerUnit = d.dividendAmount, type = "dividend", currency = d.currency))
                    }
                }
            }

            mergedHoldings[key] = holding.copy(payments = payments.sortedBy { it.date })
        }

        // Also try Finam fallback for holdings with no payments
        if (finam != null) {
            for ((key, holding) in mergedHoldings) {
                if (holding.payments.isNotEmpty()) continue

                val pos = finamPositionsByTicker[holding.ticker]
                if (pos != null) {
                    val divEvents = finam.getFutureDividends(pos.symbol)
                    val futureDivs = divEvents.filter {
                        it.date >= today.toString() && it.currency == "rub"
                    }
                    if (futureDivs.isNotEmpty()) {
                        val payments = futureDivs.map { d ->
                            PaymentEvent(date = d.date, amountPerUnit = d.amountPerShare, type = "dividend", currency = d.currency)
                        }.sortedBy { it.date }
                        mergedHoldings[key] = holding.copy(payments = payments)
                    }
                }
            }
        }

        // --- DISPLAY ---
        println()
        println("==================================================================")
        println("  СВОДНЫЙ ПОРТФЕЛЬ (все счета, все брокеры)")
        println("==================================================================")
        println()

        val sortedHoldings = mergedHoldings.entries
            .filter { it.value.quantity > 0 }
            .sortedByDescending { it.value.quantity * it.value.curPrice }

        val totalValue = sortedHoldings.sumOf { it.value.quantity * it.value.curPrice }

        if (sortedHoldings.isEmpty()) {
            println("  Нет позиций")
        } else {
            val totalPortfolioValue = totalValue

            println(" #  | Тип   | Тикер             | Название                   | Кол-во |  Средняя |  Цена    |  Стоим.   |  Доля  | Купон/мес | Купон% | Погашение  | Ист")
            println("------------------------------------------------------------------------------------------------------------------------------------------")

            sortedHoldings.forEachIndexed { idx, (_, h) ->
                val curValue = h.quantity * h.curPrice
                val share = if (totalPortfolioValue > 0) curValue / totalPortfolioValue * 100 else 0.0

                val fixedPayments = h.payments.filter { it.type == "coupon" }
                val avgCoupon = if (fixedPayments.isNotEmpty()) {
                    fixedPayments.map { it.amountPerUnit }.average()
                } else if (h.payments.isNotEmpty()) {
                    h.payments.map { it.amountPerUnit }.average()
                } else 0.0

                val monthlyCoupon = avgCoupon * h.quantity
                val annualYield = if (avgCoupon > 0 && h.couponPerYear > 0 && h.avgPrice > 0) {
                    (avgCoupon * h.couponPerYear) / h.avgPrice * 100
                } else 0.0

                val mature = if (h.maturityDate.isNotEmpty()) formatDate(h.maturityDate) else ""
                val sourceTag = when {
                    h.sources.size == 2 -> "F+T"
                    h.sources.contains("tcs") -> " T "
                    else -> " F "
                }

                println("%3d | %5s | %-16s | %-25s | %6.0f | %8.2f | %8.2f | %9.2f | %5.1f%% | %10.2f | %5.2f%% | %s  %s".format(
                    idx + 1,
                    typeShort(h.instrumentType),
                    h.ticker,
                    h.name.take(25),
                    h.quantity,
                    h.avgPrice,
                    h.curPrice,
                    curValue,
                    share,
                    monthlyCoupon,
                    annualYield,
                    mature,
                    sourceTag
                ))
            }

            println("------------------------------------------------------------------------------------------------------------------------------------------")
            val totalCost = sortedHoldings.sumOf { it.value.quantity * it.value.avgPrice }
            val totalPnl = totalValue - totalCost
            val pnlPct = if (totalCost > 0) totalPnl / totalCost * 100 else 0.0
            println()
            println("  Итого: стоимость %.2f руб, затраты %.2f руб, P&L %+.2f руб (%+.2f%%)".format(
                totalValue, totalCost, totalPnl, pnlPct
            ))
            println("==================================================================")
        }

        // --- MONTHLY PAYMENT CHART ---
        val monthlyMap = mutableMapOf<YearMonth, MutableList<Pair<String, Double>>>()

        for ((_, h) in sortedHoldings) {
            for (p in h.payments) {
                try {
                    val date = LocalDate.parse(p.date)
                    val ym = YearMonth.from(date)
                    val totalPayment = p.amountPerUnit * h.quantity
                    monthlyMap.getOrPut(ym) { mutableListOf() }
                        .add(h.ticker to totalPayment)
                } catch (_: Exception) {}
            }
        }

        if (monthlyMap.isNotEmpty()) {
            val sortedMonths = monthlyMap.entries.sortedBy { it.key }
            val aggregatedMonthly = sortedMonths.take(12).associate { it.key to it.value.sumOf { p -> p.second } }
            val payingHoldingsValue = sortedHoldings.filter { (_, h) -> h.payments.isNotEmpty() }.sumOf { (_, h) -> h.quantity * h.curPrice }
            print(buildMonthlyChart(aggregatedMonthly, "ГРАФИК ОЖИДАЕМЫХ ВЫПЛАТ (КУПОНЫ + ДИВИДЕНДЫ) ПО МЕСЯЦАМ", portfolioCost = totalValue, payingPortfolioCost = payingHoldingsValue))
        } else {
            println()
            println("  Нет будущих выплат для отображения")
        }

        // --- PER-ACCOUNT BREAKDOWN ---
        if (finam != null && finamAccounts.isNotEmpty()) {
            println()
            println("==================================================================")
            println("  ДЕТАЛИЗАЦИЯ ПО СЧЕТАМ FINAM")
            println("==================================================================")

            for (acc in finamAccounts) {
                println()
                println("--- Счёт Finam: ${acc.id} ---")
                val accountJson = finam.getAccount(acc.id)
                val equity = extractEquity(accountJson)
                val cashTotal = extractCash(accountJson)
                println("  Капитал: $equity руб, Кэш: $cashTotal руб")
            }
        }

        if (tcsAccounts.isNotEmpty()) {
            println()
            println("==================================================================")
            println("  ДЕТАЛИЗАЦИЯ ПО СЧЕТАМ T-INVEST")
            println("==================================================================")

            for (acc in tcsAccounts) {
                println()
                println("--- Счёт: ${acc.name} (${acc.id}) ---")
                println("  Тип: ${acc.type}, Статус: ${acc.status}")
                val details = tcsAccountDetails[acc.id]
                if (details != null) {
                    val equity = details.first
                    val cash = details.second
                    val cashStr = if (cash > 0 || acc.type in setOf("ACCOUNT_TYPE_TINKOFF", "ACCOUNT_TYPE_TINKOFF_IIS"))
                        "%.2f".format(cash).replace(',', '.')
                    else
                        "н/д"
                    println("  Капитал: %.2f руб, Кэш: $cashStr руб".format(equity).replace(',', '.'))
                }
            }
        }

        println()
        println("  Готово.")

    } catch (e: Exception) {
        println()
        println("  [ERROR] ${e.message}")
        e.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}

main(args)
