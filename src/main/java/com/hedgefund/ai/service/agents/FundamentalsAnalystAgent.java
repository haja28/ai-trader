package com.hedgefund.ai.service.agents;

import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.api.FinancialMetrics;
import com.hedgefund.ai.service.api.FinancialDataClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;

@Component
public class FundamentalsAnalystAgent implements AnalystAgent {

    private static final Logger log = Logger.getLogger(FundamentalsAnalystAgent.class.getName());
    private final FinancialDataClient apiClient;

    public FundamentalsAnalystAgent(FinancialDataClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override public String getAgentId() { return "fundamentals_analyst"; }
    @Override public String getDisplayName() { return "Fundamentals Analyst"; }
    @Override public String getDescription() { return "Financial Statement Specialist"; }

    @Override
    public Map<String, AnalystSignal> analyze(AgentState state) {
        Map<String, AnalystSignal> results = new LinkedHashMap<>();
        for (String ticker : state.getTickers()) {
            log.info("[" + getAgentId() + "] Analyzing " + ticker);
            List<FinancialMetrics> metricsList = apiClient.getFinancialMetrics(ticker, state.getEndDate(), "ttm", 10);
            if (metricsList.isEmpty()) { log.warning("[" + getAgentId() + "] No metrics for " + ticker); continue; }

            FinancialMetrics m = metricsList.get(0);
            List<String> signals = new ArrayList<>();
            Map<String, Object> reasoning = new LinkedHashMap<>();

            // 1. Profitability
            int profitScore = 0;
            if (m.getReturnOnEquity() != null && m.getReturnOnEquity() > 0.15) profitScore++;
            if (m.getNetMargin() != null && m.getNetMargin() > 0.20) profitScore++;
            if (m.getOperatingMargin() != null && m.getOperatingMargin() > 0.15) profitScore++;
            String profitSignal = profitScore >= 2 ? "bullish" : profitScore == 0 ? "bearish" : "neutral";
            signals.add(profitSignal);
            reasoning.put("profitability_signal", Map.of("signal", profitSignal,
                    "details", fmt("ROE", m.getReturnOnEquity(), true) + ", " + fmt("Net Margin", m.getNetMargin(), true) + ", " + fmt("Op Margin", m.getOperatingMargin(), true)));

            // 2. Growth
            int growthScore = 0;
            if (m.getRevenueGrowth() != null && m.getRevenueGrowth() > 0.10) growthScore++;
            if (m.getEarningsGrowth() != null && m.getEarningsGrowth() > 0.10) growthScore++;
            if (m.getBookValueGrowth() != null && m.getBookValueGrowth() > 0.10) growthScore++;
            String growthSignal = growthScore >= 2 ? "bullish" : growthScore == 0 ? "bearish" : "neutral";
            signals.add(growthSignal);
            reasoning.put("growth_signal", Map.of("signal", growthSignal,
                    "details", fmt("Revenue Growth", m.getRevenueGrowth(), true) + ", " + fmt("Earnings Growth", m.getEarningsGrowth(), true)));

            // 3. Financial Health
            int healthScore = 0;
            if (m.getCurrentRatio() != null && m.getCurrentRatio() > 1.5) healthScore++;
            if (m.getDebtToEquity() != null && m.getDebtToEquity() < 0.5) healthScore++;
            if (m.getFreeCashFlowPerShare() != null && m.getEarningsPerShare() != null
                    && m.getFreeCashFlowPerShare() > m.getEarningsPerShare() * 0.8) healthScore++;
            String healthSignal = healthScore >= 2 ? "bullish" : healthScore == 0 ? "bearish" : "neutral";
            signals.add(healthSignal);
            reasoning.put("financial_health_signal", Map.of("signal", healthSignal,
                    "details", fmt("Current Ratio", m.getCurrentRatio(), false) + ", " + fmt("D/E", m.getDebtToEquity(), false)));

            // 4. Price Ratios
            int priceScore = 0;
            if (m.getPriceToEarningsRatio() != null && m.getPriceToEarningsRatio() > 25) priceScore++;
            if (m.getPriceToBookRatio() != null && m.getPriceToBookRatio() > 3) priceScore++;
            if (m.getPriceToSalesRatio() != null && m.getPriceToSalesRatio() > 5) priceScore++;
            String priceSignal = priceScore >= 2 ? "bearish" : priceScore == 0 ? "bullish" : "neutral";
            signals.add(priceSignal);
            reasoning.put("price_ratios_signal", Map.of("signal", priceSignal,
                    "details", fmt("P/E", m.getPriceToEarningsRatio(), false) + ", " + fmt("P/B", m.getPriceToBookRatio(), false) + ", " + fmt("P/S", m.getPriceToSalesRatio(), false)));

            // Overall
            long bullish = signals.stream().filter("bullish"::equals).count();
            long bearish = signals.stream().filter("bearish"::equals).count();
            String overall = bullish > bearish ? "bullish" : bearish > bullish ? "bearish" : "neutral";
            double confidence = Math.round((double) Math.max(bullish, bearish) / signals.size() * 100.0);

            results.put(ticker, AnalystSignal.builder().signal(overall).confidence(confidence).reasoning(reasoning).build());
        }
        return results;
    }

    private String fmt(String name, Double value, boolean asPercent) {
        if (value == null) return name + ": N/A";
        return asPercent ? String.format("%s: %.2f%%", name, value * 100) : String.format("%s: %.2f", name, value);
    }
}
