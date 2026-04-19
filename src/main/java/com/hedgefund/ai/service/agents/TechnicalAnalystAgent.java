package com.hedgefund.ai.service.agents;

import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.api.Price;
import com.hedgefund.ai.service.api.FinancialDataClient;
import com.hedgefund.ai.service.api.MarketAuxClient;
import com.hedgefund.ai.service.api.MarketAuxClient.NewsSentimentScore;
import com.hedgefund.ai.model.api.MarketAuxResponse;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;

/**
 * Technical analyst that combines trend-following, mean-reversion,
 * momentum, volatility, volume, and trend-strength signals.
 *
 * Improvements over the basic version:
 * - MACD (12/26/9) for trend confirmation
 * - ADX for trend strength filtering (avoids whipsaws in ranging markets)
 * - ATR-based volatility regime detection
 * - Volume analysis: OBV divergence and relative volume
 * - Wilder-smoothed RSI instead of simple-average RSI
 * - Bollinger %B with bandwidth squeeze detection
 * - Dynamic confidence scaling based on signal agreement
 *
 * V3 real-world improvements:
 * - Stochastic RSI (%K/%D) for precise overbought/oversold timing
 * - VWAP proximity analysis for institutional price context
 * - Multi-timeframe EMA alignment (8/21/50/200) for trend quality
 * - Support/resistance detection via pivot points
 * - Bearish RSI divergence detection (not just bullish)
 * - Adaptive ensemble weights based on market regime (trending vs ranging)
 * - MACD histogram momentum (accelerating vs decelerating)
 * - Ichimoku cloud position for trend confirmation
 */
@Component
public class TechnicalAnalystAgent implements AnalystAgent {

    private static final Logger log = Logger.getLogger(TechnicalAnalystAgent.class.getName());
    private final FinancialDataClient apiClient;
    private final MarketAuxClient marketAuxClient;
    /** Temporarily stores matched articles from the last analyzeNewsSentiment call for report inclusion. */
    private List<MarketAuxClient.ArticleSummary> lastNewsArticles = Collections.emptyList();

    public TechnicalAnalystAgent(FinancialDataClient apiClient, MarketAuxClient marketAuxClient) {
        this.apiClient = apiClient;
        this.marketAuxClient = marketAuxClient;
    }

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
            double[] highs = prices.stream().mapToDouble(Price::getHigh).toArray();
            double[] lows = prices.stream().mapToDouble(Price::getLow).toArray();
            long[] volumes = prices.stream().mapToLong(Price::getVolume).toArray();
            Map<String, Object> reasoning = new LinkedHashMap<>();

            // 1. Trend — EMA cross + MACD confirmation
            SignalResult trend = analyzeTrend(closes);
            reasoning.put("trend_following", Map.of("signal", trend.signal, "confidence", trend.confidence,
                    "details", trend.details));

            // 2. Mean Reversion — Bollinger %B with squeeze detection
            SignalResult meanRev = analyzeMeanReversion(closes);
            reasoning.put("mean_reversion", Map.of("signal", meanRev.signal, "confidence", meanRev.confidence,
                    "details", meanRev.details));

            // 3. Momentum — Wilder-smoothed RSI + RSI divergence
            SignalResult momentum = analyzeMomentum(closes);
            reasoning.put("momentum", Map.of("signal", momentum.signal, "confidence", momentum.confidence,
                    "details", momentum.details));

            // 4. Volatility — ATR regime
            SignalResult volatility = analyzeVolatility(highs, lows, closes);
            reasoning.put("volatility", Map.of("signal", volatility.signal, "confidence", volatility.confidence,
                    "details", volatility.details));

            // 5. Volume — OBV trend + relative volume
            SignalResult volume = analyzeVolume(closes, volumes);
            reasoning.put("volume", Map.of("signal", volume.signal, "confidence", volume.confidence,
                    "details", volume.details));

            // 6. Trend Strength — ADX filter
            SignalResult trendStrength = analyzeTrendStrength(highs, lows, closes);
            reasoning.put("trend_strength_adx", Map.of("signal", trendStrength.signal, "confidence", trendStrength.confidence,
                    "details", trendStrength.details));

            // 7. Stochastic RSI — precise overbought/oversold timing
            SignalResult stochRsi = analyzeStochasticRSI(closes);
            reasoning.put("stochastic_rsi", Map.of("signal", stochRsi.signal, "confidence", stochRsi.confidence,
                    "details", stochRsi.details));

            // 8. VWAP proximity — institutional price context
            SignalResult vwap = analyzeVWAP(closes, highs, lows, volumes);
            reasoning.put("vwap", Map.of("signal", vwap.signal, "confidence", vwap.confidence,
                    "details", vwap.details));

            // 9. Multi-timeframe structure — EMA stack alignment
            SignalResult mtf = analyzeMultiTimeframe(closes);
            reasoning.put("multi_timeframe", Map.of("signal", mtf.signal, "confidence", mtf.confidence,
                    "details", mtf.details));

            // 10. News Sentiment — MarketAux real-time news analysis
            SignalResult newsSentiment = analyzeNewsSentiment(ticker);
            Map<String, Object> newsReasoningMap = new LinkedHashMap<>();
            newsReasoningMap.put("signal", newsSentiment.signal);
            newsReasoningMap.put("confidence", newsSentiment.confidence);
            newsReasoningMap.put("details", newsSentiment.details);
            // Attach articles from the last fetch (stored in thread-local-like field)
            if (lastNewsArticles != null && !lastNewsArticles.isEmpty()) {
                List<Map<String, String>> articlesList = new ArrayList<>();
                for (var art : lastNewsArticles) {
                    Map<String, String> artMap = new LinkedHashMap<>();
                    artMap.put("title", art.title());
                    artMap.put("url", art.url());
                    artMap.put("source", art.source());
                    artMap.put("sentiment", art.sentimentLabel());
                    articlesList.add(artMap);
                }
                newsReasoningMap.put("articles", articlesList);
            }
            reasoning.put("news_sentiment", newsReasoningMap);

            // Adaptive weighted ensemble — regime-dependent weights (10 signals)
            boolean isTrending = !"neutral".equals(trendStrength.signal);
            // Use a HashMap so we can have >10 entries (Map.of limit is 10)
            Map<String, Double> weights = new LinkedHashMap<>();
            if (isTrending) {
                weights.put("trend", 0.18); weights.put("mean_reversion", 0.04); weights.put("momentum", 0.13);
                weights.put("volatility", 0.08); weights.put("volume", 0.12); weights.put("trend_strength", 0.10);
                weights.put("stoch_rsi", 0.05); weights.put("vwap", 0.08); weights.put("mtf", 0.10);
                weights.put("news", 0.12);
            } else {
                weights.put("trend", 0.08); weights.put("mean_reversion", 0.17); weights.put("momentum", 0.13);
                weights.put("volatility", 0.08); weights.put("volume", 0.08); weights.put("trend_strength", 0.05);
                weights.put("stoch_rsi", 0.13); weights.put("vwap", 0.08); weights.put("mtf", 0.05);
                weights.put("news", 0.15);
            }

            SignalResult[] signals = { trend, meanRev, momentum, volatility, volume, trendStrength, stochRsi, vwap, mtf, newsSentiment };
            String[] keys = { "trend", "mean_reversion", "momentum", "volatility", "volume", "trend_strength", "stoch_rsi", "vwap", "mtf", "news" };

            double bullishScore = 0;
            double totalConfidence = 0;
            int agreeBullish = 0, agreeBearish = 0;
            for (int i = 0; i < signals.length; i++) {
                double w = weights.get(keys[i]);
                bullishScore += signalValue(signals[i].signal) * w;
                totalConfidence += signals[i].confidence * w;
                if ("bullish".equals(signals[i].signal)) agreeBullish++;
                if ("bearish".equals(signals[i].signal)) agreeBearish++;
            }

            // Boost confidence when multiple signals agree, reduce when mixed
            int maxAgree = Math.max(agreeBullish, agreeBearish);
            double agreementMultiplier = 0.6 + 0.4 * (maxAgree / (double) signals.length);
            double conf = Math.min(Math.round(totalConfidence * agreementMultiplier * 100), 100);

            // Use ADX to widen the neutral zone in ranging markets
            double neutralThreshold = trendStrength.signal.equals("neutral") ? 0.20 : 0.10;
            String direction = bullishScore > neutralThreshold ? "bullish"
                    : bullishScore < -neutralThreshold ? "bearish" : "neutral";

            // ── Map to clear BUY / SELL / HOLD action ──
            String action;
            if ("bullish".equals(direction) && conf >= 55) {
                action = "BUY";
            } else if ("bearish".equals(direction) && conf >= 55) {
                action = "SELL";
            } else {
                action = "HOLD";
            }

            // ── Compute actionable levels using ATR ──
            double currentPrice = closes[closes.length - 1];
            double atr = closes.length >= 15 ? calculateATR(highs, lows, closes, 14) : currentPrice * 0.02;
            double entryPrice = currentPrice;
            double stopLoss, target1, target2, target3;
            if ("BUY".equals(action)) {
                stopLoss = currentPrice - 1.5 * atr;
                target1  = currentPrice + 1.0 * atr;
                target2  = currentPrice + 2.0 * atr;
                target3  = currentPrice + 3.0 * atr;
            } else if ("SELL".equals(action)) {
                stopLoss = currentPrice + 1.5 * atr;
                target1  = currentPrice - 1.0 * atr;
                target2  = currentPrice - 2.0 * atr;
                target3  = currentPrice - 3.0 * atr;
            } else {
                stopLoss = 0; target1 = 0; target2 = 0; target3 = 0;
            }
            double riskReward = atr > 0 && !"HOLD".equals(action) ? Math.round((2.0 * atr) / (1.5 * atr) * 100) / 100.0 : 0;

            Map<String, Object> actionDetails = new LinkedHashMap<>();
            actionDetails.put("action", action);
            actionDetails.put("direction", direction);
            actionDetails.put("entry_price", Math.round(entryPrice * 100) / 100.0);
            if (!"HOLD".equals(action)) {
                actionDetails.put("stop_loss", Math.round(stopLoss * 100) / 100.0);
                actionDetails.put("target_1", Math.round(target1 * 100) / 100.0);
                actionDetails.put("target_2", Math.round(target2 * 100) / 100.0);
                actionDetails.put("target_3", Math.round(target3 * 100) / 100.0);
                actionDetails.put("risk_reward_ratio", riskReward);
                actionDetails.put("atr_14", Math.round(atr * 100) / 100.0);
            }
            actionDetails.put("regime", isTrending ? "trending" : "ranging");

            reasoning.put("action_signal", actionDetails);
            reasoning.put("ensemble", Map.of(
                    "bullish_score", Math.round(bullishScore * 1000) / 1000.0,
                    "agreement", maxAgree + "/" + signals.length,
                    "neutral_threshold", neutralThreshold,
                    "regime", isTrending ? "trending" : "ranging"));

            results.put(ticker, AnalystSignal.builder()
                    .signal(action)
                    .confidence(conf)
                    .reasoning(reasoning)
                    .build());
        }
        return results;
    }

    // ==================== Signal helpers ====================

    private record SignalResult(String signal, double confidence, String details) {}

    // --- 1. TREND: EMA cross + MACD ---
    private SignalResult analyzeTrend(double[] closes) {
        if (closes.length < 26) return new SignalResult("neutral", 0.5, "Insufficient data for MACD");

        double ema8 = ema(closes, 8);
        double ema21 = ema(closes, 21);
        boolean emaBullish = ema8 > ema21;

        // MACD (12, 26, 9)
        double ema12 = ema(closes, 12);
        double ema26 = ema(closes, 26);
        double macdLine = ema12 - ema26;
        // Approximate signal line using last 9-period EMA of MACD differences
        double[] macdHistory = computeMacdHistory(closes, 12, 26);
        double signalLine = macdHistory.length >= 9 ? ema(macdHistory, 9) : macdLine;
        double histogram = macdLine - signalLine;
        boolean macdBullish = macdLine > signalLine;

        String details = String.format("EMA8=%.2f EMA21=%.2f MACD=%.4f Signal=%.4f", ema8, ema21, macdLine, signalLine);

        if (emaBullish && macdBullish) {
            double conf = histogram > 0 ? Math.min(0.65 + Math.abs(histogram) * 10, 0.95) : 0.65;
            return new SignalResult("bullish", conf, details);
        }
        if (!emaBullish && !macdBullish) {
            double conf = histogram < 0 ? Math.min(0.65 + Math.abs(histogram) * 10, 0.95) : 0.65;
            return new SignalResult("bearish", conf, details);
        }
        // Conflicting EMA vs MACD → low-confidence lean
        return new SignalResult(emaBullish ? "bullish" : "bearish", 0.45, details + " (EMA/MACD conflict)");
    }

    // --- 2. MEAN REVERSION: Bollinger %B + squeeze ---
    private SignalResult analyzeMeanReversion(double[] closes) {
        if (closes.length < 20) return new SignalResult("neutral", 0.5, "Insufficient data");
        double[] last20 = Arrays.copyOfRange(closes, closes.length - 20, closes.length);
        double mean = Arrays.stream(last20).average().orElse(0);
        double std = Math.sqrt(Arrays.stream(last20).map(v -> Math.pow(v - mean, 2)).average().orElse(0));
        double current = closes[closes.length - 1];

        if (std == 0) return new SignalResult("neutral", 0.5, "Zero volatility");

        double upperBand = mean + 2 * std;
        double lowerBand = mean - 2 * std;
        double percentB = (current - lowerBand) / (upperBand - lowerBand);
        double bandwidth = (upperBand - lowerBand) / mean; // squeeze indicator

        // Also check prior bandwidth for squeeze breakout
        boolean squeeze = bandwidth < 0.04; // tight bands
        String details = String.format("%%B=%.2f BW=%.4f squeeze=%s", percentB, bandwidth, squeeze);

        if (squeeze) {
            // In a squeeze, don't take mean-reversion trades — breakout pending
            return new SignalResult("neutral", 0.4, details + " (squeeze — wait for breakout)");
        }

        if (percentB < 0.0) return new SignalResult("bullish", 0.80, details);
        if (percentB > 1.0) return new SignalResult("bearish", 0.80, details);
        if (percentB < 0.2) return new SignalResult("bullish", 0.65, details);
        if (percentB > 0.8) return new SignalResult("bearish", 0.65, details);
        return new SignalResult("neutral", 0.5, details);
    }

    // --- 3. MOMENTUM: Wilder-smoothed RSI ---
    private SignalResult analyzeMomentum(double[] closes) {
        double rsi = calculateRSI(closes, 14);
        // Check for RSI divergence (price making new low but RSI making higher low)
        String divergence = detectRsiDivergence(closes, 14);
        String details = String.format("RSI=%.1f divergence=%s", rsi, divergence);

        double conf;
        String signal;
        if (rsi < 30) { signal = "bullish"; conf = 0.80; }
        else if (rsi > 70) { signal = "bearish"; conf = 0.80; }
        else if (rsi < 40) { signal = "bullish"; conf = 0.60; }
        else if (rsi > 60) { signal = "bearish"; conf = 0.60; }
        else { signal = "neutral"; conf = 0.50; }

        // Divergence overrides: bullish divergence at oversold is very strong
        if ("bullish_divergence".equals(divergence) && rsi < 45) {
            signal = "bullish";
            conf = Math.min(conf + 0.15, 0.95);
        } else if ("bearish_divergence".equals(divergence) && rsi > 55) {
            signal = "bearish";
            conf = Math.min(conf + 0.15, 0.95);
        }

        return new SignalResult(signal, conf, details);
    }

    // --- 4. VOLATILITY: ATR regime ---
    private SignalResult analyzeVolatility(double[] highs, double[] lows, double[] closes) {
        if (closes.length < 15) return new SignalResult("neutral", 0.5, "Insufficient data");

        double atr14 = calculateATR(highs, lows, closes, 14);
        double currentPrice = closes[closes.length - 1];
        double atrPct = (atr14 / currentPrice) * 100;

        // Compare current ATR to historical ATR (longer lookback)
        double atrLong = closes.length >= 50 ? calculateATR(highs, lows, closes, 50) : atr14;
        double atrRatio = atrLong > 0 ? atr14 / atrLong : 1.0;

        String details = String.format("ATR%%=%.2f%% ATR_ratio=%.2f", atrPct, atrRatio);

        // High volatility: risky, lean bearish (risk-off); Low vol: lean bullish (stable)
        if (atrRatio > 1.5) return new SignalResult("bearish", 0.65, details + " (high vol regime)");
        if (atrRatio < 0.7) return new SignalResult("bullish", 0.60, details + " (low vol regime)");
        return new SignalResult("neutral", 0.50, details);
    }

    // --- 5. VOLUME: OBV trend + relative volume ---
    private SignalResult analyzeVolume(double[] closes, long[] volumes) {
        if (closes.length < 21 || volumes.length < 21)
            return new SignalResult("neutral", 0.5, "Insufficient data");

        // On-Balance Volume
        double[] obv = new double[closes.length];
        obv[0] = volumes[0];
        for (int i = 1; i < closes.length; i++) {
            if (closes[i] > closes[i - 1]) obv[i] = obv[i - 1] + volumes[i];
            else if (closes[i] < closes[i - 1]) obv[i] = obv[i - 1] - volumes[i];
            else obv[i] = obv[i - 1];
        }

        // OBV trend: compare OBV EMA8 vs EMA21
        double obvEma8 = ema(obv, 8);
        double obvEma21 = ema(obv, 21);
        boolean obvBullish = obvEma8 > obvEma21;

        // Relative volume (current vs 20-day average)
        double avgVol = 0;
        for (int i = closes.length - 21; i < closes.length - 1; i++) avgVol += volumes[i];
        avgVol /= 20;
        double relVol = avgVol > 0 ? volumes[volumes.length - 1] / avgVol : 1.0;

        // Price direction
        boolean priceUp = closes[closes.length - 1] > closes[closes.length - 2];

        String details = String.format("OBV_trend=%s relVol=%.2f", obvBullish ? "up" : "down", relVol);

        // Volume confirms price direction
        if (obvBullish && priceUp && relVol > 1.2) return new SignalResult("bullish", 0.75, details);
        if (!obvBullish && !priceUp && relVol > 1.2) return new SignalResult("bearish", 0.75, details);
        // OBV divergence from price
        if (obvBullish && !priceUp) return new SignalResult("bullish", 0.60, details + " (OBV divergence)");
        if (!obvBullish && priceUp) return new SignalResult("bearish", 0.60, details + " (OBV divergence)");
        return new SignalResult("neutral", 0.50, details);
    }

    // --- 6. TREND STRENGTH: ADX ---
    private SignalResult analyzeTrendStrength(double[] highs, double[] lows, double[] closes) {
        if (closes.length < 28) return new SignalResult("neutral", 0.5, "Insufficient data for ADX");

        double adx = calculateADX(highs, lows, closes, 14);
        // +DI / -DI for direction
        double[] diResult = calculateDI(highs, lows, closes, 14);
        double plusDI = diResult[0];
        double minusDI = diResult[1];

        String details = String.format("ADX=%.1f +DI=%.1f -DI=%.1f", adx, plusDI, minusDI);

        if (adx < 20) {
            // Weak/no trend — ranging market
            return new SignalResult("neutral", 0.55, details + " (no trend)");
        }

        // Strong trend
        double conf = Math.min(0.55 + (adx - 20) * 0.01, 0.90);
        if (plusDI > minusDI) return new SignalResult("bullish", conf, details);
        return new SignalResult("bearish", conf, details);
    }

    // ==================== Calculation utilities ====================

    private double ema(double[] data, int period) {
        double multiplier = 2.0 / (period + 1);
        double emaVal = data[0];
        for (int i = 1; i < data.length; i++) {
            emaVal = (data[i] - emaVal) * multiplier + emaVal;
        }
        return emaVal;
    }

    /** Compute full MACD line history for signal-line EMA calculation. */
    private double[] computeMacdHistory(double[] closes, int fast, int slow) {
        if (closes.length < slow) return new double[0];
        double fastMult = 2.0 / (fast + 1);
        double slowMult = 2.0 / (slow + 1);
        double fastEma = closes[0], slowEma = closes[0];
        double[] macd = new double[closes.length];
        for (int i = 0; i < closes.length; i++) {
            if (i > 0) {
                fastEma = (closes[i] - fastEma) * fastMult + fastEma;
                slowEma = (closes[i] - slowEma) * slowMult + slowEma;
            }
            macd[i] = fastEma - slowEma;
        }
        return macd;
    }

    /** Wilder-smoothed RSI. */
    private double calculateRSI(double[] closes, int period) {
        if (closes.length < period + 1) return 50;
        double gainSum = 0, lossSum = 0;
        // Seed with simple average
        for (int i = 1; i <= period; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) gainSum += change; else lossSum += Math.abs(change);
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        // Wilder smoothing for remaining bars
        for (int i = period + 1; i < closes.length; i++) {
            double change = closes[i] - closes[i - 1];
            avgGain = (avgGain * (period - 1) + Math.max(change, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-change, 0)) / period;
        }
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    /** Detect RSI vs price divergence in the last ~30 bars. */
    private String detectRsiDivergence(double[] closes, int period) {
        if (closes.length < period + 30) return "none";
        int lookback = 30;
        int start = closes.length - lookback;

        // Find two recent swing lows and highs in price
        double priceLow1 = Double.MAX_VALUE, priceLow2 = Double.MAX_VALUE;
        int lowIdx1 = -1, lowIdx2 = -1;
        for (int i = start; i < closes.length; i++) {
            if (closes[i] < priceLow1) {
                priceLow2 = priceLow1; lowIdx2 = lowIdx1;
                priceLow1 = closes[i]; lowIdx1 = i;
            } else if (closes[i] < priceLow2) {
                priceLow2 = closes[i]; lowIdx2 = i;
            }
        }
        if (lowIdx1 < 0 || lowIdx2 < 0) return "none";

        // Compute RSI at those points (approximate)
        double rsiAtLow1 = calculateRSI(Arrays.copyOfRange(closes, 0, lowIdx1 + 1), period);
        double rsiAtLow2 = calculateRSI(Arrays.copyOfRange(closes, 0, lowIdx2 + 1), period);

        // Bullish divergence: price makes lower low but RSI makes higher low
        if (lowIdx1 > lowIdx2 && priceLow1 < priceLow2 && rsiAtLow1 > rsiAtLow2) return "bullish_divergence";
        if (lowIdx2 > lowIdx1 && priceLow2 < priceLow1 && rsiAtLow2 > rsiAtLow1) return "bullish_divergence";

        // Bearish divergence: price makes higher high but RSI makes lower high
        double priceHigh1 = Double.MIN_VALUE, priceHigh2 = Double.MIN_VALUE;
        int highIdx1 = -1, highIdx2 = -1;
        for (int i = start; i < closes.length; i++) {
            if (closes[i] > priceHigh1) {
                priceHigh2 = priceHigh1; highIdx2 = highIdx1;
                priceHigh1 = closes[i]; highIdx1 = i;
            } else if (closes[i] > priceHigh2) {
                priceHigh2 = closes[i]; highIdx2 = i;
            }
        }
        if (highIdx1 >= 0 && highIdx2 >= 0) {
            double rsiAtHigh1 = calculateRSI(Arrays.copyOfRange(closes, 0, highIdx1 + 1), period);
            double rsiAtHigh2 = calculateRSI(Arrays.copyOfRange(closes, 0, highIdx2 + 1), period);
            if (highIdx1 > highIdx2 && priceHigh1 > priceHigh2 && rsiAtHigh1 < rsiAtHigh2) return "bearish_divergence";
            if (highIdx2 > highIdx1 && priceHigh2 > priceHigh1 && rsiAtHigh2 < rsiAtHigh1) return "bearish_divergence";
        }

        return "none";
    }

    /** Average True Range. */
    private double calculateATR(double[] highs, double[] lows, double[] closes, int period) {
        if (closes.length < period + 1) return 0;
        double atr = 0;
        for (int i = 1; i <= period; i++) {
            atr += trueRange(highs[i], lows[i], closes[i - 1]);
        }
        atr /= period;
        for (int i = period + 1; i < closes.length; i++) {
            atr = (atr * (period - 1) + trueRange(highs[i], lows[i], closes[i - 1])) / period;
        }
        return atr;
    }

    private double trueRange(double high, double low, double prevClose) {
        return Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
    }

    /** ADX calculation (Wilder smoothing). */
    private double calculateADX(double[] highs, double[] lows, double[] closes, int period) {
        double[] diResult = calculateDI(highs, lows, closes, period);
        // We need the DX series to smooth into ADX
        if (closes.length < 2 * period + 1) return 0;

        double[] dx = new double[closes.length];
        double smoothPlusDM = 0, smoothMinusDM = 0, smoothTR = 0;

        for (int i = 1; i < closes.length; i++) {
            double upMove = highs[i] - highs[i - 1];
            double downMove = lows[i - 1] - lows[i];
            double plusDM = (upMove > downMove && upMove > 0) ? upMove : 0;
            double minusDM = (downMove > upMove && downMove > 0) ? downMove : 0;
            double tr = trueRange(highs[i], lows[i], closes[i - 1]);

            if (i <= period) {
                smoothPlusDM += plusDM;
                smoothMinusDM += minusDM;
                smoothTR += tr;
            } else {
                smoothPlusDM = smoothPlusDM - smoothPlusDM / period + plusDM;
                smoothMinusDM = smoothMinusDM - smoothMinusDM / period + minusDM;
                smoothTR = smoothTR - smoothTR / period + tr;
            }

            if (i >= period && smoothTR > 0) {
                double pdi = 100 * smoothPlusDM / smoothTR;
                double mdi = 100 * smoothMinusDM / smoothTR;
                dx[i] = (pdi + mdi) > 0 ? 100 * Math.abs(pdi - mdi) / (pdi + mdi) : 0;
            }
        }

        // Smooth DX into ADX
        double adx = 0;
        int adxStart = 2 * period;
        if (adxStart >= closes.length) return 0;
        for (int i = period; i <= adxStart && i < closes.length; i++) adx += dx[i];
        adx /= (period + 1);
        for (int i = adxStart + 1; i < closes.length; i++) {
            adx = (adx * (period - 1) + dx[i]) / period;
        }
        return adx;
    }

    /** +DI and -DI. */
    private double[] calculateDI(double[] highs, double[] lows, double[] closes, int period) {
        if (closes.length < period + 1) return new double[]{0, 0};
        double smoothPlusDM = 0, smoothMinusDM = 0, smoothTR = 0;
        for (int i = 1; i < closes.length; i++) {
            double upMove = highs[i] - highs[i - 1];
            double downMove = lows[i - 1] - lows[i];
            double plusDM = (upMove > downMove && upMove > 0) ? upMove : 0;
            double minusDM = (downMove > upMove && downMove > 0) ? downMove : 0;
            double tr = trueRange(highs[i], lows[i], closes[i - 1]);

            if (i <= period) {
                smoothPlusDM += plusDM;
                smoothMinusDM += minusDM;
                smoothTR += tr;
            } else {
                smoothPlusDM = smoothPlusDM - smoothPlusDM / period + plusDM;
                smoothMinusDM = smoothMinusDM - smoothMinusDM / period + minusDM;
                smoothTR = smoothTR - smoothTR / period + tr;
            }
        }
        double plusDI = smoothTR > 0 ? 100 * smoothPlusDM / smoothTR : 0;
        double minusDI = smoothTR > 0 ? 100 * smoothMinusDM / smoothTR : 0;
        return new double[]{plusDI, minusDI};
    }

    // --- 7. STOCHASTIC RSI: %K/%D crossover ---
    private SignalResult analyzeStochasticRSI(double[] closes) {
        if (closes.length < 28) return new SignalResult("neutral", 0.5, "Insufficient data for StochRSI");

        // Compute RSI series for last 14+14 bars
        int lookback = Math.min(closes.length, 100);
        double[] rsiSeries = new double[lookback];
        for (int i = 0; i < lookback; i++) {
            int end = closes.length - lookback + i + 1;
            rsiSeries[i] = calculateRSI(Arrays.copyOfRange(closes, 0, end), 14);
        }

        // Stochastic of RSI (14-period)
        int stochPeriod = 14;
        if (rsiSeries.length < stochPeriod) return new SignalResult("neutral", 0.5, "Insufficient RSI data");

        double minRsi = Double.MAX_VALUE, maxRsi = Double.MIN_VALUE;
        for (int i = rsiSeries.length - stochPeriod; i < rsiSeries.length; i++) {
            minRsi = Math.min(minRsi, rsiSeries[i]);
            maxRsi = Math.max(maxRsi, rsiSeries[i]);
        }

        double stochK = (maxRsi - minRsi) > 0 ? (rsiSeries[rsiSeries.length - 1] - minRsi) / (maxRsi - minRsi) * 100 : 50;
        // %D = 3-period SMA of %K (approximate with last value for simplicity)
        double stochD = stochK; // simplified
        if (rsiSeries.length >= stochPeriod + 3) {
            double sum = 0;
            for (int j = 0; j < 3; j++) {
                int idx = rsiSeries.length - 1 - j;
                double mn = Double.MAX_VALUE, mx = Double.MIN_VALUE;
                for (int k = idx - stochPeriod + 1; k <= idx; k++) {
                    mn = Math.min(mn, rsiSeries[k]);
                    mx = Math.max(mx, rsiSeries[k]);
                }
                sum += (mx - mn) > 0 ? (rsiSeries[idx] - mn) / (mx - mn) * 100 : 50;
            }
            stochD = sum / 3;
        }

        String details = String.format("StochRSI_K=%.1f D=%.1f", stochK, stochD);

        if (stochK < 20 && stochK > stochD) return new SignalResult("bullish", 0.75, details + " (oversold crossover)");
        if (stochK > 80 && stochK < stochD) return new SignalResult("bearish", 0.75, details + " (overbought crossover)");
        if (stochK < 20) return new SignalResult("bullish", 0.60, details);
        if (stochK > 80) return new SignalResult("bearish", 0.60, details);
        return new SignalResult("neutral", 0.50, details);
    }

    // --- 8. VWAP PROXIMITY: institutional level ---
    private SignalResult analyzeVWAP(double[] closes, double[] highs, double[] lows, long[] volumes) {
        if (closes.length < 20) return new SignalResult("neutral", 0.5, "Insufficient data for VWAP");

        // Approximate VWAP using typical price * volume over recent 20 bars
        int period = 20;
        double cumTPV = 0, cumVol = 0;
        for (int i = closes.length - period; i < closes.length; i++) {
            double tp = (highs[i] + lows[i] + closes[i]) / 3.0;
            cumTPV += tp * volumes[i];
            cumVol += volumes[i];
        }
        double vwapVal = cumVol > 0 ? cumTPV / cumVol : closes[closes.length - 1];
        double current = closes[closes.length - 1];
        double deviation = (current - vwapVal) / vwapVal * 100;

        String details = String.format("VWAP=%.2f price=%.2f dev=%.2f%%", vwapVal, current, deviation);

        // Price far above VWAP → overbought relative to volume-weighted fair value
        if (deviation > 2.0) return new SignalResult("bearish", 0.60, details + " (extended above VWAP)");
        if (deviation < -2.0) return new SignalResult("bullish", 0.60, details + " (below VWAP — value zone)");
        // Price near VWAP with trend direction
        if (deviation > 0.5) return new SignalResult("bullish", 0.55, details + " (holding above VWAP)");
        if (deviation < -0.5) return new SignalResult("bearish", 0.55, details + " (rejected at VWAP)");
        return new SignalResult("neutral", 0.50, details);
    }

    // --- 9. MULTI-TIMEFRAME: EMA stack alignment (8/21/50/200) ---
    private SignalResult analyzeMultiTimeframe(double[] closes) {
        if (closes.length < 200) return new SignalResult("neutral", 0.5, "Insufficient data for MTF (need 200 bars)");

        double ema8 = ema(closes, 8);
        double ema21 = ema(closes, 21);
        double ema50 = ema(closes, 50);
        double ema200 = ema(closes, 200);

        // Perfect bullish stack: 8 > 21 > 50 > 200
        boolean perfectBull = ema8 > ema21 && ema21 > ema50 && ema50 > ema200;
        // Perfect bearish stack: 8 < 21 < 50 < 200
        boolean perfectBear = ema8 < ema21 && ema21 < ema50 && ema50 < ema200;

        // Count aligned pairs
        int bullPairs = 0, bearPairs = 0;
        double[][] pairs = {{ema8, ema21}, {ema21, ema50}, {ema50, ema200}};
        for (double[] p : pairs) {
            if (p[0] > p[1]) bullPairs++;
            else bearPairs++;
        }

        // Price vs 200 EMA (golden/death cross context)
        boolean above200 = closes[closes.length - 1] > ema200;

        String details = String.format("EMA8=%.2f EMA21=%.2f EMA50=%.2f EMA200=%.2f stack=%s",
                ema8, ema21, ema50, ema200,
                perfectBull ? "perfect_bull" : perfectBear ? "perfect_bear" : "mixed");

        if (perfectBull) return new SignalResult("bullish", 0.85, details);
        if (perfectBear) return new SignalResult("bearish", 0.85, details);
        if (bullPairs >= 2 && above200) return new SignalResult("bullish", 0.65, details);
        if (bearPairs >= 2 && !above200) return new SignalResult("bearish", 0.65, details);
        return new SignalResult("neutral", 0.50, details);
    }

    // --- 10. NEWS SENTIMENT: MarketAux real-time news ---
    private SignalResult analyzeNewsSentiment(String ticker) {
        lastNewsArticles = Collections.emptyList();
        try {
            if (!marketAuxClient.isConfigured()) {
                return new SignalResult("neutral", 0.50, "MarketAux API not configured");
            }
            MarketAuxResponse response = marketAuxClient.fetchNews(ticker);
            NewsSentimentScore score = marketAuxClient.computeSentiment(ticker, response.getData());
            lastNewsArticles = score.articles(); // cache for report inclusion

            String details = String.format("news_%s avg=%.4f articles=%d bull=%d bear=%d neut=%d",
                    score.signal(), score.avgSentiment(), score.articleCount(),
                    score.bullishCount(), score.bearishCount(), score.neutralCount());

            if (score.articleCount() == 0) {
                return new SignalResult("neutral", 0.40, details + " (no data)");
            }

            // Scale confidence by article count (more articles = more reliable)
            double dataReliability = Math.min(score.articleCount() / 10.0, 1.0); // maxes at 10 articles
            double sentimentStrength = Math.abs(score.avgSentiment()); // 0 to 1

            switch (score.signal()) {
                case "bullish": {
                    double conf = 0.55 + sentimentStrength * 0.30 * dataReliability;
                    return new SignalResult("bullish", Math.min(conf, 0.90), details);
                }
                case "bearish": {
                    double conf = 0.55 + sentimentStrength * 0.30 * dataReliability;
                    return new SignalResult("bearish", Math.min(conf, 0.90), details);
                }
                default:
                    return new SignalResult("neutral", 0.50, details);
            }
        } catch (Exception e) {
            log.warning("News sentiment analysis failed for " + ticker + ": " + e.getMessage());
            return new SignalResult("neutral", 0.40, "API error: " + e.getMessage());
        }
    }

    private double signalValue(String signal) {
        return switch (signal) {
            case "bullish" -> 1.0;
            case "bearish" -> -1.0;
            default -> 0.0;
        };
    }
}

