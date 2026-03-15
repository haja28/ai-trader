package com.hedgefund.ai.service.agents;

import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.api.CompanyNews;
import com.hedgefund.ai.model.api.InsiderTrade;
import com.hedgefund.ai.service.api.FinancialDataClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;

@Component
public class SentimentAnalystAgent implements AnalystAgent {

    private static final Logger log = Logger.getLogger(SentimentAnalystAgent.class.getName());
    private final FinancialDataClient apiClient;

    public SentimentAnalystAgent(FinancialDataClient apiClient) { this.apiClient = apiClient; }

    @Override
    public String getAgentId() { return "sentiment_analyst"; }

    @Override
    public String getDisplayName() { return "Sentiment Analyst"; }

    @Override
    public String getDescription() { return "Market Sentiment Specialist"; }

    @Override
    public Map<String, AnalystSignal> analyze(AgentState state) {
        Map<String, AnalystSignal> results = new LinkedHashMap<>();

        for (String ticker : state.getTickers()) {
            log.info("[" + getAgentId() + "] Analyzing " + ticker);

            // Insider trades
            List<InsiderTrade> trades = apiClient.getInsiderTrades(ticker, state.getEndDate(), 1000);
            long insiderBullish = 0, insiderBearish = 0;
            for (InsiderTrade t : trades) {
                if (t.getTransactionShares() != null) {
                    if (t.getTransactionShares() < 0) insiderBearish++;
                    else insiderBullish++;
                }
            }

            // Company news
            List<CompanyNews> news = apiClient.getCompanyNews(ticker, state.getEndDate(), 100);
            long newsBullish = 0, newsBearish = 0, newsNeutral = 0;
            for (CompanyNews n : news) {
                if ("positive".equalsIgnoreCase(n.getSentiment())) newsBullish++;
                else if ("negative".equalsIgnoreCase(n.getSentiment())) newsBearish++;
                else newsNeutral++;
            }

            // Weighted combination
            double insiderWeight = 0.3;
            double newsWeight = 0.7;
            double bullish = insiderBullish * insiderWeight + newsBullish * newsWeight;
            double bearish = insiderBearish * insiderWeight + newsBearish * newsWeight;

            String overall;
            if (bullish > bearish) overall = "bullish";
            else if (bearish > bullish) overall = "bearish";
            else overall = "neutral";

            double total = bullish + bearish;
            double confidence = total > 0 ? Math.round(Math.max(bullish, bearish) / total * 100.0) : 0;

            Map<String, Object> reasoning = new LinkedHashMap<>();
            reasoning.put("insider_trades", Map.of(
                    "bullish", insiderBullish, "bearish", insiderBearish, "total", trades.size()));
            reasoning.put("news_sentiment", Map.of(
                    "bullish", newsBullish, "bearish", newsBearish, "neutral", newsNeutral, "total", news.size()));

            results.put(ticker, AnalystSignal.builder()
                    .signal(overall)
                    .confidence(confidence)
                    .reasoning(reasoning)
                    .build());
        }
        return results;
    }
}
