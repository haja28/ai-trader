package com.hedgefund.ai.service.api;

import com.hedgefund.ai.model.api.MarketAuxResponse;
import com.hedgefund.ai.model.api.MarketAuxResponse.Article;
import com.hedgefund.ai.model.api.MarketAuxResponse.Entity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client for the MarketAux News Sentiment API.
 * Fetches real-time news with per-entity sentiment scores (-1 to +1).
 * Uses an internal per-ticker cache so batch and individual fetches share results.
 */
@Service
public class MarketAuxClient {

    private static final Logger log = Logger.getLogger(MarketAuxClient.class.getName());
    private final RestTemplate restTemplate;

    /**
     * Per-ticker article cache. Populated by both batch and individual fetches.
     * Key = ticker symbol (upper case), Value = list of articles matching that ticker.
     * Entries expire when the service bean is recreated (per-request caching is handled by
     * Caffeine at the Spring level; this is a within-request dedup cache).
     */
    private final Map<String, List<Article>> tickerArticleCache = new ConcurrentHashMap<>();

    @Value("${marketaux.api-token:}")
    private String apiToken;

    @Value("${marketaux.base-url:https://api.marketaux.com/v1}")
    private String baseUrl;

    @Value("${marketaux.default-limit:50}")
    private int defaultLimit;

    public MarketAuxClient() {
        this.restTemplate = new RestTemplate();
    }

    public boolean isConfigured() {
        return apiToken != null && !apiToken.isBlank();
    }

    /**
     * Fetch news for multiple tickers in a single API call.
     * Populates the per-ticker cache so subsequent single-ticker calls are free.
     */
    public MarketAuxResponse fetchNews(List<String> tickers, int limit) {
        if (!isConfigured()) {
            log.warning("MarketAux API token not configured. Set marketaux.api-token in application.properties");
            return emptyResponse();
        }

        // Check if all tickers are already cached
        boolean allCached = tickers.stream().allMatch(t -> tickerArticleCache.containsKey(t.toUpperCase()));
        if (allCached) {
            // Build response from cache
            List<Article> allArticles = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String t : tickers) {
                for (Article art : tickerArticleCache.getOrDefault(t.toUpperCase(), Collections.emptyList())) {
                    if (art.getUuid() != null && seen.add(art.getUuid())) {
                        allArticles.add(art);
                    }
                }
            }
            MarketAuxResponse cached = new MarketAuxResponse();
            cached.setData(allArticles);
            MarketAuxResponse.Meta meta = new MarketAuxResponse.Meta();
            meta.setFound(allArticles.size());
            meta.setReturned(allArticles.size());
            cached.setMeta(meta);
            return cached;
        }

        String symbols = String.join(",", tickers);
        String url = String.format("%s/news/all?symbols=%s&filter_entities=true&language=en&limit=%d&api_token=%s",
                baseUrl, symbols, limit, apiToken);
        try {
            log.info("Fetching MarketAux news for: " + symbols);
            MarketAuxResponse response = restTemplate.getForObject(url, MarketAuxResponse.class);
            if (response != null && response.getData() != null) {
                log.info("MarketAux returned " + response.getData().size() + " articles for " + symbols);
                // Populate per-ticker cache
                populateTickerCache(tickers, response.getData());
                return response;
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "MarketAux API call failed: " + e.getMessage());
        }

        // Mark all tickers as cached (empty) to avoid re-fetching
        for (String t : tickers) {
            tickerArticleCache.putIfAbsent(t.toUpperCase(), Collections.emptyList());
        }
        return emptyResponse();
    }

    /**
     * Fetch news for a single ticker. Uses cache if already populated by a batch call.
     */
    public MarketAuxResponse fetchNews(String ticker) {
        String key = ticker.toUpperCase();
        // Return from cache if already fetched (by batch or prior individual call)
        if (tickerArticleCache.containsKey(key)) {
            List<Article> cached = tickerArticleCache.get(key);
            MarketAuxResponse response = new MarketAuxResponse();
            response.setData(cached);
            MarketAuxResponse.Meta meta = new MarketAuxResponse.Meta();
            meta.setFound(cached.size());
            meta.setReturned(cached.size());
            response.setMeta(meta);
            if (!cached.isEmpty()) {
                log.info("MarketAux cache hit for " + ticker + " (" + cached.size() + " articles)");
            }
            return response;
        }
        // Not cached — fetch individually
        return fetchNews(List.of(ticker), defaultLimit);
    }

    /**
     * Populate the per-ticker cache from a batch response.
     * Each article is assigned to every ticker it mentions in its entities.
     */
    private void populateTickerCache(List<String> tickers, List<Article> articles) {
        // Initialize all requested tickers with empty lists
        for (String t : tickers) {
            tickerArticleCache.putIfAbsent(t.toUpperCase(), new ArrayList<>());
        }
        // Assign articles to matching tickers
        for (Article article : articles) {
            if (article.getEntities() == null) continue;
            for (Entity entity : article.getEntities()) {
                if (entity.getSymbol() == null) continue;
                String symbol = entity.getSymbol().toUpperCase();
                tickerArticleCache.computeIfAbsent(symbol, k -> new ArrayList<>()).add(article);
            }
        }
    }

    /**
     * Clear the per-ticker cache. Call this between requests if needed.
     */
    public void clearCache() {
        tickerArticleCache.clear();
    }

    /**
     * Compute aggregate news sentiment for a specific ticker.
     * Uses match_score-weighted average of entity sentiment scores.
     */
    public NewsSentimentScore computeSentiment(String ticker, List<Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return new NewsSentimentScore(0, 0, 0, 0, 0, "neutral", "No news data available for " + ticker, Collections.emptyList());
        }
        double weightedSum = 0, totalWeight = 0;
        int bullish = 0, bearish = 0, neutral = 0, matched = 0;
        List<ArticleSummary> matchedArticles = new ArrayList<>();

        for (Article article : articles) {
            if (article.getEntities() == null) continue;
            for (Entity entity : article.getEntities()) {
                if (!ticker.equalsIgnoreCase(entity.getSymbol())) continue;
                matched++;
                double sentiment = entity.getSentimentScore();
                double weight = Math.max(entity.getMatchScore(), 1.0);
                weightedSum += sentiment * weight;
                totalWeight += weight;
                if (sentiment > 0.15) bullish++;
                else if (sentiment < -0.15) bearish++;
                else neutral++;

                // Collect article summary (deduplicate by title)
                String title = article.getTitle();
                if (title != null && matchedArticles.stream().noneMatch(a -> title.equals(a.title()))) {
                    String sentLabel = sentiment > 0.15 ? "bullish" : sentiment < -0.15 ? "bearish" : "neutral";
                    matchedArticles.add(new ArticleSummary(
                            title, article.getUrl(), article.getSource(),
                            article.getPublishedAt(), sentiment, sentLabel));
                }
                break; // one match per article is enough
            }
        }
        if (matched == 0) return new NewsSentimentScore(0, 0, 0, 0, 0, "neutral", "No matched articles for " + ticker, Collections.emptyList());

        double avg = totalWeight > 0 ? weightedSum / totalWeight : 0;
        String signal = avg > 0.20 ? "bullish" : avg < -0.20 ? "bearish" : "neutral";
        String details = String.format("articles=%d bull=%d bear=%d neut=%d avg_sentiment=%.4f", matched, bullish, bearish, neutral, avg);
        return new NewsSentimentScore(avg, matched, bullish, bearish, neutral, signal, details, matchedArticles);
    }

    private MarketAuxResponse emptyResponse() {
        MarketAuxResponse r = new MarketAuxResponse();
        r.setData(Collections.emptyList());
        MarketAuxResponse.Meta m = new MarketAuxResponse.Meta();
        m.setFound(0);
        m.setReturned(0);
        r.setMeta(m);
        return r;
    }

    public record NewsSentimentScore(
            double avgSentiment, int articleCount, int bullishCount,
            int bearishCount, int neutralCount, String signal, String details,
            List<ArticleSummary> articles
    ) {}

    /**
     * Compact summary of a matched news article for report rendering.
     */
    public record ArticleSummary(
            String title, String url, String source,
            String publishedAt, double sentiment, String sentimentLabel
    ) {}
}

