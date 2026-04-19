package com.hedgefund.ai.service.agents;

import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.api.CompanyNews;
import com.hedgefund.ai.model.api.MarketAuxResponse;
import com.hedgefund.ai.service.LlmService;
import com.hedgefund.ai.service.api.FinancialDataClient;
import com.hedgefund.ai.service.api.MarketAuxClient;
import com.hedgefund.ai.service.api.MarketAuxClient.NewsSentimentScore;
import com.hedgefund.ai.service.api.MarketAuxClient.ArticleSummary;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;

@Component
public class NewsSentimentAgent implements AnalystAgent {

    private static final Logger log = Logger.getLogger(NewsSentimentAgent.class.getName());
    private final FinancialDataClient apiClient;
    private final LlmService llmService;
    private final MarketAuxClient marketAuxClient;

    public NewsSentimentAgent(FinancialDataClient apiClient, LlmService llmService, MarketAuxClient marketAuxClient) {
        this.apiClient = apiClient;
        this.llmService = llmService;
        this.marketAuxClient = marketAuxClient;
    }

    @Override public String getAgentId() { return "news_sentiment_analyst"; }
    @Override public String getDisplayName() { return "News Sentiment Analyst"; }
    @Override public String getDescription() { return "News Sentiment Specialist — MarketAux + EODHD dual-source analysis"; }

    public static class SentimentResult {
        private String sentiment;
        private int confidence;
        public SentimentResult() {}
        public String getSentiment() { return sentiment; }
        public void setSentiment(String v) { this.sentiment = v; }
        public int getConfidence() { return confidence; }
        public void setConfidence(int v) { this.confidence = v; }
    }

    @Override
    public Map<String, AnalystSignal> analyze(AgentState state) {
        Map<String, AnalystSignal> results = new LinkedHashMap<>();

        // Fetch MarketAux news for all tickers at once (more efficient)
        MarketAuxResponse marketAuxResponse = null;
        if (marketAuxClient.isConfigured()) {
            try {
                marketAuxResponse = marketAuxClient.fetchNews(state.getTickers(), 50);
            } catch (Exception e) {
                log.warning("MarketAux batch fetch failed: " + e.getMessage());
            }
        }

        for (String ticker : state.getTickers()) {
            log.info("[" + getAgentId() + "] Analyzing " + ticker);

            // ── Source 1: MarketAux (real-time, entity-level sentiment scores) ──
            NewsSentimentScore marketAuxScore = null;
            List<ArticleSummary> marketAuxArticles = Collections.emptyList();
            if (marketAuxResponse != null && marketAuxResponse.getData() != null) {
                marketAuxScore = marketAuxClient.computeSentiment(ticker, marketAuxResponse.getData());
                marketAuxArticles = marketAuxScore.articles();
            }

            // ── Source 2: EODHD / FinancialDatasets (existing provider) ──
            List<CompanyNews> providerNews = apiClient.getCompanyNews(ticker, state.getEndDate(), 100);
            int classifiedByLlm = 0;
            if (!providerNews.isEmpty()) {
                List<CompanyNews> needClassification = providerNews.stream()
                        .limit(10).filter(n -> n.getSentiment() == null).limit(5).toList();
                for (CompanyNews news : needClassification) {
                    String systemPrompt = "You are a financial news sentiment classifier. Respond ONLY with JSON: {\"sentiment\":\"positive|negative|neutral\",\"confidence\":0-100}";
                    String userPrompt = String.format("Analyze the sentiment of this headline for stock %s. Headline: %s", ticker, news.getTitle());
                    SentimentResult result = llmService.call(systemPrompt, userPrompt, SentimentResult.class, state.getModelProvider(), state.getModelName());
                    news.setSentiment(result != null ? result.getSentiment().toLowerCase() : "neutral");
                    classifiedByLlm++;
                }
            }
            long provBullish = 0, provBearish = 0, provNeutral = 0;
            for (CompanyNews n : providerNews) {
                if ("positive".equalsIgnoreCase(n.getSentiment())) provBullish++;
                else if ("negative".equalsIgnoreCase(n.getSentiment())) provBearish++;
                else provNeutral++;
            }

            // ── Combine both sources (MarketAux weighted 60%, provider 40%) ──
            double combinedBullish, combinedBearish;
            if (marketAuxScore != null && marketAuxScore.articleCount() > 0) {
                // MarketAux: scale sentiment score (-1 to +1) to bullish/bearish weight
                double maBullish = Math.max(marketAuxScore.avgSentiment(), 0) * marketAuxScore.articleCount();
                double maBearish = Math.max(-marketAuxScore.avgSentiment(), 0) * marketAuxScore.articleCount();
                combinedBullish = maBullish * 0.6 + provBullish * 0.4;
                combinedBearish = maBearish * 0.6 + provBearish * 0.4;
            } else {
                combinedBullish = provBullish;
                combinedBearish = provBearish;
            }

            String overall = combinedBullish > combinedBearish ? "bullish"
                    : combinedBearish > combinedBullish ? "bearish" : "neutral";
            double total = combinedBullish + combinedBearish;
            double confidence = total > 0 ? Math.round(Math.max(combinedBullish, combinedBearish) / total * 100.0) : 0;

            // ── Build reasoning with article titles and links ──
            Map<String, Object> reasoning = new LinkedHashMap<>();

            // MarketAux data
            Map<String, Object> marketAuxData = new LinkedHashMap<>();
            if (marketAuxScore != null && marketAuxScore.articleCount() > 0) {
                marketAuxData.put("signal", marketAuxScore.signal());
                marketAuxData.put("avg_sentiment", Math.round(marketAuxScore.avgSentiment() * 10000) / 10000.0);
                marketAuxData.put("article_count", marketAuxScore.articleCount());
                marketAuxData.put("bullish_count", marketAuxScore.bullishCount());
                marketAuxData.put("bearish_count", marketAuxScore.bearishCount());
                marketAuxData.put("neutral_count", marketAuxScore.neutralCount());

                // Include article titles and links (up to 10)
                List<Map<String, String>> articlesList = new ArrayList<>();
                int count = 0;
                for (ArticleSummary art : marketAuxArticles) {
                    if (count >= 10) break;
                    Map<String, String> artMap = new LinkedHashMap<>();
                    artMap.put("title", art.title());
                    artMap.put("url", art.url() != null ? art.url() : "");
                    artMap.put("source", art.source() != null ? art.source() : "");
                    artMap.put("sentiment", art.sentimentLabel());
                    articlesList.add(artMap);
                    count++;
                }
                marketAuxData.put("articles", articlesList);
            } else {
                marketAuxData.put("status", marketAuxClient.isConfigured() ? "no_data" : "not_configured");
            }
            reasoning.put("marketaux", marketAuxData);

            // Provider (EODHD) data
            reasoning.put("provider_news", Map.of(
                    "total_articles", providerNews.size(),
                    "bullish", provBullish,
                    "bearish", provBearish,
                    "neutral", provNeutral,
                    "classified_by_llm", classifiedByLlm));

            reasoning.put("combined_signal", overall);
            reasoning.put("combined_confidence", confidence);

            results.put(ticker, AnalystSignal.builder()
                    .signal(overall)
                    .confidence(confidence)
                    .reasoning(reasoning)
                    .build());
        }
        return results;
    }
}
