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
import java.util.function.*;
import java.util.regex.*;
import javax.net.ssl.*;

public class PortfolioScanner {

    static final Properties props;
    static final String TCS_API_KEY, FINAM_SECRET, FINAM_ACCOUNT_ID;
    static {
        props = new Properties();
        try { props.load(new FileInputStream(System.getProperty("user.dir") + "/application.properties")); }
        catch (Exception e) { System.out.println("  [WARN] loadProperties: " + e.getMessage()); }
        TCS_API_KEY = props.getProperty("tcs.apiKey", "");
        FINAM_SECRET = props.getProperty("finam.apiKey", "");
        FINAM_ACCOUNT_ID = props.getProperty("finam.accountId", "");
    }

    // ── Data classes ─────────────────────────────────────────────────

    static class TcsAccount { final String id, name, status, type; TcsAccount(String i,String n,String s,String t){id=i;name=n;status=s;type=t;} }
    static class TcsPortfolioPosition { final String figi, instrumentType, ticker, classCode; final double quantity; final Double avgPrice, curPrice, expectedYield, currentNkd;
        TcsPortfolioPosition(String f,String it,double q,Double ap,Double cp,Double ey,Double cn,String t,String cc){figi=f;instrumentType=it;quantity=q;avgPrice=ap;curPrice=cp;expectedYield=ey;currentNkd=cn;ticker=t;classCode=cc;} }
    static class BondInstrument { final String ticker,name,figi,classCode,sector,maturityDate; final double nominal; final int couponQuantityPerYear;
        BondInstrument(String t,String n,String f,String cc,double nom,String s,int cpy,String md){ticker=t;name=n;figi=f;classCode=cc;nominal=nom;sector=s;couponQuantityPerYear=cpy;maturityDate=md;} }
    static class CouponEvent { final String couponDate,couponType,currency; final double payOneBond; CouponEvent(String d,double p,String t,String c){couponDate=d;payOneBond=p;couponType=t;currency=c;} }
    static class DividendEvent { final String dividendDate,currency; final double dividendAmount; DividendEvent(String d,double a,String c){dividendDate=d;dividendAmount=a;currency=c;} }
    static class InstrumentInfo { final String ticker,figi,name,instrumentType,classCode; InstrumentInfo(String t,String f,String n,String it,String cc){ticker=t;figi=f;name=n;instrumentType=it;classCode=cc;} }
    static class FinamPosition { final String symbol; final double quantity,averagePrice,currentPrice,unrealizedPnl;
        FinamPosition(String s,double q,double ap,double cp,double up){symbol=s;quantity=q;averagePrice=ap;currentPrice=cp;unrealizedPnl=up;} }
    static class AccountInfo { final String id, name; AccountInfo(String i,String n){id=i;name=n;} }
    static class FinamDividendEvent { final String date,currency; final double amountPerShare; FinamDividendEvent(String d,double a,String c){date=d;amountPerShare=a;currency=c;} }
    static class PaymentEvent { final String date,type,currency; final double amountPerUnit; PaymentEvent(String d,double a,String t,String c){date=d;amountPerUnit=a;type=t;currency=c;} }
    static class UnifiedHolding {
        final String figi,ticker,name,instrumentType,maturityDate,sector;
        final double quantity,avgPrice,curPrice,nominal;
        final int couponPerYear;
        final Set<String> sources;
        final List<PaymentEvent> payments;
        UnifiedHolding(String f,String t,String n,String it,double q,double ap,double cp,double nom,String s,int cpy,String md,Set<String> src,List<PaymentEvent> p) {
            figi=f;ticker=t;name=n;instrumentType=it;quantity=q;avgPrice=ap;curPrice=cp;nominal=nom;sector=s;couponPerYear=cpy;maturityDate=md;sources=src;payments=p;
        }
    }

    // ── SSL ──────────────────────────────────────────────────────────

    static SSLContext createTrustAllSSLContext() throws Exception {
        X509TrustManager trustAll = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c,String a){}
            public void checkServerTrusted(X509Certificate[] c,String a){}
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
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
    static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
    static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"?([\\d.]+)\"?").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }
    static String extractValue(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
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
    static Double extractMoneyValue(String json, String key) {
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

    // ── Helpers ──────────────────────────────────────────────────────

    static String extractTickerFromSymbol(String symbol) {
        int idx = symbol.indexOf('@');
        return idx > 0 ? symbol.substring(0, idx) : symbol;
    }

    static boolean isBondSymbol(String symbol) {
        String ticker = extractTickerFromSymbol(symbol);
        return ticker.length() >= 10;
    }

    static String sectorShort(String sector) {
        switch (sector.isEmpty() ? "corporate" : sector) {
            case "corporate": return "Корп";
            case "government": return "Гос";
            case "bank": return "Банк";
            case "financial": return "Фин";
            case "materials": return "Мат";
            case "energy": return "Энер";
            case "consumer": return "Потр";
            case "it": return "IT";
            case "industrials": return "Пром";
            default: return sector.substring(0, Math.min(5, sector.length()));
        }
    }

    static String typeShort(String type) {
        switch (type) {
            case "share": case "stock": return "Акц";
            case "bond": return "Облиг";
            case "etf": return "ETF";
            case "currency": return "Вал";
            case "futures": return "Фьюч";
            default: return type.substring(0, Math.min(5, type.length()));
        }
    }

    static String formatDate(String d) {
        if (d.length() >= 10) return d.substring(8,10)+"."+d.substring(5,7)+"."+d.substring(0,4);
        return d;
    }

    static String formatCash(String json) {
        int cs = json.indexOf("\"cash\"");
        if (cs < 0) return "0";
        String sub = json.substring(cs);
        Matcher mu = Pattern.compile("\"units\"\\s*:\\s*\"?([\\d.]+)\"?").matcher(sub);
        Matcher mn = Pattern.compile("\"nanos\"\\s*:\\s*(\\d+)").matcher(sub);
        double units = mu.find() ? Double.parseDouble(mu.group(1)) : 0.0;
        long nano = mn.find() ? Long.parseLong(mn.group(1)) : 0L;
        return String.format(java.util.Locale.US, "%.2f", units + nano / 1_000_000_000.0);
    }

    static double extractEquity(String json) {
        String v = extractValue(json, "equity");
        return v.isEmpty() ? 0.0 : Double.parseDouble(v);
    }

    static List<AccountInfo> extractFinamAccounts(String json) {
        List<AccountInfo> accounts = new ArrayList<>();
        int ks = json.indexOf("\"account_ids\"");
        if (ks < 0) return accounts;
        int as = json.indexOf('[', ks), ae = json.indexOf(']', as);
        if (as < 0 || ae < 0) return accounts;
        String content = json.substring(as + 1, ae);
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(content);
        while (m.find()) accounts.add(new AccountInfo(m.group(1), ""));
        return accounts;
    }

    // ── TcsClient ────────────────────────────────────────────────────

    static class TcsClient {
        final HttpClient http = createHttpClient();

        String post(String url, String body, int retries, boolean silent) {
            for (int attempt = 1; attempt <= retries; attempt++) {
                try {
                    HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder().uri(URI.create(url))
                            .header("Authorization", "Bearer " + TCS_API_KEY)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 429) {
                        long wait = attempt < retries ? 3L : 0L;
                        if (!silent) System.out.println("  [WARN] Rate limit 429, attempt " + attempt + "/" + retries + ", wait " + (wait*10) + "s...");
                        Thread.sleep(wait * 1000L);
                        continue;
                    }
                    if (resp.statusCode() != 200) {
                        if (attempt == retries) {
                            String method = url.substring(url.lastIndexOf('/') + 1);
                            if (!silent) System.out.println("  [WARN] " + method + ": HTTP " + resp.statusCode());
                            return null;
                        }
                        Thread.sleep(2000L);
                        continue;
                    }
                    Thread.sleep(150L);
                    return resp.body();
                } catch (Exception e) {
                    if (attempt == retries) { System.out.println("  [WARN] TCS API error: " + e.getMessage()); return null; }
                    try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
                }
            }
            return null;
        }
        String post(String url, String body) { return post(url, body, 3, false); }

        double getWithdrawLimits(String accountId) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetWithdrawLimits",
                "{\"accountId\":\"" + accountId + "\"}", 2, true);
            if (json == null) return 0.0;
            return extractCashTotal(json) - extractBlockedMargin(json);
        }

        private double extractCashTotal(String json) {
            int ma = json.indexOf("\"money\"");
            if (ma < 0) return 0.0;
            int bs = json.indexOf('[', ma), be = json.indexOf(']', bs);
            if (bs < 0 || be < 0) return 0.0;
            String content = json.substring(bs + 1, be);
            int depth = 0, objStart = -1; double total = 0;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') { if (depth == 0) objStart = i; depth++; }
                else if (c == '}') { depth--; if (depth == 0 && objStart >= 0) {
                    String obj = content.substring(objStart, i + 1);
                    String cur = extractString(obj, "currency");
                    Double units = extractMoney(obj, "units");
                    Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(obj);
                    long nano = mn.find() ? Long.parseLong(mn.group(1)) : 0;
                    double amount = (units != null ? units : 0) + nano / 1_000_000_000.0;
                    if ("rub".equals(cur) || "RUB".equals(cur)) total += amount;
                    objStart = -1;
                } }
            }
            return total;
        }

        private double extractBlockedMargin(String json) {
            double total = 0;
            for (String key : List.of("blockedGuarantee", "blocked")) {
                int arr = json.indexOf("\"" + key + "\"");
                if (arr < 0) continue;
                int bs = -1;
                for (int i = arr; i < json.length(); i++) { if (json.charAt(i) == '[') { bs = i; break; } }
                if (bs < 0) continue;
                int be = -1, depth = 0;
                for (int i = bs; i < json.length(); i++) {
                    char c = json.charAt(i);
                    if (c == '[') depth++; else if (c == ']') { depth--; if (depth == 0) { be = i; break; } }
                }
                if (be < 0) continue;
                String content = json.substring(bs + 1, be);
                int od = 0, os = -1;
                for (int i = 0; i < content.length(); i++) {
                    char c = content.charAt(i);
                    if (c == '{') { if (od == 0) os = i; od++; }
                    else if (c == '}') { od--; if (od == 0 && os >= 0) {
                        String obj = content.substring(os, i + 1);
                        String cur = extractString(obj, "currency");
                        Double units = extractMoney(obj, "units");
                        Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(obj);
                        long nano = mn.find() ? Long.parseLong(mn.group(1)) : 0;
                        double amount = (units != null ? units : 0) + nano / 1_000_000_000.0;
                        if ("rub".equals(cur) || "RUB".equals(cur)) total += amount;
                        os = -1;
                    } }
                }
                if (total > 0) break;
            }
            return total;
        }

        List<TcsAccount> getAccounts() {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.UsersService/GetAccounts",
                "{\"status\":\"ACCOUNT_STATUS_OPEN\"}");
            if (json == null) return Collections.emptyList();
            return parseAccounts(json);
        }

        private List<TcsAccount> parseAccounts(String json) {
            List<TcsAccount> accounts = new ArrayList<>();
            int as = json.indexOf("\"accounts\"");
            if (as < 0) return accounts;
            int bs = json.indexOf('[', as), be = json.indexOf(']', bs);
            if (bs < 0 || be < 0) return accounts;
            String content = json.substring(bs + 1, be);
            int depth = 0, objStart = -1;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') { if (depth == 0) objStart = i; depth++; }
                else if (c == '}') { depth--; if (depth == 0 && objStart >= 0) {
                    String obj = content.substring(objStart, i + 1);
                    accounts.add(new TcsAccount(extractString(obj,"id"),extractString(obj,"name"),extractString(obj,"status"),extractString(obj,"type")));
                    objStart = -1;
                } }
            }
            return accounts;
        }

        List<TcsPortfolioPosition> getPortfolio(String accountId) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetPortfolio",
                "{\"accountId\":\"" + accountId + "\",\"currency\":\"RUB\"}");
            if (json == null) return Collections.emptyList();
            return parsePortfolioPositions(json);
        }

        private List<TcsPortfolioPosition> parsePortfolioPositions(String json) {
            List<TcsPortfolioPosition> positions = new ArrayList<>();
            int as = json.indexOf("\"positions\"");
            if (as < 0) return positions;
            int depth = 0, inArray = 0, arrayStart = -1;
            for (int i = as; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[' && inArray == 0) { inArray = 1; arrayStart = i; }
                else if (inArray > 0) {
                    if (c == '[') depth++;
                    if (c == ']') { depth--; if (depth < 0) {
                        String content = json.substring(arrayStart + 1, i);
                        int od = 0, os = -1;
                        for (int j = 0; j < content.length(); j++) {
                            char ch = content.charAt(j);
                            if (ch == '{') { if (od == 0) os = j; od++; }
                            else if (ch == '}') { od--; if (od == 0 && os >= 0) {
                                String obj = content.substring(os, j + 1);
                                Double q = extractMoney(obj, "quantity");
                                positions.add(new TcsPortfolioPosition(extractString(obj,"figi"),extractString(obj,"instrumentType"),
                                    q != null ? q : 0.0, extractMoney(obj,"averagePositionPrice"), extractMoney(obj,"currentPrice"),
                                    extractMoney(obj,"expectedYield"), extractMoney(obj,"currentNkd"),
                                    extractString(obj,"ticker"), extractString(obj,"classCode")));
                                os = -1;
                            } }
                        }
                        break;
                    } }
                }
            }
            return positions;
        }

        List<BondInstrument> getAllBonds() {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/Bonds",
                        "{\"instrumentStatus\":\"INSTRUMENT_STATUS_BASE\"}", 3, false);
                    if (json != null) return parseBondsJson(json);
                } catch (Exception e) {
                    if (attempt == 3) throw new RuntimeException(e);
                    try { Thread.sleep(3000L); } catch (InterruptedException ignored) {}
                }
            }
            return Collections.emptyList();
        }

        private List<BondInstrument> parseBondsJson(String json) {
            List<BondInstrument> instruments = new ArrayList<>();
            int start = json.indexOf("\"instruments\"");
            if (start < 0) return instruments;
            int depth = 0, inArray = 0, arrayStart = -1;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[' && inArray == 0) { inArray = 1; arrayStart = i; }
                else if (inArray > 0) {
                    if (c == '[') depth++;
                    if (c == ']') { depth--; if (depth < 0) {
                        String content = json.substring(arrayStart + 1, i);
                        int od = 0, os = -1;
                        for (int j = 0; j < content.length(); j++) {
                            char ch = content.charAt(j);
                            if (ch == '{') { if (od == 0) os = j; od++; }
                            else if (ch == '}') { od--; if (od == 0 && os >= 0) {
                                String obj = content.substring(os, j + 1);
                                String md = extractString(obj, "maturityDate");
                                int tIdx = md.indexOf('T');
                                instruments.add(new BondInstrument(extractString(obj,"ticker"),extractString(obj,"name"),
                                    extractString(obj,"figi"),extractString(obj,"classCode"),extractBondNominal(obj),
                                    extractString(obj,"sector"), extractInt(obj,"couponQuantityPerYear"),
                                    tIdx > 0 ? md.substring(0, tIdx) : md));
                                os = -1;
                            } }
                        }
                        break;
                    } }
                }
            }
            return instruments;
        }

        private double extractBondNominal(String json) {
            int s = json.indexOf("\"nominal\"");
            if (s < 0) return 1000.0;
            String sub = json.substring(s);
            Matcher mu = Pattern.compile("\"units\"\\s*:\\s*\"?(\\d+)\"?").matcher(sub);
            Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(sub);
            long units = mu.find() ? Long.parseLong(mu.group(1)) : 0L;
            long nano = mn.find() ? Long.parseLong(mn.group(1)) : 0L;
            return units + nano / 1_000_000_000.0;
        }

        List<CouponEvent> getBondCoupons(String figi) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetBondCoupons",
                "{\"figi\":\"" + figi + "\"}");
            if (json == null) return Collections.emptyList();
            List<CouponEvent> events = new ArrayList<>();
            int start = json.indexOf("\"events\"");
            if (start < 0) return events;
            int depth = 0, inArray = 0, arrayStart = -1;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[' && inArray == 0) { inArray = 1; arrayStart = i; }
                else if (inArray > 0) {
                    if (c == '[') depth++;
                    if (c == ']') { depth--; if (depth < 0) {
                        String content = json.substring(arrayStart + 1, i);
                        int od = 0, os = -1;
                        for (int j = 0; j < content.length(); j++) {
                            char ch = content.charAt(j);
                            if (ch == '{') { if (od == 0) os = j; od++; }
                            else if (ch == '}') { od--; if (od == 0 && os >= 0) {
                                String obj = content.substring(os, j + 1);
                                Double p = extractMoney(obj, "payOneBond");
                                String cur = extractString(obj, "currency");
                                events.add(new CouponEvent(extractString(obj,"couponDate"), p != null ? p : 0.0,
                                    extractString(obj,"couponType"), cur.isEmpty() ? "rub" : cur));
                                os = -1;
                            } }
                        }
                        break;
                    } }
                }
            }
            return events;
        }

        List<DividendEvent> getDividends(String instrumentId) {
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetDividends",
                "{\"instrumentId\":\"" + instrumentId + "\"}", 2, false);
            if (json == null) return Collections.emptyList();
            List<DividendEvent> events = new ArrayList<>();
            int start = json.indexOf("\"dividends\"");
            if (start < 0) {
                String msg = extractString(json, "message");
                if (!msg.isEmpty()) System.out.println("  [WARN] GetDividends: " + msg);
                return events;
            }
            int depth = 0, inArray = 0, arrayStart = -1;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[' && inArray == 0) { inArray = 1; arrayStart = i; }
                else if (inArray > 0) {
                    if (c == '[') depth++;
                    if (c == ']') { depth--; if (depth < 0) {
                        String content = json.substring(arrayStart + 1, i);
                        int od = 0, os = -1;
                        for (int j = 0; j < content.length(); j++) {
                            char ch = content.charAt(j);
                            if (ch == '{') { if (od == 0) os = j; od++; }
                            else if (ch == '}') { od--; if (od == 0 && os >= 0) {
                                String obj = content.substring(os, j + 1);
                                Double amt = extractMoney(obj, "dividendNet");
                                if (amt == null || amt <= 0) amt = extractMoney(obj, "dividendAmount");
                                if (amt != null && amt > 0) {
                                    String pDate = extractString(obj, "paymentDate");
                                    String rDate = extractString(obj, "recordDate");
                                    String d = pDate.isEmpty() ? rDate : pDate;
                                    int tIdx = d.indexOf('T');
                                    events.add(new DividendEvent(tIdx > 0 ? d.substring(0, tIdx) : d, amt, extractString(obj,"currency").isEmpty() ? "rub" : extractString(obj,"currency")));
                                }
                                os = -1;
                            } }
                        }
                        break;
                    } }
                }
            }
            return events;
        }

        InstrumentInfo findInstrument(String query, String kind, String classCode) {
            String kindFilter = kind.isEmpty() ? "" : ",\"instrumentKind\":\"" + kind + "\"";
            String json = post("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/FindInstrument",
                "{\"query\":\"" + query + "\"" + kindFilter + "}", 2, false);
            if (json == null) return null;
            int as = json.indexOf("\"instruments\"");
            if (as < 0) return null;
            int depth = 0, inArray = 0, arrayStart = -1;
            for (int i = as; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[' && inArray == 0) { inArray = 1; arrayStart = i; }
                else if (inArray > 0) {
                    if (c == '[') depth++;
                    if (c == ']') { depth--; if (depth < 0) {
                        String content = json.substring(arrayStart + 1, i);
                        int od = 0, os = -1;
                        for (int j = 0; j < content.length(); j++) {
                            char ch = content.charAt(j);
                            if (ch == '{') { if (od == 0) os = j; od++; }
                            else if (ch == '}') { od--; if (od == 0 && os >= 0) {
                                String obj = content.substring(os, j + 1);
                                String ot = extractString(obj, "instrumentType");
                                String ot2 = extractString(obj, "ticker");
                                String occ = extractString(obj, "classCode");
                                if ((kind.isEmpty() || ot.equals(kind.replace("INSTRUMENT_TYPE_", "").toLowerCase())) &&
                                    (query.isEmpty() || ot2.equals(query)) &&
                                    (classCode.isEmpty() || occ.equals(classCode))) {
                                    return new InstrumentInfo(ot2, extractString(obj,"figi"), extractString(obj,"name"), ot, occ);
                                }
                                os = -1;
                            } }
                        }
                        break;
                    } }
                }
            }
            return null;
        }

        BondInstrument findBondByTicker(String ticker, List<BondInstrument> bonds) {
            for (BondInstrument b : bonds) { if (b.ticker.equals(ticker) || b.figi.equals(ticker)) return b; }
            for (BondInstrument b : bonds) {
                if (ticker.startsWith(b.ticker) || b.ticker.startsWith(ticker)) return b;
            }
            return null;
        }

        List<FinamDividendEvent> parseFinamDividends(String json) {
            List<FinamDividendEvent> events = new ArrayList<>();
            int start = json.indexOf("\"events\"");
            if (start < 0) return events;
            int depth = 0, inArray = 0, arrayStart = -1;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[' && inArray == 0) { inArray = 1; arrayStart = i; }
                else if (inArray > 0) {
                    if (c == '[') depth++;
                    if (c == ']') { depth--; if (depth < 0) {
                        String content = json.substring(arrayStart + 1, i);
                        int od = 0, os = -1;
                        for (int j = 0; j < content.length(); j++) {
                            char ch = content.charAt(j);
                            if (ch == '{') { if (od == 0) os = j; od++; }
                            else if (ch == '}') { od--; if (od == 0 && os >= 0) {
                                String obj = content.substring(os, j + 1);
                                Double amt = extractMoneyValue(obj, "dividend_amount");
                                if (amt == null || amt <= 0) amt = extractMoneyValue(obj, "amount");
                                String ds = extractString(obj, "dividend_date");
                                if (ds.isEmpty()) ds = extractString(obj, "date");
                                String cur = extractString(obj, "currency").isEmpty() ? "rub" : extractString(obj, "currency").toLowerCase();
                                if (!ds.isEmpty() && amt != null && amt > 0) {
                                    int tIdx = ds.indexOf('T');
                                    events.add(new FinamDividendEvent(tIdx > 0 ? ds.substring(0, tIdx) : ds, amt, cur));
                                }
                                os = -1;
                            } }
                        }
                        break;
                    } }
                }
            }
            return events;
        }
    }

    // ── FinamClient ──────────────────────────────────────────────────

    static class FinamClient {
        final HttpClient http = createHttpClient();
        String jwtToken;

        void authenticate() throws Exception {
            HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create("https://api.finam.ru/v1/sessions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"secret\":\"" + FINAM_SECRET + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new Exception("Finam auth failed: HTTP " + resp.statusCode());
            jwtToken = extractString(resp.body(), "token");
        }

        String getTokenDetails() throws Exception {
            HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create("https://api.finam.ru/v1/sessions/details"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + jwtToken + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new Exception("Finam token details failed: HTTP " + resp.statusCode());
            return resp.body();
        }

        String getAccount(String accountId) throws Exception {
            HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create("https://api.finam.ru/v1/accounts/" + accountId))
                    .header("Authorization", "Bearer " + jwtToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new Exception("Finam get account failed: HTTP " + resp.statusCode());
            return resp.body();
        }

        List<FinamDividendEvent> getFutureDividends(String symbol) {
            try {
                HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder().uri(URI.create("https://api.finam.ru/v1/future-dividends?symbol=" + symbol + "&sort_direction=asc&limit=50"))
                        .header("Authorization", "Bearer " + jwtToken).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) return Collections.emptyList();
                TcsClient tcs = new TcsClient();
                return tcs.parseFinamDividends(resp.body());
            } catch (Exception e) {
                System.out.println("  [WARN] Finam future-dividends error: " + e.getMessage());
                return Collections.emptyList();
            }
        }

        List<FinamPosition> parseFinamPositions(String json) {
            List<FinamPosition> positions = new ArrayList<>();
            int ps = json.indexOf("\"positions\"");
            if (ps < 0) return positions;
            int depth = 0, inArray = 0, arrayStart = -1;
            for (int i = ps; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[' && inArray == 0) { inArray = 1; arrayStart = i; }
                else if (inArray > 0) {
                    if (c == '[') depth++;
                    if (c == ']') { depth--; if (depth < 0) {
                        String content = json.substring(arrayStart + 1, i);
                        int od = 0, os = -1;
                        for (int j = 0; j < content.length(); j++) {
                            char ch = content.charAt(j);
                            if (ch == '{') { if (od == 0) os = j; od++; }
                            else if (ch == '}') { od--; if (od == 0 && os >= 0) {
                                String obj = content.substring(os, j + 1);
                                String qv = extractValue(obj, "quantity");
                                String apv = extractValue(obj, "average_price");
                                String cpv = extractValue(obj, "current_price");
                                String upv = extractValue(obj, "unrealized_pnl");
                                positions.add(new FinamPosition(extractString(obj,"symbol"),
                                    qv.isEmpty() ? 0 : Double.parseDouble(qv),
                                    apv.isEmpty() ? 0 : Double.parseDouble(apv),
                                    cpv.isEmpty() ? 0 : Double.parseDouble(cpv),
                                    upv.isEmpty() ? 0 : Double.parseDouble(upv)));
                                os = -1;
                            } }
                        }
                        break;
                    } }
                }
            }
            return positions;
        }
    }

    // ── Monthly chart ────────────────────────────────────────────────

    static String buildMonthlyChart(Map<String, Double> monthlyPayments, String title, int totalMonths, double portfolioCost, double payingPortfolioCost) {
        if (monthlyPayments.isEmpty()) return "  Нет данных о платежах";
        YearMonth now = YearMonth.now();
        List<YearMonth> relevantMonths = new ArrayList<>();
        for (int i = 0; i < totalMonths; i++) relevantMonths.add(now.plusMonths(i));

        double maxPayment = 1.0;
        for (YearMonth m : relevantMonths) { double v = monthlyPayments.getOrDefault(m.toString(), 0.0); if (v > maxPayment) maxPayment = v; }
        int barMaxWidth = 40;

        StringBuilder sb = new StringBuilder();
        sb.append("\n================================================================\n");
        sb.append("  ").append(title).append("\n");
        sb.append("================================================================\n\n");

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM yyyy", java.util.Locale.of("ru"));
        for (YearMonth month : relevantMonths) {
            double payment = monthlyPayments.getOrDefault(month.toString(), 0.0);
            String label = month.format(fmt).replace(".", "");
            int barLen = maxPayment > 0 ? Math.max(1, (int)((payment / maxPayment) * barMaxWidth)) : 1;
            StringBuilder bar = new StringBuilder();
            for (int b = 0; b < barLen; b++) bar.append("\u2588");
            sb.append(String.format("  %-10s ", label)).append("\u2502 ").append(bar).append(" \u2502 ").append(String.format("%10.2f", payment)).append(" руб\n");
        }

        sb.append("\n");
        double totalPayments = 0;
        for (YearMonth m : relevantMonths) totalPayments += monthlyPayments.getOrDefault(m.toString(), 0.0);
        double avgPayment = totalPayments / totalMonths;
        sb.append(String.format("  Всего за 12 мес: %.2f руб\n", totalPayments));
        sb.append(String.format("  В среднем в мес: %.2f руб\n", avgPayment));
        if (portfolioCost > 0) sb.append(String.format("  Средняя доходность: %.2f%% годовых\n", totalPayments / portfolioCost * 100));
        if (payingPortfolioCost > 0) sb.append(String.format("  Средняя доходность (только платящие): %.2f%% годовых\n", totalPayments / payingPortfolioCost * 100));
        sb.append("================================================================\n");
        return sb.toString();
    }

    // ── Main ─────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("=== Портфельный сканер (Finam + T-Invest) ===\n");

        if (TCS_API_KEY.isEmpty()) {
            System.out.println("  [ERROR] Не настроен T-Invest API ключ");
            System.exit(1);
        }

        boolean finamAvailable = !FINAM_SECRET.isEmpty();

        try {
            TcsClient tcs = new TcsClient();
            FinamClient finam = null;
            List<AccountInfo> finamAccounts = Collections.emptyList();

            if (finamAvailable) {
                System.out.println("  [1/6] Авторизация в Finam API...");
                finam = new FinamClient();
                finam.authenticate();
                System.out.println("  [OK]");
                System.out.println("  [2/6] Получение счетов Finam...");
                String td = finam.getTokenDetails();
                finamAccounts = extractFinamAccounts(td);
                System.out.println("  Найдено счетов: " + finamAccounts.size());
                if (!FINAM_ACCOUNT_ID.isEmpty()) {
                    List<AccountInfo> filtered = new ArrayList<>();
                    for (AccountInfo a : finamAccounts) { if (a.id.equals(FINAM_ACCOUNT_ID)) filtered.add(a); }
                    finamAccounts = filtered;
                    System.out.println("  (фильтр: только счёт " + FINAM_ACCOUNT_ID + ")");
                }
            } else {
                System.out.println("  [1/6] Finam API ключ не указан — работаем только с T-Invest");
                System.out.println("  [2/6] Пропущено");
            }

            System.out.println("\n  [3/6] Получение счетов T-Invest...");
            List<TcsAccount> tcsAccounts = tcs.getAccounts();
            System.out.println("  Найдено счетов: " + tcsAccounts.size());
            for (TcsAccount a : tcsAccounts) System.out.println("    - " + a.name + " (" + a.id.substring(0, Math.min(8, a.id.length())) + "...)" + " [" + a.type + "]");

            if (finamAccounts.isEmpty() && tcsAccounts.isEmpty()) {
                System.out.println("  [ERROR] Нет доступных счетов");
                System.exit(1);
            }

            System.out.println("\n  [4/6] Загрузка справочника облигаций...");
            List<BondInstrument> allBonds = tcs.getAllBonds();
            System.out.println("  Загружено: " + allBonds.size() + " облигаций");

            System.out.println("\n  [5/6] Сбор позиций со всех счетов...\n");

            Map<String, UnifiedHolding> finamHoldings = new LinkedHashMap<>();
            double finamEquity = 0;
            Map<String, FinamPosition> finamPositionsByTicker = new LinkedHashMap<>();

            if (finam != null) {
                for (AccountInfo acc : finamAccounts) {
                    String accountJson = finam.getAccount(acc.id);
                    double equity = extractEquity(accountJson);
                    finamEquity += equity;
                    String cashStr = formatCash(accountJson);
                    List<FinamPosition> positions = finam.parseFinamPositions(accountJson);
                    System.out.println("  [Finam] Счёт " + acc.id + ": " + positions.size() + " позиций, капитал " + equity + " руб, кэш " + cashStr + " руб");

                    for (FinamPosition pos : positions) {
                        String ticker = extractTickerFromSymbol(pos.symbol);
                        finamPositionsByTicker.put(ticker, pos);
                        BondInstrument bond = tcs.findBondByTicker(ticker, allBonds);
                        double nominal = bond != null ? bond.nominal : 1000.0;
                        String sector = bond != null ? bond.sector : "";
                        int couponPerYear = bond != null ? bond.couponQuantityPerYear : 0;
                        String maturity = bond != null ? bond.maturityDate : "";
                        String name = bond != null ? bond.name : ticker;
                        String figi = bond != null ? bond.figi : "";
                        double avgPriceRub = pos.averagePrice * nominal / 100.0;
                        double curPriceRub = pos.currentPrice * nominal / 100.0;
                        String key = figi.isEmpty() ? ticker : figi;

                        UnifiedHolding existing = finamHoldings.get(key);
                        if (existing != null) {
                            double newQty = existing.quantity + pos.quantity;
                            double newAvg = newQty > 0 ? (existing.quantity * existing.avgPrice + pos.quantity * avgPriceRub) / newQty : existing.avgPrice;
                            finamHoldings.put(key, new UnifiedHolding(existing.figi, existing.ticker, existing.name, existing.instrumentType,
                                newQty, newAvg, existing.curPrice, existing.nominal, existing.sector, existing.couponPerYear,
                                existing.maturityDate, new LinkedHashSet<>(existing.sources) {{ add("finam"); }}, existing.payments));
                        } else {
                            Set<String> src = new LinkedHashSet<>(); src.add("finam");
                            finamHoldings.put(key, new UnifiedHolding(figi, ticker, name, bond != null ? "bond" : "share",
                                pos.quantity, avgPriceRub, curPriceRub, nominal, sector, couponPerYear, maturity, src, Collections.emptyList()));
                        }
                    }
                }
            }

            Map<String, UnifiedHolding> tcsHoldings = new LinkedHashMap<>();
            Map<String, double[]> tcsAccountDetails = new LinkedHashMap<>();

            for (TcsAccount acc : tcsAccounts) {
                List<TcsPortfolioPosition> positions = tcs.getPortfolio(acc.id);
                double accountEquity = 0, futuresValue = 0;
                for (TcsPortfolioPosition pos : positions) {
                    double value = pos.quantity * (pos.curPrice != null ? pos.curPrice : 0);
                    if ("futures".equalsIgnoreCase(pos.instrumentType)) futuresValue += value;
                    else accountEquity += value;
                }
                double accountCash = tcs.getWithdrawLimits(acc.id);
                tcsAccountDetails.put(acc.id, new double[]{accountEquity, accountCash});

                String cashStr = (accountCash > 0 || "ACCOUNT_TYPE_TINKOFF".equals(acc.type) || "ACCOUNT_TYPE_TINKOFF_IIS".equals(acc.type))
                    ? String.format(java.util.Locale.US, "%.2f", accountCash) : "н/д";
                System.out.printf("  [T-Invest] Счёт %s (%s): %d позиций, капитал %.2f руб, кэш %s руб%n",
                    acc.name, acc.id, positions.size(), accountEquity, cashStr);
                if (futuresValue > 0) System.out.printf(" (фьючерсы: %.0f руб, в капитал не входят)%n", futuresValue);

                for (TcsPortfolioPosition pos : positions) {
                    if (!"bond".equals(pos.instrumentType) && !"share".equals(pos.instrumentType) && !"etf".equals(pos.instrumentType)) continue;
                    String ticker, name; double nominal; String sector; int couponPerYear; String maturity;
                    if ("bond".equals(pos.instrumentType)) {
                        BondInstrument bond = tcs.findBondByTicker(pos.ticker.isEmpty() ? pos.figi : pos.ticker, allBonds);
                        ticker = pos.ticker.isEmpty() ? (bond != null ? bond.ticker : pos.figi) : pos.ticker;
                        name = bond != null ? bond.name : (pos.ticker.isEmpty() ? pos.figi : pos.ticker);
                        nominal = bond != null ? bond.nominal : 1000.0;
                        sector = bond != null ? bond.sector : "";
                        couponPerYear = bond != null ? bond.couponQuantityPerYear : 0;
                        maturity = bond != null ? bond.maturityDate : "";
                    } else {
                        String kindFilter = "share".equals(pos.instrumentType) ? "INSTRUMENT_TYPE_SHARE" : "etf".equals(pos.instrumentType) ? "INSTRUMENT_TYPE_ETF" : "";
                        InstrumentInfo info = !pos.ticker.isEmpty() ? tcs.findInstrument(pos.ticker, kindFilter, pos.classCode) : null;
                        ticker = pos.ticker.isEmpty() ? (info != null ? info.ticker : pos.figi) : pos.ticker;
                        name = info != null ? info.name : (pos.ticker.isEmpty() ? pos.figi : pos.ticker);
                        nominal = 1000.0; sector = ""; couponPerYear = 0; maturity = "";
                    }
                    double avgPriceRub = pos.avgPrice != null ? pos.avgPrice : 0;
                    double curPriceRub = pos.curPrice != null ? pos.curPrice : 0;

                    UnifiedHolding existing = tcsHoldings.get(pos.figi);
                    if (existing != null) {
                        double newQty = existing.quantity + pos.quantity;
                        double newAvg = (newQty > 0 && avgPriceRub > 0) ? (existing.quantity * existing.avgPrice + pos.quantity * avgPriceRub) / newQty : existing.avgPrice;
                        tcsHoldings.put(pos.figi, new UnifiedHolding(existing.figi, existing.ticker, existing.name, existing.instrumentType,
                            newQty, newAvg, existing.curPrice, existing.nominal, existing.sector, existing.couponPerYear,
                            existing.maturityDate, new LinkedHashSet<>(existing.sources) {{ add("tcs"); }}, existing.payments));
                    } else {
                        Set<String> src = new LinkedHashSet<>(); src.add("tcs");
                        tcsHoldings.put(pos.figi, new UnifiedHolding(pos.figi, ticker, name, pos.instrumentType,
                            pos.quantity, avgPriceRub, curPriceRub, nominal, sector, couponPerYear, maturity, src, Collections.emptyList()));
                    }
                }
            }

            // Merge
            Map<String, UnifiedHolding> merged = new LinkedHashMap<>();
            for (Map.Entry<String, UnifiedHolding> e : tcsHoldings.entrySet()) merged.put(e.getKey(), e.getValue());
            for (Map.Entry<String, UnifiedHolding> e : finamHoldings.entrySet()) {
                UnifiedHolding existing = merged.get(e.getKey());
                UnifiedHolding h = e.getValue();
                if (existing != null) {
                    double newQty = existing.quantity + h.quantity;
                    double newAvg = newQty > 0 ? (existing.quantity * existing.avgPrice + h.quantity * h.avgPrice) / newQty : existing.avgPrice;
                    Set<String> src = new LinkedHashSet<>(existing.sources); src.addAll(h.sources);
                    merged.put(e.getKey(), new UnifiedHolding(existing.figi, existing.ticker, existing.name, existing.instrumentType,
                        newQty, newAvg, existing.curPrice > 0 ? existing.curPrice : h.curPrice, existing.nominal, existing.sector,
                        existing.couponPerYear, existing.maturityDate, src, existing.payments));
                } else merged.put(e.getKey(), h);
            }

            System.out.println("\n  Всего позиций (уникальных): " + merged.size());

            // 6. Fetch payments
            System.out.println("\n  [6/6] Загрузка будущих купонов и дивидендов...\n");
            String todayStr = LocalDate.now().toString();

            for (Map.Entry<String, UnifiedHolding> e : merged.entrySet()) {
                UnifiedHolding h = e.getValue();
                List<PaymentEvent> payments = new ArrayList<>();
                if ("bond".equals(h.instrumentType) && !h.figi.isEmpty()) {
                    for (CouponEvent c : tcs.getBondCoupons(h.figi)) {
                        String d = c.couponDate.contains("T") ? c.couponDate.substring(0, c.couponDate.indexOf('T')) : c.couponDate;
                        if (d.compareTo(todayStr) >= 0 && "rub".equals(c.currency))
                            payments.add(new PaymentEvent(d, c.payOneBond, "coupon", c.currency));
                    }
                }
                if (("share".equals(h.instrumentType) || "etf".equals(h.instrumentType)) && !h.figi.isEmpty()) {
                    for (DividendEvent d : tcs.getDividends(h.figi)) {
                        if (d.dividendDate.compareTo(todayStr) >= 0 && "rub".equals(d.currency))
                            payments.add(new PaymentEvent(d.dividendDate, d.dividendAmount, "dividend", d.currency));
                    }
                }
                payments.sort(Comparator.comparing(p -> p.date));
                List<PaymentEvent> finalPayments = payments;
                merged.put(e.getKey(), new UnifiedHolding(h.figi, h.ticker, h.name, h.instrumentType, h.quantity, h.avgPrice, h.curPrice,
                    h.nominal, h.sector, h.couponPerYear, h.maturityDate, h.sources, finalPayments));
            }

            // Finam fallback
            if (finam != null) {
                for (Map.Entry<String, UnifiedHolding> e : merged.entrySet()) {
                    UnifiedHolding h = e.getValue();
                    if (!h.payments.isEmpty()) continue;
                    FinamPosition pos = finamPositionsByTicker.get(h.ticker);
                    if (pos != null) {
                        List<FinamDividendEvent> divs = finam.getFutureDividends(pos.symbol);
                        List<PaymentEvent> futureDivs = new ArrayList<>();
                        for (FinamDividendEvent d : divs) {
                            if (d.date.compareTo(todayStr) >= 0 && "rub".equals(d.currency))
                                futureDivs.add(new PaymentEvent(d.date, d.amountPerShare, "dividend", d.currency));
                        }
                        if (!futureDivs.isEmpty()) {
                            futureDivs.sort(Comparator.comparing(p -> p.date));
                            merged.put(e.getKey(), new UnifiedHolding(h.figi, h.ticker, h.name, h.instrumentType, h.quantity, h.avgPrice, h.curPrice,
                                h.nominal, h.sector, h.couponPerYear, h.maturityDate, h.sources, futureDivs));
                        }
                    }
                }
            }

            // Display
            System.out.println("\n==================================================================");
            System.out.println("  СВОДНЫЙ ПОРТФЕЛЬ (все счета, все брокеры)");
            System.out.println("==================================================================\n");

            List<Map.Entry<String, UnifiedHolding>> sorted = new ArrayList<>(merged.entrySet());
            sorted.removeIf(e -> e.getValue().quantity <= 0);
            sorted.sort((a, b) -> Double.compare(b.getValue().quantity * b.getValue().curPrice, a.getValue().quantity * a.getValue().curPrice));

            List<Map.Entry<String, UnifiedHolding>> liquidH = new ArrayList<>(), futuresH = new ArrayList<>();
            for (Map.Entry<String, UnifiedHolding> e : sorted) {
                if ("futures".equalsIgnoreCase(e.getValue().instrumentType)) futuresH.add(e);
                else liquidH.add(e);
            }

            double totalLiquidValue = 0, totalFuturesValue = 0;
            for (Map.Entry<String, UnifiedHolding> e : liquidH) totalLiquidValue += e.getValue().quantity * e.getValue().curPrice;
            for (Map.Entry<String, UnifiedHolding> e : futuresH) totalFuturesValue += e.getValue().quantity * e.getValue().curPrice;

            if (sorted.isEmpty()) {
                System.out.println("  Нет позиций");
            } else {
                System.out.printf(" #  | %-5s | %-16s | %-25s | %6s | %8s | %8s | %9s | %5s | %10s | %5s | %9s | %3s%n",
                    "Тип", "Тикер", "Название", "Кол-во", "Средняя", "Цена", "Стоим.", "Доля", "Купон/мес", "Купон%", "Погашение", "Ист");
                System.out.println("------------------------------------------------------------------------------------------------------------------------------------------");

                int idx = 0;
                for (Map.Entry<String, UnifiedHolding> e : sorted) {
                    UnifiedHolding h = e.getValue();
                    idx++;
                    double curValue = h.quantity * h.curPrice;
                    double share = totalLiquidValue > 0 ? curValue / totalLiquidValue * 100 : 0.0;
                    List<PaymentEvent> fixedP = new ArrayList<>();
                    for (PaymentEvent p : h.payments) if ("coupon".equals(p.type)) fixedP.add(p);
                    double avgCoupon;
                    if (!fixedP.isEmpty()) { double s = 0; for (PaymentEvent p : fixedP) s += p.amountPerUnit; avgCoupon = s / fixedP.size(); }
                    else if (!h.payments.isEmpty()) { double s = 0; for (PaymentEvent p : h.payments) s += p.amountPerUnit; avgCoupon = s / h.payments.size(); }
                    else avgCoupon = 0;
                    double monthlyCoupon = avgCoupon * h.quantity;
                    double annualYield = (avgCoupon > 0 && h.couponPerYear > 0 && h.avgPrice > 0) ? (avgCoupon * h.couponPerYear) / h.avgPrice * 100 : 0;
                    String mature = h.maturityDate.isEmpty() ? "" : formatDate(h.maturityDate);
                    String srcTag = h.sources.size() == 2 ? "F+T" : h.sources.contains("tcs") ? " T " : " F ";

                    System.out.printf("%3d | %5s | %-16s | %-25s | %6.0f | %8.2f | %8.2f | %9.2f | %5.1f%% | %10.2f | %5.2f%% | %s  %s%n",
                        idx, typeShort(h.instrumentType), h.ticker, h.name.substring(0, Math.min(25, h.name.length())),
                        h.quantity, h.avgPrice, h.curPrice, curValue, share, monthlyCoupon, annualYield, mature, srcTag);
                }
                System.out.println("------------------------------------------------------------------------------------------------------------------------------------------");

                double totalLiquidCost = 0, totalFuturesCost = 0;
                for (Map.Entry<String, UnifiedHolding> e : liquidH) totalLiquidCost += e.getValue().quantity * e.getValue().avgPrice;
                for (Map.Entry<String, UnifiedHolding> e : futuresH) totalFuturesCost += e.getValue().quantity * e.getValue().avgPrice;
                double liquidPnl = totalLiquidValue - totalLiquidCost;
                double liquidPnlPct = totalLiquidCost > 0 ? liquidPnl / totalLiquidCost * 100 : 0;

                System.out.println();
                System.out.printf("  Ликвидные активы:  %.2f руб (затраты %.2f руб, P&L %+.2f руб %+.2f%%)%n", totalLiquidValue, totalLiquidCost, liquidPnl, liquidPnlPct);
                if (!futuresH.isEmpty()) {
                    double futuresPnl = totalFuturesValue - totalFuturesCost;
                    double futuresPnlPct = totalFuturesCost > 0 ? futuresPnl / totalFuturesCost * 100 : 0;
                    System.out.printf("  Фьючерсы (контракты): %.2f руб (P&L %+.2f руб %+.2f%%)%n", totalFuturesValue, futuresPnl, futuresPnlPct);
                    System.out.println("  \u26A0 Фьючерсы НЕ считаются стоимостью портфеля \u2014 реальный вклад = только ГО.");
                }
                System.out.printf("  Итого (портфель): %.2f руб%n", totalLiquidValue);
                System.out.println("==================================================================");
            }

            // Monthly chart
            Map<String, Double> monthlyPayments = new TreeMap<>();
            Map<String, Double> monthlyPaymentsPerTicker = new TreeMap<>();
            for (Map.Entry<String, UnifiedHolding> e : sorted) {
                UnifiedHolding h = e.getValue();
                for (PaymentEvent p : h.payments) {
                    try {
                        LocalDate d = LocalDate.parse(p.date);
                        String ym = YearMonth.from(d).toString();
                        double total = p.amountPerUnit * h.quantity;
                        monthlyPayments.merge(ym, total, Double::sum);
                    } catch (Exception ignored) {}
                }
            }

            if (!monthlyPayments.isEmpty()) {
                double payingValue = 0;
                for (Map.Entry<String, UnifiedHolding> e : liquidH) {
                    if (!e.getValue().payments.isEmpty()) payingValue += e.getValue().quantity * e.getValue().curPrice;
                }
                System.out.println(buildMonthlyChart(monthlyPayments, "ГРАФИК ОЖИДАЕМЫХ ВЫПЛАТ (КУПОНЫ + ДИВИДЕНДЫ) ПО МЕСЯЦАМ", 12, totalLiquidValue, payingValue));
            } else {
                System.out.println("\n  Нет будущих выплат для отображения");
            }

            // Per-account breakdown
            if (finam != null && !finamAccounts.isEmpty()) {
                System.out.println("\n==================================================================");
                System.out.println("  ДЕТАЛИЗАЦИЯ ПО СЧЕТАМ FINAM");
                System.out.println("==================================================================");
                for (AccountInfo acc : finamAccounts) {
                    System.out.println("\n--- Счёт Finam: " + acc.id + " ---");
                    String accountJson = finam.getAccount(acc.id);
                    System.out.println("  Капитал: " + extractEquity(accountJson) + " руб, Кэш: " + formatCash(accountJson) + " руб");
                }
            }

            if (!tcsAccounts.isEmpty()) {
                System.out.println("\n==================================================================");
                System.out.println("  ДЕТАЛИЗАЦИЯ ПО СЧЕТАМ T-INVEST");
                System.out.println("==================================================================");
                for (TcsAccount acc : tcsAccounts) {
                    System.out.println("\n--- Счёт: " + acc.name + " (" + acc.id + ") ---");
                    System.out.println("  Тип: " + acc.type + ", Статус: " + acc.status);
                    double[] details = tcsAccountDetails.get(acc.id);
                    if (details != null) {
                        double eq = details[0], cash = details[1];
                        String cashStr = (cash > 0 || "ACCOUNT_TYPE_TINKOFF".equals(acc.type) || "ACCOUNT_TYPE_TINKOFF_IIS".equals(acc.type))
                            ? String.format(java.util.Locale.US, "%.2f", cash) : "н/д";
                        System.out.printf("  Капитал: %.2f руб, Кэш: %s руб%n", eq, cashStr);
                    }
                }
            }

            System.out.println("\n  Готово.");

        } catch (Exception e) {
            System.out.println("\n  [ERROR] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
