package com.hedgefund.ai.service.agents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.agent.PortfolioDecision;
import com.hedgefund.ai.service.LlmService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;

@Component
public class PortfolioManagerAgent {

    private static final Logger log = Logger.getLogger(PortfolioManagerAgent.class.getName());
    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    public PortfolioManagerAgent(LlmService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public Map<String, PortfolioDecision> decide(AgentState state,
                                                  Map<String, Map<String, Object>> riskData) {
        Map<String, PortfolioDecision> decisions = new LinkedHashMap<>();
        Map<String, Object> portfolio = state.getPortfolio();
        double cash = ((Number) portfolio.getOrDefault("cash", 0.0)).doubleValue();
        Map<String, Map<String, Object>> positions =
                (Map<String, Map<String, Object>>) portfolio.getOrDefault("positions", Map.of());
        double marginRequirement = ((Number) portfolio.getOrDefault("margin_requirement", 0.5)).doubleValue();

        // Build signals and allowed-actions per ticker
        Map<String, Object> signalsByTicker = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> allowedByTicker = new LinkedHashMap<>();
        List<String> tickersForLlm = new ArrayList<>();

        for (String ticker : state.getTickers()) {
            // Compress analyst signals
            Map<String, Object> compact = new LinkedHashMap<>();
            for (var entry : state.getAnalystSignals().entrySet()) {
                AnalystSignal sig = entry.getValue().get(ticker);
                if (sig != null) {
                    compact.put(entry.getKey(), Map.of("sig", sig.getSignal(), "conf", sig.getConfidence()));
                }
            }
            signalsByTicker.put(ticker, compact);

            // Compute allowed actions
            Map<String, Integer> allowed = computeAllowedActions(
                    ticker, cash, positions, riskData, marginRequirement);

            if (allowed.keySet().stream().allMatch("hold"::equals)) {
                decisions.put(ticker, PortfolioDecision.builder()
                        .action("hold").quantity(0).confidence(100)
                        .reasoning("No valid trade available").build());
            } else {
                allowedByTicker.put(ticker, allowed);
                tickersForLlm.add(ticker);
            }
        }

        if (tickersForLlm.isEmpty()) return decisions;

        // Ask LLM
        String systemPrompt = """
            You are a portfolio manager.
            Inputs per ticker: analyst signals and allowed actions with max qty (already validated).
            Pick one allowed action per ticker and a quantity <= the max.
            Keep reasoning very concise (max 100 chars). No cash or margin math. Return JSON only.
            """;

        Map<String, Object> compactSignals = new LinkedHashMap<>();
        Map<String, Object> compactAllowed = new LinkedHashMap<>();
        for (String t : tickersForLlm) {
            compactSignals.put(t, signalsByTicker.get(t));
            compactAllowed.put(t, allowedByTicker.get(t));
        }

        String userPrompt = String.format(
                "Signals:\n%s\n\nAllowed:\n%s\n\n" +
                        "Format:\n{\"TICKER\":{\"action\":\"...\",\"quantity\":int,\"confidence\":int,\"reasoning\":\"...\"}}",
                toJson(compactSignals), toJson(compactAllowed));

        try {
            String rawResponse = callLlmRaw(systemPrompt, userPrompt, state.getModelProvider(), state.getModelName());
            if (rawResponse != null) {
                Map<String, PortfolioDecision> parsed = objectMapper.readValue(
                        rawResponse, new TypeReference<Map<String, PortfolioDecision>>() {});
                decisions.putAll(parsed);
            }
        } catch (Exception e) {
            log.warning("Failed to parse portfolio manager LLM response: " + e.getMessage());
        }

        // Fill any missing tickers with hold
        for (String t : tickersForLlm) {
            decisions.putIfAbsent(t, PortfolioDecision.builder()
                    .action("hold").quantity(0).confidence(0)
                    .reasoning("Default: hold").build());
        }

        return decisions;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> computeAllowedActions(
            String ticker, double cash,
            Map<String, Map<String, Object>> positions,
            Map<String, Map<String, Object>> riskData,
            double marginRequirement) {

        Map<String, Integer> actions = new LinkedHashMap<>();
        actions.put("hold", 0);

        Map<String, Object> risk = riskData.getOrDefault(ticker, Map.of());
        double price = ((Number) risk.getOrDefault("current_price", 0.0)).doubleValue();
        double posLimit = ((Number) risk.getOrDefault("remaining_position_limit", 0.0)).doubleValue();
        int maxQty = price > 0 ? (int) (posLimit / price) : 0;

        Map<String, Object> pos = positions.getOrDefault(ticker,
                Map.of("long", 0, "short", 0));
        int longShares = ((Number) pos.getOrDefault("long", 0)).intValue();
        int shortShares = ((Number) pos.getOrDefault("short", 0)).intValue();

        // Sell existing longs
        if (longShares > 0) actions.put("sell", longShares);

        // Buy
        if (cash > 0 && price > 0) {
            int maxBuy = Math.max(0, Math.min(maxQty, (int) (cash / price)));
            if (maxBuy > 0) actions.put("buy", maxBuy);
        }

        // Cover existing shorts
        if (shortShares > 0) actions.put("cover", shortShares);

        // Short
        if (price > 0 && maxQty > 0) {
            int maxShort = maxQty;
            if (marginRequirement > 0) {
                double equity = cash; // simplified
                double availableMargin = Math.max(0, equity / marginRequirement);
                maxShort = Math.max(0, Math.min(maxQty, (int) (availableMargin / price)));
            }
            if (maxShort > 0) actions.put("short", maxShort);
        }

        return actions;
    }

    private String callLlmRaw(String systemPrompt, String userPrompt, String provider, String modelName) {
        // Use LlmService but we need the raw string; wrap in a simple call
        Map<?, ?> result = llmService.call(systemPrompt, userPrompt, Map.class, provider, modelName);
        if (result != null) {
            return toJson(result);
        }
        return null;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
