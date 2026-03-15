package com.hedgefund.ai.service.api;

import com.hedgefund.ai.cache.ApiCache;
import com.hedgefund.ai.model.api.*;
import com.hedgefund.ai.model.api.ApiResponses.*;
import org.springframework.beans.factory.annotation.Value;
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

@Service
public class FinancialDatasetsClient implements FinancialDataClient {

    private static final Logger log = Logger.getLogger(FinancialDatasetsClient.class.getName());

    private final WebClient webClient;
    private final ApiCache cache;

    public FinancialDatasetsClient(
            @Value("${financial.datasets.base-url}") String baseUrl,
            @Value("${financial.datasets.api-key:}") String apiKey,
            ApiCache cache) {
        this.cache = cache;

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(16 * 1024 * 1024)) // 16 MB
                .build();

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(strategies)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json");

        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("X-API-KEY", apiKey);
        }
        this.webClient = builder.build();
    }

    // ======================== Prices ========================

    public List<Price> getPrices(String ticker, String startDate, String endDate) {
        String cacheKey = "prices_" + ticker + "_" + startDate + "_" + endDate;
        List<Price> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        // Try the requested range first
        List<Price> prices = fetchPrices(ticker, startDate, endDate);

        // If empty, the requested dates may be in the future or beyond the API's data range.
        // Auto-detect available data by trying progressively older date ranges.
        if (prices.isEmpty()) {
            log.info("No price data for " + ticker + " in range " + startDate + " to " + endDate
                    + ". Searching for most recent available data...");

            // Fallback date ranges: each pair is (months-back-for-end, months-span)
            int[][] fallbacks = {
                    {0, 3},   // today - 3m  (maybe end_date was in the future)
                    {1, 3},   // 1 month ago - 3m
                    {3, 3},   // 3 months ago - 3m
                    {6, 6},   // 6 months ago - 6m
                    {12, 6},  // 1 year ago - 6m
                    {18, 6},  // 1.5 years ago - 6m
            };

            LocalDate today = LocalDate.now();
            for (int[] fb : fallbacks) {
                LocalDate fbEnd   = today.minusMonths(fb[0]);
                LocalDate fbStart = fbEnd.minusMonths(fb[1]);
                String fbEndStr   = fbEnd.format(DateTimeFormatter.ISO_LOCAL_DATE);
                String fbStartStr = fbStart.format(DateTimeFormatter.ISO_LOCAL_DATE);

                // Skip if this is the same as what we already tried
                if (fbEndStr.equals(endDate) && fbStartStr.equals(startDate)) continue;

                prices = fetchPrices(ticker, fbStartStr, fbEndStr);
                if (!prices.isEmpty()) {
                    log.info("Found " + prices.size() + " bars for " + ticker
                            + " in fallback range " + fbStartStr + " to " + fbEndStr);
                    break;
                }
            }

            if (prices.isEmpty()) {
                log.warning("No price data found for " + ticker + " in any fallback range");
            }
        }

        if (!prices.isEmpty()) cache.put(cacheKey, prices);
        return prices;
    }

    /**
     * Low-level price fetch — single attempt, no fallback.
     */
    private List<Price> fetchPrices(String ticker, String startDate, String endDate) {
        try {
            log.fine("Fetching prices: ticker=" + ticker + " start=" + startDate + " end=" + endDate);
            PriceResponse resp = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/prices/")
                            .queryParam("ticker", ticker)
                            .queryParam("interval", "day")
                            .queryParam("interval_multiplier", 1)
                            .queryParam("start_date", startDate)
                            .queryParam("end_date", endDate)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp2 -> Mono.empty())
                    .bodyToMono(PriceResponse.class)
                    .block();

            return (resp != null && resp.getPrices() != null) ? resp.getPrices() : List.of();
        } catch (Exception e) {
            log.log(Level.WARNING, "Error fetching prices for " + ticker
                    + " (" + startDate + " to " + endDate + "): " + e.getMessage(), e);
            return List.of();
        }
    }

    // ======================== Financial Metrics ========================

    public List<FinancialMetrics> getFinancialMetrics(String ticker, String endDate, String period, int limit) {
        String cacheKey = "metrics_" + ticker + "_" + period + "_" + endDate + "_" + limit;
        List<FinancialMetrics> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        try {
            FinancialMetricsResponse resp = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/financial-metrics/")
                            .queryParam("ticker", ticker)
                            .queryParam("report_period_lte", endDate)
                            .queryParam("limit", limit)
                            .queryParam("period", period)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp2 -> Mono.empty())
                    .bodyToMono(FinancialMetricsResponse.class)
                    .block();

            List<FinancialMetrics> metrics = (resp != null && resp.getFinancialMetrics() != null)
                    ? resp.getFinancialMetrics() : List.of();
            if (!metrics.isEmpty()) cache.put(cacheKey, metrics);
            return metrics;
        } catch (Exception e) {
            log.log(Level.WARNING, "Error fetching financial metrics for " + ticker + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    // ======================== Line Items ========================

    @Override
    public List<LineItem> searchLineItems(String ticker, List<String> lineItems,
                                          String endDate, String period, int limit) {
        try {
            Map<String, Object> body = Map.of(
                    "tickers", List.of(ticker),
                    "line_items", lineItems,
                    "end_date", endDate,
                    "period", period,
                    "limit", limit
            );

            LineItemResponse resp = webClient.post()
                    .uri("/financials/search/line-items")
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp2 -> Mono.empty())
                    .bodyToMono(LineItemResponse.class)
                    .block();

            if (resp != null && resp.getSearchResults() != null) {
                List<LineItem> results = resp.getSearchResults();
                return results.subList(0, Math.min(results.size(), limit));
            }
            return List.of();
        } catch (Exception e) {
            log.log(Level.WARNING, "Error fetching line items for " + ticker + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    // ======================== Insider Trades ========================

    @Override
    public List<InsiderTrade> getInsiderTrades(String ticker, String endDate, int limit) {
        String cacheKey = "insider_" + ticker + "_" + endDate + "_" + limit;
        List<InsiderTrade> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        try {
            InsiderTradeResponse resp = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/insider-trades/")
                            .queryParam("ticker", ticker)
                            .queryParam("filing_date_lte", endDate)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp2 -> Mono.empty())
                    .bodyToMono(InsiderTradeResponse.class)
                    .block();

            List<InsiderTrade> trades = (resp != null && resp.getInsiderTrades() != null)
                    ? resp.getInsiderTrades() : List.of();
            if (!trades.isEmpty()) cache.put(cacheKey, trades);
            return trades;
        } catch (Exception e) {
            log.log(Level.WARNING, "Error fetching insider trades for " + ticker + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    // ======================== Company News ========================

    @Override
    public List<CompanyNews> getCompanyNews(String ticker, String endDate, int limit) {
        String cacheKey = "news_" + ticker + "_" + endDate + "_" + limit;
        List<CompanyNews> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        try {
            CompanyNewsResponse resp = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/news/")
                            .queryParam("ticker", ticker)
                            .queryParam("end_date", endDate)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp2 -> Mono.empty())
                    .bodyToMono(CompanyNewsResponse.class)
                    .block();

            List<CompanyNews> news = (resp != null && resp.getNews() != null)
                    ? resp.getNews() : List.of();
            if (!news.isEmpty()) cache.put(cacheKey, news);
            return news;
        } catch (Exception e) {
            log.log(Level.WARNING, "Error fetching company news for " + ticker + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    // ======================== Market Cap ========================

    @Override
    public Double getMarketCap(String ticker, String endDate) {
        // If end_date is today, use company facts API
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (today.equals(endDate)) {
            try {
                CompanyFactsResponse resp = webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/company/facts/")
                                .queryParam("ticker", ticker)
                                .build())
                        .retrieve()
                        .onStatus(status -> status.value() == 404, resp2 -> {
                            log.log(Level.WARNING, "Company facts not found for ticker '" + ticker + "'. Skipping.");
                            return Mono.empty();
                        })
                        .onStatus(HttpStatusCode::isError, resp2 -> {
                            log.log(Level.WARNING, "Error fetching company facts for " + ticker + ": " + resp2.statusCode());
                            return Mono.empty();
                        })
                        .bodyToMono(CompanyFactsResponse.class)
                        .block();

                if (resp != null && resp.getCompanyFacts() != null) {
                    return resp.getCompanyFacts().getMarketCap();
                }
            } catch (Exception e) {
                log.log(Level.WARNING, "Error fetching market cap for " + ticker + ": " + e.getMessage(), e);
            }
            return null;
        }

        // Otherwise fall back to financial metrics
        List<FinancialMetrics> metrics = getFinancialMetrics(ticker, endDate, "ttm", 1);
        if (!metrics.isEmpty() && metrics.get(0).getMarketCap() != null) {
            return metrics.get(0).getMarketCap();
        }
        return null;
    }
}

