package com.hedgefund.ai.service.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.hedgefund.ai.cache.ApiCache;
import com.hedgefund.ai.model.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * EODHD Financial Data API client.
 * <p>
 * Maps EODHD endpoints to the common {@link FinancialDataClient} contract so that
 * every analyst agent can work transparently with either provider.
 * <p>
 * EODHD authenticates via the {@code api_token} query-parameter.
 *
 * @see <a href="https://eodhd.com/financial-apis">EODHD API docs</a>
 */
@Service
public class EodhdClient implements FinancialDataClient {

    private static final Logger log = Logger.getLogger(EodhdClient.class.getName());

    private final WebClient webClient;
    private final ApiCache cache;
    private final String apiToken;
    private final String defaultExchange;

    public EodhdClient(
            @Value("${eodhd.base-url:https://eodhd.com/api}") String baseUrl,
            @Value("${eodhd.api-token:}") String apiToken,
            @Value("${eodhd.default-exchange:US}") String defaultExchange,
            ApiCache cache) {
        this.cache = cache;
        this.apiToken = apiToken;
        this.defaultExchange = defaultExchange;

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(16 * 1024 * 1024))
                .build();

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(strategies)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    // ======================== Prices ========================

    @Override
    public List<Price> getPrices(String ticker, String startDate, String endDate) {
        String cacheKey = "eodhd_prices_" + ticker + "_" + startDate + "_" + endDate;
        List<Price> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        List<Price> prices = fetchPrices(ticker, startDate, endDate);

        if (prices.isEmpty()) {
            log.info("No EODHD price data for " + ticker + " in range " + startDate + " to " + endDate
                    + ". Searching for most recent available data...");

            int[][] fallbacks = {
                    {0, 3}, {1, 3}, {3, 3}, {6, 6}, {12, 6}, {18, 6},
            };
            LocalDate today = LocalDate.now();
            for (int[] fb : fallbacks) {
                LocalDate fbEnd = today.minusMonths(fb[0]);
                LocalDate fbStart = fbEnd.minusMonths(fb[1]);
                String fbEndStr = fbEnd.format(DateTimeFormatter.ISO_LOCAL_DATE);
                String fbStartStr = fbStart.format(DateTimeFormatter.ISO_LOCAL_DATE);
                if (fbEndStr.equals(endDate) && fbStartStr.equals(startDate)) continue;

                prices = fetchPrices(ticker, fbStartStr, fbEndStr);
                if (!prices.isEmpty()) {
                    log.info("Found " + prices.size() + " bars for " + ticker
                            + " in fallback range " + fbStartStr + " to " + fbEndStr);
                    break;
                }
            }
            if (prices.isEmpty()) {
                log.warning("No price data found for " + ticker + " in any fallback range (EODHD)");
            }
        }

        if (!prices.isEmpty()) cache.put(cacheKey, prices);
        return prices;
    }

    /**
     * EODHD EOD endpoint: GET /eod/{TICKER.US}?from=…&to=…&period=d&fmt=json&api_token=…
     * Returns an array of {@link EodDataPoint}.
     */
    private List<Price> fetchPrices(String ticker, String startDate, String endDate) {
        try {
            String eodhdTicker = toEodhdTicker(ticker);
            log.fine("Fetching EODHD prices: ticker=" + eodhdTicker + " from=" + startDate + " to=" + endDate);

            List<EodDataPoint> points = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/eod/{ticker}")
                            .queryParam("from", startDate)
                            .queryParam("to", endDate)
                            .queryParam("period", "d")
                            .queryParam("fmt", "json")
                            .queryParam("api_token", apiToken)
                            .build(eodhdTicker))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> Mono.empty())
                    .bodyToMono(new ParameterizedTypeReference<List<EodDataPoint>>() {})
                    .block();

            if (points == null) return List.of();
            return points.stream().map(EodDataPoint::toPrice).toList();
        } catch (Exception e) {
            log.log(Level.WARNING, "Error fetching EODHD prices for " + ticker
                    + " (" + startDate + " to " + endDate + "): " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * EODHD intraday endpoint: GET /intraday/{TICKER.US}?interval=5m|1h&from=unix&to=unix.
     * Time is stored as epoch seconds + gmtoffset so charts display exchange-local time.
     */
    @Override
    public List<Price> getIntradayPrices(String ticker, String interval, String startDate, String endDate) {
        String cacheKey = "eodhd_intraday_" + ticker + "_" + interval + "_" + startDate + "_" + endDate;
        List<Price> cached = cache.get(cacheKey);
        if (cached != null) return cached;
        try {
            String eodhdTicker = toEodhdTicker(ticker);
            long from = LocalDate.parse(startDate).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
            long to = LocalDate.parse(endDate).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
            JsonNode root = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/intraday/{ticker}")
                            .queryParam("interval", interval)
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .queryParam("fmt", "json")
                            .queryParam("api_token", apiToken)
                            .build(eodhdTicker))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> Mono.empty())
                    .bodyToMono(JsonNode.class)
                    .block();
            if (root == null || !root.isArray()) return List.of();
            List<Price> out = new ArrayList<>();
            for (JsonNode n : root) {
                if (n.path("open").isNull() || n.path("close").isNull() || !n.has("timestamp")) continue;
                long ts = n.path("timestamp").asLong() + n.path("gmtoffset").asLong(0);
                out.add(new Price(n.path("open").asDouble(), n.path("close").asDouble(),
                        n.path("high").asDouble(), n.path("low").asDouble(),
                        n.path("volume").asLong(0), String.valueOf(ts)));
            }
            if (!out.isEmpty()) cache.put(cacheKey, out);
            return out;
        } catch (Exception e) {
            log.log(Level.WARNING, "Error fetching EODHD intraday for " + ticker + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    // ======================== Financial Metrics ========================

    /**
     * EODHD has no dedicated "financial-metrics" endpoint identical to Financial Datasets.
     * We derive the same metrics from the {@code /fundamentals/{ticker}} response which
     * contains Highlights, Valuation, Financials, etc.
     */
    @Override
    public List<FinancialMetrics> getFinancialMetrics(String ticker, String endDate, String period, int limit) {
        String cacheKey = "eodhd_metrics_" + ticker + "_" + period + "_" + endDate + "_" + limit;
        List<FinancialMetrics> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        try {
            String eodhdTicker = toEodhdTicker(ticker);
            JsonNode root = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/fundamentals/{ticker}")
                            .queryParam("fmt", "json")
                            .queryParam("api_token", apiToken)
                            .build(eodhdTicker))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> Mono.empty())
                    .bodyToMono(JsonNode.class)
                    .block();

            if (root == null) return List.of();

            FinancialMetrics m = mapFundamentalsToMetrics(root, ticker);
            List<FinancialMetrics> result = List.of(m);
            cache.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            log.log(Level.WARNING, "Error fetching EODHD fundamentals for " + ticker + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    // ======================== Line Items ========================

    /**
     * EODHD provides financial statements inside /fundamentals under
     * Financials.Income_Statement, Financials.Balance_Sheet, Financials.Cash_Flow.
     * We extract the requested line-item keys from those sections.
     */
    @Override
    public List<LineItem> searchLineItems(String ticker, List<String> lineItems,
                                           String endDate, String period, int limit) {
        try {
            String eodhdTicker = toEodhdTicker(ticker);
            JsonNode root = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/fundamentals/{ticker}")
                            .queryParam("fmt", "json")
                            .queryParam("api_token", apiToken)
                            .build(eodhdTicker))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> Mono.empty())
                    .bodyToMono(JsonNode.class)
                    .block();

            if (root == null) return List.of();

            List<LineItem> results = extractLineItems(root, ticker, lineItems, endDate, period, limit);
            return results.subList(0, Math.min(results.size(), limit));
        } catch (Exception e) {
            log.log(Level.WARNING, "Error fetching EODHD line items for " + ticker + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    // ======================== Insider Trades ========================

    /**
     * EODHD endpoint: GET /insider-transactions?code={TICKER.US}&from=…&to=…&limit=…&fmt=json
     */
    @Override
    public List<InsiderTrade> getInsiderTrades(String ticker, String endDate, int limit) {
        String cacheKey = "eodhd_insider_" + ticker + "_" + endDate + "_" + limit;
        List<InsiderTrade> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        try {
            String eodhdTicker = toEodhdTicker(ticker);
            List<EodhdInsiderTransaction> txns = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/insider-transactions")
                            .queryParam("code", eodhdTicker)
                            .queryParam("to", endDate)
                            .queryParam("limit", limit)
                            .queryParam("fmt", "json")
                            .queryParam("api_token", apiToken)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> Mono.empty())
                    .bodyToMono(new ParameterizedTypeReference<List<EodhdInsiderTransaction>>() {})
                    .block();

            if (txns == null) return List.of();

            List<InsiderTrade> trades = txns.stream().map(t -> {
                InsiderTrade it = new InsiderTrade();
                it.setTicker(ticker);
                it.setName(t.ownerName);
                it.setTitle(t.ownerTitle);
                it.setTransactionDate(t.date);
                it.setFilingDate(t.date);
                it.setTransactionShares(t.transactionShares);
                it.setTransactionPricePerShare(t.transactionPrice);
                it.setTransactionValue(t.transactionAmount);
                return it;
            }).toList();

            if (!trades.isEmpty()) cache.put(cacheKey, trades);
            return trades;
        } catch (Exception e) {
            log.log(Level.WARNING, "Error fetching EODHD insider trades for " + ticker + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    // ======================== Company News ========================

    /**
     * EODHD endpoint: GET /news?s={TICKER.US}&to=…&limit=…&fmt=json
     */
    @Override
    public List<CompanyNews> getCompanyNews(String ticker, String endDate, int limit) {
        String cacheKey = "eodhd_news_" + ticker + "_" + endDate + "_" + limit;
        List<CompanyNews> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        try {
            String eodhdTicker = toEodhdTicker(ticker);
            List<EodhdNewsArticle> articles = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/news")
                            .queryParam("s", eodhdTicker)
                            .queryParam("to", endDate)
                            .queryParam("limit", limit)
                            .queryParam("fmt", "json")
                            .queryParam("api_token", apiToken)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> Mono.empty())
                    .bodyToMono(new ParameterizedTypeReference<List<EodhdNewsArticle>>() {})
                    .block();

            if (articles == null) return List.of();

            List<CompanyNews> news = articles.stream().map(a -> {
                CompanyNews cn = new CompanyNews();
                cn.setTicker(ticker);
                cn.setTitle(a.title);
                cn.setDate(a.date);
                cn.setUrl(a.link);
                cn.setSource("EODHD");
                // Map EODHD polarity to sentiment label
                if (a.sentiment != null && a.sentiment.polarity != null) {
                    if (a.sentiment.polarity > 0.15) cn.setSentiment("positive");
                    else if (a.sentiment.polarity < -0.15) cn.setSentiment("negative");
                    else cn.setSentiment("neutral");
                }
                return cn;
            }).toList();

            if (!news.isEmpty()) cache.put(cacheKey, news);
            return news;
        } catch (Exception e) {
            log.log(Level.WARNING, "Error fetching EODHD news for " + ticker + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    // ======================== Market Cap ========================

    /**
     * EODHD endpoint: GET /historical-market-cap/{TICKER.US}?from=…&to=…&fmt=json
     * Falls back to the Highlights.MarketCapitalization from /fundamentals.
     */
    @Override
    public Double getMarketCap(String ticker, String endDate) {
        try {
            String eodhdTicker = toEodhdTicker(ticker);

            // Try historical market cap endpoint first
            List<EodhdMarketCapPoint> points = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/historical-market-cap/{ticker}")
                            .queryParam("from", endDate)
                            .queryParam("to", endDate)
                            .queryParam("fmt", "json")
                            .queryParam("api_token", apiToken)
                            .build(eodhdTicker))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> Mono.empty())
                    .bodyToMono(new ParameterizedTypeReference<List<EodhdMarketCapPoint>>() {})
                    .block();

            if (points != null && !points.isEmpty()) {
                return points.get(points.size() - 1).value;
            }

            // Fallback: fundamentals → Highlights → MarketCapitalization
            JsonNode root = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/fundamentals/{ticker}")
                            .queryParam("filter", "Highlights::MarketCapitalization")
                            .queryParam("fmt", "json")
                            .queryParam("api_token", apiToken)
                            .build(eodhdTicker))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> Mono.empty())
                    .bodyToMono(JsonNode.class)
                    .block();

            if (root != null) {
                JsonNode mcNode = root.path("Highlights").path("MarketCapitalization");
                if (!mcNode.isMissingNode() && mcNode.isNumber()) {
                    return mcNode.doubleValue();
                }
                // If the filter returns the value directly
                if (root.isNumber()) {
                    return root.doubleValue();
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Error fetching EODHD market cap for " + ticker + ": " + e.getMessage(), e);
        }
        return null;
    }

    // ======================== Helper: ticker conversion ========================

    /**
     * Convert a bare ticker to EODHD format ({@code SYMBOL.EXCHANGE}).
     * <p>
     * If the ticker already contains a dot (e.g. {@code D05.SG}, {@code VOD.LSE}),
     * it is returned as-is. Otherwise the configured {@code eodhd.default-exchange}
     * suffix is appended (defaults to {@code US}).
     * <p>
     * <b>Singapore (SGX) examples:</b>
     * <ul>
     *   <li>{@code D05.SG}  — DBS Group</li>
     *   <li>{@code O39.SG}  — OCBC Bank</li>
     *   <li>{@code U11.SG}  — UOB</li>
     *   <li>{@code Z74.SG}  — Singtel</li>
     *   <li>{@code C6L.SG}  — Singapore Airlines</li>
     *   <li>{@code BS6.SG}  — YZJ Shipbuilding</li>
     *   <li>{@code S68.SG}  — SGX</li>
     *   <li>{@code A17U.SG} — CapitaLand Ascendas REIT</li>
     * </ul>
     */
    private String toEodhdTicker(String ticker) {
        if (ticker == null) return "";
        return ticker.contains(".") ? ticker : ticker + "." + defaultExchange;
    }

    // ======================== Helper: fundamentals → FinancialMetrics ========================

    private FinancialMetrics mapFundamentalsToMetrics(JsonNode root, String ticker) {
        FinancialMetrics m = new FinancialMetrics();
        m.setTicker(ticker);

        JsonNode highlights = root.path("Highlights");
        JsonNode valuation = root.path("Valuation");

        // Market cap
        m.setMarketCap(dbl(highlights, "MarketCapitalization"));

        // Valuation ratios
        m.setPriceToEarningsRatio(firstNonNull(
                dbl(highlights, "PERatio"),
                dbl(valuation, "TrailingPE")));
        m.setPriceToBookRatio(dbl(valuation, "PriceBookMRQ"));
        m.setPriceToSalesRatio(dbl(valuation, "PriceSalesTTM"));
        m.setEnterpriseValueToEbitdaRatio(dbl(valuation, "EnterpriseValueEbitda"));
        m.setEnterpriseValueToRevenueRatio(dbl(valuation, "EnterpriseValueRevenue"));
        m.setEnterpriseValue(dbl(highlights, "EnterpriseValue"));
        m.setPegRatio(dbl(highlights, "PEGRatio"));

        // Profitability
        m.setGrossMargin(dbl(highlights, "GrossProfitTTM") != null && dbl(highlights, "RevenueTTM") != null
                ? dbl(highlights, "GrossProfitTTM") / dbl(highlights, "RevenueTTM") : null);
        m.setOperatingMargin(dbl(highlights, "OperatingMarginTTM"));
        m.setNetMargin(dbl(highlights, "ProfitMargin"));
        m.setReturnOnEquity(dbl(highlights, "ReturnOnEquityTTM"));
        m.setReturnOnAssets(dbl(highlights, "ReturnOnAssetsTTM"));

        // Growth
        m.setRevenueGrowth(dbl(highlights, "QuarterlyRevenueGrowthYOY"));
        m.setEarningsGrowth(dbl(highlights, "QuarterlyEarningsGrowthYOY"));

        // Per-share
        m.setEarningsPerShare(dbl(highlights, "EarningsShare"));
        m.setBookValuePerShare(dbl(highlights, "BookValue"));

        // Financial health — derive from Financials if available
        JsonNode balanceSheet = root.path("Financials").path("Balance_Sheet").path("quarterly");
        if (balanceSheet.isObject()) {
            Iterator<JsonNode> it = balanceSheet.elements();
            if (it.hasNext()) {
                JsonNode latest = it.next();
                Double totalCurrentAssets = dbl(latest, "totalCurrentAssets");
                Double totalCurrentLiabilities = dbl(latest, "totalCurrentLiabilities");
                Double totalDebt = firstNonNull(dbl(latest, "shortLongTermDebtTotal"), dbl(latest, "longTermDebt"));
                Double totalEquity = dbl(latest, "totalStockholderEquity");

                if (totalCurrentAssets != null && totalCurrentLiabilities != null && totalCurrentLiabilities != 0) {
                    m.setCurrentRatio(totalCurrentAssets / totalCurrentLiabilities);
                }
                if (totalDebt != null && totalEquity != null && totalEquity != 0) {
                    m.setDebtToEquity(totalDebt / totalEquity);
                }
            }
        }

        // Report period
        JsonNode earnings = root.path("Earnings").path("History");
        if (earnings.isObject()) {
            Iterator<String> fieldNames = earnings.fieldNames();
            if (fieldNames.hasNext()) {
                m.setReportPeriod(fieldNames.next());
            }
        }
        m.setPeriod("ttm");

        // Currency from General section (e.g. "USD", "SGD", "GBP")
        JsonNode general = root.path("General");
        String currency = general.has("CurrencyCode") ? general.get("CurrencyCode").asText("USD") : "USD";
        m.setCurrency(currency);

        return m;
    }

    // ======================== Helper: fundamentals → LineItems ========================

    private List<LineItem> extractLineItems(JsonNode root, String ticker,
                                             List<String> requestedItems, String endDate,
                                             String period, int limit) {
        List<LineItem> results = new ArrayList<>();

        // Map period to EODHD section key
        String sectionKey = "annual".equals(period) || "ttm".equals(period) ? "yearly" : "quarterly";

        // Search across all three financial statement types
        String[] statementTypes = {"Income_Statement", "Balance_Sheet", "Cash_Flow"};

        JsonNode financials = root.path("Financials");

        for (String stmtType : statementTypes) {
            JsonNode section = financials.path(stmtType).path(sectionKey);
            if (!section.isObject()) continue;

            Iterator<Map.Entry<String, JsonNode>> periods = section.fields();
            int count = 0;
            while (periods.hasNext() && count < limit) {
                Map.Entry<String, JsonNode> entry = periods.next();
                String reportDate = entry.getKey();

                // Filter by endDate
                if (endDate != null && reportDate.compareTo(endDate) > 0) continue;

                JsonNode periodData = entry.getValue();

                LineItem li = new LineItem();
                li.setTicker(ticker);
                li.setReportPeriod(reportDate);
                li.setPeriod(period);
                li.setCurrency(periodData.has("currency_symbol")
                        ? periodData.get("currency_symbol").asText() : "USD");

                boolean hasData = false;
                for (String item : requestedItems) {
                    // Try the exact key and common EODHD camelCase variants
                    JsonNode val = periodData.path(item);
                    if (val.isMissingNode()) val = periodData.path(toCamelCase(item));
                    if (val.isMissingNode()) val = periodData.path(toSnakeCase(item));

                    if (!val.isMissingNode() && val.isNumber()) {
                        li.setExtra(item, val.doubleValue());
                        hasData = true;
                    }
                }

                if (hasData) {
                    results.add(li);
                    count++;
                }
            }
        }

        return results;
    }

    // ======================== Utility ========================

    private Double dbl(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull() || !v.isNumber()) return null;
        return v.doubleValue();
    }

    private Double firstNonNull(Double... values) {
        for (Double v : values) {
            if (v != null) return v;
        }
        return null;
    }

    /** Convert snake_case like "total_revenue" to camelCase "totalRevenue". */
    private String toCamelCase(String s) {
        if (s == null || !s.contains("_")) return s;
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : s.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    /** Convert camelCase like "totalRevenue" to snake_case "total_revenue". */
    private String toSnakeCase(String s) {
        if (s == null) return s;
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    // ======================== EODHD response DTOs ========================

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EodDataPoint {
        public String date;
        public Double open;
        public Double high;
        public Double low;
        public Double close;
        @JsonProperty("adjusted_close") public Double adjustedClose;
        public Long volume;

        Price toPrice() {
            Price p = new Price();
            p.setOpen(close != null ? open : 0);
            p.setClose(adjustedClose != null ? adjustedClose : (close != null ? close : 0));
            p.setHigh(high != null ? high : 0);
            p.setLow(low != null ? low : 0);
            p.setVolume(volume != null ? volume : 0);
            p.setTime(date);
            return p;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EodhdInsiderTransaction {
        public String code;
        public String date;
        public String ownerName;
        public String ownerTitle;
        public String transactionType;
        public Double transactionAmount;
        public Double transactionPrice;
        public Double transactionShares;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EodhdNewsArticle {
        public String date;
        public String title;
        public String content;
        public String link;
        public List<String> symbols;
        public List<String> tags;
        public EodhdSentiment sentiment;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EodhdSentiment {
        public Double polarity;
        public Double neg;
        public Double neu;
        public Double pos;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EodhdMarketCapPoint {
        public String date;
        public Double value;
    }
}

