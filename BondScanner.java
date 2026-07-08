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

public class BondScanner {

    // ── Constants ────────────────────────────────────────────────────

    static final String TCS_API_KEY;
    static {
        String key = "";
        try {
            String path = System.getProperty("user.dir") + "/application.properties";
            Properties p = new Properties();
            p.load(new FileInputStream(path));
            key = p.getProperty("tcs.apiKey", "");
        } catch (Exception e) { System.out.println("  [WARN] loadProperties: " + e.getMessage()); }
        TCS_API_KEY = key;
    }

    static final Map<String, Integer> RISK_LEVELS;
    static final Map<String, String> RISK_TO_RATING;
    static final Map<String, String> RISK_LEVEL_ALIASES;
    static final Map<Integer, String> FINANCIAL_SCORE_LABELS;
    static {
        RISK_LEVELS = new LinkedHashMap<>();
        RISK_LEVELS.put("RISK_LEVEL_LOW", 1); RISK_LEVELS.put("RISK_LEVEL_MODERATE", 2);
        RISK_LEVELS.put("RISK_LEVEL_HIGH", 3); RISK_LEVELS.put("RISK_LEVEL_VERY_HIGH", 4);
        RISK_TO_RATING = new LinkedHashMap<>();
        RISK_TO_RATING.put("RISK_LEVEL_LOW", "AAA"); RISK_TO_RATING.put("RISK_LEVEL_MODERATE", "AA-BB");
        RISK_TO_RATING.put("RISK_LEVEL_HIGH", "B-CCC"); RISK_TO_RATING.put("RISK_LEVEL_VERY_HIGH", "D");
        RISK_LEVEL_ALIASES = new LinkedHashMap<>();
        RISK_LEVEL_ALIASES.put("AAA", "RISK_LEVEL_LOW"); RISK_LEVEL_ALIASES.put("LOW", "RISK_LEVEL_LOW");
        RISK_LEVEL_ALIASES.put("MODERATE", "RISK_LEVEL_MODERATE"); RISK_LEVEL_ALIASES.put("HIGH", "RISK_LEVEL_HIGH");
        RISK_LEVEL_ALIASES.put("VERY_HIGH", "RISK_LEVEL_VERY_HIGH");
        FINANCIAL_SCORE_LABELS = new LinkedHashMap<>();
        FINANCIAL_SCORE_LABELS.put(1, "плохо"); FINANCIAL_SCORE_LABELS.put(2, "слабо");
        FINANCIAL_SCORE_LABELS.put(3, "средне"); FINANCIAL_SCORE_LABELS.put(4, "хорошо");
        FINANCIAL_SCORE_LABELS.put(5, "отлично");
    }

    // ── Data classes ─────────────────────────────────────────────────

    static class BondConfig {
        final double targetYield; final int couponFrequency; final boolean noAmortization;
        final boolean excludeFloating; final boolean excludeQualInvestorOnly;
        final String minRiskLevel; final int maxBondsCount; final double brokerCommission;
        final double capital; final int minDaysToMaturity; final int maxDaysToMaturity;
        final boolean autoMode; final boolean dryRun; final String accountId;
        final int autoBuyCount; final double autoBuyAmount;
        BondConfig(double ty, int cf, boolean na, boolean ef, boolean eq, String mr, int mc,
                   double bc, double cap, int mdn, int mxd, boolean am, boolean dr, String ai,
                   int abc, double aba) {
            targetYield=ty; couponFrequency=cf; noAmortization=na; excludeFloating=ef;
            excludeQualInvestorOnly=eq; minRiskLevel=mr; maxBondsCount=mc; brokerCommission=bc;
            capital=cap; minDaysToMaturity=mdn; maxDaysToMaturity=mxd; autoMode=am;
            dryRun=dr; accountId=ai; autoBuyCount=abc; autoBuyAmount=aba;
        }
    }

    static class BondData {
        final String ticker, name, figi, classCode, riskLevel, sector, assetUid, financialScoreLabel;
        final double price, nominal, couponAmount, currentYield, ytm, totalCoupons, totalProfit, commission, netProfit, netYield;
        final int couponFrequency, daysToMaturity;
        final LocalDate maturityDate;
        final boolean amortizationFlag;
        final int financialScore;
        final IssuerFundamentals fundamentals;
        BondData(String ticker, String name, String figi, String classCode, double price, double nominal,
                 double couponAmount, int couponFrequency, LocalDate maturityDate, int daysToMaturity,
                 double currentYield, double ytm, String riskLevel, String sector, boolean amortizationFlag,
                 double totalCoupons, double totalProfit, double commission, double netProfit, double netYield,
                 String assetUid, int financialScore, String financialScoreLabel, IssuerFundamentals fundamentals) {
            this.ticker=ticker; this.name=name; this.figi=figi; this.classCode=classCode; this.price=price;
            this.nominal=nominal; this.couponAmount=couponAmount; this.couponFrequency=couponFrequency;
            this.maturityDate=maturityDate; this.daysToMaturity=daysToMaturity; this.currentYield=currentYield;
            this.ytm=ytm; this.riskLevel=riskLevel; this.sector=sector; this.amortizationFlag=amortizationFlag;
            this.totalCoupons=totalCoupons; this.totalProfit=totalProfit; this.commission=commission;
            this.netProfit=netProfit; this.netYield=netYield; this.assetUid=assetUid;
            this.financialScore=financialScore; this.financialScoreLabel=financialScoreLabel;
            this.fundamentals=fundamentals;
        }
    }

    static class IssuerFundamentals {
        final String name; final double debtToEquity, netDebtToEbitda, currentRatio, roe, roic, netMargin, revenueGrowth5y, marketCap;
        IssuerFundamentals(String n, double d, double n2, double c, double r, double ri, double nm, double rg, double mc) {
            name=n; debtToEquity=d; netDebtToEbitda=n2; currentRatio=c; roe=r; roic=ri; netMargin=nm; revenueGrowth5y=rg; marketCap=mc;
        }
    }

    static class TcsAccount { final String id, name, type; TcsAccount(String i,String n,String t){id=i;name=n;type=t;} }

    static class BondInstrument {
        final String ticker, name, figi, classCode, assetUid, maturityDate, riskLevel, sector;
        final double nominal; final int couponQuantityPerYear;
        final boolean amortizationFlag, floatingCouponFlag, forQualInvestorFlag;
        BondInstrument(String t, String n, String f, String cc, String au, int cqty, String md,
                       double nom, String rl, String s, boolean af, boolean fcf, boolean fqf) {
            ticker=t;name=n;figi=f;classCode=cc;assetUid=au;couponQuantityPerYear=cqty;maturityDate=md;
            nominal=nom;riskLevel=rl;sector=s;amortizationFlag=af;floatingCouponFlag=fcf;forQualInvestorFlag=fqf;
        }
    }

    static class ShareAssetRef { final String assetUid, name, ticker; ShareAssetRef(String a,String n,String t){assetUid=a;name=n;ticker=t;} }
    static class CouponEvent { final String couponDate, couponType, currency; final double payOneBond;
        CouponEvent(String d,double p,String t,String c){couponDate=d;payOneBond=p;couponType=t;currency=c;} }
    static class FinamOrderBookEntry { final double price, quantity; FinamOrderBookEntry(double p,double q){price=p;quantity=q;} }
    static class FinamOrderBook { final List<FinamOrderBookEntry> bids, asks; FinamOrderBook(List<FinamOrderBookEntry> b, List<FinamOrderBookEntry> a){bids=b;asks=a;} }

    // ── Utility methods ──────────────────────────────────────────────

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

    static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? parseInt(m.group(1)) : 0;
    }

    static boolean extractBoolean(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([0-9.]+(?:[eE][+-]?\\d+)?)").matcher(json);
        return m.find() ? parseDouble(m.group(1)) : 0.0;
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

    static double extractNominal(String json) {
        int s = json.indexOf("\"nominal\"");
        if (s < 0) return 1000.0;
        String sub = json.substring(s);
        Matcher mu = Pattern.compile("\"units\"\\s*:\\s*\"?(\\d+)\"?").matcher(sub);
        Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(sub);
        long units = mu.find() ? Long.parseLong(mu.group(1)) : 0L;
        long nano = mn.find() ? Long.parseLong(mn.group(1)) : 0L;
        return units + nano / 1_000_000_000.0;
    }

    static double extractPrice(String json) {
        int s = json.indexOf("\"price\"");
        if (s < 0) return 0.0;
        String sub = json.substring(s);
        Matcher mu = Pattern.compile("\"units\"\\s*:\\s*\"?(\\d+)\"?").matcher(sub);
        Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(sub);
        long units = mu.find() ? Long.parseLong(mu.group(1)) : 0L;
        long nano = mn.find() ? Long.parseLong(mn.group(1)) : 0L;
        return units + nano / 1_000_000_000.0;
    }

    static double extractCouponPayment(String json) {
        int s = json.indexOf("\"payOneBond\"");
        if (s < 0) return 0.0;
        String sub = json.substring(s);
        Matcher mu = Pattern.compile("\"units\"\\s*:\\s*\"?(\\d+)\"?").matcher(sub);
        Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(sub);
        long units = mu.find() ? Long.parseLong(mu.group(1)) : 0L;
        long nano = mn.find() ? Long.parseLong(mn.group(1)) : 0L;
        return units + nano / 1_000_000_000.0;
    }

    static String extractCouponCurrency(String json) {
        int s = json.indexOf("\"payOneBond\"");
        if (s < 0) return "rub";
        Matcher m = Pattern.compile("\"currency\"\\s*:\\s*\"([^\"]*)\"").matcher(json.substring(s));
        return m.find() ? m.group(1) : "rub";
    }

    static String extractNestedValue(String json, String key) {
        int ki = json.indexOf("\"" + key + "\"");
        if (ki < 0) return "";
        Matcher m = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"").matcher(json.substring(ki));
        return m.find() ? m.group(1) : "";
    }

    static int parseInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
    static double parseDouble(String s) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }
    static long parseLong(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; } }

    static String normalizeRiskLevel(String level) {
        String up = level.toUpperCase();
        return RISK_LEVEL_ALIASES.containsKey(up) ? RISK_LEVEL_ALIASES.get(up) : level;
    }

    static String formatAcceptedRisk(String maxRiskLevel) {
        Integer maxValue = RISK_LEVELS.get(maxRiskLevel);
        if (maxValue == null) return maxRiskLevel;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : RISK_LEVELS.entrySet()) {
            if (e.getValue() <= maxValue) {
                String rating = RISK_TO_RATING.getOrDefault(e.getKey(), e.getKey());
                parts.add(rating);
            }
        }
        return String.join(", ", parts);
    }

    static String financialScoreLabel(int score) {
        return score >= 1 && score <= 5 ? FINANCIAL_SCORE_LABELS.get(score) : "н/д";
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

    // ── Financial scoring ────────────────────────────────────────────

    static int calculateFinancialHealthScore(IssuerFundamentals f) {
        double score = 0, weight = 0;
        if (f.debtToEquity >= 0.01 && f.debtToEquity <= 500) {
            double s = f.debtToEquity <= 30 ? 100 : f.debtToEquity <= 50 ? 80 : f.debtToEquity <= 100 ? 60 : f.debtToEquity <= 200 ? 40 : f.debtToEquity <= 300 ? 20 : 10;
            score += s * 2; weight += 2;
        }
        if (f.netDebtToEbitda >= 0.01 && f.netDebtToEbitda <= 50) {
            double s = f.netDebtToEbitda <= 1 ? 100 : f.netDebtToEbitda <= 2 ? 80 : f.netDebtToEbitda <= 3 ? 60 : f.netDebtToEbitda <= 5 ? 40 : f.netDebtToEbitda <= 8 ? 20 : 10;
            score += s * 2; weight += 2;
        }
        if (f.currentRatio >= 0.01 && f.currentRatio <= 20) {
            double s = f.currentRatio >= 2 ? 100 : f.currentRatio >= 1.5 ? 80 : f.currentRatio >= 1.2 ? 60 : f.currentRatio >= 1.0 ? 40 : f.currentRatio >= 0.8 ? 20 : 10;
            score += s * 1.5; weight += 1.5;
        }
        if (f.roe >= 0.01 && f.roe <= 100) {
            double s = f.roe >= 20 ? 100 : f.roe >= 15 ? 80 : f.roe >= 10 ? 60 : f.roe >= 5 ? 40 : 20;
            score += s; weight++;
        }
        if (f.roic >= 0.01 && f.roic <= 100) {
            double s = f.roic >= 15 ? 100 : f.roic >= 10 ? 80 : f.roic >= 7 ? 60 : f.roic >= 4 ? 40 : 20;
            score += s; weight++;
        }
        if (f.netMargin >= 0.01 && f.netMargin <= 100) {
            double s = f.netMargin >= 20 ? 100 : f.netMargin >= 15 ? 80 : f.netMargin >= 10 ? 60 : f.netMargin >= 5 ? 40 : 20;
            score += s; weight++;
        }
        if (f.revenueGrowth5y > 0) {
            double s = f.revenueGrowth5y >= 15 ? 100 : f.revenueGrowth5y >= 10 ? 80 : f.revenueGrowth5y >= 5 ? 60 : f.revenueGrowth5y >= 2 ? 40 : 20;
            score += s * 0.5; weight += 0.5;
        }
        if (weight <= 0) return 0;
        double avg = score / weight;
        if (avg < 20) return 1; if (avg < 40) return 2; if (avg < 60) return 3; if (avg < 80) return 4; return 5;
    }

    static String findIssuerAssetUid(String bondName, List<ShareAssetRef> shareAssets) {
        String normalized = bondName
            .replaceAll("\\s+\\d+Р?-\\d+.*$", "")
            .replaceAll("\\s+БО-.*$", "")
            .replaceAll("(?i)\\s+облигаци.*$", "")
            .trim();
        if (normalized.length() < 3) return "";
        List<ShareAssetRef> sorted = new ArrayList<>(shareAssets);
        sorted.sort((a, b) -> b.name.length() - a.name.length());
        for (ShareAssetRef asset : sorted) {
            if (normalized.toLowerCase().contains(asset.name.toLowerCase()) ||
                asset.name.toLowerCase().contains(normalized.toLowerCase())) {
                return asset.assetUid;
            }
        }
        return "";
    }

    static double calculateBondBuyScore(BondData bond) {
        double riskScore;
        switch (bond.riskLevel) {
            case "RISK_LEVEL_LOW": riskScore = 100; break;
            case "RISK_LEVEL_MODERATE": riskScore = 70; break;
            case "RISK_LEVEL_HIGH": riskScore = 40; break;
            case "RISK_LEVEL_VERY_HIGH": riskScore = 10; break;
            default: riskScore = 50;
        }
        double fundScore = bond.financialScore > 0 ? bond.financialScore * 20.0 : 50.0;
        double ytmScore = Math.max(0, Math.min(100, bond.ytm / 30.0 * 100.0));
        double couponScore = Math.max(0, Math.min(100, bond.currentYield / 25.0 * 100.0));
        return riskScore * 0.2 + fundScore * 0.3 + ytmScore * 0.3 + couponScore * 0.2;
    }

    // ── TcsBondClient ────────────────────────────────────────────────

    static class TcsBondClient {
        final HttpClient http = createHttpClient();

        String postJson(String url, String body, int retries) {
            for (int attempt = 1; attempt <= retries; attempt++) {
                try {
                    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                        .header("Authorization", "Bearer " + TCS_API_KEY)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 429) { Thread.sleep(5000); continue; }
                    if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode());
                    return resp.body();
                } catch (Exception e) {
                    if (attempt == retries) { System.out.println("  [ERROR] " + e.getMessage()); return null; }
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                }
            }
            return null;
        }
        String postJson(String url, String body) { return postJson(url, body, 3); }

        List<TcsAccount> getAccounts() {
            String json = postJson("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.UsersService/GetAccounts", "{\"status\":\"ACCOUNT_STATUS_OPEN\"}");
            if (json == null) return Collections.emptyList();
            List<TcsAccount> accounts = new ArrayList<>();
            int as = json.indexOf("\"accounts\"");
            if (as < 0) return accounts;
            int bs = json.indexOf('[', as), be = json.indexOf(']', bs);
            if (bs < 0 || be < 0) return accounts;
            parseAccountObjects(json.substring(bs + 1, be), accounts);
            return accounts;
        }

        private void parseAccountObjects(String content, List<TcsAccount> accounts) {
            int depth = 0, objStart = -1;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') { if (depth == 0) objStart = i; depth++; }
                else if (c == '}') { depth--; if (depth == 0 && objStart >= 0) {
                    String obj = content.substring(objStart, i + 1);
                    accounts.add(new TcsAccount(extractString(obj, "id"), extractString(obj, "name"), extractString(obj, "type")));
                    objStart = -1;
                } }
            }
        }

        double getWithdrawLimits(String accountId) {
            String json = postJson("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.OperationsService/GetWithdrawLimits", "{\"accountId\":\"" + accountId + "\"}", 2);
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
                    String currency = extractString(obj, "currency");
                    Double units = extractMoney(obj, "units");
                    Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(obj);
                    long nano = mn.find() ? parseLong(mn.group(1)) : 0;
                    double amount = (units != null ? units : 0) + nano / 1_000_000_000.0;
                    if ("rub".equals(currency) || "RUB".equals(currency)) total += amount;
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
                        String currency = extractString(obj, "currency");
                        Double units = extractMoney(obj, "units");
                        Matcher mn = Pattern.compile("\"nano\"\\s*:\\s*(\\d+)").matcher(obj);
                        long nano = mn.find() ? parseLong(mn.group(1)) : 0;
                        double amount = (units != null ? units : 0) + nano / 1_000_000_000.0;
                        if ("rub".equals(currency) || "RUB".equals(currency)) total += amount;
                        os = -1;
                    } }
                }
                if (total > 0) break;
            }
            return total;
        }

        List<BondInstrument> getAllBonds() {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    String json = postJson("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/Bonds", "{\"instrumentStatus\":\"INSTRUMENT_STATUS_BASE\"}");
                    if (json != null) return parseBondsJson(json);
                } catch (Exception e) {
                    if (attempt == 3) throw new RuntimeException(e);
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
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
                        parseBondObjects(json.substring(arrayStart + 1, i), instruments);
                        break;
                    } }
                }
            }
            return instruments;
        }

        private void parseBondObjects(String content, List<BondInstrument> instruments) {
            int depth = 0, objStart = -1;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') { if (depth == 0) objStart = i; depth++; }
                else if (c == '}') { depth--; if (depth == 0 && objStart >= 0) {
                    instruments.add(parseSingleBond(content.substring(objStart, i + 1)));
                    objStart = -1;
                } }
            }
        }

        private BondInstrument parseSingleBond(String json) {
            String md = extractString(json, "maturityDate");
            int tIdx = md.indexOf('T');
            return new BondInstrument(extractString(json, "ticker"), extractString(json, "name"),
                extractString(json, "figi"), extractString(json, "classCode"), extractString(json, "assetUid"),
                extractInt(json, "couponQuantityPerYear"), tIdx > 0 ? md.substring(0, tIdx) : md,
                extractNominal(json), extractString(json, "riskLevel").isEmpty() ? "RISK_LEVEL_UNSPECIFIED" : extractString(json, "riskLevel"),
                extractString(json, "sector"), extractBoolean(json, "amortizationFlag"),
                extractBoolean(json, "floatingCouponFlag"), extractBoolean(json, "forQualInvestorFlag"));
        }

        Map<String, Double> getLastPrices(List<String> ids) {
            if (ids.isEmpty()) return Collections.emptyMap();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ids.size(); i++) { if (i > 0) sb.append(","); sb.append("\"").append(ids.get(i)).append("\""); }
            String json = postJson("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.MarketDataService/GetLastPrices", "{\"instrumentId\":[" + sb + "]}", 2);
            return json == null ? Collections.emptyMap() : parsePricesJson(json);
        }

        private Map<String, Double> parsePricesJson(String json) {
            Map<String, Double> prices = new LinkedHashMap<>();
            int start = json.indexOf("\"lastPrices\"");
            if (start < 0) return prices;
            int depth = 0, inArray = 0, arrayStart = -1;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[' && inArray == 0) { inArray = 1; arrayStart = i; }
                else if (inArray > 0) {
                    if (c == '[') depth++;
                    if (c == ']') { depth--; if (depth < 0) {
                        parsePriceObjects(json.substring(arrayStart + 1, i), prices);
                        break;
                    } }
                }
            }
            return prices;
        }

        private void parsePriceObjects(String content, Map<String, Double> prices) {
            int depth = 0, objStart = -1;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') { if (depth == 0) objStart = i; depth++; }
                else if (c == '}') { depth--; if (depth == 0 && objStart >= 0) {
                    String obj = content.substring(objStart, i + 1);
                    String ticker = extractString(obj, "ticker");
                    double price = extractPrice(obj);
                    if (!ticker.isEmpty() && price > 0) prices.put(ticker, price);
                    objStart = -1;
                } }
            }
        }

        List<CouponEvent> getBondCoupons(String figi) {
            String json = postJson("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetBondCoupons", "{\"figi\":\"" + figi + "\"}", 2);
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
                        parseCouponObjects(json.substring(arrayStart + 1, i), events);
                        break;
                    } }
                }
            }
            return events;
        }

        private void parseCouponObjects(String content, List<CouponEvent> events) {
            int depth = 0, objStart = -1;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') { if (depth == 0) objStart = i; depth++; }
                else if (c == '}') { depth--; if (depth == 0 && objStart >= 0) {
                    String obj = content.substring(objStart, i + 1);
                    events.add(new CouponEvent(extractString(obj, "couponDate"), extractCouponPayment(obj),
                        extractString(obj, "couponType"), extractCouponCurrency(obj)));
                    objStart = -1;
                } }
            }
        }

        List<ShareAssetRef> getShareAssets() {
            try {
                String json = postJson("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetAssets", "{\"instrumentType\":\"INSTRUMENT_TYPE_SHARE\"}");
                if (json == null) return Collections.emptyList();
                List<ShareAssetRef> refs = new ArrayList<>();
                for (String asset : extractNestedArray(json, "assets")) {
                    String uid = extractString(asset, "uid");
                    if (uid.isEmpty()) continue;
                    String name = extractString(asset, "name");
                    List<String> instruments = extractNestedArray(asset, "instruments");
                    String ticker = instruments.isEmpty() ? "" : extractString(instruments.get(0), "ticker");
                    refs.add(new ShareAssetRef(uid, name, ticker));
                }
                return refs;
            } catch (Exception e) { System.out.println("  [WARN] GetAssets: " + e.getMessage()); return Collections.emptyList(); }
        }

        Map<String, IssuerFundamentals> getAssetFundamentals(List<String> assetIds) {
            if (assetIds.isEmpty()) return Collections.emptyMap();
            Map<String, IssuerFundamentals> result = new HashMap<>();
            for (int i = 0; i < assetIds.size(); i += 100) {
                List<String> chunk = assetIds.subList(i, Math.min(i + 100, assetIds.size()));
                try {
                    StringBuilder idsJson = new StringBuilder();
                    for (int j = 0; j < chunk.size(); j++) { if (j > 0) idsJson.append(","); idsJson.append("\"").append(chunk.get(j)).append("\""); }
                    String resp = postJson("https://invest-public-api.tbank.ru/rest/tinkoff.public.invest.api.contract.v1.InstrumentsService/GetAssetFundamentals", "{\"assets\":[" + idsJson + "]}", 3);
                    if (resp == null) continue;
                    for (String item : extractNestedArray(resp, "fundamentals")) {
                        String uid = extractString(item, "assetUid");
                        if (uid.isEmpty()) continue;
                        result.put(uid, new IssuerFundamentals(extractString(item, "name"), extractDouble(item, "totalDebtToEquityMrq"),
                            extractDouble(item, "netDebtToEbitda"), extractDouble(item, "currentRatioMrq"),
                            extractDouble(item, "roe"), extractDouble(item, "roic"),
                            extractDouble(item, "netMarginMrq"), extractDouble(item, "fiveYearAnnualRevenueGrowthRate"),
                            extractDouble(item, "marketCapitalization")));
                    }
                } catch (Exception e) { System.out.println("  [WARN] GetAssetFundamentals: " + e.getMessage()); }
            }
            return result;
        }
    }

    // ── FinamClient ──────────────────────────────────────────────────

    static class FinamClient {
        final HttpClient http = createHttpClient();
        String jwtToken;

        String authenticate(String secret) throws Exception {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://api.finam.ru/v1/sessions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"secret\":\"" + secret + "\"}")).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new Exception("Finam auth failed: HTTP " + resp.statusCode());
            jwtToken = extractString(resp.body(), "token");
            return jwtToken;
        }

        String getAccount(String accountId) throws Exception {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://api.finam.ru/v1/accounts/" + accountId))
                .header("Authorization", "Bearer " + jwtToken).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new Exception("Finam get account failed: HTTP " + resp.statusCode());
            return resp.body();
        }

        FinamOrderBook getOrderBook(String symbol) {
            try {
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://api.finam.ru/v1/instruments/" + symbol + "/orderbook"))
                    .header("Authorization", "Bearer " + jwtToken).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) { System.out.println("  [WARN] OrderBook " + symbol + ": HTTP " + resp.statusCode()); return null; }
                return parseOrderBook(resp.body());
            } catch (Exception e) { System.out.println("  [WARN] OrderBook " + symbol + ": " + e.getMessage()); return null; }
        }

        String placeLimitBuyOrder(String accountId, String symbol, String quantity, String price) {
            try {
                String body = "{\"symbol\":\"" + symbol + "\",\"quantity\":{\"value\":\"" + quantity + "\"},\"side\":\"SIDE_BUY\",\"type\":\"ORDER_TYPE_LIMIT\",\"time_in_force\":\"TIME_IN_FORCE_DAY\",\"limit_price\":{\"value\":\"" + price + "\"}}";
                System.out.println("  [Finam] BUY " + quantity + " x " + symbol + " @ " + price);
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://api.finam.ru/v1/accounts/" + accountId + "/orders"))
                    .header("Authorization", "Bearer " + jwtToken).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) { System.out.println("  [ERROR] PlaceOrder: HTTP " + resp.statusCode() + " " + resp.body()); return null; }
                String orderId = extractString(resp.body(), "orderId");
                System.out.println("  [Finam] OrderId: " + orderId);
                return orderId;
            } catch (Exception e) { System.out.println("  [ERROR] PlaceOrder: " + e.getMessage()); return null; }
        }

        private FinamOrderBook parseOrderBook(String json) {
            List<FinamOrderBookEntry> bids = new ArrayList<>(), asks = new ArrayList<>();
            int rowsStart = json.indexOf("\"rows\"");
            if (rowsStart < 0) return new FinamOrderBook(bids, asks);
            int depth = 0, inArray = 0, arrayStart = -1;
            for (int i = rowsStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[' && inArray == 0) { inArray = 1; arrayStart = i; }
                else if (inArray > 0) {
                    if (c == '[') depth++;
                    if (c == ']') { depth--; if (depth < 0) {
                        parseOrderBookRows(json.substring(arrayStart + 1, i), bids, asks);
                        break;
                    } }
                }
            }
            return new FinamOrderBook(bids, asks);
        }

        private void parseOrderBookRows(String content, List<FinamOrderBookEntry> bids, List<FinamOrderBookEntry> asks) {
            int depth = 0, objStart = -1;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') { if (depth == 0) objStart = i; depth++; }
                else if (c == '}') { depth--; if (depth == 0 && objStart >= 0) {
                    String obj = content.substring(objStart, i + 1);
                    String price = extractNestedValue(obj, "price");
                    String sellSize = extractNestedValue(obj, "sell_size");
                    String buySize = extractNestedValue(obj, "buy_size");
                    if (!price.isEmpty()) {
                        double pv = parseDouble(price);
                        if (!sellSize.isEmpty() && parseDouble(sellSize) > 0) asks.add(new FinamOrderBookEntry(pv, parseDouble(sellSize)));
                        if (!buySize.isEmpty() && parseDouble(buySize) > 0) bids.add(new FinamOrderBookEntry(pv, parseDouble(buySize)));
                    }
                    objStart = -1;
                } }
            }
        }
    }

    // ── extractFinamCash ─────────────────────────────────────────────

    static double extractFinamCash(String json) {
        Matcher m = Pattern.compile("\"available_cash\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (m.find()) return parseDouble(m.group(1));
        int cs = json.indexOf("\"cash\"");
        if (cs < 0) return 0.0;
        String sub = json.substring(cs);
        Matcher mu = Pattern.compile("\"units\"\\s*:\\s*\"?([\\d.]+)\"?").matcher(sub);
        Matcher mn = Pattern.compile("\"nanos\"\\s*:\\s*(\\d+)").matcher(sub);
        double units = mu.find() ? parseDouble(mu.group(1)) : 0.0;
        long nano = mn.find() ? parseLong(mn.group(1)) : 0;
        return units + nano / 1_000_000_000.0;
    }

    // ── BondScanner.scan ─────────────────────────────────────────────

    static List<BondData> scan(BondConfig config) {
        TcsBondClient client = new TcsBondClient();
        System.out.println("=== Сканер облигаций ===");
        System.out.println("  Параметры:");
        System.out.println("    Целевая доходность: " + config.targetYield + "%");
        System.out.println("    Частота купона: " + config.couponFrequency + "/год");
        System.out.println("    Без амортизации: " + config.noAmortization);
        System.out.println("    Без флоатеров: " + config.excludeFloating);
        System.out.println("    Без бумаг для квал. инвесторов: " + config.excludeQualInvestorOnly);
        System.out.println("    Риск (T-Invest): " + formatAcceptedRisk(config.minRiskLevel));
        System.out.println("    Срок до погашения: " + config.minDaysToMaturity + "-" + config.maxDaysToMaturity + " дн.");
        System.out.println();

        System.out.println("  Загрузка облигаций...");
        List<BondInstrument> allBonds = client.getAllBonds();
        System.out.println("  Найдено: " + allBonds.size());

        long forNonQual = allBonds.stream().filter(b -> !b.forQualInvestorFlag).count();
        long nonFloating = allBonds.stream().filter(b -> !b.floatingCouponFlag).count();
        System.out.println("  Доступно неквалифицированным: " + forNonQual + ", без флоатеров: " + nonFloating);

        List<BondInstrument> filtered = new ArrayList<>();
        for (BondInstrument bond : allBonds) {
            if (bond.couponQuantityPerYear == config.couponFrequency &&
                (!config.noAmortization || !bond.amortizationFlag) &&
                (!config.excludeFloating || !bond.floatingCouponFlag) &&
                (!config.excludeQualInvestorOnly || !bond.forQualInvestorFlag) &&
                isRiskLevelAcceptable(bond.riskLevel, config.minRiskLevel) &&
                !"government".equals(bond.sector)) {
                filtered.add(bond);
            }
        }
        System.out.println("  После фильтрации: " + filtered.size());

        System.out.println("  Получение цен...");
        List<String> instrumentIds = new ArrayList<>();
        for (BondInstrument b : filtered) instrumentIds.add(b.ticker + "_" + b.classCode);
        Map<String, Double> prices = client.getLastPrices(instrumentIds);

        List<BondData> bondsWithData = new ArrayList<>();
        for (BondInstrument bond : filtered) {
            Double price = prices.get(bond.ticker);
            if (price == null || price <= 0) { System.out.println("  [WARN] Нет цены для " + bond.ticker); continue; }

            List<CouponEvent> coupons = client.getBondCoupons(bond.figi);
            LocalDate now = LocalDate.now();
            String todayStr = now.toString();
            List<CouponEvent> futureCoupons = new ArrayList<>();
            for (CouponEvent c : coupons) {
                if (c.couponDate.compareTo(todayStr) > 0 && "rub".equals(c.currency) &&
                    isAcceptableCouponType(c.couponType, config.excludeFloating)) {
                    futureCoupons.add(c);
                }
            }
            if (futureCoupons.size() > config.couponFrequency * 2)
                futureCoupons = futureCoupons.subList(0, config.couponFrequency * 2);

            if (config.excludeFloating && futureCoupons.isEmpty()) {
                boolean hasFloating = false;
                for (CouponEvent c : coupons) {
                    if (c.couponDate.compareTo(todayStr) > 0 && isFloatingCouponType(c.couponType)) { hasFloating = true; break; }
                }
                if (hasFloating) continue;
            }

            double avgCoupon;
            if (!futureCoupons.isEmpty()) {
                if (config.excludeFloating) {
                    avgCoupon = futureCoupons.stream().mapToDouble(c -> c.payOneBond).average().orElse(0);
                } else {
                    avgCoupon = futureCoupons.stream().min(Comparator.comparing(c -> c.couponDate)).get().payOneBond;
                }
            } else {
                avgCoupon = bond.nominal * 0.12 / config.couponFrequency;
            }

            LocalDate maturityDate = LocalDate.parse(bond.maturityDate);
            int daysToMaturity = (int) ChronoUnit.DAYS.between(now, maturityDate);
            if (daysToMaturity < config.minDaysToMaturity || daysToMaturity > config.maxDaysToMaturity) continue;

            double priceInRubles = price * bond.nominal / 100.0;
            double annualCoupon = avgCoupon * config.couponFrequency;
            double currentYield = annualCoupon / priceInRubles * 100;
            double yearsToMaturity = daysToMaturity / 365.0;
            double priceDiff = bond.nominal - priceInRubles;
            double totalCouponIncome = annualCoupon * yearsToMaturity;
            double totalReturn = totalCouponIncome + priceDiff;
            double ytm = (totalReturn / priceInRubles / yearsToMaturity) * 100;

            bondsWithData.add(new BondData(bond.ticker, bond.name, bond.figi, bond.classCode, price, bond.nominal,
                avgCoupon, bond.couponQuantityPerYear, maturityDate, daysToMaturity, currentYield, ytm,
                bond.riskLevel, bond.sector, bond.amortizationFlag, totalCouponIncome,
                totalCouponIncome + priceDiff, priceInRubles * config.brokerCommission,
                totalCouponIncome + priceDiff - (priceInRubles * config.brokerCommission),
                (totalCouponIncome + priceDiff - (priceInRubles * config.brokerCommission)) / priceInRubles / yearsToMaturity * 100,
                bond.assetUid, 0, "", null));
        }

        if (config.excludeFloating) bondsWithData.sort((a, b) -> Double.compare(b.ytm, a.ytm));
        else bondsWithData.sort((a, b) -> Double.compare(b.couponAmount, a.couponAmount));
        if (bondsWithData.size() > config.maxBondsCount) bondsWithData = bondsWithData.subList(0, config.maxBondsCount);

        String sortMode = config.excludeFloating ? "YTM" : "купон";
        System.out.println("  Отобрано: " + bondsWithData.size() + " (сортировка по " + sortMode + ")");

        System.out.println("  Фундаментальный анализ эмитентов...");
        List<BondData> enriched = enrichWithFundamentals(bondsWithData, client);
        long withScore = enriched.stream().filter(b -> b.financialScore > 0).count();
        System.out.println("  Оценка эмитентов: " + withScore + "/" + enriched.size());
        System.out.println();
        return enriched;
    }

    private static List<BondData> enrichWithFundamentals(List<BondData> bonds, TcsBondClient client) {
        if (bonds.isEmpty()) return bonds;
        List<ShareAssetRef> shareAssets = client.getShareAssets();
        Map<String, String> resolvedUids = new HashMap<>();
        for (BondData b : bonds) resolvedUids.put(b.ticker, resolveAssetUid(b, shareAssets));
        Map<String, IssuerFundamentals> fundamentals = client.getAssetFundamentals(
            new ArrayList<>(new LinkedHashSet<>(resolvedUids.values())));

        // Fallback: find by bond name
        List<BondData> missing = new ArrayList<>();
        for (BondData b : bonds) { String uid = resolvedUids.get(b.ticker); if (uid == null || !fundamentals.containsKey(uid)) missing.add(b); }
        if (!missing.isEmpty() && !shareAssets.isEmpty()) {
            Map<String, String> fallbackUids = new HashMap<>();
            for (BondData b : missing) {
                String fb = findIssuerAssetUid(b.name, shareAssets);
                String primary = resolvedUids.getOrDefault(b.ticker, "");
                if (!fb.isEmpty() && !fb.equals(primary)) fallbackUids.put(b.ticker, fb);
            }
            if (!fallbackUids.isEmpty()) {
                Map<String, IssuerFundamentals> extra = client.getAssetFundamentals(new ArrayList<>(new LinkedHashSet<>(fallbackUids.values())));
                fundamentals.putAll(extra);
                List<BondData> result = new ArrayList<>();
                for (BondData b : bonds) {
                    String primary = resolvedUids.getOrDefault(b.ticker, "");
                    String assetUid = fallbackUids.getOrDefault(b.ticker, primary);
                    IssuerFundamentals fund = fundamentals.getOrDefault(assetUid, fundamentals.get(primary));
                    result.add(toBondWithFundamentals(b, assetUid, fund));
                }
                return result;
            }
        }
        List<BondData> result = new ArrayList<>();
        for (BondData b : bonds) {
            String uid = resolvedUids.getOrDefault(b.ticker, "");
            result.add(toBondWithFundamentals(b, uid, fundamentals.get(uid)));
        }
        return result;
    }

    private static BondData toBondWithFundamentals(BondData bond, String assetUid, IssuerFundamentals fund) {
        if (fund == null) return new BondData(bond.ticker, bond.name, bond.figi, bond.classCode, bond.price, bond.nominal,
            bond.couponAmount, bond.couponFrequency, bond.maturityDate, bond.daysToMaturity, bond.currentYield, bond.ytm,
            bond.riskLevel, bond.sector, bond.amortizationFlag, bond.totalCoupons, bond.totalProfit, bond.commission,
            bond.netProfit, bond.netYield, assetUid, 0, "", null);
        int score = calculateFinancialHealthScore(fund);
        return new BondData(bond.ticker, bond.name, bond.figi, bond.classCode, bond.price, bond.nominal,
            bond.couponAmount, bond.couponFrequency, bond.maturityDate, bond.daysToMaturity, bond.currentYield, bond.ytm,
            bond.riskLevel, bond.sector, bond.amortizationFlag, bond.totalCoupons, bond.totalProfit, bond.commission,
            bond.netProfit, bond.netYield, assetUid, score, financialScoreLabel(score), fund);
    }

    private static String resolveAssetUid(BondData bond, List<ShareAssetRef> shareAssets) {
        if (bond.assetUid != null && !bond.assetUid.isEmpty()) return bond.assetUid;
        return findIssuerAssetUid(bond.name, shareAssets);
    }

    static boolean isRiskLevelAcceptable(String riskLevel, String minRiskLevel) {
        int rv = RISK_LEVELS.getOrDefault(riskLevel, Integer.MAX_VALUE);
        int mv = RISK_LEVELS.getOrDefault(minRiskLevel, Integer.MAX_VALUE);
        return rv <= mv;
    }

    static boolean isFixedCouponType(String ct) {
        return "COUPON_TYPE_FIX".equals(ct) || "COUPON_TYPE_FIXED".equals(ct) || "COUPON_TYPE_CONSTANT".equals(ct);
    }

    static boolean isFloatingCouponType(String ct) {
        return "COUPON_TYPE_FLOATING".equals(ct) || "COUPON_TYPE_VARIABLE".equals(ct);
    }

    static boolean isAcceptableCouponType(String ct, boolean excludeFloating) {
        return isFixedCouponType(ct) || (!excludeFloating && isFloatingCouponType(ct));
    }

    // ── printResults ─────────────────────────────────────────────────

    static void printResults(List<BondData> bonds, BondConfig config) {
        String sortLabel = config.excludeFloating ? "ДОХОДНОСТИ" : "КУПОНУ";
        System.out.println("======================================================================================================================================================");
        System.out.println("  ТОП-" + config.maxBondsCount + " ОБЛИГАЦИЙ ПО " + sortLabel + "  |  Частота купона: " + config.couponFrequency + "/год  |  Без амортизации: " + config.noAmortization + "  |  Без флоатеров: " + config.excludeFloating + "  |  Без квал.: " + config.excludeQualInvestorOnly);
        System.out.println("======================================================================================================================================================");
        if (bonds.isEmpty()) { System.out.println("  Нет облигаций, соответствующих критериям"); return; }

        System.out.println();
        System.out.printf("%-4s | %-12s | %-25s | %5s | %10s | %6s | %6s | %5s | %-10s | %-6s | %-12s%n", "#", "Тикер", "Название", "Цена", "Купон", "Купон%", "YTM%", "Дней", "Риск", "Фин.", "Сектор");
        System.out.println("--------------------------------------------------------------------------------------------------------------------------------------------------");

        for (int idx = 0; idx < bonds.size(); idx++) {
            BondData b = bonds.get(idx);
            String sectorShort = switchSector(b.sector);
            String riskDisplay = RISK_TO_RATING.getOrDefault(b.riskLevel, b.riskLevel.replace("RISK_LEVEL_", ""));
            String finDisplay = b.financialScore > 0 ? b.financialScore + " " + b.financialScoreLabel.substring(0, Math.min(4, b.financialScoreLabel.length())) : "н/д";
            System.out.printf("%-4d | %-12s | %-25s | %5.2f%% | %6.2f руб | %5.1f%% | %5.1f%% | %5d  | %-10s | %-6s | %-12s%n",
                idx + 1, b.ticker, b.name.substring(0, Math.min(25, b.name.length())), b.price, b.couponAmount,
                b.currentYield, b.ytm, b.daysToMaturity, riskDisplay, finDisplay, sectorShort);
        }
        System.out.println("--------------------------------------------------------------------------------------------------------------------------------------------------");
        System.out.println();
        System.out.println("  Примечание: риск — T-Invest (" + formatAcceptedRisk(config.minRiskLevel) + "); фин. — оценка эмитента 1-5 (плохо → отлично)");

        List<BondData> withFund = new ArrayList<>();
        for (BondData b : bonds) { if (b.fundamentals != null) withFund.add(b); }
        if (!withFund.isEmpty()) {
            System.out.println();
            System.out.println("  Фундаментальный анализ эмитентов (топ-" + bonds.size() + "):");
            for (BondData b : withFund) {
                IssuerFundamentals f = b.fundamentals;
                System.out.println();
                System.out.println("  " + b.ticker + " — " + b.financialScore + "/5 (" + b.financialScoreLabel + ")");
                if (f.name != null && !f.name.isEmpty()) System.out.println("    Эмитент: " + f.name);
                List<String> metrics = new ArrayList<>();
                if (f.debtToEquity > 0) metrics.add(String.format("D/E: %.1f%%", f.debtToEquity));
                if (f.netDebtToEbitda > 0) metrics.add(String.format("NetDebt/EBITDA: %.2f", f.netDebtToEbitda));
                if (f.currentRatio > 0) metrics.add(String.format("Тек.ликв: %.2f", f.currentRatio));
                if (f.roe > 0) metrics.add(String.format("ROE: %.1f%%", f.roe));
                if (f.roic > 0) metrics.add(String.format("ROIC: %.1f%%", f.roic));
                if (f.netMargin > 0) metrics.add(String.format("Маржа: %.1f%%", f.netMargin));
                if (f.revenueGrowth5y > 0) metrics.add(String.format("Рост выручки 5л: %.1f%%", f.revenueGrowth5y));
                if (!metrics.isEmpty()) System.out.println("    " + String.join(" | ", metrics));
            }
        }

        double avgYtm = bonds.stream().mapToDouble(b -> b.ytm).average().orElse(0);
        double avgCoupon = bonds.stream().mapToDouble(b -> b.couponAmount).average().orElse(0);
        int avgDays = (int) bonds.stream().mapToInt(b -> b.daysToMaturity).average().orElse(0);
        double totalCoupons = bonds.stream().mapToDouble(b -> b.totalCoupons).sum();

        System.out.println();
        System.out.println("  Статистика:");
        if (config.excludeFloating) System.out.printf("    Средняя YTM: %.2f%%%n", avgYtm);
        else System.out.printf("    Средний купон: %.2f руб%n", avgCoupon);
        long scored = bonds.stream().filter(b -> b.financialScore > 0).count();
        if (scored > 0) {
            double avgFin = bonds.stream().filter(b -> b.financialScore > 0).mapToInt(b -> b.financialScore).average().orElse(0);
            System.out.printf("    Средняя оценка эмитентов: %.1f/5%n", avgFin);
        }
        System.out.println("    Средний срок: " + avgDays + " дн.");
        System.out.printf("    Суммарные купоны (на 1 облиг): %.2f руб%n", totalCoupons / bonds.size());
        System.out.println();
        System.out.println("  Рекомендуемая стратегия:");
        System.out.println("    - Диверсифицировать портфель (5-10 облигаций)");
        System.out.println("    - Реинвестировать купоны");
        System.out.println("    - Учитывать риск дефолта (особенно для high-yield)");
        System.out.println();

        if (config.capital > 0) {
            System.out.printf("  Пример портфеля на %.0f руб:%n", config.capital);
            double perBond = config.capital / config.maxBondsCount;
            for (BondData b : bonds) {
                double priceInRubles = b.price * b.nominal / 100.0;
                int quantity = (int)(perBond / priceInRubles);
                double cost = quantity * priceInRubles;
                double monthlyCoupon = quantity * b.couponAmount;
                System.out.printf("    %s: %d шт. = %.0f руб | Купон/мес: %.2f руб%n", b.ticker, quantity, cost, monthlyCoupon);
            }
            double totalMonthly = 0;
            for (BondData b : bonds) {
                double priceInRubles = b.price * b.nominal / 100.0;
                totalMonthly += (int)(perBond / priceInRubles) * b.couponAmount;
            }
            System.out.printf("    Итого купон/мес: %.2f руб (%.2f%% год.)%n", totalMonthly, totalMonthly * 12 / config.capital * 100);
        }
        System.out.println("======================================================================================================================================================");
        System.out.println("Не является инвестиционной рекомендацией");
        System.out.println("======================================================================================================================================================");
    }

    static String switchSector(String sector) {
        String s;
        switch (sector) {
            case "corporate": s = "Корпоративный"; break;
            case "bank": s = "Банковский"; break;
            case "government": s = "Гос. облигации"; break;
            case "utilities": s = "Коммунальщики"; break;
            case "energy": s = "Энергетика"; break;
            case "materials": s = "Материалы"; break;
            case "financial": s = "Финансы"; break;
            case "consumer": s = "Потребительский"; break;
            case "it": s = "IT"; break;
            case "industrials": s = "Промышленность"; break;
            default: s = sector;
        }
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    // ── parseArgs ────────────────────────────────────────────────────

    static BondConfig parseArgs(String[] args) {
        double targetYield = 16.0; int couponFreq = 12; boolean noAmort = true;
        boolean excludeFloating = true, excludeQualOnly = true;
        String minRisk = "RISK_LEVEL_MODERATE"; int maxCount = 10; double capital = 100_000;
        int minDays = 90, maxDays = 730; boolean autoMode = false, dryRun = false;
        String accountId = ""; int autoBuyCount = 5; double autoBuyAmount = 0;

        int i = 0;
        while (i < args.length) {
            switch (args[i]) {
                case "--yield": if (i + 1 < args.length) targetYield = parseDouble(args[++i]); break;
                case "--coupon-freq": if (i + 1 < args.length) couponFreq = parseInt(args[++i]); break;
                case "--allow-amortization": noAmort = false; break;
                case "--allow-floating": case "-f": excludeFloating = false; break;
                case "--allow-qual": case "-q": excludeQualOnly = false; break;
                case "--min-risk": if (i + 1 < args.length) minRisk = normalizeRiskLevel(args[++i]); break;
                case "--count": if (i + 1 < args.length) maxCount = parseInt(args[++i]); break;
                case "--capital": if (i + 1 < args.length) capital = parseDouble(args[++i]); break;
                case "--min-days": if (i + 1 < args.length) minDays = parseInt(args[++i]); break;
                case "--max-days": if (i + 1 < args.length) maxDays = parseInt(args[++i]); break;
                case "--auto": autoMode = true; break;
                case "--dry": dryRun = true; break;
                case "--account-id": if (i + 1 < args.length) accountId = args[++i]; break;
                case "--auto-count": if (i + 1 < args.length) autoBuyCount = parseInt(args[++i]); break;
                case "--auto-amount": if (i + 1 < args.length) autoBuyAmount = parseDouble(args[++i]); break;
                case "--help":
                    System.out.println("Использование: BondScanner [опции]");
                    System.out.println("Опции:");
                    System.out.println("  --yield <value>          Целевая доходность (по умолчанию: 16.0)");
                    System.out.println("  --coupon-freq <value>    Частота купона в год (по умолчанию: 12)");
                    System.out.println("  --allow-amortization     Разрешить амортизацию (по умолчанию: false)");
                    System.out.println("  --allow-floating, -f     Разрешить флоатеры (по умолчанию: false)");
                    System.out.println("  --allow-qual, -q         Разрешить бумаги для квал. инвесторов (по умолчанию: false)");
                    System.out.println("  --min-risk <level>       Макс. риск: AAA (только надежные), MODERATE (надежные+средние, по умолчанию)");
                    System.out.println("  --count <value>          Кол-во облигаций в топе (по умолчанию: 10)");
                    System.out.println("  --capital <value>        Капитал для инвестиций (по умолчанию: 100000)");
                    System.out.println("  --min-days <value>       Мин. дней до погашения (по умолчанию: 90)");
                    System.out.println("  --max-days <value>       Макс. дней до погашения (по умолчанию: 730)");
                    System.out.println("  --auto                   Автоматическая покупка лучших облигаций через Finam");
                    System.out.println("  --dry                    Режим dry run (без реальных сделок)");
                    System.out.println("  --account-id <id>        ID аккаунта Finam (или finam.accountId в properties)");
                    System.out.println("  --auto-count <value>     Кол-во облигаций для покупки (по умолчанию: 5)");
                    System.out.println("  --auto-amount <value>    Сумма на покупку (0 = весь свободный кэш)");
                    System.out.println("  --help                   Показать эту справку");
                    System.exit(0);
                default: break;
            }
            i++;
        }
        return new BondConfig(targetYield, couponFreq, noAmort, excludeFloating, excludeQualOnly, minRisk,
            maxCount, 0.0005, capital, minDays, maxDays, autoMode, dryRun, accountId, autoBuyCount, autoBuyAmount);
    }

    // ── executeAutoBuy ───────────────────────────────────────────────

    static void executeAutoBuy(List<BondData> bonds, BondConfig config, Properties props) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  АВТОМАТИЧЕСКАЯ ПОКУПКА ОБЛИГАЦИЙ");
        System.out.println("================================================================");

        String finamSecret = props.getProperty("finam.apiKey", "");
        if (finamSecret.isEmpty()) { System.out.println("  [ERROR] Не настроен finam.apiKey"); return; }

        FinamClient finam = new FinamClient();
        try {
            System.out.println("  [1/4] Авторизация в Finam API...");
            finam.authenticate(finamSecret);
            System.out.println("  [OK]");
        } catch (Exception e) { System.out.println("  [ERROR] Ошибка авторизации Finam: " + e.getMessage()); return; }

        String accId = config.accountId.isEmpty() ? props.getProperty("finam.accountId", "") : config.accountId;
        if (accId.isEmpty()) { System.out.println("  [ERROR] Не указан --account-id и finam.accountId"); return; }
        System.out.println("  Аккаунт: " + accId);

        System.out.println("  [2/4] Получение информации об аккаунте...");
        String accountJson;
        try { accountJson = finam.getAccount(accId); } catch (Exception e) { System.out.println("  [ERROR] " + e.getMessage()); return; }

        double freeCash = extractFinamCash(accountJson);
        System.out.printf("  Свободные средства: %.2f руб.%n", freeCash);
        if (freeCash <= 0) { System.out.println("  [WARN] Нет свободных средств"); return; }

        double buyAmount = config.autoBuyAmount > 0 ? Math.min(config.autoBuyAmount, freeCash) : freeCash;
        System.out.printf("  Сумма на покупку: %.2f руб.%n", buyAmount);

        System.out.println("  [3/4] Ранжирование облигаций...");
        List<BondData> ranked = new ArrayList<>();
        for (BondData b : bonds) { if (b.price > 0 && b.ytm > 0) ranked.add(b); }
        ranked.sort((a, b) -> Double.compare(calculateBondBuyScore(b), calculateBondBuyScore(a)));
        if (ranked.size() > config.autoBuyCount) ranked = ranked.subList(0, config.autoBuyCount);

        if (ranked.isEmpty()) { System.out.println("  [WARN] Нет подходящих облигаций"); return; }

        System.out.println("  Лучшие облигации для покупки:");
        for (int idx = 0; idx < ranked.size(); idx++) {
            BondData b = ranked.get(idx);
            double score = calculateBondBuyScore(b);
            System.out.printf("    %d. %s — YTM: %.1f%%, Купон: %.1f%%, Риск: %s, Фин: %d/5, Скор: %.1f%n",
                idx + 1, b.ticker, b.ytm, b.currentYield, RISK_TO_RATING.getOrDefault(b.riskLevel, "?"), b.financialScore, score);
        }

        double perBond = buyAmount / ranked.size();
        System.out.println();
        System.out.println("  [4/4] Размещение ордеров...");
        if (config.dryRun) System.out.println("  *** DRY RUN — реальные ордера НЕ размещаются ***");
        System.out.printf("  На каждую облигацию: %.2f руб.%n", perBond);
        System.out.println();

        double totalSpent = 0; int ordersPlaced = 0;
        for (BondData bond : ranked) {
            double pricePerBond = bond.price * bond.nominal / 100.0;
            int quantity = (int)(perBond / pricePerBond);
            if (quantity <= 0) { System.out.printf("  [SKIP] %s: недостаточно средств (%.2f руб.)%n", bond.ticker, pricePerBond); continue; }

            String symbol = bond.ticker + "@MISX";
            FinamOrderBook orderBook = finam.getOrderBook(symbol);
            double buyPrice;
            if (orderBook != null && !orderBook.asks.isEmpty()) {
                buyPrice = orderBook.asks.stream().min(Comparator.comparingDouble(e -> e.price)).get().price;
            } else { buyPrice = bond.price; }
            String priceFormatted = String.format(java.util.Locale.US, "%.2f", buyPrice);

            String askInfo = orderBook != null ? "ask=" + orderBook.asks.stream().min(Comparator.comparingDouble(e -> e.price)).map(e -> String.valueOf(e.price)).orElse("0") : "нет данных";
            System.out.printf("  [BUY] %s: %d шт. @ %s%% (стакан: %s)%n", bond.ticker, quantity, priceFormatted, askInfo);

            if (config.dryRun) {
                double cost = quantity * buyPrice * bond.nominal / 100.0;
                totalSpent += cost; ordersPlaced++;
                System.out.printf("  [DRY] Ордер пропущен (dry run), стоимость: %.2f руб.%n", cost);
            } else {
                String orderId = finam.placeLimitBuyOrder(accId, symbol, String.valueOf(quantity), priceFormatted);
                if (orderId != null) {
                    double cost = quantity * buyPrice * bond.nominal / 100.0;
                    totalSpent += cost; ordersPlaced++;
                    System.out.printf("  [OK] Ордер размещён: %s, стоимость: %.2f руб.%n", orderId, cost);
                } else { System.out.println("  [ERROR] Ордер не размещён для " + bond.ticker); }
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }

        System.out.println();
        System.out.println("================================================================");
        System.out.println("  ИТОГО АВТОПОКУПКИ");
        System.out.println("================================================================");
        System.out.println("  Ордеров размещено: " + ordersPlaced);
        System.out.printf("  Потрачено: %.2f руб.%n", totalSpent);
        System.out.printf("  Остаток: %.2f руб.%n", buyAmount - totalSpent);
        System.out.println("================================================================");
    }

    // ── Main ─────────────────────────────────────────────────────────

    public static void main(String[] args) {
        BondConfig config = parseArgs(args);
        List<BondData> bonds = scan(config);
        printResults(bonds, config);
        if (config.autoMode && !bonds.isEmpty()) {
            Properties props = new Properties();
            try { props.load(new FileInputStream(System.getProperty("user.dir") + "/application.properties")); } catch (Exception ignored) {}
            executeAutoBuy(bonds, config, props);
        }
    }
}
