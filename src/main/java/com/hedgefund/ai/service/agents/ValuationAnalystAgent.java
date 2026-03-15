package com.hedgefund.ai.service.agents;

import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.api.FinancialMetrics;
import com.hedgefund.ai.model.api.LineItem;
import com.hedgefund.ai.service.api.FinancialDataClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;

@Component
public class ValuationAnalystAgent implements AnalystAgent {

    private static final Logger log = Logger.getLogger(ValuationAnalystAgent.class.getName());
    private final FinancialDataClient apiClient;

    public ValuationAnalystAgent(FinancialDataClient apiClient) { this.apiClient = apiClient; }

    @Override public String getAgentId() { return "valuation_analyst"; }
    @Override public String getDisplayName() { return "Valuation Analyst"; }
    @Override public String getDescription() { return "Company Valuation Specialist"; }

    @Override
    public Map<String, AnalystSignal> analyze(AgentState state) {
        Map<String, AnalystSignal> results = new LinkedHashMap<>();
        for (String ticker : state.getTickers()) {
            log.info("[" + getAgentId() + "] Analyzing " + ticker);
            List<FinancialMetrics> metricsList = apiClient.getFinancialMetrics(ticker, state.getEndDate(), "ttm", 8);
            if (metricsList.isEmpty()) { log.warning("[" + getAgentId() + "] No metrics for " + ticker); continue; }

            List<LineItem> lineItems = apiClient.searchLineItems(ticker,
                    List.of("free_cash_flow", "net_income", "revenue", "operating_income", "total_debt", "cash_and_equivalents"),
                    state.getEndDate(), "ttm", 10);
            Double marketCap = apiClient.getMarketCap(ticker, state.getEndDate());
            FinancialMetrics m = metricsList.get(0);

            List<String> signals = new ArrayList<>();
            Map<String, Object> reasoning = new LinkedHashMap<>();

            // 1. DCF
            Double dcfValue = computeDCF(lineItems);
            if (dcfValue != null && marketCap != null && marketCap > 0) {
                double gap = (dcfValue - marketCap) / marketCap;
                String sig = gap > 0.15 ? "bullish" : gap < -0.15 ? "bearish" : "neutral";
                signals.add(sig);
                reasoning.put("dcf", Map.of("signal", sig, "intrinsic_value", dcfValue, "gap_pct", gap));
            }
            // 2. P/E
            if (m.getPriceToEarningsRatio() != null) {
                double pe = m.getPriceToEarningsRatio();
                String sig = pe < 15 ? "bullish" : pe > 30 ? "bearish" : "neutral";
                signals.add(sig);
                reasoning.put("pe_ratio", Map.of("signal", sig, "value", pe));
            }
            // 3. P/B
            if (m.getPriceToBookRatio() != null) {
                double pb = m.getPriceToBookRatio();
                String sig = pb < 1.5 ? "bullish" : pb > 5 ? "bearish" : "neutral";
                signals.add(sig);
                reasoning.put("pb_ratio", Map.of("signal", sig, "value", pb));
            }
            // 4. FCF Yield
            if (m.getFreeCashFlowYield() != null) {
                double y = m.getFreeCashFlowYield();
                String sig = y > 0.08 ? "bullish" : y < 0.02 ? "bearish" : "neutral";
                signals.add(sig);
                reasoning.put("fcf_yield", Map.of("signal", sig, "value", y));
            }

            long bullish = signals.stream().filter("bullish"::equals).count();
            long bearish = signals.stream().filter("bearish"::equals).count();
            String overall = bullish > bearish ? "bullish" : bearish > bullish ? "bearish" : "neutral";
            double confidence = !signals.isEmpty() ? Math.round((double) Math.max(bullish, bearish) / signals.size() * 100.0) : 0;
            results.put(ticker, AnalystSignal.builder().signal(overall).confidence(confidence).reasoning(reasoning).build());
        }
        return results;
    }

    private Double computeDCF(List<LineItem> lineItems) {
        if (lineItems.isEmpty()) return null;
        Double fcf = lineItems.get(0).getNumericField("free_cash_flow");
        if (fcf == null || fcf <= 0) return null;
        double growthRate = 0.05, discountRate = 0.10, terminalGrowth = 0.03;
        double total = 0;
        for (int y = 1; y <= 10; y++) total += fcf * Math.pow(1 + growthRate, y) / Math.pow(1 + discountRate, y);
        double terminalFcf = fcf * Math.pow(1 + growthRate, 10) * (1 + terminalGrowth);
        total += (terminalFcf / (discountRate - terminalGrowth)) / Math.pow(1 + discountRate, 10);
        return total;
    }
}
