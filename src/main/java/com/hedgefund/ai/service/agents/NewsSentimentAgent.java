package com.hedgefund.ai.service.agents;

import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.api.CompanyNews;
import com.hedgefund.ai.service.LlmService;
import com.hedgefund.ai.service.api.FinancialDataClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;

@Component
public class NewsSentimentAgent implements AnalystAgent {

    private static final Logger log = Logger.getLogger(NewsSentimentAgent.class.getName());
    private final FinancialDataClient apiClient;
    private final LlmService llmService;

    public NewsSentimentAgent(FinancialDataClient apiClient, LlmService llmService) {
        this.apiClient = apiClient;
        this.llmService = llmService;
    }

    @Override public String getAgentId() { return "news_sentiment_analyst"; }
    @Override public String getDisplayName() { return "News Sentiment Analyst"; }
    @Override public String getDescription() { return "News Sentiment Specialist"; }

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
        for (String ticker : state.getTickers()) {
            log.info("[" + getAgentId() + "] Analyzing " + ticker);
            List<CompanyNews> allNews = apiClient.getCompanyNews(ticker, state.getEndDate(), 100);
            int classifiedByLlm = 0;
            if (!allNews.isEmpty()) {
                List<CompanyNews> needClassification = allNews.stream()
                        .limit(10).filter(n -> n.getSentiment() == null).limit(5).toList();
                for (CompanyNews news : needClassification) {
                    String systemPrompt = "You are a financial news sentiment classifier. Respond ONLY with JSON: {\"sentiment\":\"positive|negative|neutral\",\"confidence\":0-100}";
                    String userPrompt = String.format("Analyze the sentiment of this headline for stock %s. Headline: %s", ticker, news.getTitle());
                    SentimentResult result = llmService.call(systemPrompt, userPrompt, SentimentResult.class, state.getModelProvider(), state.getModelName());
                    news.setSentiment(result != null ? result.getSentiment().toLowerCase() : "neutral");
                    classifiedByLlm++;
                }
            }
            long bullish = 0, bearish = 0, neutral = 0;
            for (CompanyNews n : allNews) {
                if ("positive".equalsIgnoreCase(n.getSentiment())) bullish++;
                else if ("negative".equalsIgnoreCase(n.getSentiment())) bearish++;
                else neutral++;
            }
            String overall = bullish > bearish ? "bullish" : bearish > bullish ? "bearish" : "neutral";
            long total = bullish + bearish + neutral;
            double confidence = total > 0 ? Math.round((double) Math.max(bullish, bearish) / total * 100.0) : 0;
            Map<String, Object> reasoning = new LinkedHashMap<>();
            reasoning.put("total_articles", total);
            reasoning.put("bullish_articles", bullish);
            reasoning.put("bearish_articles", bearish);
            reasoning.put("neutral_articles", neutral);
            reasoning.put("articles_classified_by_llm", classifiedByLlm);
            results.put(ticker, AnalystSignal.builder().signal(overall).confidence(confidence).reasoning(Map.of("news_sentiment", reasoning)).build());
        }
        return results;
    }
}
