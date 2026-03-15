package com.hedgefund.ai.service.agents;

import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.api.FinancialMetrics;
import com.hedgefund.ai.service.api.FinancialDataClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;

@Component
public class GrowthAnalystAgent implements AnalystAgent {

    private static final Logger log = Logger.getLogger(GrowthAnalystAgent.class.getName());
    private final FinancialDataClient apiClient;

    public GrowthAnalystAgent(FinancialDataClient apiClient) { this.apiClient = apiClient; }

    @Override public String getAgentId() { return "growth_analyst"; }
    @Override public String getDisplayName() { return "Growth Analyst"; }
    @Override public String getDescription() { return "Growth Specialist"; }

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

            if (m.getRevenueGrowth() != null) {
                String sig = m.getRevenueGrowth() > 0.20 ? "bullish" : m.getRevenueGrowth() < 0 ? "bearish" : "neutral";
                signals.add(sig);
                reasoning.put("revenue_growth", Map.of("signal", sig, "value", m.getRevenueGrowth()));
            }
            if (m.getEarningsGrowth() != null) {
                String sig = m.getEarningsGrowth() > 0.15 ? "bullish" : m.getEarningsGrowth() < 0 ? "bearish" : "neutral";
                signals.add(sig);
                reasoning.put("earnings_growth", Map.of("signal", sig, "value", m.getEarningsGrowth()));
            }
            if (m.getPegRatio() != null) {
                String sig = m.getPegRatio() < 1.0 ? "bullish" : m.getPegRatio() > 2.0 ? "bearish" : "neutral";
                signals.add(sig);
                reasoning.put("peg_ratio", Map.of("signal", sig, "value", m.getPegRatio()));
            }
            if (m.getFreeCashFlowGrowth() != null) {
                String sig = m.getFreeCashFlowGrowth() > 0.15 ? "bullish" : m.getFreeCashFlowGrowth() < 0 ? "bearish" : "neutral";
                signals.add(sig);
                reasoning.put("fcf_growth", Map.of("signal", sig, "value", m.getFreeCashFlowGrowth()));
            }

            long bullish = signals.stream().filter("bullish"::equals).count();
            long bearish = signals.stream().filter("bearish"::equals).count();
            String overall = bullish > bearish ? "bullish" : bearish > bullish ? "bearish" : "neutral";
            double confidence = !signals.isEmpty() ? Math.round((double) Math.max(bullish, bearish) / signals.size() * 100.0) : 0;
            results.put(ticker, AnalystSignal.builder().signal(overall).confidence(confidence).reasoning(reasoning).build());
        }
        return results;
    }
}
