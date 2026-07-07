///usr/bin/env java "$0" "$@" ; exit $? // for Java 11+ single-file source-code programs

import java.io.*;
import java.math.*;
import java.net.*;
import java.net.http.*;
import java.security.*;
import java.security.cert.*;
import java.time.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.*;
import javax.net.ssl.*;

public class NasdScanner {

    static final BigDecimal CASH_TARGET_PERCENT = new BigDecimal("0.10");
    static final BigDecimal BROKER_COLLATERAL_DISCOUNT = new BigDecimal("0.10");

    // ── Data classes ────────────────────────────────────────────────

    static class TcsAccount {
        final String id, name, status, type;
        TcsAccount(String id, String name, String status, String type) {
            this.id = id; this.name = name; this.status = status; this.type = type;
        }
    }

    static class TcsPortfolioPosition {
        final String figi, instrumentType, ticker, classCode;
        final double quantity;
        final Double avgPrice, curPrice;
        TcsPortfolioPosition(String figi, String instrumentType, double quantity,
                             Double avgPrice, Double curPrice, String ticker, String classCode) {
            this.figi = figi; this.instrumentType = instrumentType; this.quantity = quantity;
            this.avgPrice = avgPrice; this.curPrice = curPrice; this.ticker = ticker; this.classCode = classCode;
        }
    }

    static class InstrumentInfo {
        final String figi, ticker, classCode, instrumentType, maturityDate, isin, uid;
        InstrumentInfo(String figi, String ticker, String classCode, String instrumentType,
                       String maturityDate, String isin, String uid) {
            this.figi = figi; this.ticker = ticker; this.classCode = classCode;
            this.instrumentType = instrumentType; this.maturityDate = maturityDate;
            this.isin = isin; this.uid = uid;
        }
    }

    static class RebalanceAction {
        final String type, figi, ticker, classCode, uid;
        final int quantity;
        final double price, amount;
        RebalanceAction(String type, String figi, String ticker, String classCode, String uid,
                        int quantity, double price, double amount) {
            this.type = type; this.figi = figi; this.ticker = ticker; this.classCode = classCode;
            this.uid = uid; this.quantity = quantity; this.price = price; this.amount = amount;
        }
    }

    // ── Utility ─────────────────────────────────────────────────────

    static Properties loadProperties() {
        Properties props = new Properties();
        try {
            props.load(new FileInputStream(System.getProperty("user.dir") + "/application.properties"));
        } catch (Exception e) {
            System.out.println("  [WARN] Не удалось загрузить application.properties: " + e.getMessage());
        }
        return props;
    }

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
        try {
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .sslContext(createTrustAllSSLContext())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    static Double extractMoney(String json, String key) {
        int start = json.indexOf("\"" + key + "\"");
        if (start < 0) return null;
        String sub = json.substring(start);
        Matcher mu = Pattern.compile("\"units\"\\s*:\\s*\"?(\\d+)\"?").matcher(sub);
        if (!mu.find()) return null;
        long units = Long.parseLong(mu.group(1));
        Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(sub);
        long nano = mn.find() ? Long.parseLong(mn.group(1)) : 0L;
        return units + nano / 1_000_000_000.0;
    }

    static double readDouble(String prompt) {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print(prompt);
            try {
                String line = br.readLine();
                if (line == null) continue;
                double v = Double.parseDouble(line.trim().replace(",", "."));
                if (v > 0) return v;
            } catch (Exception ignored) {}
            System.out.println("  [ОШИБКА] Введите положительное число.");
        }
    }

    static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    static double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    static String fmt(double v) {
        return String.format("%,.2f", v).replace(',', ' ');
    }

    static String fmtDot(double v) {
        return String.format("%.2f", v).replace(',', '.');
    }

    static <T> List<T> distinctBy(List<T> list, Function<T, ?> key) {
        Set<Object> seen = new HashSet<>();
        List<T> out = new ArrayList<>();
        for (T item : list) { if (seen.add(key.apply(item))) out.add(item); }
        return out;
    }

    static <T, R> List<R> mapNotNull(List<T> list, Function<T, R> fn) {
        List<R> out = new ArrayList<>();
        for (T item : list) { R r = fn.apply(item); if (r != null) out.add(r); }
        return out;
    }

    static <T> T firstOrNull(List<T> list, Predicate<T> pred) {
        for (T item : list) { if (pred.test(item)) return item; }
        return null;
    }

    // ── TcsClient ───────────────────────────────────────────────────

    static class TcsClient {
        private final HttpClient http;
        private final String apiKey;

        TcsClient(String apiKey) {
            this.apiKey = apiKey;
            this.http = createHttpClient();
        }

        String post(String url, String body, int retries, boolean silent) {
            for (int attempt = 1; attempt <= retries; attempt++) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

                    if (resp.statusCode() == 429) {
                        long wait = attempt < retries ? 3L : 0L;
                        if (!silent) {
                            System.out.println("  [WARN] Rate limit (429), попытка " + attempt + "/" + retries + ", жду " + wait + "0 сек...");
                            System.out.println("  [WARN] Request: " + url);
                            System.out.println("  [WARN] Request body: " + body);
                        }
                        Thread.sleep(wait * 1000L);
                        continue;
                    }
                    if (resp.statusCode() != 200) {
                        if (attempt == retries) {
                            System.out.println("  [ERROR] HTTP " + resp.statusCode());
                            System.out.println("  [ERROR] Request: " + url);
                            System.out.println("  [ERROR] Request body: " + body);
                            System.out.println("  [ERROR] Response: " + resp.body());
                            return null;
                        }
                        Thread.sleep(2000L);
                        continue;
                    }
                    Thread.sleep(150L);
                    return resp.body();
                } catch (Exception e) {
                    if (attempt == retries) {
                        System.out.println("  [ERROR] TCS API exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        System.out.println("  [ERROR] Request: " + url);
                        System.out.println("  [ERROR] Request body: " + body);
                        e.printStackTrace();
                        return null;
                    }
                    try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
                }
            }
            return null;
        }

        String post(String url, String body) { return post(url, body, 3, false); }
        String post(String url, String body, int retries) { return post(url, body, retries, false); }

        List<TcsAccount> getAccounts() {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.UsersService/GetAccounts",
                    "{\"status\": \"ACCOUNT_STATUS_OPEN\"}");
            return json == null ? Collections.emptyList() : parseAccounts(json);
        }

        List<TcsPortfolioPosition> getPortfolio(String accountId) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetPortfolio",
                    "{\"accountId\": \"" + accountId + "\", \"currency\": \"RUB\"}");
            return json == null ? Collections.emptyList() : parsePortfolioPositions(json);
        }

        double getWithdrawLimits(String accountId) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetWithdrawLimits",
                    "{\"accountId\": \"" + accountId + "\"}", 3, true);
            if (json == null) return 0.0;
            double totalCash = extractCashTotal(json);
            double blocked = extractBlockedMargin(json);
            if (blocked > 0.0) {
                System.out.println("  Кэш (всего): " + fmt(totalCash) + " руб");
                System.out.println("  Заблокировано (ГО): " + fmt(blocked) + " руб");
                System.out.println("  Свободный кэш: " + fmt(totalCash - blocked) + " руб");
            }
            return totalCash - blocked;
        }

        InstrumentInfo findInstrument(String query) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/FindInstrument",
                    "{\"query\": \"" + query + "\"}", 2);
            return json == null ? null : parseFindInstrument(json);
        }

        List<InstrumentInfo> findAllInstruments(String query) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/FindInstrument",
                    "{\"query\": \"" + query + "\"}", 2);
            return json == null ? Collections.emptyList() : parseFindAllInstruments(json);
        }

        InstrumentInfo findByIsin(String isin) {
            List<InstrumentInfo> byTicker = findAllInstruments("TMON@");
            InstrumentInfo match = firstOrNull(byTicker, i -> isin.equals(i.isin));
            if (match != null) return match;
            List<InstrumentInfo> byIsin = findAllInstruments(isin);
            return firstOrNull(byIsin, i -> isin.equals(i.isin));
        }

        List<InstrumentInfo> findBestNasdaqFutures() {
            List<InstrumentInfo> candidates = new ArrayList<>();
            for (String q : List.of("NAZ", "NQ", "NASD")) {
                candidates.addAll(findAllInstruments(q));
            }
            candidates = distinctBy(candidates, i -> i.figi);
            LocalDate today = LocalDate.now();
            List<InstrumentInfo> nasdFutures = new ArrayList<>();
            for (InstrumentInfo inst : candidates) {
                if ("SPBFUT".equals(inst.classCode) && inst.figi.toUpperCase().contains("NASD")) {
                    LocalDate expiry = parseMoexFuturesExpiry(inst.figi);
                    if (expiry != null && expiry.isAfter(today)) {
                        nasdFutures.add(inst);
                    }
                }
            }
            nasdFutures.sort(Comparator.comparing(i -> parseMoexFuturesExpiry(i.figi)));
            return nasdFutures;
        }

        InstrumentInfo findNearestNasdaqFutures() {
            List<InstrumentInfo> list = findBestNasdaqFutures();
            return list.isEmpty() ? null : list.get(0);
        }

        Double getFuturesMargin(String figi) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetFuturesMargin",
                    "{\"instrumentId\": \"" + figi + "\"}", 2, true);
            return json == null ? null : extractMoney(json, "initialMarginOnSell");
        }

        Map<String, Double> getLastPrices(List<String> instIds) {
            if (instIds.isEmpty()) return Collections.emptyMap();
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < instIds.size(); i++) {
                if (i > 0) ids.append(",");
                ids.append("\"").append(instIds.get(i)).append("\"");
            }
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetLastPrices",
                    "{\"instrumentId\": [" + ids + "]}", 2);
            return json == null ? Collections.emptyMap() : parseLastPrices(json);
        }

        Double getLastCandle(String figi) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetCandles",
                    "{\"figi\": \"" + figi + "\", \"interval\": \"CANDLE_INTERVAL_DAY\", \"limit\": 1}", 2, true);
            return json == null ? null : parseLastCandleClose(json);
        }

        Double getCandlesByUid(String uid) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetCandles",
                    "{\"instrumentId\": \"" + uid + "\", \"interval\": \"CANDLE_INTERVAL_DAY\", \"limit\": 1}", 2, true);
            return json == null ? null : parseLastCandleClose(json);
        }

        String postOrder(String accountId, String figi, long quantity, String direction,
                         String orderType, Double price, String ticker, String classCode, String uid) {
            String url = "https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OrdersService/PostOrder";
            String priceField = (price != null && "ORDER_TYPE_LIMIT".equals(orderType))
                    ? ", \"price\": {" + "\"units\": \"" + price.longValue() + "\", \"nano\": " + ((long)(price % 1 * 1_000_000_000)) + "}"
                    : "";
            String instrumentId;
            if (!ticker.isEmpty() && !classCode.isEmpty()) {
                instrumentId = ", \"instrumentId\": \"" + ticker + "_" + classCode + "\"";
            } else if (!figi.isEmpty()) {
                instrumentId = ", \"instrumentId\": \"" + figi + "\"";
            } else if (!uid.isEmpty()) {
                instrumentId = ", \"instrumentId\": \"" + uid + "\"";
            } else {
                instrumentId = ", \"instrumentId\": \"" + ticker + "_" + classCode + "\"";
            }
            String body = "{\"accountId\": \"" + accountId + "\"" + instrumentId
                    + ", \"quantity\": " + quantity + ", \"direction\": \"" + direction
                    + "\", \"orderType\": \"" + orderType + "\"" + priceField
                    + ", \"orderId\": \"" + System.currentTimeMillis() + "\"}";
            String idDesc = !uid.isEmpty() ? uid : (!figi.isEmpty() ? figi : ticker + ":" + classCode);
            System.out.println("  [API] PostOrder: " + direction + " " + quantity + " x " + idDesc);
            String json = post(url, body, 2);
            if (json == null) return null;
            String orderId = extractString(json, "orderId");
            String rejects = extractString(json, "rejectReason");
            if (!rejects.isEmpty()) {
                System.out.println("  [ERROR] Ордер отклонён: " + rejects);
                System.out.println("  [ERROR] Response: " + json);
                return null;
            }
            System.out.println("  [API] OrderId: " + orderId);
            return orderId;
        }

        String getOrderState(String accountId, String orderId) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OrdersService/GetOrderState",
                    "{\"accountId\": \"" + accountId + "\", \"orderId\": \"" + orderId + "\"}", 2, true);
            return json == null ? null : extractString(json, "executedExecutedQuantity");
        }

        List<String> getOrders(String accountId) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OrdersService/GetOrders",
                    "{\"accountId\": \"" + accountId + "\"}", 2, true);
            if (json == null) return Collections.emptyList();
            List<String> orderIds = new ArrayList<>();
            int searchStart = 0;
            while (true) {
                int idx = json.indexOf("\"orderId\"", searchStart);
                if (idx < 0) break;
                String orderId = extractString(json.substring(idx), "orderId");
                if (!orderId.isEmpty()) orderIds.add(orderId);
                searchStart = idx + 10;
            }
            return orderIds;
        }

        boolean cancelOrder(String accountId, String orderId) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OrdersService/CancelOrder",
                    "{\"accountId\": \"" + accountId + "\", \"orderId\": \"" + orderId + "\"}", 2);
            return json != null && json.contains("canceled");
        }

        private double extractCashTotal(String json) {
            int moneyArr = json.indexOf("\"money\"");
            if (moneyArr < 0) return 0.0;
            int bracketStart = json.indexOf('[', moneyArr);
            int bracketEnd = json.indexOf(']', bracketStart);
            if (bracketStart < 0 || bracketEnd < 0) return 0.0;
            String content = json.substring(bracketStart + 1, bracketEnd);
            int depth = 0; int objStart = -1; double total = 0.0;
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '{') { if (depth == 0) objStart = i; depth++; }
                else if (ch == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String obj = content.substring(objStart, i + 1);
                        String currency = extractString(obj, "currency");
                        double units = extractMoney(obj, "units") != null ? extractMoney(obj, "units") : 0.0;
                        Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(obj);
                        long nano = mn.find() ? parseLong(mn.group(1)) : 0L;
                        double amount = units + nano / 1_000_000_000.0;
                        if ("rub".equals(currency) || "RUB".equals(currency)) total += amount;
                        objStart = -1;
                    }
                }
            }
            return total;
        }

        private double extractBlockedMargin(String json) {
            double total = 0.0;
            for (String key : List.of("blockedGuarantee", "blocked")) {
                int arr = json.indexOf("\"" + key + "\"");
                if (arr < 0) continue;
                int bracketStart = -1;
                for (int i = arr; i < json.length(); i++) {
                    if (json.charAt(i) == '[') { bracketStart = i; break; }
                }
                if (bracketStart < 0) continue;
                int bracketEnd = -1; int depth = 0;
                for (int i = bracketStart; i < json.length(); i++) {
                    char ch = json.charAt(i);
                    if (ch == '[') depth++;
                    else if (ch == ']') { depth--; if (depth == 0) { bracketEnd = i; break; } }
                }
                if (bracketEnd < 0) continue;
                String content = json.substring(bracketStart + 1, bracketEnd);
                int objDepth = 0; int objStart = -1;
                for (int i = 0; i < content.length(); i++) {
                    char ch = content.charAt(i);
                    if (ch == '{') { if (objDepth == 0) objStart = i; objDepth++; }
                    else if (ch == '}') {
                        objDepth--;
                        if (objDepth == 0 && objStart >= 0) {
                            String obj = content.substring(objStart, i + 1);
                            String currency = extractString(obj, "currency");
                            double units = extractMoney(obj, "units") != null ? extractMoney(obj, "units") : 0.0;
                            Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(obj);
                            long nano = mn.find() ? parseLong(mn.group(1)) : 0L;
                            double amount = units + nano / 1_000_000_000.0;
                            if ("rub".equals(currency) || "RUB".equals(currency)) total += amount;
                            objStart = -1;
                        }
                    }
                }
                if (total > 0) break;
            }
            return total;
        }

        private List<TcsAccount> parseAccounts(String json) {
            List<TcsAccount> accounts = new ArrayList<>();
            int arrStart = json.indexOf("\"accounts\"");
            if (arrStart < 0) return accounts;
            int bStart = json.indexOf('[', arrStart);
            int bEnd = json.indexOf(']', bStart);
            if (bStart < 0 || bEnd < 0) return accounts;
            String content = json.substring(bStart + 1, bEnd);
            parseAccountObjects(content, accounts);
            return accounts;
        }

        private void parseAccountObjects(String content, List<TcsAccount> accounts) {
            int depth = 0; int objStart = -1;
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '{') { if (depth == 0) objStart = i; depth++; }
                else if (ch == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String obj = content.substring(objStart, i + 1);
                        accounts.add(new TcsAccount(extractString(obj, "id"), extractString(obj, "name"),
                                extractString(obj, "status"), extractString(obj, "type")));
                        objStart = -1;
                    }
                }
            }
        }

        private List<TcsPortfolioPosition> parsePortfolioPositions(String json) {
            List<TcsPortfolioPosition> positions = new ArrayList<>();
            int arrStart = json.indexOf("\"positions\"");
            if (arrStart < 0) return positions;
            int depth = 0; boolean inArray = false; int arrayStart = -1;
            for (int i = arrStart; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == '[' && !inArray) { inArray = true; arrayStart = i; }
                else if (inArray) {
                    if (ch == '[') depth++;
                    if (ch == ']') {
                        depth--;
                        if (depth < 0) {
                            parsePositionObjects(json.substring(arrayStart + 1, i), positions);
                            break;
                        }
                    }
                }
            }
            return positions;
        }

        private void parsePositionObjects(String content, List<TcsPortfolioPosition> positions) {
            int depth = 0; int objStart = -1;
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '{') { if (depth == 0) objStart = i; depth++; }
                else if (ch == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String obj = content.substring(objStart, i + 1);
                        Double q = extractMoney(obj, "quantity");
                        positions.add(new TcsPortfolioPosition(
                                extractString(obj, "figi"), extractString(obj, "instrumentType"),
                                q != null ? q : 0.0, extractMoney(obj, "averagePositionPrice"),
                                extractMoney(obj, "currentPrice"), extractString(obj, "ticker"),
                                extractString(obj, "classCode")));
                        objStart = -1;
                    }
                }
            }
        }

        private InstrumentInfo parseFindInstrument(String json) {
            int arrStart = json.indexOf("\"instruments\"");
            if (arrStart < 0) return null;
            int depth = 0; boolean inArray = false; int arrayStart = -1;
            for (int i = arrStart; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == '[' && !inArray) { inArray = true; arrayStart = i; }
                else if (inArray && ch == '[') depth++;
                else if (inArray && ch == ']') {
                    depth--;
                    if (depth < 0) {
                        String content = json.substring(arrayStart + 1, i);
                        int objDepth = 0; int objStart = -1;
                        for (int j = 0; j < content.length(); j++) {
                            char c = content.charAt(j);
                            if (c == '{') { if (objDepth == 0) objStart = j; objDepth++; }
                            else if (c == '}') {
                                objDepth--;
                                if (objDepth == 0 && objStart >= 0) {
                                    return parseInstrumentObj(content.substring(objStart, j + 1));
                                }
                            }
                        }
                        break;
                    }
                }
            }
            return null;
        }

        private List<InstrumentInfo> parseFindAllInstruments(String json) {
            List<InstrumentInfo> result = new ArrayList<>();
            int arrStart = json.indexOf("\"instruments\"");
            if (arrStart < 0) return result;
            int depth = 0; boolean inArray = false; int arrayStart = -1;
            for (int i = arrStart; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == '[' && !inArray) { inArray = true; arrayStart = i; }
                else if (inArray && ch == '[') depth++;
                else if (inArray && ch == ']') {
                    depth--;
                    if (depth < 0) {
                        String content = json.substring(arrayStart + 1, i);
                        int objDepth = 0; int objStart = -1;
                        for (int j = 0; j < content.length(); j++) {
                            char c = content.charAt(j);
                            if (c == '{') { if (objDepth == 0) objStart = j; objDepth++; }
                            else if (c == '}') {
                                objDepth--;
                                if (objDepth == 0 && objStart >= 0) {
                                    result.add(parseInstrumentObj(content.substring(objStart, j + 1)));
                                    objStart = -1;
                                }
                            }
                        }
                        break;
                    }
                }
            }
            return result;
        }

        private InstrumentInfo parseInstrumentObj(String obj) {
            String mat = extractString(obj, "maturityDate");
            int tIdx = mat.indexOf('T');
            return new InstrumentInfo(extractString(obj, "figi"), extractString(obj, "ticker"),
                    extractString(obj, "classCode"), extractString(obj, "instrumentType"),
                    tIdx > 0 ? mat.substring(0, tIdx) : mat,
                    extractString(obj, "isin"), extractString(obj, "uid"));
        }

        private Map<String, Double> parseLastPrices(String json) {
            Map<String, Double> prices = new LinkedHashMap<>();
            int arrStart = json.indexOf("\"lastPrices\"");
            if (arrStart < 0) return prices;
            int depth = 0; boolean inArray = false; int arrayStart = -1;
            for (int i = arrStart; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == '[' && !inArray) { inArray = true; arrayStart = i; }
                else if (inArray && ch == '[') depth++;
                else if (inArray && ch == ']') {
                    depth--;
                    if (depth < 0) {
                        String content = json.substring(arrayStart + 1, i);
                        int objDepth = 0; int objStart = -1;
                        for (int j = 0; j < content.length(); j++) {
                            char c = content.charAt(j);
                            if (c == '{') { if (objDepth == 0) objStart = j; objDepth++; }
                            else if (c == '}') {
                                objDepth--;
                                if (objDepth == 0 && objStart >= 0) {
                                    String obj = content.substring(objStart, j + 1);
                                    String figi = extractString(obj, "figi");
                                    String uid = extractString(obj, "instrumentUid");
                                    Double price = extractMoney(obj, "price");
                                    if (price != null) {
                                        if (!figi.isEmpty()) prices.put(figi, price);
                                        if (!uid.isEmpty()) prices.put(uid, price);
                                    }
                                    objStart = -1;
                                }
                            }
                        }
                        break;
                    }
                }
            }
            return prices;
        }

        private Double parseLastCandleClose(String json) {
            int candlesStart = json.indexOf("\"candles\"");
            if (candlesStart < 0) return null;
            int depth = 0; boolean inArray = false; int arrayStart = -1;
            for (int i = candlesStart; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == '[' && !inArray) { inArray = true; arrayStart = i; }
                else if (inArray && ch == '[') depth++;
                else if (inArray && ch == ']') {
                    depth--;
                    if (depth < 0) {
                        String content = json.substring(arrayStart + 1, i);
                        int objDepth = 0; int objStart = -1;
                        for (int j = 0; j < content.length(); j++) {
                            char c = content.charAt(j);
                            if (c == '{') { if (objDepth == 0) objStart = j; objDepth++; }
                            else if (c == '}') {
                                objDepth--;
                                if (objDepth == 0 && objStart >= 0) {
                                    Double close = extractMoney(content.substring(objStart, j + 1), "close");
                                    if (close != null) return close;
                                    objStart = -1;
                                }
                            }
                        }
                        break;
                    }
                }
            }
            return null;
        }
    }

    // ── Business logic ──────────────────────────────────────────────

    static LocalDate parseMoexFuturesExpiry(String figi) {
        Matcher m = Pattern.compile("F(?:UT)?NASD(\\d{2})(\\d{2})\\d+").matcher(figi);
        if (!m.find()) return null;
        int month = parseInt(m.group(1));
        int year = 2000 + parseInt(m.group(2));
        if (month < 1 || month > 12 || year < 2024) return null;
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.FRIDAY));
    }

    static InstrumentInfo selectBestFuturesContract(List<InstrumentInfo> futuresList, TcsClient client) {
        if (futuresList.isEmpty()) return null;
        LocalDate today = LocalDate.now();
        int minDaysToExpiry = 30;

        List<Object[]> viable = new ArrayList<>(); // [InstrumentInfo, LocalDate, long]
        for (InstrumentInfo inst : futuresList) {
            LocalDate expiry = parseMoexFuturesExpiry(inst.figi);
            if (expiry != null && expiry.isAfter(today.plusDays(minDaysToExpiry))) {
                viable.add(new Object[]{inst, expiry, ChronoUnit.DAYS.between(today, expiry)});
            }
        }

        if (viable.isEmpty()) {
            System.out.println("  [WARN] Нет фьючерсов с достаточным сроком до экспирации (>" + minDaysToExpiry + " дней)");
            return futuresList.get(0);
        }

        InstrumentInfo bestContract = null;
        double bestScore = Double.MIN_VALUE;
        System.out.println("  Анализ доступных контрактов:");
        for (Object[] v : viable) {
            InstrumentInfo inst = (InstrumentInfo) v[0];
            LocalDate expiry = (LocalDate) v[1];
            long daysToExpiry = (long) v[2];
            Double go = client.getFuturesMargin(inst.figi);
            double goValue = go != null ? go : 0.0;
            Map<String, Double> prices = client.getLastPrices(List.of(inst.figi));
            double price = prices.getOrDefault(inst.figi, 0.0);
            double goRatio = price > 0 ? goValue / price : 1.0;
            double optimalDays = 60.0;
            double daysFactor = daysToExpiry <= optimalDays ? 1.0 : 1.0 - (daysToExpiry - optimalDays) / 365.0 * 0.5;
            double score = (1.0 - goRatio) * 0.6 + daysFactor * 0.4;
            System.out.println("    " + inst.ticker + " (" + inst.figi + "): exp=" + expiry + ", дн=" + daysToExpiry + ", ГО=" + goValue + ", оценка=" + String.format("%.3f", score));
            if (score > bestScore) { bestScore = score; bestContract = inst; }
        }
        return bestContract;
    }

    static List<RebalanceAction> calculatePortfolioStructure(
            double totalAccountValue, double fullContractCostRur, double singleContractGoRur,
            int existingFuturesCount, String existingFuturesFigi, double existingFuturesAvgPrice,
            String futuresTicker, String futuresFigi,
            String tmonTicker, String tmonFigi, String tmonClassCode, String tmonUid,
            double tmonPrice, double currentCash, double currentLqdtValue) {

        BigDecimal total = BigDecimal.valueOf(totalAccountValue);
        BigDecimal contractCost = BigDecimal.valueOf(fullContractCostRur);
        BigDecimal go = BigDecimal.valueOf(singleContractGoRur);
        List<RebalanceAction> actions = new ArrayList<>();

        BigDecimal targetCash = total.multiply(CASH_TARGET_PERCENT);
        BigDecimal targetLiquidityFund = total.subtract(targetCash);
        int targetFuturesCount = total.divide(contractCost, 0, RoundingMode.DOWN).intValue();
        int futuresDifference = targetFuturesCount - existingFuturesCount;
        BigDecimal totalRequiredGo = go.multiply(BigDecimal.valueOf(targetFuturesCount));
        BigDecimal fundCollateralValue = targetLiquidityFund.multiply(BigDecimal.ONE.subtract(BROKER_COLLATERAL_DISCOUNT));

        System.out.println("================================================================");
        System.out.println("  ОТЧЕТ ПО АККАУНТУ (ФЬЮЧЕРСЫ NASD + ЛИКВИДНОСТЬ)");
        System.out.println("================================================================");
        System.out.println("  Текущая оценка аккаунта: " + total.setScale(2, RoundingMode.HALF_UP) + " руб.");
        System.out.println("  Стоимость 1 контракта NASD: " + contractCost.setScale(2, RoundingMode.HALF_UP) + " руб.");
        System.out.println("  ГО 1 контракта: " + go.setScale(2, RoundingMode.HALF_UP) + " руб.");
        System.out.println("----------------------------------------------------------------");

        if (existingFuturesCount > 0) {
            System.out.println("  ТЕКУЩАЯ ПОЗИЦИЯ В ПОРТФЕЛЕ:");
            System.out.println("  - Фьючерсов NASD уже есть: " + existingFuturesCount + " шт.");
            System.out.println("  - Средняя цена входа: " + fmtDot(existingFuturesAvgPrice) + " руб.");
            System.out.println("  - FIGI текущего контракта: " + existingFuturesFigi);
            System.out.println("----------------------------------------------------------------");
        }

        System.out.println("  ИДЕАЛЬНОЕ РАСПРЕДЕЛЕНИЕ АКТИВОВ ДЛЯ ВВОДА В ТЕРМИНАЛ:");
        System.out.println("  1. Оставить в КЭШЕ (чистые рубли): " + targetCash.setScale(2, RoundingMode.HALF_UP) + " руб.");
        System.out.println("  2. Купить ФОНД ЛИКВИДНОСТИ (TMON@): " + targetLiquidityFund.setScale(2, RoundingMode.HALF_UP) + " руб.");
        System.out.println("  3. Удерживать ФЬЮЧЕРСОВ NASD: " + targetFuturesCount + " шт.");

        System.out.println("----------------------------------------------------------------");
        if (futuresDifference > 0) {
            BigDecimal requiredGo = go.multiply(BigDecimal.valueOf(futuresDifference));
            double requiredGoD = requiredGo.doubleValue();
            System.out.println("  РЕБАЛАНСИРОВКА ТРЕБУЕТСЯ:");
            System.out.println("  [!!!] Нужно ДОКУПИТЬ: " + futuresDifference + " шт. фьючерсов NASD");
            System.out.println("        Требуется ГО: " + requiredGo.setScale(2, RoundingMode.HALF_UP) + " руб.");
            if (currentCash >= requiredGoD) {
                System.out.println("        Источник: свободный кэш (" + fmtDot(currentCash) + " руб.)");
                actions.add(new RebalanceAction("BUY_FUTURES", futuresFigi, futuresTicker, "", "",
                        futuresDifference, fullContractCostRur, requiredGoD));
            } else {
                double deficit = requiredGoD - currentCash;
                int tmonSellQty = tmonPrice > 0 ? Math.max(1, (int)(deficit / tmonPrice)) : 0;
                System.out.println("        Кэша недостаточно: нужно " + fmtDot(requiredGoD) + " руб., есть " + fmtDot(currentCash) + " руб.");
                System.out.println("        Источник: продать " + tmonSellQty + " шт. TMON@ + весь кэш");
                actions.add(new RebalanceAction("BUY_FUTURES", futuresFigi, futuresTicker, "", "",
                        futuresDifference, fullContractCostRur, requiredGoD));
                if (tmonSellQty > 0) {
                    actions.add(new RebalanceAction("SELL_TMON", tmonFigi, tmonTicker, tmonClassCode, tmonUid,
                            tmonSellQty, tmonPrice, deficit));
                }
            }
        } else if (futuresDifference < 0) {
            BigDecimal freedGo = go.multiply(BigDecimal.valueOf(-futuresDifference));
            System.out.println("  РЕБАЛАНСИРОВКА ТРЕБУЕТСЯ:");
            System.out.println("  [!!!] Нужно ПРОДАТЬ: " + (-futuresDifference) + " шт. фьючерсов NASD");
            System.out.println("        Освободится ГО: " + freedGo.setScale(2, RoundingMode.HALF_UP) + " руб.");
            actions.add(new RebalanceAction("SELL_FUTURES", futuresFigi, futuresTicker, "", "",
                    -futuresDifference, fullContractCostRur, freedGo.doubleValue()));
            double freedGoD = freedGo.doubleValue();
            double currentCashAfterSale = totalAccountValue + freedGoD;
            double targetCashAfter = currentCashAfterSale * CASH_TARGET_PERCENT.doubleValue();
            double newFundTarget = currentCashAfterSale - targetCashAfter;
            double currentTmonValue = totalAccountValue - (totalAccountValue * CASH_TARGET_PERCENT.doubleValue());
            double tmonToBuy = newFundTarget - currentTmonValue;
            if (tmonToBuy > 0) {
                System.out.println("        Рекомендация: докупить TMON@ на " + BigDecimal.valueOf(tmonToBuy).setScale(2, RoundingMode.HALF_UP) + " руб.");
                System.out.println("        Остаток кэша: " + BigDecimal.valueOf(targetCashAfter).setScale(2, RoundingMode.HALF_UP) + " руб.");
                int qty = tmonPrice > 0 ? Math.max(1, (int)(tmonToBuy / tmonPrice)) : 0;
                actions.add(new RebalanceAction("BUY_TMON", tmonFigi, tmonTicker, tmonClassCode, tmonUid,
                        qty, tmonPrice, tmonToBuy));
            } else if (tmonToBuy < 0) {
                System.out.println("        Рекомендация: продать TMON@ на " + BigDecimal.valueOf(-tmonToBuy).setScale(2, RoundingMode.HALF_UP) + " руб.");
                System.out.println("        Остаток кэша: " + BigDecimal.valueOf(targetCashAfter).setScale(2, RoundingMode.HALF_UP) + " руб.");
                int qty = tmonPrice > 0 ? Math.max(1, (int)(-tmonToBuy / tmonPrice)) : 0;
                actions.add(new RebalanceAction("SELL_TMON", tmonFigi, tmonTicker, tmonClassCode, tmonUid,
                        qty, tmonPrice, -tmonToBuy));
            } else {
                System.out.println("        Рекомендация: оставить как кэш (" + BigDecimal.valueOf(targetCashAfter).setScale(2, RoundingMode.HALF_UP) + " руб.)");
            }
        } else {
            System.out.println("  [OK] Фьючерсы сбалансированы.");
        }

        // TMON@ balance check
        double targetCashD = totalAccountValue * CASH_TARGET_PERCENT.doubleValue();
        double excessCash = currentCash - targetCashD;
        if (excessCash > tmonPrice) {
            int tmonQty = (int)(excessCash / tmonPrice);
            double tmonCost = tmonQty * tmonPrice;
            System.out.println("  РЕБАЛАНСИРОВКА TMON@:");
            System.out.println("  [!!!] Нужно ДОКУПИТЬ: " + tmonQty + " шт. TMON@ на " + fmtDot(tmonCost) + " руб.");
            System.out.println("        Кэш: " + fmtDot(currentCash) + " руб., целевой: " + fmtDot(targetCashD) + " руб.");
            actions.add(new RebalanceAction("BUY_TMON", tmonFigi, tmonTicker, tmonClassCode, tmonUid,
                    tmonQty, tmonPrice, tmonCost));
        } else if (currentLqdtValue > 0 && currentCash < targetCashD - tmonPrice) {
            double deficit = targetCashD - currentCash;
            int tmonSellQty = Math.max(1, (int)(deficit / tmonPrice));
            double tmonSellValue = tmonSellQty * tmonPrice;
            System.out.println("  РЕБАЛАНСИРОВКА TMON@:");
            System.out.println("  [!!!] Нужно ПРОДАТЬ: " + tmonSellQty + " шт. TMON@ на " + fmtDot(tmonSellValue) + " руб.");
            System.out.println("        Кэш: " + fmtDot(currentCash) + " руб., целевой: " + fmtDot(targetCashD) + " руб.");
            actions.add(new RebalanceAction("SELL_TMON", tmonFigi, tmonTicker, tmonClassCode, tmonUid,
                    tmonSellQty, tmonPrice, tmonSellValue));
        } else {
            System.out.println("  [OK] TMON@ сбалансирован.");
        }

        System.out.println("----------------------------------------------------------------");
        System.out.println("  АНАЛИЗ БЕЗОПАСНОСТИ МАРЖИНАЛЬНЫХ ТРЕБОВАНИЙ:");
        System.out.println("  - Заблокировано под ГО фьючерсов: " + totalRequiredGo.setScale(2, RoundingMode.HALF_UP) + " руб.");
        System.out.println("  - Доступная стоимость залога от фонда: " + fundCollateralValue.setScale(2, RoundingMode.HALF_UP) + " руб.");
        if (fundCollateralValue.compareTo(totalRequiredGo) > 0) {
            System.out.println("  [OK] Статус: БЕЗОПАСНО. Залог полностью покрывает требования ГО.");
        } else {
            System.out.println("  [!!!] Статус: КРИТИЧЕСКИЙ РИСК! ГО превышает залоговую стоимость фонда.");
            System.out.println("        Уменьшите количество фьючерсов.");
        }
        System.out.println("================================================================");
        System.out.println();
        System.out.println("  КАК РАБОТАЕТ СТРАТЕГИЯ ПРИ ПРОСАДКЕ:");
        System.out.println("  - Кэш в 10% постепенно тает, превращаясь в вариационную маржу.");
        System.out.println("  - Если кэш упал (например, до 4%), программа порекомендует");
        System.out.println("    продать часть фонда ликвидности для восстановления 10% кэша.");
        System.out.println("================================================================");
        return actions;
    }

    static void executeRebalancing(String accountId, TcsClient tcs, List<RebalanceAction> actions) {
        if (actions.isEmpty()) {
            System.out.println("  [OK] Ребалансировка не требуется.");
            return;
        }
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  ВЫПОЛНЕНИЕ РЕБАЛАНСИРОВКИ");
        System.out.println("================================================================");

        List<RebalanceAction> sellActions = new ArrayList<>();
        List<RebalanceAction> buyTmon = new ArrayList<>();
        List<RebalanceAction> buyFutures = new ArrayList<>();
        for (RebalanceAction a : actions) {
            if (a.type.startsWith("SELL")) sellActions.add(a);
            else if ("BUY_TMON".equals(a.type)) buyTmon.add(a);
            else if ("BUY_FUTURES".equals(a.type)) buyFutures.add(a);
        }

        for (RebalanceAction action : sellActions) {
            System.out.println("  [SELL] " + action.ticker + ": " + action.quantity + " шт по " + fmtDot(action.price) + " = " + fmtDot(action.amount) + " руб");
            String orderId = tcs.postOrder(accountId, action.figi, action.quantity, "ORDER_DIRECTION_SELL",
                    "ORDER_TYPE_MARKET", null, action.ticker, action.classCode, action.uid);
            if (orderId != null) {
                System.out.println("  [OK] Ордер размещён: " + orderId);
            } else if (action.type.contains("TMON")) {
                System.out.println("  [WARN] " + action.ticker + " недоступен для API-торговли, пропускаю");
            } else {
                System.out.println("  [ERROR] Ордер не размещён! Прерываю ребалансировку.");
                return;
            }
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }

        List<RebalanceAction> buyActions = new ArrayList<>();
        buyActions.addAll(buyTmon);
        buyActions.addAll(buyFutures);
        for (RebalanceAction action : buyActions) {
            System.out.println("  [BUY] " + action.ticker + ": " + action.quantity + " шт по " + fmtDot(action.price) + " = " + fmtDot(action.amount) + " руб");
            String orderId = tcs.postOrder(accountId, action.figi, action.quantity, "ORDER_DIRECTION_BUY",
                    "ORDER_TYPE_MARKET", null, action.ticker, action.classCode, action.uid);
            if (orderId != null) {
                System.out.println("  [OK] Ордер размещён: " + orderId);
            } else if (action.type.contains("TMON")) {
                System.out.println("  [WARN] " + action.ticker + " недоступен для API-торговли, пропускаю");
            } else {
                System.out.println("  [ERROR] Ордер не размещён! Прерываю ребалансировку.");
                return;
            }
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }

        System.out.println("================================================================");
        System.out.println("  РЕБАЛАНСИРОВКА ЗАВЕРШЕНА");
        System.out.println("================================================================");
    }

    // ── Main ────────────────────────────────────────────────────────

    public static void main(String[] args) {
        boolean autoMode = Arrays.asList(args).contains("--auto");

        System.out.println("================================================================");
        System.out.println("  NASD SCANNER (Фьючерсы + Ликвидность)");
        System.out.println("  Дата: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        if (autoMode) System.out.println("  РЕЖИМ: АВТОМАТИЧЕСКАЯ РЕБАЛАНСИРОВКА");
        System.out.println("================================================================");
        System.out.println();

        // 1. API key
        Properties props = loadProperties();
        String tcsApiKey = props.getProperty("tcs.apiKey", "");
        if (tcsApiKey.isEmpty()) {
            System.out.println("  [ERROR] Не настроен T-Invest API ключ в application.properties");
            System.exit(1);
        }
        TcsClient tcs = new TcsClient(tcsApiKey);

        // 2. Account
        String accountId = props.getProperty("tcs.accountId", "");
        if (accountId.isEmpty()) {
            System.out.println("  [WARN] tcs.accountId не настроен. Загрузка списка счетов...");
            List<TcsAccount> accounts = tcs.getAccounts();
            if (accounts.isEmpty()) {
                System.out.println("  [ERROR] Нет доступных счетов");
                System.exit(1);
            }
            System.out.println();
            System.out.println("  Доступные счета:");
            for (int idx = 0; idx < accounts.size(); idx++) {
                TcsAccount acc = accounts.get(idx);
                System.out.println("    " + (idx + 1) + ". " + acc.name + " [" + acc.type + "] (" + acc.id + ")");
            }
            System.out.print("\n  Введите номер ИИС счета: ");
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
                int choice = Integer.parseInt(br.readLine().trim());
                if (choice < 1 || choice > accounts.size()) {
                    System.out.println("  [ERROR] Неверный выбор");
                    System.exit(1);
                }
                accountId = accounts.get(choice - 1).id;
                System.out.println("  Выбран: " + accounts.get(choice - 1).name + " (" + accountId + ")");
            } catch (Exception e) {
                System.out.println("  [ERROR] Неверный ввод");
                System.exit(1);
            }
        }
        System.out.println("  [1/5] Подключение к аккаунту (" + accountId + ")...");

        // 3. Portfolio & cash
        System.out.println("  [2/5] Загрузка портфеля аккаунта...");
        List<TcsPortfolioPosition> positions = tcs.getPortfolio(accountId);
        double cash = tcs.getWithdrawLimits(accountId);

        double liquidSecuritiesValue = 0.0;
        double futuresValue = 0.0;
        for (TcsPortfolioPosition pos : positions) {
            double value = pos.quantity * (pos.curPrice != null ? pos.curPrice : 0.0);
            if ("FUTURES".equalsIgnoreCase(pos.instrumentType)) futuresValue += value;
            else if ("CURRENCY".equalsIgnoreCase(pos.instrumentType)) { /* skip */ }
            else liquidSecuritiesValue += value;
        }

        double liquidAssets = liquidSecuritiesValue + cash;
        System.out.println("  Свободный кэш (за вычетом ГО): " + fmt(cash) + " руб");
        System.out.println("  Ликвидные бумаги (ETF/акции/облигации): " + fmt(liquidSecuritiesValue) + " руб");
        System.out.println("  Фьючерсы (нельзя тратить): " + fmt(futuresValue) + " руб");
        System.out.println("  Доступно для инвестирования: " + fmt(liquidAssets) + " руб");
        System.out.println("  Позиций: " + positions.size());

        if (!positions.isEmpty()) {
            System.out.println();
            System.out.println("  Состав портфеля:");
            for (TcsPortfolioPosition pos : positions) {
                if (pos.quantity <= 0) continue;
                double value = pos.quantity * (pos.curPrice != null ? pos.curPrice : 0.0);
                String name = !pos.ticker.isEmpty() ? pos.ticker : pos.figi;
                System.out.println("    " + name + " (" + pos.instrumentType + "): " + pos.quantity + " шт x " + fmtDot(pos.curPrice != null ? pos.curPrice : 0.0) + " = " + fmtDot(value) + " руб");
            }
        }

        // 4. Find NASD futures
        System.out.println();
        System.out.println("  [3/5] Поиск фьючерса NASD...");

        int existingFuturesCount = 0;
        String existingFuturesFigi = "";
        double existingFuturesAvgPrice = 0.0;
        for (TcsPortfolioPosition pos : positions) {
            if ("FUTURES".equalsIgnoreCase(pos.instrumentType) && pos.figi.toUpperCase().contains("NASD")) {
                System.out.println("  [INFO] В портфеле уже есть NASD фьючерсы:");
                System.out.println("    - " + pos.ticker + " (" + pos.figi + "): " + pos.quantity + " шт, средняя цена: " + fmtDot(pos.avgPrice != null ? pos.avgPrice : 0.0) + " руб.");
                existingFuturesCount += (int) pos.quantity;
                existingFuturesFigi = pos.figi;
                existingFuturesAvgPrice = pos.avgPrice != null ? pos.avgPrice : 0.0;
            }
        }
        if (existingFuturesCount > 0) {
            System.out.println("  Итого NASD фьючерсов в портфеле: " + existingFuturesCount + " шт.");
        }

        List<InstrumentInfo> availableFutures = tcs.findBestNasdaqFutures();
        if (availableFutures.isEmpty()) {
            System.out.println("  Futures endpoint не вернул результат, пробуем FindInstrument...");
            InstrumentInfo alt = tcs.findInstrument("NASD");
            if (alt == null) alt = tcs.findInstrument("NQ");
            if (alt != null && !"FAKEFAKE".equals(alt.classCode)) {
                System.out.println("  Найден: " + alt.ticker + " (" + alt.figi + ") class=" + alt.classCode + " exp=" + alt.maturityDate);
            } else {
                System.out.println("  [ERROR] Фьючерс NASD не найден.");
                System.exit(1);
            }
        }

        InstrumentInfo nasdInstrument;
        if (availableFutures.size() > 1) {
            System.out.println("  [INFO] Доступно " + availableFutures.size() + " контрактов, выбираю наиболее выгодный...");
            nasdInstrument = selectBestFuturesContract(availableFutures, tcs);
            if (nasdInstrument == null) nasdInstrument = availableFutures.get(0);
        } else {
            nasdInstrument = availableFutures.isEmpty() ? tcs.findInstrument("NASD") : availableFutures.get(0);
        }
        if (nasdInstrument == null) {
            System.out.println("  [ERROR] Не удалось найти подходящий фьючерс NASD");
            System.exit(1);
        }
        System.out.println("  Выбран контракт: " + nasdInstrument.ticker + " (" + nasdInstrument.figi + ")");

        System.out.println("  [4/5] Получение рыночных котировок...");

        // TMON@ lookup
        String tmonIsin = "RU000A106DL2";
        String tmonFigiDefault = "TCS70A106DL2";
        String tmonClassCodeDefault = "SPBRU";
        InstrumentInfo tmonInstrument = tcs.findByIsin(tmonIsin);
        String tmonTicker = "TMON@";
        String tmonClassCode = tmonClassCodeDefault;
        String tmonFigi = tmonFigiDefault;
        String tmonUid = tmonInstrument != null ? tmonInstrument.uid : "";
        if (tmonInstrument != null) {
            System.out.println("  TMON@ ISIN " + tmonIsin + " -> ticker: " + tmonTicker + ", classCode: " + tmonClassCode + ", figi: " + tmonFigi + ", uid: " + tmonUid);
        } else {
            System.out.println("  [WARN] ISIN " + tmonIsin + " не найден в API, используем данные по умолчанию");
        }

        String tmonPriceId = !tmonUid.isEmpty() ? tmonUid : (!tmonFigi.isEmpty() ? tmonFigi : tmonIsin);
        List<String> priceIds = new ArrayList<>();
        priceIds.add(nasdInstrument.figi);
        priceIds.add(tmonPriceId);
        Map<String, Double> prices = tcs.getLastPrices(priceIds);

        Double nasdPrice = prices.get(nasdInstrument.figi);
        if (nasdPrice == null) nasdPrice = tcs.getLastCandle(nasdInstrument.figi);
        if (nasdPrice == null && !nasdInstrument.uid.isEmpty()) nasdPrice = tcs.getCandlesByUid(nasdInstrument.uid);
        if (nasdPrice == null) {
            System.out.println("  [ERROR] Не удалось получить цену фьючерса NASD (" + nasdInstrument.figi + ")");
            System.exit(1);
        }
        System.out.println("  Цена NASD: " + fmt(nasdPrice) + " руб");

        Double tmonPrice = prices.get(tmonPriceId);
        if (tmonPrice == null && !tmonUid.isEmpty()) tmonPrice = tcs.getCandlesByUid(tmonUid);
        if (tmonPrice == null) {
            System.out.println("  [ERROR] Не удалось получить цену TMON@ (uid: " + tmonUid + ")");
            System.exit(1);
        }
        System.out.println("  Цена TMON@: " + fmt(tmonPrice) + " руб");

        // 5. Margin & calculation
        double fullContractCostRur = nasdPrice;
        System.out.println("  Стоимость контракта: " + fmt(fullContractCostRur) + " руб");

        Double apiGo = tcs.getFuturesMargin(nasdInstrument.figi);
        double singleContractGoRur;
        if (apiGo != null && apiGo > 0) {
            singleContractGoRur = apiGo;
            System.out.println("  ГО из API: " + fmt(singleContractGoRur) + " руб");
        } else {
            singleContractGoRur = fullContractCostRur * 0.12;
            System.out.println("  [WARN] ГО из API недоступно, используем ~12%: " + fmt(singleContractGoRur) + " руб");
        }

        System.out.println();
        System.out.println("  [5/5] Расчет оптимальной структуры портфеля...");
        System.out.println();

        List<RebalanceAction> actions = calculatePortfolioStructure(
                liquidAssets, fullContractCostRur, singleContractGoRur,
                existingFuturesCount, existingFuturesFigi, existingFuturesAvgPrice,
                nasdInstrument.ticker, nasdInstrument.figi,
                tmonTicker, tmonFigi, tmonClassCode, tmonUid,
                tmonPrice, cash, liquidSecuritiesValue);

        if (autoMode) {
            executeRebalancing(accountId, tcs, actions);
        }
    }
}
