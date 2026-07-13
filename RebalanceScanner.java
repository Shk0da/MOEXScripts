///usr/bin/env java "$0" "$@" ; exit $? // for Java 11+ single-file source-code programs

import java.io.*;
import java.math.*;
import java.net.*;
import java.net.http.*;
import java.security.*;
import java.security.cert.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;
import javax.net.ssl.*;

public class RebalanceScanner {

    static final Properties props;
    static final String TCS_API_KEY;
    static {
        props = new Properties();
        try { props.load(new FileInputStream(System.getProperty("user.dir") + "/application.properties")); }
        catch (Exception e) { System.out.println("  [WARN] loadProperties: " + e.getMessage()); }
        TCS_API_KEY = props.getProperty("tcs.apiKey", "");
    }

    static final Locale US = Locale.US;

    // ── Data classes ─────────────────────────────────────────────────

    static class TcsAccount {
        final String id, name, status, type;
        TcsAccount(String i, String n, String s, String t) { id = i; name = n; status = s; type = t; }
    }

    static class TcsPortfolioPosition {
        final String figi, instrumentType, ticker, classCode, uid;
        final double quantity, curPrice;
        final Double avgPrice;
        TcsPortfolioPosition(String f, String it, double q, Double ap, Double cp, String t, String cc, String u) {
            figi = f; instrumentType = it; quantity = q; avgPrice = ap; curPrice = cp; ticker = t; classCode = cc; uid = u;
        }
    }

    static class InstrumentInfo {
        final String figi, ticker, classCode, instrumentType, maturityDate, isin, uid;
        InstrumentInfo(String f, String t, String cc, String it, String md, String i, String u) {
            figi = f; ticker = t; classCode = cc; instrumentType = it; maturityDate = md; isin = i; uid = u;
        }
    }

    static class RebalanceTarget {
        final String instrumentType, ticker;
        final double weightPercent;
        RebalanceTarget(String it, String t, double w) { instrumentType = it; ticker = t; weightPercent = w; }
    }

    static class InstrumentAnalysis {
        final RebalanceTarget target;
        final InstrumentInfo instrument;
        final double currentQty, currentPrice, currentValue, targetValue, diffValue;
        final int diffQty;
        InstrumentAnalysis(RebalanceTarget t, InstrumentInfo i, double cq, double cp, double cv, double tv, double dv, int dq) {
            target = t; instrument = i; currentQty = cq; currentPrice = cp; currentValue = cv; targetValue = tv; diffValue = dv; diffQty = dq;
        }
    }

    static class RebalanceAction {
        final String type, ticker, figi, classCode, uid;
        final int quantity;
        final double price, amount;
        RebalanceAction(String tp, String t, String f, String cc, String u, int q, double p, double a) {
            type = tp; ticker = t; figi = f; classCode = cc; uid = u; quantity = q; price = p; amount = a;
        }
    }

    // ── SSL ──────────────────────────────────────────────────────────

    static SSLContext createTrustAllSSLContext() throws Exception {
        X509TrustManager trustAll = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{trustAll}, new SecureRandom());
        return ctx;
    }

    static HttpClient createHttpClient() {
        try { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).sslContext(createTrustAllSSLContext()).build(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    // ── JSON utilities ───────────────────────────────────────────────

    static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    static Double extractMoney(String json, String key) {
        int s = json.indexOf("\"" + key + "\"");
        if (s < 0) return null;
        String sub = json.substring(s);
        Matcher mu = Pattern.compile("\"units\"\\s*:\\s*\"?(\\d+)\"?").matcher(sub);
        if (!mu.find()) return null;
        long units = Long.parseLong(mu.group(1));
        Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(sub);
        long nano = mn.find() ? Long.parseLong(mn.group(1)) : 0L;
        return units + nano / 1_000_000_000.0;
    }

    static List<String> extractNestedArray(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return Collections.emptyList();
        String rest = json.substring(idx);
        int start = rest.indexOf('[');
        if (start < 0) return Collections.emptyList();
        int depth = 0; boolean inStr = false; int itemStart = -1;
        List<String> items = new ArrayList<>();
        for (int i = start + 1; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '"') inStr = !inStr;
            if (inStr) continue;
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth < 0) break; }
            else if (c == '{') { if (depth == 0 && itemStart < 0) itemStart = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && itemStart >= 0) { items.add(rest.substring(itemStart, i + 1)); itemStart = -1; } }
        }
        return items;
    }

    // ── Formatting ───────────────────────────────────────────────────

    static String fmt(double value, int decimals) {
        String pattern = decimals > 0 ? "%,." + decimals + "f" : "%,.0f";
        return String.format(US, pattern, value).replace(',', ' ');
    }
    static String fmt(double value) { return fmt(value, 0); }

    // ── TCS API Client ───────────────────────────────────────────────

    static final HttpClient HTTP = createHttpClient();

    static String post(String url, String body, int retries, boolean silent) {
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + TCS_API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 429) {
                    long wait = attempt < retries ? 3L : 0L;
                    if (!silent) System.out.println("  [WARN] Rate limit (429), попытка " + attempt + "/" + retries + ", жду " + wait + "с...");
                    Thread.sleep(wait * 1000L);
                    continue;
                }
                if (resp.statusCode() != 200) {
                    if (attempt == retries) {
                        System.out.println("  [ERROR] HTTP " + resp.statusCode() + " — " + url);
                        return null;
                    }
                    Thread.sleep(2000L);
                    continue;
                }
                Thread.sleep(150L);
                return resp.body();
            } catch (Exception e) {
                if (attempt == retries) {
                    System.out.println("  [ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    return null;
                }
                try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }
    static String post(String url, String body) { return post(url, body, 3, false); }

    static List<TcsAccount> getAccounts() {
        String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.UsersService/GetAccounts",
            "{\"status\": \"ACCOUNT_STATUS_OPEN\"}");
        if (json == null) return Collections.emptyList();
        List<TcsAccount> result = new ArrayList<>();
        for (String a : extractNestedArray(json, "accounts")) {
            result.add(new TcsAccount(extractString(a, "id"), extractString(a, "name"), extractString(a, "status"), extractString(a, "type")));
        }
        return result;
    }

    static List<TcsPortfolioPosition> getPortfolio(String accountId) {
        String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetPortfolio",
            "{\"accountId\": \"" + accountId + "\", \"currency\": \"RUB\"}");
        if (json == null) return Collections.emptyList();
        List<TcsPortfolioPosition> result = new ArrayList<>();
        for (String p : extractNestedArray(json, "positions")) {
            Double qty = extractMoney(p, "quantity");
            if (qty == null || qty == 0.0) continue;
            result.add(new TcsPortfolioPosition(
                extractString(p, "figi"), extractString(p, "instrumentType"), qty,
                extractMoney(p, "averagePositionPrice"), extractMoney(p, "currentPrice"),
                extractString(p, "ticker"), extractString(p, "classCode"), extractString(p, "instrumentUid")));
        }
        return result;
    }

    static double getWithdrawLimits(String accountId) {
        String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetWithdrawLimits",
            "{\"accountId\": \"" + accountId + "\"}", 3, true);
        if (json == null) return 0.0;
        return extractCashTotal(json) - extractBlockedMargin(json);
    }

    static List<InstrumentInfo> findAllInstruments(String query) {
        String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/FindInstrument",
            "{\"query\": \"" + query + "\"}", 2, false);
        if (json == null) return Collections.emptyList();
        List<InstrumentInfo> result = new ArrayList<>();
        for (String inst : extractNestedArray(json, "instruments")) {
            result.add(new InstrumentInfo(
                extractString(inst, "figi"), extractString(inst, "ticker"), extractString(inst, "classCode"),
                extractString(inst, "instrumentType"), extractString(inst, "maturityDate"),
                extractString(inst, "isin"), extractString(inst, "uid")));
        }
        return result;
    }

    static double[] getOrderBookBestPrices(String instrumentId) {
        String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetOrderBook",
            "{\"depth\": 1, \"instrumentId\": \"" + instrumentId + "\"}", 2, false);
        if (json == null) return null;
        List<String> bids = extractNestedArray(json, "bids");
        List<String> asks = extractNestedArray(json, "asks");
        if (bids.isEmpty() || asks.isEmpty()) return null;
        Double bestBid = extractMoney(bids.get(0), "price");
        Double bestAsk = extractMoney(asks.get(0), "price");
        if (bestBid == null || bestAsk == null || bestBid <= 0 || bestAsk <= 0) return null;
        return new double[]{bestBid, bestAsk};
    }

    static Double getOrderBookMidPrice(String instrumentId) {
        double[] prices = getOrderBookBestPrices(instrumentId);
        if (prices == null) return null;
        return (prices[0] + prices[1]) / 2.0;
    }

    static Map<String, Double> getLastPrices(List<String> instIds) {
        if (instIds.isEmpty()) return Collections.emptyMap();
        StringBuilder sb = new StringBuilder();
        for (String id : instIds) { if (sb.length() > 0) sb.append(","); sb.append("\"").append(id).append("\""); }
        String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetLastPrices",
            "{\"instrumentId\": [" + sb + "]}", 2, false);
        if (json == null) return Collections.emptyMap();
        Map<String, Double> result = new LinkedHashMap<>();
        for (String p : extractNestedArray(json, "lastPrices")) {
            String figi = extractString(p, "figi");
            String uid = extractString(p, "uid");
            Double price = extractMoney(p, "price");
            if (price == null) continue;
            String key = uid.isEmpty() ? figi : uid;
            if (!key.isEmpty()) result.put(key, price);
        }
        return result;
    }

    static Double getCandlesByUid(String uid) {
        String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetCandles",
            "{\"instrumentId\": \"" + uid + "\", \"interval\": \"CANDLE_INTERVAL_DAY\", \"limit\": 1}", 2, true);
        if (json == null) return null;
        List<String> candles = extractNestedArray(json, "candles");
        if (candles.isEmpty()) return null;
        return extractMoney(candles.get(0), "close");
    }

    static String postOrder(String accountId, String figi, long quantity, String direction, String ticker, String classCode, String uid) {
        String instrumentId = "";
        if (!ticker.isEmpty() && !classCode.isEmpty()) instrumentId = ticker + "_" + classCode;
        else if (!figi.isEmpty()) instrumentId = figi;
        else if (!uid.isEmpty()) instrumentId = uid;
        if (instrumentId.isEmpty()) return null;
        String body = "{\"accountId\": \"" + accountId + "\", \"instrumentId\": \"" + instrumentId + "\", \"quantity\": " + quantity + ", \"direction\": \"" + direction + "\", \"orderType\": \"ORDER_TYPE_MARKET\", \"orderId\": \"" + System.currentTimeMillis() + "\"}";
        System.out.println("  [API] PostOrder: " + direction + " " + quantity + " x " + (ticker.isEmpty() ? figi : ticker));
        String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OrdersService/PostOrder", body, 2, false);
        if (json == null) return null;
        String rejects = extractString(json, "rejectReason");
        if (!rejects.isEmpty()) {
            System.out.println("  [ERROR] Ордер отклонён: " + rejects);
            return null;
        }
        return extractString(json, "orderId");
    }

    static double extractCashTotal(String json) {
        int moneyArr = json.indexOf("\"money\"");
        if (moneyArr < 0) return 0.0;
        int bracketStart = json.indexOf('[', moneyArr);
        int bracketEnd = json.indexOf(']', bracketStart);
        if (bracketStart < 0 || bracketEnd < 0) return 0.0;
        String content = json.substring(bracketStart + 1, bracketEnd);
        int depth = 0; int objStart = -1; double total = 0.0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') { if (depth == 0) objStart = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = content.substring(objStart, i + 1);
                    String currency = extractString(obj, "currency");
                    Double money = extractMoney(obj, "units");
                    if (money == null) money = 0.0;
                    Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(obj);
                    long nano = mn.find() ? Long.parseLong(mn.group(1)) : 0L;
                    if (currency.equalsIgnoreCase("rub")) total += money + nano / 1_000_000_000.0;
                    objStart = -1;
                }
            }
        }
        return total;
    }

    static double extractBlockedMargin(String json) {
        double total = 0.0;
        for (String key : new String[]{"blockedGuarantee", "blocked"}) {
            int arr = json.indexOf("\"" + key + "\"");
            if (arr < 0) continue;
            int bracketStart = -1;
            for (int i = arr; i < json.length(); i++) { if (json.charAt(i) == '[') { bracketStart = i; break; } }
            if (bracketStart < 0) continue;
            int bracketEnd = -1; int depth = 0;
            for (int i = bracketStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') { depth--; if (depth == 0) { bracketEnd = i; break; } }
            }
            if (bracketEnd < 0) continue;
            String content = json.substring(bracketStart + 1, bracketEnd);
            int objDepth = 0; int objStart = -1;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') { if (objDepth == 0) objStart = i; objDepth++; }
                else if (c == '}') {
                    objDepth--;
                    if (objDepth == 0 && objStart >= 0) {
                        String obj = content.substring(objStart, i + 1);
                        String currency = extractString(obj, "currency");
                        Double units = extractMoney(obj, "units");
                        if (units == null) units = 0.0;
                        Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(obj);
                        long nano = mn.find() ? Long.parseLong(mn.group(1)) : 0L;
                        if (currency.equalsIgnoreCase("rub")) total += units + nano / 1_000_000_000.0;
                        objStart = -1;
                    }
                }
            }
        }
        return total;
    }

    // ── Rebalance config parser ──────────────────────────────────────

    static List<RebalanceTarget> parseRebalanceConfig(String config) {
        List<RebalanceTarget> result = new ArrayList<>();
        for (String entry : config.split(";")) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;
            String[] parts = entry.split(":");
            if (parts.length != 3) {
                System.out.println("  [WARN] Неверный формат rebalance: '" + entry + "' (ожидается TYPE:TICKER:WEIGHT)");
                continue;
            }
            String type = parts[0].trim().toUpperCase();
            String ticker = parts[1].trim().toUpperCase();
            try {
                double weight = Double.parseDouble(parts[2].trim());
                if (weight <= 0) { System.out.println("  [WARN] Неверный вес для " + ticker); continue; }
                result.add(new RebalanceTarget(type, ticker, weight));
            } catch (NumberFormatException e) {
                System.out.println("  [WARN] Неверный вес для " + ticker + ": '" + parts[2] + "'");
            }
        }
        return result;
    }

    // ── Rebalance logic ──────────────────────────────────────────────

    static InstrumentInfo findBestInstrument(List<InstrumentInfo> instruments) {
        List<String> preferred = Arrays.asList("TQBR", "SPBRU");
        for (InstrumentInfo inst : instruments) {
            if (preferred.contains(inst.classCode)) return inst;
        }
        return instruments.isEmpty() ? null : instruments.get(0);
    }

    static Object[] calculateRebalance(List<RebalanceTarget> targets, List<TcsPortfolioPosition> positions,
                                        double cash, InstrumentInfo tmonInstrument) {
        String tmonFigiDefault = "TCS70A106DL2";
        String tmonClassCodeDefault = "SPBRU";

        // Find TMON@ in portfolio
        TcsPortfolioPosition tmonPosition = null;
        String tmonFigi = tmonInstrument != null ? tmonInstrument.figi : tmonFigiDefault;
        String tmonClassCode = tmonInstrument != null ? tmonInstrument.classCode : tmonClassCodeDefault;
        String tmonUid = tmonInstrument != null ? tmonInstrument.uid : "";
        for (TcsPortfolioPosition pos : positions) {
            if (pos.figi.equals(tmonFigi) || pos.ticker.equalsIgnoreCase("TMON@") ||
                (pos.instrumentType.equalsIgnoreCase("ETF") && pos.figi.contains("TMON"))) {
                tmonPosition = pos;
                break;
            }
        }

        // Calculate total portfolio value
        double totalSecuritiesValue = 0.0, cashInPortfolio = 0.0;
        for (TcsPortfolioPosition pos : positions) {
            double value = pos.quantity * (pos.curPrice > 0 ? pos.curPrice : 0.0);
            if (pos.instrumentType.equalsIgnoreCase("CURRENCY")) cashInPortfolio += value;
            else if (!pos.instrumentType.equalsIgnoreCase("FUTURES")) totalSecuritiesValue += value;
        }
        double totalPortfolioValue = totalSecuritiesValue + cashInPortfolio;
        double totalWeight = 0.0;
        for (RebalanceTarget t : targets) totalWeight += t.weightPercent;

        System.out.println("  Общая стоимость портфеля: " + fmt(totalPortfolioValue, 2) + " руб.");
        System.out.println("  Кэш в портфеле: " + fmt(cashInPortfolio, 2) + " руб.");
        System.out.println("  Ценные бумаги: " + fmt(totalSecuritiesValue, 2) + " руб.");
        System.out.printf("  Суммарный вес целей: %.1f%%\n", totalWeight);

        if (totalWeight > 100) {
            System.out.println("  [ERROR] Суммарный вес целей превышает 100%!");
            return new Object[]{Collections.emptyList(), Collections.emptyList()};
        }

        // Find instruments and get prices
        List<InstrumentAnalysis> analyses = new ArrayList<>();
        System.out.println();
        System.out.println("  Поиск инструментов в API и получение цен из стакана...");
        System.out.println();

        for (RebalanceTarget target : targets) {
            List<InstrumentInfo> allInstruments = findAllInstruments(target.ticker);
            if (allInstruments.isEmpty()) {
                System.out.println("  [WARN] Инструмент " + target.ticker + " (" + target.instrumentType + ") не найден в API");
                analyses.add(new InstrumentAnalysis(target, null, 0, 0, 0, totalPortfolioValue * target.weightPercent / 100.0, 0, 0));
                continue;
            }

            InstrumentInfo instrument = findBestInstrument(allInstruments);
            System.out.println("  " + target.ticker + ": classCode=" + instrument.classCode + ", figi=" + instrument.figi);

            String instrumentId = instrument.ticker + "_" + instrument.classCode;

            // Get price from order book
            Double midPrice = getOrderBookMidPrice(instrumentId);
            double price;
            if (midPrice != null) {
                System.out.printf("    %s: стакан mid = %.2f руб.\n", target.ticker, midPrice);
                price = midPrice;
            } else {
                Map<String, Double> lastPrices = getLastPrices(Collections.singletonList(instrumentId));
                Double fallback = lastPrices.get(instrumentId);
                if (fallback != null) {
                    System.out.printf("    %s: последняя цена = %.2f руб. (стакан недоступен)\n", target.ticker, fallback);
                    price = fallback;
                } else {
                    Double candlePrice = !instrument.uid.isEmpty() ? getCandlesByUid(instrument.uid) : null;
                    if (candlePrice != null) {
                        System.out.printf("    %s: цена свечи = %.2f руб. (стакан недоступен)\n", target.ticker, candlePrice);
                        price = candlePrice;
                    } else {
                        System.out.println("    " + target.ticker + ": [WARN] цена недоступна!");
                        price = 0.0;
                    }
                }
            }

            // Current position
            TcsPortfolioPosition pos = null;
            for (TcsPortfolioPosition p : positions) {
                if (p.ticker.equalsIgnoreCase(target.ticker) || p.figi.equals(instrument.figi)) { pos = p; break; }
            }
            double currentQty = pos != null ? pos.quantity : 0.0;
            double currentPriceForVal = pos != null && pos.curPrice > 0 ? pos.curPrice : price;
            double currentValue = currentQty * currentPriceForVal;
            double targetValue = totalPortfolioValue * target.weightPercent / 100.0;
            double diffValue = currentValue - targetValue;
            int diffQty = price > 0 ? (int)(diffValue / price) : 0;

            analyses.add(new InstrumentAnalysis(target, instrument, currentQty, price, currentValue, targetValue, diffValue, diffQty));
        }

        // TMON@ calculation
        double tmonTargetWeight = 100.0 - totalWeight;
        double tmonTargetValue = totalPortfolioValue * tmonTargetWeight / 100.0;
        double tmonPositionValue = tmonPosition != null ? tmonPosition.quantity * (tmonPosition.curPrice > 0 ? tmonPosition.curPrice : 0.0) : 0.0;

        String tmonInstrumentId = !tmonUid.isEmpty() ? tmonUid : (!tmonFigi.isEmpty() ? tmonFigi : "TMON@_SPBRU");
        Double tmonPrice = getOrderBookMidPrice(tmonInstrumentId);
        if (tmonPrice == null) {
            Map<String, Double> lastPrices = getLastPrices(Collections.singletonList(tmonInstrumentId));
            tmonPrice = lastPrices.get(tmonInstrumentId);
        }
        if (tmonPrice == null && !tmonUid.isEmpty()) tmonPrice = getCandlesByUid(tmonUid);
        if (tmonPrice == null) tmonPrice = 0.0;

        if (tmonTargetWeight > 0) {
            System.out.printf("    TMON@: стакан mid = %.2f руб.%s\n", tmonPrice, tmonPrice <= 0 ? " [WARN] цена недоступна!" : "");
        }

        double tmonDiffValue = tmonPositionValue - tmonTargetValue;
        int tmonDiffQty = tmonPrice > 0 ? (int)(tmonDiffValue / tmonPrice) : 0;

        // Build actions
        List<RebalanceAction> actions = new ArrayList<>();
        double totalBuyAmount = 0.0;

        for (InstrumentAnalysis a : analyses) {
            if (a.diffQty < 0 && a.instrument != null) {
                double amount = (-a.diffQty) * a.currentPrice;
                totalBuyAmount += amount;
                actions.add(new RebalanceAction("BUY", a.target.ticker, a.instrument.figi, a.instrument.classCode, a.instrument.uid, -a.diffQty, a.currentPrice, amount));
            }
        }

        if (totalBuyAmount > 0) {
            if (cash >= totalBuyAmount) {
                System.out.println("  [INFO] Кэша достаточно (" + fmt(cash) + " р.) для покупок на " + fmt(totalBuyAmount) + " р.");
            } else {
                double deficit = totalBuyAmount - cash;
                int tmonSellQty = tmonPrice > 0 ? Math.max((int)(deficit / tmonPrice), 1) : 0;
                double tmonSellAmount = tmonSellQty * tmonPrice;
                if (tmonSellQty > 0 && tmonSellAmount > 0) {
                    System.out.println("  [INFO] Кэша недостаточно: нужно " + fmt(totalBuyAmount) + " р., есть " + fmt(cash) + " р.");
                    System.out.println("  [INFO] Продаем " + tmonSellQty + " шт. TMON@ на " + fmt(tmonSellAmount) + " р.");
                    actions.add(new RebalanceAction("SELL", "TMON@", tmonFigi, tmonClassCode, tmonUid, tmonSellQty, tmonPrice, tmonSellAmount));
                }
            }
        }

        for (InstrumentAnalysis a : analyses) {
            if (a.diffQty > 0 && a.instrument != null) {
                System.out.println("  [INFO] " + a.target.ticker + ": избыточная доля +" + fmt(a.diffValue) + " р. (" + (int)a.currentQty + " шт). Бумаги не продаются — остаются в портфеле.");
            }
        }

        double totalSellAmount = 0.0;
        for (RebalanceAction a : actions) { if (a.type.equals("SELL")) totalSellAmount += a.amount; }
        double cashAfterBuysAndSells = cash + totalSellAmount - totalBuyAmount;
        if (cashAfterBuysAndSells > tmonPrice && tmonPrice > 0) {
            int tmonBuyQty = (int)(cashAfterBuysAndSells / tmonPrice);
            double tmonBuyAmount = tmonBuyQty * tmonPrice;
            if (tmonBuyQty > 0) {
                System.out.println("  [INFO] Остаток кэша " + fmt(cashAfterBuysAndSells) + " р. → докупаем " + tmonBuyQty + " шт. TMON@");
                actions.add(new RebalanceAction("BUY", "TMON@", tmonFigi, tmonClassCode, tmonUid, tmonBuyQty, tmonPrice, tmonBuyAmount));
            }
        }

        if (tmonTargetWeight > 0) {
            analyses.add(new InstrumentAnalysis(
                new RebalanceTarget("ETF", "TMON@", tmonTargetWeight), tmonInstrument,
                tmonPosition != null ? tmonPosition.quantity : 0.0, tmonPrice, tmonPositionValue,
                tmonTargetValue, tmonDiffValue, tmonDiffQty));
        }

        return new Object[]{analyses, actions};
    }

    // ── Report ───────────────────────────────────────────────────────

    static void printRebalanceReport(List<InstrumentAnalysis> analyses, List<RebalanceAction> actions) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  ОТЧЕТ ПО РЕБАЛАНСИРОВКЕ ПОРТФЕЛЯ");
        System.out.println("================================================================");
        System.out.println();

        System.out.printf("  %-8s  %-10s  %15s  %15s  %15s  %12s  %6s\n", "ТИП", "ТИКЕР", "ТЕКУЩАЯ", "ЦЕЛЕВАЯ", "РАЗНИЦА", "ЦЕНА", "ВЕС%");
        System.out.println("  " + "-".repeat(80));

        for (InstrumentAnalysis a : analyses) {
            String sign = a.diffValue > 0 ? "+" : "";
            System.out.printf("  %-8s  %-10s  %15s  %15s  %s%14s  %12s  %5.1f%%\n",
                a.target.instrumentType, a.target.ticker,
                fmt(a.currentValue) + " р.", fmt(a.targetValue) + " р.",
                sign, fmt(a.diffValue) + " р.",
                a.currentPrice > 0 ? fmt(a.currentPrice, 2) + " р." : "—",
                a.target.weightPercent);
        }
        System.out.println();

        double totalCurrent = 0.0, totalTarget = 0.0;
        for (InstrumentAnalysis a : analyses) { totalCurrent += a.currentValue; totalTarget += a.targetValue; }
        System.out.println("  ИТОГО:  текущая=" + fmt(totalCurrent) + " р.  целевая=" + fmt(totalTarget) + " р.");

        if (actions.isEmpty()) {
            System.out.println();
            System.out.println("  [OK] Портфель сбалансирован. Ребалансировка не требуется.");
        } else {
            System.out.println();
            System.out.println("================================================================");
            System.out.println("  ДЕЙСТВИЯ ДЛЯ РЕБАЛАНСИРОВКИ");
            System.out.println("================================================================");
            System.out.println();

            List<RebalanceAction> sells = new ArrayList<>(), buys = new ArrayList<>();
            for (RebalanceAction a : actions) { if (a.type.equals("SELL")) sells.add(a); else buys.add(a); }

            if (!sells.isEmpty()) {
                System.out.println("  ПРОДАЖА:");
                for (RebalanceAction a : sells) {
                    System.out.printf("    %-6s  %-10s  %5d шт  x  %12s р.  =  %12s р.\n",
                        a.type, a.ticker, a.quantity, fmt(a.price, 2), fmt(a.amount));
                }
                double totalSell = 0.0; for (RebalanceAction a : sells) totalSell += a.amount;
                System.out.println("  Итого продажа: " + fmt(totalSell) + " руб.");
                System.out.println();
            }

            if (!buys.isEmpty()) {
                System.out.println("  ПОКУПКА:");
                for (RebalanceAction a : buys) {
                    System.out.printf("    %-6s  %-10s  %5d шт  x  %12s р.  =  %12s р.\n",
                        a.type, a.ticker, a.quantity, fmt(a.price, 2), fmt(a.amount));
                }
                double totalBuy = 0.0; for (RebalanceAction a : buys) totalBuy += a.amount;
                System.out.println("  Итого покупка: " + fmt(totalBuy) + " руб.");
            }

            System.out.println();
            System.out.println("  [!] Бумаги не продаются. Для покупок продается TMON@ (денежный буфер)");
            System.out.println("================================================================");
        }
    }

    // ── Execute rebalancing ──────────────────────────────────────────

    static void executeRebalancing(String accountId, List<RebalanceAction> actions) {
        if (actions.isEmpty()) return;
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  ВЫПОЛНЕНИЕ РЕБАЛАНСИРОВКИ");
        System.out.println("================================================================");

        for (RebalanceAction a : actions) {
            if (a.type.equals("SELL")) {
                System.out.printf("  [SELL] %s: %d шт по %s р.\n", a.ticker, a.quantity, fmt(a.price, 2));
                String orderId = postOrder(accountId, a.figi, a.quantity, "ORDER_DIRECTION_SELL", a.ticker, a.classCode, a.uid);
                if (orderId != null) System.out.println("  [OK] Ордер: " + orderId);
                else System.out.println("  [WARN] Не удалось разместить ордер на продажу " + a.ticker);
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }

        for (RebalanceAction a : actions) {
            if (a.type.equals("BUY")) {
                System.out.printf("  [BUY] %s: %d шт по %s р.\n", a.ticker, a.quantity, fmt(a.price, 2));
                String orderId = postOrder(accountId, a.figi, a.quantity, "ORDER_DIRECTION_BUY", a.ticker, a.classCode, a.uid);
                if (orderId != null) System.out.println("  [OK] Ордер: " + orderId);
                else System.out.println("  [WARN] Не удалось разместить ордер на покупку " + a.ticker);
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }

        System.out.println("  [OK] Ребалансировка завершена.");
    }

    // ── Main ─────────────────────────────────────────────────────────

    public static void main(String[] args) {
        boolean autoMode = Arrays.asList(args).contains("--auto");
        String overrideAccountId = null;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--account")) overrideAccountId = args[++i];
        }

        System.out.println("================================================================");
        System.out.println("  REBALANCE SCANNER (Ребалансировка портфеля)");
        System.out.println("  Дата: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        if (autoMode) System.out.println("  РЕЖИМ: АВТОМАТИЧЕСКАЯ РЕБАЛАНСИРОВКА");
        System.out.println("================================================================");
        System.out.println();

        if (TCS_API_KEY.isEmpty()) {
            System.out.println("  [ERROR] Не настроен T-Invest API ключ в application.properties");
            System.exit(1);
        }

        // Determine account
        String accountId = overrideAccountId != null ? overrideAccountId : props.getProperty("tcs.accountId", "");
        if (accountId.isEmpty()) {
            System.out.println("  [WARN] Аккаунт не указан. Загрузка списка счетов...");
            List<TcsAccount> accounts = getAccounts();
            if (accounts.isEmpty()) {
                System.out.println("  [ERROR] Нет доступных счетов");
                System.exit(1);
            }
            System.out.println();
            System.out.println("  Доступные счета:");
            for (int i = 0; i < accounts.size(); i++) {
                TcsAccount acc = accounts.get(i);
                System.out.println("    " + (i + 1) + ". " + acc.name + " [" + acc.type + "] (" + acc.id + ")");
            }
            System.out.print("\n  Выберите номер счета: ");
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                int choice = Integer.parseInt(reader.readLine().trim());
                if (choice < 1 || choice > accounts.size()) { System.out.println("  [ERROR] Неверный выбор"); System.exit(1); }
                accountId = accounts.get(choice - 1).id;
                System.out.println("  Выбран: " + accounts.get(choice - 1).name + " (" + accountId + ")");
            } catch (Exception e) { System.out.println("  [ERROR] Неверный ввод"); System.exit(1); }
        }
        System.out.println("  Аккаунт: " + accountId);
        if (overrideAccountId != null) System.out.println("  [INFO] Используется аккаунт из параметров командной строки (--account)");

        // Load rebalance config
        String rebalanceConfig = props.getProperty("tcs.rebalance", "");
        if (rebalanceConfig.isEmpty()) {
            System.out.println("  [ERROR] tcs.rebalance не настроен в application.properties");
            System.out.println("  Формат: TYPE:TICKER:WEIGHT;TYPE:TICKER:WEIGHT;...");
            System.exit(1);
        }

        List<RebalanceTarget> targets = parseRebalanceConfig(rebalanceConfig);
        if (targets.isEmpty()) {
            System.out.println("  [ERROR] Нет валидных целей в tcs.rebalance");
            System.exit(1);
        }

        System.out.println();
        System.out.println("  Цели ребалансировки:");
        for (RebalanceTarget t : targets) System.out.printf("    %s  %s  →  %.1f%%\n", t.instrumentType, t.ticker, t.weightPercent);

        // Load portfolio
        System.out.println();
        System.out.println("  Загрузка портфеля...");
        List<TcsPortfolioPosition> positions = getPortfolio(accountId);
        double cash = getWithdrawLimits(accountId);

        System.out.println("  Позиций в портфеле: " + positions.size());
        System.out.println("  Свободный кэш: " + fmt(cash, 2) + " руб.");

        if (!positions.isEmpty()) {
            System.out.println();
            System.out.println("  Состав портфеля:");
            for (TcsPortfolioPosition pos : positions) {
                if (pos.quantity <= 0) continue;
                double value = pos.quantity * (pos.curPrice > 0 ? pos.curPrice : 0.0);
                System.out.printf("    %s (%s): %.0f шт x %s р. = %s р.\n",
                    pos.ticker.isEmpty() ? pos.figi : pos.ticker, pos.instrumentType,
                    pos.quantity, fmt(pos.curPrice > 0 ? pos.curPrice : 0.0, 2), fmt(value));
            }
        }

        // Find TMON@
        System.out.println();
        System.out.println("  Поиск TMON@ (фонд денежного рынка)...");
        List<InstrumentInfo> tmonAll = findAllInstruments("TMON@");
        InstrumentInfo tmonInstrument = null;
        for (InstrumentInfo inst : tmonAll) { if (inst.classCode.equals("SPBRU")) { tmonInstrument = inst; break; } }
        if (tmonInstrument == null && !tmonAll.isEmpty()) tmonInstrument = tmonAll.get(0);
        if (tmonInstrument != null) {
            System.out.println("  TMON@: ticker=" + tmonInstrument.ticker + ", figi=" + tmonInstrument.figi + ", classCode=" + tmonInstrument.classCode);
        } else {
            System.out.println("  [WARN] TMON@ не найден через API, используются значения по умолчанию");
        }

        // Calculate rebalance
        System.out.println();
        System.out.println("  Анализ портфеля и расчет ребалансировки...");
        Object[] result = calculateRebalance(targets, positions, cash, tmonInstrument);
        @SuppressWarnings("unchecked")
        List<InstrumentAnalysis> analyses = (List<InstrumentAnalysis>) result[0];
        @SuppressWarnings("unchecked")
        List<RebalanceAction> actions = (List<RebalanceAction>) result[1];

        // Print report
        printRebalanceReport(analyses, actions);

        // Execute if auto mode
        if (autoMode && !actions.isEmpty()) {
            executeRebalancing(accountId, actions);
        }
    }
}
