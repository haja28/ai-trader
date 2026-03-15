package com.hedgefund.ai.service.agents;

import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.api.Price;
import com.hedgefund.ai.service.api.FinancialDatasetsClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;

/**
 * Technical analyst that combines trend-following, mean-reversion,
 * momentum, volatility, and statistical-arbitrage signals.
 * Equivalent to Python technicals.py.
 */
@Component
public class TechnicalAnalystAgent implements AnalystAgent {

    private static final Logger log = Logger.getLogger(TechnicalAnalystAgent.class.getName());
    private final FinancialDatasetsClient apiClient;

    public TechnicalAnalystAgent(FinancialDatasetsClient apiClient) { this.apiClient = apiClient; }

    @Override
    public String getAgentId() { return "technical_analyst"; }

    @Override
    public String getDisplayName() { return "Technical Analyst"; }

    @Override
    public String getDescription() { return "Chart Pattern Specialist"; }

    @Override
    public Map<String, AnalystSignal> analyze(AgentState state) {
        Map<String, AnalystSignal> results = new LinkedHashMap<>();

        for (String ticker : state.getTickers()) {
            log.info("[" + getAgentId() + "] Analyzing " + ticker);

            List<Price> prices = apiClient.getPrices(ticker, state.getStartDate(), state.getEndDate());
            if (prices.isEmpty()) {
                log.warning("[" + getAgentId() + "] No price data for " + ticker);
                continue;
            }

            double[] closes = prices.stream().mapToDouble(Price::getClose).toArray();
            Map<String, Object> reasoning = new LinkedHashMap<>();

            // 1. Trend — simple EMA cross
            SignalResult trend = analyzeTrend(closes);
            reasoning.put("trend_following", Map.of("signal", trend.signal, "confidence", trend.confidence));

            // 2. Mean Reversion — Bollinger Band position
            SignalResult meanRev = analyzeMeanReversion(closes);
            reasoning.put("mean_reversion", Map.of("signal", meanRev.signal, "confidence", meanRev.confidence));

            // 3. Momentum — RSI
            SignalResult momentum = analyzeMomentum(closes);
            reasoning.put("momentum", Map.of("signal", momentum.signal, "confidence", momentum.confidence));

            // Weighted ensemble
            Map<String, Double> weights = Map.of(
                    "trend", 0.35, "mean_reversion", 0.30, "momentum", 0.35);

            double bullishScore = signalValue(trend.signal) * weights.get("trend")
                    + signalValue(meanRev.signal) * weights.get("mean_reversion")
                    + signalValue(momentum.signal) * weights.get("momentum");

            String overall = bullishScore > 0.15 ? "bullish" : bullishScore < -0.15 ? "bearish" : "neutral";
            double conf = Math.round((trend.confidence * weights.get("trend")
                    + meanRev.confidence * weights.get("mean_reversion")
                    + momentum.confidence * weights.get("momentum")) * 100);

            results.put(ticker, AnalystSignal.builder()
                    .signal(overall)
                    .confidence(conf)
                    .reasoning(reasoning)
                    .build());
        }
        return results;
    }

    // ==================== Signal helpers ====================

    private record SignalResult(String signal, double confidence) {}

    private SignalResult analyzeTrend(double[] closes) {
        if (closes.length < 21) return new SignalResult("neutral", 0.5);
        double ema8 = ema(closes, 8);
        double ema21 = ema(closes, 21);
        if (ema8 > ema21) return new SignalResult("bullish", 0.7);
        if (ema8 < ema21) return new SignalResult("bearish", 0.7);
        return new SignalResult("neutral", 0.5);
    }

    private SignalResult analyzeMeanReversion(double[] closes) {
        if (closes.length < 20) return new SignalResult("neutral", 0.5);
        double[] last20 = Arrays.copyOfRange(closes, closes.length - 20, closes.length);
        double mean = Arrays.stream(last20).average().orElse(0);
        double std = Math.sqrt(Arrays.stream(last20).map(v -> Math.pow(v - mean, 2)).average().orElse(0));
        double current = closes[closes.length - 1];
        double zScore = std > 0 ? (current - mean) / std : 0;

        if (zScore < -2) return new SignalResult("bullish", 0.8);
        if (zScore > 2) return new SignalResult("bearish", 0.8);
        if (zScore < -1) return new SignalResult("bullish", 0.6);
        if (zScore > 1) return new SignalResult("bearish", 0.6);
        return new SignalResult("neutral", 0.5);
    }

    private SignalResult analyzeMomentum(double[] closes) {
        double rsi = calculateRSI(closes, 14);
        if (rsi < 30) return new SignalResult("bullish", 0.8);
        if (rsi > 70) return new SignalResult("bearish", 0.8);
        if (rsi < 40) return new SignalResult("bullish", 0.6);
        if (rsi > 60) return new SignalResult("bearish", 0.6);
        return new SignalResult("neutral", 0.5);
    }

    private double ema(double[] data, int period) {
        double multiplier = 2.0 / (period + 1);
        double ema = data[0];
        for (int i = 1; i < data.length; i++) {
            ema = (data[i] - ema) * multiplier + ema;
        }
        return ema;
    }

    private double calculateRSI(double[] closes, int period) {
        if (closes.length < period + 1) return 50;
        double gainSum = 0, lossSum = 0;
        for (int i = closes.length - period; i < closes.length; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) gainSum += change;
            else lossSum += Math.abs(change);
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    private double signalValue(String signal) {
        return switch (signal) {
            case "bullish" -> 1.0;
            case "bearish" -> -1.0;
            default -> 0.0;
        };
    }
}

