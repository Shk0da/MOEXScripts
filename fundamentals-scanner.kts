#!/usr/bin/env kotlin

import java.io.FileInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
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

data class Config(
    val topN: Int = 10,
    val minPrice: Double = 10.0,
    val maxPE: Double = 50.0,
    val minDividendYield: Double = 0.0,
    val maxDebtToEquity: Double = 500.0,
    val excludeEtfs: Boolean = true,
    val minMarketCap: Double = 1_000_000_000.0,
    val showAllFundamentals: Boolean = false
)

// Очистка названия от юридических префиксов
fun cleanCompanyName(fullName: String): String {
    var name = fullName
    // Удаляем префиксы с акциями (в правильном порядке)
    name = name.replace(Regex("^Акции\\s+"), "")
    name = name.replace(Regex("^(обыкновенные|привилегированные)\\s+"), "")
    name = name.replace(Regex("^типа\\s+[А-ЯA-Z]\\s*"), "")  // типа А или типа A (кириллица и латиница)
    name = name.replace(Regex("^(ПАО|АО|ОАО|ЗАО|ООО|НАО)\\s*"), "")
    // Удаляем кавычки
    name = name.replace("\"", "")
    // Удаляем скобки в конце
    name = name.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")
    // Удаляем суффиксы
    name = name.replace(Regex("\\s+-\\s*привилегированные\\s+акции\\s*$"), "")
    name = name.trim()
    return name.ifEmpty { fullName }
}

data class ShareInfo(
    val uid: String,
    val figi: String,
    val ticker: String,
    val name: String,
    val currency: String,
    val sector: String,
    val lot: Int,
    val forQualInvestorFlag: Boolean = false  // true = только для квалифицированных инвесторов
) {
    val forNonQualifiedInvestor: Boolean get() = !forQualInvestorFlag
    val shortName: String get() = cleanCompanyName(name)
}

data class AssetMapping(
    val assetUid: String,
    val assetName: String,
    val instrumentUid: String,
    val ticker: String,
    val figi: String
)

data class FundamentalData(
    val assetUid: String,
    val name: String,
    val ticker: String,
    val marketCap: Double,
    val peRatio: Double,
    val priceToBook: Double,
    val priceToSales: Double,
    val evToEbitda: Double,
    val debtToEquity: Double,
    val roe: Double,
    val roa: Double,
    val roic: Double,
    val dividendYield: Double,
    val forwardDividendYield: Double,
    val revenueGrowth5y: Double,
    val netMargin: Double,
    val currentRatio: Double,
    val epsTtm: Double,
    val dilutedEpsTtm: Double,
    val freeCashFlowTtm: Double,
    val beta: Double,
    val netDebtToEbitda: Double,
    val enterpriseValue: Double,
    val highPrice52w: Double,
    val lowPrice52w: Double,
    val sharesOutstanding: Double,
    val avgVolume10d: Double,
    val freeFloat: Double,
    val sector: String = ""
)

data class Forecast(
    val ticker: String,
    val company: String,
    val consensusTarget: Double,
    val minTarget: Double,
    val maxTarget: Double,
    val currentPrice: Double,
    val upsidePercent: Double,
    val consensus: String,
    val targets: List<ForecastTarget> = emptyList()
)

data class ForecastTarget(
    val source: String,
    val company: String,
    val targetPrice: Double,
    val currentPrice: Double,
    val priceChangeRel: Double,
    val recommendation: String,
    val date: String
)

data class RankedStock(
    val ticker: String,
    val name: String,
    val sector: String,
    val marketCap: Double,
    val pe: Double,
    val pb: Double,
    val ps: Double,
    val evEbitda: Double,
    val de: Double,
    val roe: Double,
    val roic: Double,
    val divYield: Double,
    val fwdDivYield: Double,
    val revenueGrowth5y: Double,
    val netMargin: Double,
    val beta: Double,
    val undervaluationScore: Double,
    val forecast: Forecast? = null
) {
    val shortName: String get() = cleanCompanyName(name)
}

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

class TcsFundamentalsClient {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .sslContext(createTrustAllSSLContext())
        .build()

    private fun post(url: String, body: String, retries: Int = 3): String {
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
                    throw Exception("HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
                }
                return resp.body()
            } catch (e: Exception) {
                if (attempt == retries) throw e
                Thread.sleep(3000L)
            }
        }
        throw Exception("All retries exhausted")
    }

    private fun extractString(json: String, key: String): String {
        val m = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun extractInt(json: String, key: String): Int {
        val m = Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractDouble(json: String, key: String): Double {
        val m = Regex("\"$key\"\\s*:\\s*([0-9.]+(?:[eE][+-]?\\d+)?)").find(json)
        return m?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    private fun extractBool(json: String, key: String): Boolean {
        val m = Regex("\"$key\"\\s*:\\s*(true|false)").find(json)
        return m?.groupValues?.get(1) == "true"
    }

    private fun extractUnitsNano(json: String, key: String): Double {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return 0.0
        val chunk = json.substring(idx)
        val u = Regex("\"units\"\\s*:\\s*\"?(\\d+|-?\\d+)\"?").find(chunk)
        val n = Regex("\"nano\"\\s*:\\s*(-?\\d+)").find(chunk)
        val units = u?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val nano = n?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return units + nano / 1_000_000_000.0
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

    fun extractNestedArray(json: String, key: String): List<String> {
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

    fun getShares(): List<ShareInfo> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/Shares"
        val body = """{"instrumentStatus":"INSTRUMENT_STATUS_BASE"}"""
        val resp = post(url, body)

        val shares = mutableListOf<ShareInfo>()
        val items = parseJsonArray(resp, "instruments")
        for (item in items) {
            val uid = extractString(item, "uid")
            val figi = extractString(item, "figi")
            val ticker = extractString(item, "ticker")
            val name = extractString(item, "name")
            val currency = extractString(item, "currency")
            val sector = extractString(item, "sector")
            val lot = extractInt(item, "lot")
            val forQualInvestor = extractBool(item, "forQualInvestorFlag")
            if (uid.isNotEmpty()) {
                shares.add(ShareInfo(uid, figi, ticker, name, currency, sector, lot, forQualInvestor))
            }
        }
        return shares
    }

    fun getAssets(): List<AssetMapping> {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetAssets"
        val body = """{"instrumentType":"INSTRUMENT_TYPE_SHARE"}"""
        val resp = post(url, body)

        val mappings = mutableListOf<AssetMapping>()
        val assets = parseJsonArray(resp, "assets")
        for (asset in assets) {
            val assetUid = extractString(asset, "uid")
            val assetName = extractString(asset, "name")
            if (assetUid.isEmpty()) continue

            val instruments = extractNestedArray(asset, "instruments")
            for (inst in instruments) {
                val instUid = extractString(inst, "uid")
                val ticker = extractString(inst, "ticker")
                val figi = extractString(inst, "figi")
                if (instUid.isNotEmpty()) {
                    mappings.add(AssetMapping(assetUid, assetName, instUid, ticker, figi))
                }
            }
        }
        return mappings
    }

    fun getFundamentals(assetIds: List<String>): List<FundamentalData> {
        if (assetIds.isEmpty()) return emptyList()

        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetAssetFundamentals"
        val idsJson = assetIds.joinToString(",") { "\"$it\"" }
        val body = """{"assets":[$idsJson]}"""
        val resp = post(url, body)

        val results = mutableListOf<FundamentalData>()
        val items = extractNestedArray(resp, "fundamentals")
        for (item in items) {
            val f = parseFundamental(item)
            if (f != null) results.add(f)
        }
        return results
    }

    private fun parseFundamental(json: String): FundamentalData? {
        val assetUid = extractString(json, "assetUid")
        if (assetUid.isEmpty()) return null

        return FundamentalData(
            assetUid = assetUid,
            name = extractString(json, "name"),
            ticker = extractString(json, "ticker"),
            marketCap = extractDouble(json, "marketCapitalization"),
            peRatio = extractDouble(json, "peRatioTtm"),
            priceToBook = extractDouble(json, "priceToBookTtm"),
            priceToSales = extractDouble(json, "priceToSalesTtm"),
            evToEbitda = extractDouble(json, "evToEbitdaMrq"),
            debtToEquity = extractDouble(json, "totalDebtToEquityMrq"),
            roe = extractDouble(json, "roe"),
            roa = extractDouble(json, "roa"),
            roic = extractDouble(json, "roic"),
            dividendYield = extractDouble(json, "dividendYieldDailyTtm"),
            forwardDividendYield = extractDouble(json, "forwardAnnualDividendYield"),
            revenueGrowth5y = extractDouble(json, "fiveYearAnnualRevenueGrowthRate"),
            netMargin = extractDouble(json, "netMarginMrq"),
            currentRatio = extractDouble(json, "currentRatioMrq"),
            epsTtm = extractDouble(json, "epsTtm"),
            dilutedEpsTtm = extractDouble(json, "dilutedEpsTtm"),
            freeCashFlowTtm = extractDouble(json, "freeCashFlowTtm"),
            beta = extractDouble(json, "beta"),
            netDebtToEbitda = extractDouble(json, "netDebtToEbitda"),
            enterpriseValue = extractDouble(json, "totalEnterpriseValueMrq"),
            highPrice52w = extractDouble(json, "highPriceLast52Weeks"),
            lowPrice52w = extractDouble(json, "lowPriceLast52Weeks"),
            sharesOutstanding = extractDouble(json, "sharesOutstanding"),
            avgVolume10d = extractDouble(json, "averageDailyVolumeLast10Days"),
            freeFloat = extractDouble(json, "freeFloat")
        )
    }

    fun getForecastBy(instrumentId: String): Forecast? {
        val url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetForecastBy"
        val body = """{"instrumentId":"$instrumentId"}"""
        return try {
            val resp = post(url, body)
            parseForecast(resp)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseForecast(json: String): Forecast? {
        val targets = extractNestedArray(json, "targets")
        if (targets.isEmpty()) return null

        val parsedTargets = targets.mapNotNull { t ->
            val source = extractString(t, "uid")
            val company = extractString(t, "company")
            val tp = extractUnitsNano(t, "targetPrice")
            val cp = extractUnitsNano(t, "currentPrice")
            val changeRel = extractUnitsNano(t, "priceChangeRel")
            val rec = extractString(t, "recommendation")
            val date = extractString(t, "recommendationDate")
            if (source.isNotEmpty()) {
                ForecastTarget(source, company, tp, cp, changeRel, rec, date)
            } else null
        }
        if (parsedTargets.isEmpty()) return null

        val consensusJson = extractObject(json, "consensus")
        val ticker = if (consensusJson.isNotEmpty()) extractString(consensusJson, "ticker") else ""
        val company = extractString(json, "showName").ifEmpty {
            if (consensusJson.isNotEmpty()) extractString(consensusJson, "showName") else ""
        }
        var consensusTarget = 0.0
        var minTarget = 0.0
        var maxTarget = 0.0
        var currentPrice = 0.0
        var upsidePercent = 0.0
        var consensus = ""

        if (consensusJson.isNotEmpty()) {
            consensusTarget = extractUnitsNano(consensusJson, "consensus")
            minTarget = extractUnitsNano(consensusJson, "minTarget")
            maxTarget = extractUnitsNano(consensusJson, "maxTarget")
            currentPrice = extractUnitsNano(consensusJson, "currentPrice")
            upsidePercent = extractUnitsNano(consensusJson, "priceChangeRel")
            consensus = extractString(consensusJson, "recommendation")
        }

        return Forecast(
            ticker = ticker,
            company = company,
            consensusTarget = consensusTarget,
            minTarget = minTarget,
            maxTarget = maxTarget,
            currentPrice = currentPrice,
            upsidePercent = upsidePercent,
            consensus = consensus,
            targets = parsedTargets
        )
    }

    private fun extractObject(json: String, key: String): String {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return ""
        val chunk = json.substring(idx)
        val start = chunk.indexOf('{')
        if (start < 0) return ""
        var depth = 0
        var inStr = false
        for (i in start until chunk.length) {
            val c = chunk[i]
            if (c == '"') inStr = !inStr
            if (inStr) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return chunk.substring(start, i + 1)
                    }
                }
            }
        }
        return ""
    }
}

fun calculateScore(f: FundamentalData, config: Config): Double {
    var score = 0.0
    var factors = 0

    if (f.marketCap >= config.minMarketCap && f.peRatio > 0 && f.peRatio < config.maxPE) {
        val peScore = if (f.peRatio <= 5) 100.0
        else if (f.peRatio <= 10) 85.0
        else if (f.peRatio <= 15) 65.0
        else if (f.peRatio <= 20) 45.0
        else if (f.peRatio <= 25) 30.0
        else if (f.peRatio <= 30) 20.0
        else 10.0
        score += peScore * 3
        factors += 3
    }

    if (f.priceToBook > 0 && f.priceToBook < 20) {
        val pbScore = if (f.priceToBook <= 0.5) 100.0
        else if (f.priceToBook <= 0.7) 90.0
        else if (f.priceToBook <= 1.0) 75.0
        else if (f.priceToBook <= 1.5) 55.0
        else if (f.priceToBook <= 2.0) 35.0
        else if (f.priceToBook <= 3.0) 20.0
        else 10.0
        score += pbScore * 2
        factors += 2
    }

    if (f.evToEbitda > 0 && f.evToEbitda < 50) {
        val evScore = if (f.evToEbitda <= 3) 100.0
        else if (f.evToEbitda <= 5) 80.0
        else if (f.evToEbitda <= 8) 60.0
        else if (f.evToEbitda <= 12) 40.0
        else if (f.evToEbitda <= 20) 20.0
        else 10.0
        score += evScore * 2
        factors += 2
    }

    if (f.dividendYield > 0) {
        val dyScore = if (f.dividendYield >= 15) 100.0
        else if (f.dividendYield >= 10) 85.0
        else if (f.dividendYield >= 6) 65.0
        else if (f.dividendYield >= 3) 45.0
        else if (f.dividendYield >= 1) 25.0
        else 10.0
        score += dyScore * 1
        factors += 1
    }

    if (f.roe > 0 && f.roe < 100) {
        val roeScore = if (f.roe >= 30) 100.0
        else if (f.roe >= 20) 80.0
        else if (f.roe >= 15) 60.0
        else if (f.roe >= 10) 40.0
        else if (f.roe >= 5) 20.0
        else 10.0
        score += roeScore
        factors += 1
    }

    if (f.roic > 0 && f.roic < 100) {
        val roicScore = if (f.roic >= 20) 100.0
        else if (f.roic >= 15) 80.0
        else if (f.roic >= 10) 60.0
        else if (f.roic >= 5) 40.0
        else 20.0
        score += roicScore
        factors += 1
    }

    if (f.debtToEquity > 0 && f.debtToEquity < 500) {
        val deScore = if (f.debtToEquity <= 10) 100.0
        else if (f.debtToEquity <= 30) 80.0
        else if (f.debtToEquity <= 50) 60.0
        else if (f.debtToEquity <= 100) 40.0
        else if (f.debtToEquity <= 200) 20.0
        else 10.0
        score += deScore
        factors += 1
    }

    if (f.revenueGrowth5y > 0) {
        val rgScore = if (f.revenueGrowth5y >= 20) 100.0
        else if (f.revenueGrowth5y >= 10) 80.0
        else if (f.revenueGrowth5y >= 5) 60.0
        else if (f.revenueGrowth5y >= 2) 40.0
        else 20.0
        score += rgScore
        factors += 1
    }

    return if (factors > 0) score / factors else 0.0
}

fun formatMoney(v: Double): String {
    return when {
        v >= 1_000_000_000_000 -> "%.2fT".format(v / 1_000_000_000_000)
        v >= 1_000_000_000 -> "%.2fB".format(v / 1_000_000_000)
        v >= 1_000_000 -> "%.2fM".format(v / 1_000_000)
        else -> "%.0f".format(v)
    }
}

fun formatPct(v: Double): String {
    return if (v > 0) "%.2f%%".format(v) else "—"
}

fun formatDouble(v: Double): String {
    return if (v > 0) "%.2f".format(v) else "—"
}

fun printHeader(text: String) {
    val line = "═".repeat(80)
    println("\n$line")
    println("  $text")
    println(line)
}

fun main(args: Array<String>) {
    val config = Config()

    println("============================================================")
    println("  T-Bank Fundamentals Scanner — поиск недооценённых бумаг")
    println("============================================================")

    if (tcsApiKey.isEmpty()) {
        println("\n  [ERROR] tcs.apiKey не найден в application.properties")
        return
    }

    val client = TcsFundamentalsClient()

    // Step 1: Get all shares
    printHeader("Загрузка списка акций...")
    val shares = client.getShares()
    val forNonQualified = shares.count { !it.forQualInvestorFlag }
    println("  Найдено акций: ${shares.size} (доступно неквалифицированным: $forNonQualified)")
    val shareMap = shares.associateBy { it.uid }

    // Step 2: Get all assets and build mapping
    printHeader("Загрузка активов и построение маппинга...")
    val assets = client.getAssets()
    println("  Найдено маппингов: ${assets.size}")

    val uidToAsset = mutableMapOf<String, String>()
    val tickerToAsset = mutableMapOf<String, String>()
    val assetToTicker = mutableMapOf<String, String>()
    val assetToName = mutableMapOf<String, String>()

    for (a in assets) {
        uidToAsset[a.instrumentUid] = a.assetUid
        tickerToAsset[a.ticker] = a.assetUid
        assetToTicker[a.assetUid] = a.ticker
        assetToName[a.assetUid] = a.assetName
    }

    val uniqueAssetIds = uidToAsset.values.toSet().toList()
    println("  Уникальных активов: ${uniqueAssetIds.size}")

    // Step 3: Fetch fundamentals for all assets (batch of 100)
    printHeader("Загрузка фундаментальных показателей...")
    val allFundamentals = mutableListOf<FundamentalData>()

    uniqueAssetIds.chunked(100).forEachIndexed { idx, batch ->
        print("  Пакет ${idx + 1}/${(uniqueAssetIds.size + 99) / 100}...")
        val results = client.getFundamentals(batch)
        println(" получено ${results.size} записей")
        allFundamentals.addAll(results)
    }
    println("\n  Всего фундаментальных данных: ${allFundamentals.size}")

    // Step 4: Filter and score
    printHeader("Ранжирование по мультипликаторам...")
    val withSector = allFundamentals.map { f ->
        val ticker = f.ticker.ifEmpty { assetToTicker[f.assetUid] ?: "" }
        val share = shareMap.values.find { it.ticker == ticker }
        // Приоритет имён: share.name (Shares API) > assetToName > f.name
        val name = share?.name ?: assetToName[f.assetUid] ?: f.name
        f.copy(
            ticker = ticker,
            name = name,
            sector = share?.sector ?: ""
        )
    }.filter { f ->
        val share = shareMap.values.find { it.ticker == f.ticker }
        f.marketCap >= config.minMarketCap &&
        f.peRatio > 0 &&
        f.peRatio <= config.maxPE &&
        f.dividendYield >= config.minDividendYield &&
        (f.debtToEquity == 0.0 || f.debtToEquity <= config.maxDebtToEquity) &&
        !(share?.forQualInvestorFlag ?: true)  // Только для неквалифицированных инвесторов
    }

    val ranked = withSector.map { f ->
        RankedStock(
            ticker = f.ticker,
            name = f.name,
            sector = f.sector,
            marketCap = f.marketCap,
            pe = f.peRatio,
            pb = f.priceToBook,
            ps = f.priceToSales,
            evEbitda = f.evToEbitda,
            de = f.debtToEquity,
            roe = f.roe,
            roic = f.roic,
            divYield = f.dividendYield,
            fwdDivYield = f.forwardDividendYield,
            revenueGrowth5y = f.revenueGrowth5y,
            netMargin = f.netMargin,
            beta = f.beta,
            undervaluationScore = calculateScore(f, config)
        )
    }.filter { it.undervaluationScore > 0 }
     .sortedByDescending { it.undervaluationScore }

    println("  После фильтрации: ${ranked.size} бумаг")
    println("  Топ-${config.topN} недооценённых:\n")

    // Print top N
    val topN = ranked.take(config.topN)
    println("  №  Тикер     Название                       Сектор           P/E    P/B   EV/EB  D/E   ROE    ROIC   Div%  Рост  Оценка")
    println("  ── ───────── ────────────────────────────── ─────────────── ────── ───── ───── ───── ───── ────── ────── ───── ──────")
    topN.forEachIndexed { i, s ->
        val tickerPad = s.ticker.padEnd(9).take(9)
        val nameDisplay = s.shortName.let { if (it.length > 30) it.take(27) + "..." else it }.padEnd(30)
        val sectorPad = s.sector.take(15).padEnd(15)
        println("  %2d  %-9s %-30s %-15s %6s %5s %5s %5s %5s %6s %6s %5s %6s"
            .format(
                i + 1,
                tickerPad,
                nameDisplay,
                sectorPad,
                formatDouble(s.pe).padStart(6),
                formatDouble(s.pb).padStart(5),
                formatDouble(s.evEbitda).padStart(5),
                formatDouble(s.de).padStart(5),
                formatDouble(s.roe).padStart(5),
                formatDouble(s.roic).padStart(6),
                formatPct(s.divYield).padStart(6),
                formatPct(s.revenueGrowth5y).padStart(5),
                "%.0f".format(s.undervaluationScore).padStart(6)
            )
        )
    }

    // Step 5: Fetch forecasts for top 10
    printHeader("Прогнозы аналитиков для топ-10 бумаг...")
    var forecastCount = 0
    for (stock in topN) {
        val share = shareMap.values.find { it.ticker == stock.ticker }
        val instrumentId = share?.figi ?: continue

        val forecast = client.getForecastBy(instrumentId)
        if (forecast != null) {
            forecastCount++

            println("\n  [${stock.ticker}] ${stock.shortName}")
            println("  ├─ Консенсус:  ${forecast.consensusTarget.let { formatDouble(it) }}")
            println("  ├─ Диапазон:   ${formatDouble(forecast.minTarget)} – ${formatDouble(forecast.maxTarget)}")
            println("  ├─ Текущая:    ${formatDouble(forecast.currentPrice)}")
            println("  └─ Потенциал:  ${formatPct(forecast.upsidePercent)}")

            if (forecast.targets.isNotEmpty()) {
                println("     Прогнозы инвестдомов:")
                for (t in forecast.targets) {
                    val rec = when {
                        t.recommendation.contains("BUY") -> "\u001B[32mПокупать\u001B[0m"
                        t.recommendation.contains("HOLD") -> "\u001B[33mДержать\u001B[0m"
                        t.recommendation.contains("SELL") -> "\u001B[31mПродавать\u001B[0m"
                        else -> t.recommendation
                    }
                    val changePct = if (t.currentPrice > 0)
                        (t.targetPrice / t.currentPrice - 1) * 100 else t.priceChangeRel
                    println("     ├─ ${t.company.padEnd(20)} TP: ${formatDouble(t.targetPrice).padStart(8)} → $rec (${formatPct(changePct)})")
                }
            }
        }
    }

    if (forecastCount == 0) {
        println("  Прогнозы не найдены")
    }

    // Summary
    printHeader("Итоговый рейтинг недооценённых бумаг (топ-${config.topN})")
    println("  Методология: взвешенная оценка P/E (×3), P/B (×2), EV/EBITDA (×2),")
    println("  Dividend Yield (×1), ROE (×1), D/E (×1). Максимум — 100 баллов.\n")

    topN.forEachIndexed { i, s ->
        println("  ${i + 1}. ${s.ticker} (${s.shortName}) — ${"%.0f".format(s.undervaluationScore)} баллов")
        println("     P/E: ${formatDouble(s.pe)} | P/B: ${formatDouble(s.pb)} | P/S: ${formatDouble(s.ps)} | EV/EBITDA: ${formatDouble(s.evEbitda)}")
        println("     D/E: ${formatDouble(s.de)} | ROE: ${formatPct(s.roe)} | ROIC: ${formatPct(s.roic)} | Див.: ${formatPct(s.divYield)}")
        println("     Рост выручки (5л): ${formatPct(s.revenueGrowth5y)} | Маржа: ${formatPct(s.netMargin)} | Beta: ${formatDouble(s.beta)}")
        println("     Капитализация: ${formatMoney(s.marketCap)}")
        println()
    }
}

main(args)
