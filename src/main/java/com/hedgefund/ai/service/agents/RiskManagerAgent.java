package com.hedgefund.ai.service.agents;

import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.api.Price;
import com.hedgefund.ai.service.api.FinancialDataClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;

/**
 * Volatility-adjusted risk management that calculates position limits.
 * Equivalent to Python risk_manager.py.
 */
@Component
public class RiskManagerAgent {

    private static final Logger log = Logger.getLogger(RiskManagerAgent.class.getName());
    private final FinancialDataClient apiClient;

    public RiskManagerAgent(FinancialDataClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * Calculate position limits and current prices for each ticker.
     *
     * @return ticker -> risk data map containing remaining_position_limit and current_price
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> analyze(AgentState state) {
        Map<String, Map<String, Object>> riskAnalysis = new LinkedHashMap<>();
        Map<String, Double> currentPrices = new LinkedHashMap<>();
        Map<String, double[]> returnsByTicker = new LinkedHashMap<>();

        Map<String, Object> portfolio = state.getPortfolio();
        double cash = ((Number) portfolio.getOrDefault("cash", 0.0)).doubleValue();

        // Calculate portfolio value
        double totalPortfolioValue = cash;
        Map<String, Map<String, Object>> positions =
                (Map<String, Map<String, Object>>) portfolio.getOrDefault("positions", Map.of());

        // Fetch prices and compute volatility for all tickers
        for (String ticker : state.getTickers()) {
            log.info("[risk_manager] Fetching prices for " + ticker);
            List<Price> prices = apiClient.getPrices(ticker, state.getStartDate(), state.getEndDate());

            if (prices.size() < 2) {
                log.warning("[risk_manager] Insufficient price data for " + ticker);
                continue;
            }

            double currentPrice = prices.get(prices.size() - 1).getClose();
            currentPrices.put(ticker, currentPrice);

            // Daily returns
            double[] closes = prices.stream().mapToDouble(Price::getClose).toArray();
            double[] returns = new double[closes.length - 1];
            for (int i = 0; i < returns.length; i++) {
                returns[i] = (closes[i + 1] - closes[i]) / closes[i];
            }
            returnsByTicker.put(ticker, returns);

            // Add position value
            if (positions.containsKey(ticker)) {
                Map<String, Object> pos = positions.get(ticker);
                int longShares = ((Number) pos.getOrDefault("long", 0)).intValue();
                int shortShares = ((Number) pos.getOrDefault("short", 0)).intValue();
                totalPortfolioValue += (longShares - shortShares) * currentPrice;
            }
        }

        state.setCurrentPrices(currentPrices);

        // Calculate risk-adjusted limits
        for (String ticker : state.getTickers()) {
            if (!currentPrices.containsKey(ticker)) {
                riskAnalysis.put(ticker, Map.of(
                        "remaining_position_limit", 0.0,
                        "current_price", 0.0,
                        "reasoning", "Missing price data"));
                continue;
            }

            double currentPrice = currentPrices.get(ticker);
            double[] returns = returnsByTicker.getOrDefault(ticker, new double[0]);

            // Annualized volatility
            double dailyVol = returns.length > 1 ? stdDev(returns) : 0.05;
            double annualizedVol = dailyVol * Math.sqrt(252);

            // Volatility-adjusted position limit
            double limitPct;
            if (annualizedVol < 0.15) limitPct = 0.25;      // Low vol
            else if (annualizedVol < 0.30) limitPct = 0.20;  // Medium vol
            else if (annualizedVol < 0.50) limitPct = 0.15;  // High vol
            else limitPct = 0.10;                              // Very high vol

            double positionLimit = totalPortfolioValue * limitPct;

            // Current exposure
            double currentExposure = 0;
            if (positions.containsKey(ticker)) {
                Map<String, Object> pos = positions.get(ticker);
                int longShares = ((Number) pos.getOrDefault("long", 0)).intValue();
                int shortShares = ((Number) pos.getOrDefault("short", 0)).intValue();
                currentExposure = Math.abs(longShares - shortShares) * currentPrice;
            }

            double remainingLimit = Math.min(positionLimit - currentExposure, cash);

            riskAnalysis.put(ticker, Map.of(
                    "remaining_position_limit", remainingLimit,
                    "current_price", currentPrice,
                    "reasoning", Map.of(
                            "portfolio_value", totalPortfolioValue,
                            "annualized_volatility", annualizedVol,
                            "position_limit_pct", limitPct,
                            "position_limit", positionLimit,
                            "current_exposure", currentExposure,
                            "remaining_limit", remainingLimit
                    )));
        }

        return riskAnalysis;
    }

    private double stdDev(double[] data) {
        double mean = Arrays.stream(data).average().orElse(0);
        double variance = Arrays.stream(data).map(v -> Math.pow(v - mean, 2)).average().orElse(0);
        return Math.sqrt(variance);
    }
}
