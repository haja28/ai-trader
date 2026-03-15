package com.hedgefund.ai.service.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.api.FinancialMetrics;
import com.hedgefund.ai.model.api.LineItem;
import com.hedgefund.ai.service.LlmService;
import com.hedgefund.ai.service.api.FinancialDatasetsClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;

@Component
public class WarrenBuffettAgent implements AnalystAgent {

    private static final Logger log = Logger.getLogger(WarrenBuffettAgent.class.getName());
    private final FinancialDatasetsClient apiClient;
    private final LlmService llmService;

    public WarrenBuffettAgent(FinancialDatasetsClient apiClient, LlmService llmService) {
        this.apiClient = apiClient;
        this.llmService = llmService;
    }

    @Override public String getAgentId() { return "warren_buffett"; }
    @Override public String getDisplayName() { return "Warren Buffett"; }
    @Override public String getDescription() { return "The Oracle of Omaha"; }

    public static class BuffettSignal {
        private String signal;
        private int confidence;
        private String reasoning;
        public BuffettSignal() {}
        public String getSignal() { return signal; }
        public void setSignal(String v) { this.signal = v; }
        public int getConfidence() { return confidence; }
        public void setConfidence(int v) { this.confidence = v; }
        public String getReasoning() { return reasoning; }
        public void setReasoning(String v) { this.reasoning = v; }
    }

    @Override
    public Map<String, AnalystSignal> analyze(AgentState state) {
        Map<String, AnalystSignal> results = new LinkedHashMap<>();
        for (String ticker : state.getTickers()) {
            log.info("[" + getAgentId() + "] Analyzing " + ticker);
            List<FinancialMetrics> metrics = apiClient.getFinancialMetrics(ticker, state.getEndDate(), "ttm", 10);
            List<LineItem> lineItems = apiClient.searchLineItems(ticker,
                    List.of("net_income", "revenue", "free_cash_flow", "total_assets", "total_liabilities", "shareholders_equity", "gross_profit"),
                    state.getEndDate(), "ttm", 10);
            Double marketCap = apiClient.getMarketCap(ticker, state.getEndDate());

            int score = 0, maxScore = 10;
            Map<String, Object> analysis = new LinkedHashMap<>();
            if (!metrics.isEmpty()) {
                FinancialMetrics m = metrics.get(0);
                if (m.getReturnOnEquity() != null && m.getReturnOnEquity() > 0.15) score += 2;
                if (m.getDebtToEquity() != null && m.getDebtToEquity() < 0.5) score += 2;
                if (m.getOperatingMargin() != null && m.getOperatingMargin() > 0.15) score += 2;
                if (m.getCurrentRatio() != null && m.getCurrentRatio() > 1.5) score += 1;
                if (m.getGrossMargin() != null && m.getGrossMargin() > 0.40) score += 2;
                if (m.getReturnOnInvestedCapital() != null && m.getReturnOnInvestedCapital() > 0.12) score += 1;
                analysis.put("metrics_score", score);
                analysis.put("max_score", maxScore);
                analysis.put("market_cap", marketCap);
            }
            Double intrinsicValue = estimateIntrinsicValue(lineItems);
            if (intrinsicValue != null && marketCap != null && marketCap > 0) {
                analysis.put("intrinsic_value", intrinsicValue);
                analysis.put("margin_of_safety", (intrinsicValue - marketCap) / marketCap);
            }
            analysis.put("ticker", ticker);
            analysis.put("score", score);

            String systemPrompt = "You are Warren Buffett AI. Make investment decisions using his principles: " +
                    "1. Seek companies with durable competitive advantages. 2. Focus on strong fundamentals. " +
                    "3. Buy at a margin of safety. 4. Think long-term. " +
                    "Respond ONLY with JSON: {\"signal\":\"bullish|bearish|neutral\",\"confidence\":0-100,\"reasoning\":\"...\"}";
            String userPrompt = "Analysis data for " + ticker + ":\n" + toJson(analysis);
            BuffettSignal llmResult = llmService.call(systemPrompt, userPrompt, BuffettSignal.class, state.getModelProvider(), state.getModelName());

            if (llmResult != null) {
                results.put(ticker, AnalystSignal.builder().signal(llmResult.getSignal()).confidence(llmResult.getConfidence()).reasoning(llmResult.getReasoning()).build());
            } else {
                String sig = score >= 7 ? "bullish" : score <= 4 ? "bearish" : "neutral";
                results.put(ticker, AnalystSignal.builder().signal(sig).confidence(score * 10.0).reasoning("LLM unavailable, rule-based fallback. Score: " + score + "/" + maxScore).build());
            }
        }
        return results;
    }

    private Double estimateIntrinsicValue(List<LineItem> lineItems) {
        if (lineItems.isEmpty()) return null;
        Double fcf = lineItems.get(0).getNumericField("free_cash_flow");
        if (fcf == null || fcf <= 0) return null;
        double total = 0, growthRate = 0.05, discountRate = 0.10;
        for (int y = 1; y <= 10; y++) total += fcf * Math.pow(1 + growthRate, y) / Math.pow(1 + discountRate, y);
        double terminalFcf = fcf * Math.pow(1 + growthRate, 10) * 1.03;
        total += (terminalFcf / (discountRate - 0.03)) / Math.pow(1 + discountRate, 10);
        return total;
    }

    private String toJson(Object obj) {
        try { return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(obj); }
        catch (Exception e) { return obj.toString(); }
    }
}
