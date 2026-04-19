package com.hedgefund.ai.service.agents;

import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.agent.TradingSignalResult;
import com.hedgefund.ai.model.api.Price;
import com.hedgefund.ai.service.api.FinancialDataClient;
import com.hedgefund.ai.service.api.MarketAuxClient;
import com.hedgefund.ai.service.api.MarketAuxClient.NewsSentimentScore;
import com.hedgefund.ai.model.api.MarketAuxResponse;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;

/**
 * <h2>Sniper Confluence Entry / Exit Agent</h2>
 *
 * A precision trade-entry system that combines <b>EMA crossover signals</b> with a
 * <b>7-factor confluence scoring engine</b>. Signals only fire when multiple
 * independent technical factors align, dramatically reducing false entries.
 *
 * <h3>Signal Generation</h3>
 * Entries are triggered by a Fast EMA / Slow EMA crossover — but <b>only</b> when the
 * confluence score meets the minimum threshold (default 4/7).
 *
 * <h3>7-Factor Confluence Engine (scored 0 – 7 per bar)</h3>
 * <ol>
 *   <li><b>Price vs VWAP</b> — Is price above (bullish) / below (bearish) the VWAP?</li>
 *   <li><b>RSI (14)</b> — Is momentum bullish (&gt;50) or bearish (&lt;50)?</li>
 *   <li><b>MACD Trend</b> — Is the MACD line above/below its signal line?</li>
 *   <li><b>EMA Alignment</b> — Is the fast EMA above/below the slow EMA?</li>
 *   <li><b>ADX + Directional Movement</b> — Is trend strength &gt;25 with DI confirming?</li>
 *   <li><b>Volume Confirmation</b> — Is volume above average with confirming candle?</li>
 *   <li><b>Secondary TF RSI</b> — Does a 3× period RSI confirm the direction?</li>
 * </ol>
 *
 * <h3>Risk Management</h3>
 * <ul>
 *   <li><b>Stop-Loss</b> — ATR × risk multiplier from entry</li>
 *   <li><b>TP1-TP5</b> — 1R, 2R, 3R, 4R, 5R from entry (R = risk distance)</li>
 *   <li><b>Trailing Stop → Breakeven</b> — SL moves to entry price after TP1 is hit</li>
 * </ul>
 */
@Component
public class TradingSignalAgent implements AnalystAgent {

    private static final Logger log = Logger.getLogger(TradingSignalAgent.class.getName());
    private final FinancialDataClient apiClient;
    private final MarketAuxClient marketAuxClient;

    // ── Configurable parameters (Swing / Daily defaults) ──
    private static final int    FAST_EMA_PERIOD       = 9;
    private static final int    SLOW_EMA_PERIOD       = 21;
    private static final int    RSI_PERIOD            = 14;
    private static final int    ATR_PERIOD            = 14;
    private static final int    ADX_PERIOD            = 14;
    private static final int    VWAP_LOOKBACK         = 20;
    private static final int    VOLUME_AVG_PERIOD     = 20;
    private static final int    SECONDARY_RSI_MULT    = 3;    // secondary TF = RSI(14*3 = 42)
    private static final double ATR_SL_MULTIPLIER     = 2.0;  // swing default
    private static final int    MIN_CONFLUENCE_SCORE  = 4;    // out of 7

    // V2 weighted model + gates
    private static final double WEIGHT_F1_PRICE_VWAP        = 1.0;
    private static final double WEIGHT_F2_RSI               = 0.8;
    private static final double WEIGHT_F3_MACD              = 0.8;
    private static final double WEIGHT_F4_EMA_ALIGN         = 1.0;
    private static final double WEIGHT_F5_ADX_DI            = 1.2;
    private static final double WEIGHT_F6_VOLUME            = 0.7;
    private static final double WEIGHT_F7_SECONDARY_RSI     = 0.7;
    private static final double WEIGHT_F8_REGIME            = 1.5;
    private static final double WEIGHT_F9_HTF_PROXY         = 1.3;
    private static final double WEIGHT_F10_REL_STRENGTH     = 1.0;
    private static final double WEIGHT_F11_NEWS_SENTIMENT   = 1.2;  // MarketAux news sentiment

    private static final double WEIGHTED_SCORE_MAX          = 11.2; // updated for 11 factors
    private static final double WEIGHTED_SCORE_THRESHOLD    = 6.2;
    private static final double WEIGHTED_SCORE_CROSS_TH     = 5.6;

    private static final double GATE_MIN_ATR_PCT            = 0.8;
    private static final double GATE_MAX_ATR_PCT            = 8.0;
    private static final double GATE_MIN_ADX                = 18.0;
    private static final double ADX_TREND_THRESHOLD         = 25.0;
    private static final double TREND_SPREAD_PCT_MIN        = 0.2;

    private static final int    EMA_HTF_FAST                = 50;
    private static final int    EMA_HTF_SLOW                = 100;

    public TradingSignalAgent(FinancialDataClient apiClient, MarketAuxClient marketAuxClient) {
        this.apiClient = apiClient;
        this.marketAuxClient = marketAuxClient;
    }

    @Override public String getAgentId()    { return "trading_signal"; }
    @Override public String getDisplayName(){ return "Sniper Confluence Agent"; }
    @Override public String getDescription(){ return "11-Factor Confluence Entry/Exit — LONG/SHORT with News Sentiment, TP1-TP5, ATR Stop-Loss & Breakeven Trailing"; }

    // ════════════════════════════════════════════════════════════
    //  MAIN ANALYSIS
    // ════════════════════════════════════════════════════════════

    @Override
    public Map<String, AnalystSignal> analyze(AgentState state) {
        Map<String, AnalystSignal> results = new LinkedHashMap<>();

        for (String ticker : state.getTickers()) {
            log.info("[" + getAgentId() + "] Analyzing " + ticker);

            List<Price> prices = apiClient.getPrices(ticker, state.getStartDate(), state.getEndDate());
            int minBars = Math.max(SLOW_EMA_PERIOD + 10, 35);
            if (prices.size() < minBars) {
                log.warning("[" + getAgentId() + "] Insufficient data for " + ticker + " (" + prices.size() + " bars)");
                results.put(ticker, fallbackSignal(ticker));
                continue;
            }

            double[] closes = prices.stream().mapToDouble(Price::getClose).toArray();
            double[] highs  = prices.stream().mapToDouble(Price::getHigh).toArray();
            double[] lows   = prices.stream().mapToDouble(Price::getLow).toArray();
            long[]   vols   = prices.stream().mapToLong(Price::getVolume).toArray();
            int n = closes.length;

            double currentPrice = closes[n - 1];
            double prevClose    = closes[n - 2];

            // ─── Compute all indicators ───
            double[] fastEmaArr = emaArray(closes, FAST_EMA_PERIOD);
            double[] slowEmaArr = emaArray(closes, SLOW_EMA_PERIOD);
            double[] ema50Arr = emaArray(closes, EMA_HTF_FAST);
            double[] ema100Arr = emaArray(closes, EMA_HTF_SLOW);
            double fastEma = fastEmaArr[n - 1];
            double slowEma = slowEmaArr[n - 1];
            double prevFastEma = fastEmaArr[n - 2];
            double prevSlowEma = slowEmaArr[n - 2];

            double[] macdLine   = macdLineArray(closes, 12, 26);
            double[] signalLine = emaArray(macdLine, 9);
            double macd   = macdLine[n - 1];
            double signal = signalLine[n - 1];
            double macdHist = macd - signal;

            double rsi14 = rsi(closes, RSI_PERIOD);
            double rsiSecondary = rsi(closes, RSI_PERIOD * SECONDARY_RSI_MULT);

            double atr = atr(highs, lows, closes, ATR_PERIOD);
            double atrPct = currentPrice > 0 ? (atr / currentPrice * 100.0) : 0;
            double vwap = vwap(closes, vols, VWAP_LOOKBACK);
            double avgVolume = avgVolume(vols, VOLUME_AVG_PERIOD);

            double[] adxResult = adx(highs, lows, closes, ADX_PERIOD);
            double adxValue = adxResult[0];
            double plusDI  = adxResult[1];
            double minusDI = adxResult[2];

            // ─── EMA Crossover detection (bar-close confirmed) ───
            boolean bullishCross = prevFastEma <= prevSlowEma && fastEma > slowEma;
            boolean bearishCross = prevFastEma >= prevSlowEma && fastEma < slowEma;
            // Also accept existing alignment as a valid ongoing signal
            boolean emaBullish = fastEma > slowEma;
            boolean emaBearish = fastEma < slowEma;

            // Determine raw direction from EMA alignment
            boolean directionIsLong = emaBullish;

            // ─── 7-FACTOR CONFLUENCE SCORING ───
            Map<String, Object> factorDetails = new LinkedHashMap<>();
            int score = 0;
            double weightedScore = 0.0;

            // Factor 1: Price vs VWAP
            boolean f1 = directionIsLong ? (currentPrice > vwap) : (currentPrice < vwap);
            if (f1) { score++; weightedScore += WEIGHT_F1_PRICE_VWAP; }
            factorDetails.put("1_price_vs_vwap", Map.of(
                    "pass", f1,
                    "price", r2(currentPrice), "vwap", r2(vwap),
                    "position", currentPrice > vwap ? "above" : "below",
                    "distance_pct", r2((currentPrice - vwap) / vwap * 100)
            ));

            // Factor 2: RSI (14) momentum
            boolean f2 = directionIsLong ? (rsi14 > 50) : (rsi14 < 50);
            if (f2) { score++; weightedScore += WEIGHT_F2_RSI; }
            factorDetails.put("2_rsi_momentum", Map.of(
                    "pass", f2,
                    "rsi_14", r2(rsi14),
                    "direction", rsi14 > 50 ? "bullish" : "bearish"
            ));

            // Factor 3: MACD Trend
            boolean f3 = directionIsLong ? (macd > signal) : (macd < signal);
            if (f3) { score++; weightedScore += WEIGHT_F3_MACD; }
            factorDetails.put("3_macd_trend", Map.of(
                    "pass", f3,
                    "macd", r2(macd), "signal", r2(signal), "histogram", r2(macdHist),
                    "direction", macd > signal ? "bullish" : "bearish"
            ));

            // Factor 4: EMA Alignment
            boolean f4 = directionIsLong ? emaBullish : emaBearish;
            if (f4) { score++; weightedScore += WEIGHT_F4_EMA_ALIGN; }
            factorDetails.put("4_ema_alignment", Map.of(
                    "pass", f4,
                    "fast_ema", r2(fastEma), "slow_ema", r2(slowEma),
                    "alignment", emaBullish ? "bullish" : "bearish",
                    "crossover_detected", bullishCross ? "bullish_cross" : bearishCross ? "bearish_cross" : "none"
            ));

            // Factor 5: ADX + Directional Movement
            boolean adxStrong = adxValue > 25;
            boolean diConfirms = directionIsLong ? (plusDI > minusDI) : (minusDI > plusDI);
            boolean f5 = adxStrong && diConfirms;
            if (f5) { score++; weightedScore += WEIGHT_F5_ADX_DI; }
            factorDetails.put("5_adx_direction", Map.of(
                    "pass", f5,
                    "adx", r2(adxValue), "plus_di", r2(plusDI), "minus_di", r2(minusDI),
                    "trend_strong", adxStrong, "di_confirms", diConfirms
            ));

            // Factor 6: Volume Confirmation
            boolean volAboveAvg = vols[n - 1] > avgVolume;
            boolean candleConfirms = directionIsLong
                    ? (closes[n - 1] > closes[n - 2])
                    : (closes[n - 1] < closes[n - 2]);
            boolean f6 = volAboveAvg && candleConfirms;
            if (f6) { score++; weightedScore += WEIGHT_F6_VOLUME; }
            factorDetails.put("6_volume_confirmation", Map.of(
                    "pass", f6,
                    "current_volume", vols[n - 1], "avg_volume", Math.round(avgVolume),
                    "volume_ratio", r2(vols[n - 1] / avgVolume),
                    "above_average", volAboveAvg, "candle_confirms", candleConfirms
            ));

            // Factor 7: Secondary TF RSI
            boolean f7 = directionIsLong ? (rsiSecondary > 50) : (rsiSecondary < 50);
            if (f7) { score++; weightedScore += WEIGHT_F7_SECONDARY_RSI; }
            factorDetails.put("7_secondary_tf_rsi", Map.of(
                    "pass", f7,
                    "rsi_" + (RSI_PERIOD * SECONDARY_RSI_MULT), r2(rsiSecondary),
                    "direction", rsiSecondary > 50 ? "bullish" : "bearish"
            ));

            // Factor 8: Regime compatibility
            String regime;
            boolean trendSpreadOk = currentPrice > 0 && (Math.abs(fastEma - slowEma) / currentPrice * 100.0) > TREND_SPREAD_PCT_MIN;
            boolean trendRegime = adxValue >= ADX_TREND_THRESHOLD && trendSpreadOk;
            if (trendRegime) {
                regime = "trend";
            } else if (atrPct > 4.5) {
                regime = "high_volatility";
            } else if (atrPct < 1.0) {
                regime = "low_volatility";
            } else {
                regime = "range";
            }
            boolean regimePass = trendRegime && diConfirms;
            boolean f8 = regimePass;
            if (f8) { weightedScore += WEIGHT_F8_REGIME; }
            factorDetails.put("8_regime_compatibility", Map.of(
                    "pass", f8,
                    "regime", regime,
                    "trend_regime", trendRegime,
                    "trend_spread_pct", r2(currentPrice > 0 ? Math.abs(fastEma - slowEma) / currentPrice * 100.0 : 0),
                    "adx", r2(adxValue),
                    "di_confirms", diConfirms
            ));

            // Factor 9: Higher-timeframe proxy (adaptive)
            boolean hasLongHtf = n >= EMA_HTF_SLOW + 5;
            double ema50 = ema50Arr[n - 1];
            double ema100 = ema100Arr[n - 1];
            int slopeLookback = Math.min(5, n - 2);

            boolean f9;
            String htfMode;
            if (hasLongHtf) {
                double ema50Past = ema50Arr[n - 1 - slopeLookback];
                boolean ema50SlopeUp = ema50 > ema50Past;
                boolean ema50SlopeDown = ema50 < ema50Past;
                f9 = directionIsLong
                        ? (ema50 > ema100 && ema50SlopeUp)
                        : (ema50 < ema100 && ema50SlopeDown);
                htfMode = "ema50_ema100";
                factorDetails.put("9_htf_proxy_alignment", Map.of(
                        "pass", f9,
                        "mode", htfMode,
                        "ema50", r2(ema50),
                        "ema100", r2(ema100),
                        "lookback_bars", slopeLookback
                ));
            } else {
                int shortLookback = Math.min(5, n - 2);
                double fastPast = fastEmaArr[n - 1 - shortLookback];
                double slowPast = slowEmaArr[n - 1 - shortLookback];
                boolean fastSlopeUp = fastEma > fastPast;
                boolean fastSlopeDown = fastEma < fastPast;
                boolean alignNow = directionIsLong ? (fastEma > slowEma) : (fastEma < slowEma);
                boolean alignPast = directionIsLong ? (fastPast > slowPast) : (fastPast < slowPast);
                f9 = alignNow && alignPast && (directionIsLong ? fastSlopeUp : fastSlopeDown);
                htfMode = "ema9_ema21_fallback";
                factorDetails.put("9_htf_proxy_alignment", Map.of(
                        "pass", f9,
                        "mode", htfMode,
                        "fast_ema", r2(fastEma),
                        "slow_ema", r2(slowEma),
                        "lookback_bars", shortLookback
                ));
            }
            if (f9) { weightedScore += WEIGHT_F9_HTF_PROXY; }

            // Factor 10: Relative strength proxy (adaptive lookback)
            int lb20 = Math.min(20, n - 1);
            int lb60 = Math.min(60, n - 1);
            double ret20 = closes[n - 1 - lb20] != 0 ? (closes[n - 1] / closes[n - 1 - lb20]) - 1.0 : 0;
            double ret60 = closes[n - 1 - lb60] != 0 ? (closes[n - 1] / closes[n - 1 - lb60]) - 1.0 : 0;
            boolean rsSampleOk = lb20 >= 10 && lb60 >= 30;
            boolean f10 = rsSampleOk && (directionIsLong
                    ? (ret20 > 0 && ret60 > 0 && ret20 > (ret60 * 0.3))
                    : (ret20 < 0 && ret60 < 0 && ret20 < (ret60 * 0.3)));
            if (f10) { weightedScore += WEIGHT_F10_REL_STRENGTH; }
            factorDetails.put("10_relative_strength_proxy", Map.of(
                    "pass", f10,
                    "sample_ok", rsSampleOk,
                    "lb20", lb20,
                    "lb60", lb60,
                    "ret20_pct", r2(ret20 * 100.0),
                    "ret60_pct", r2(ret60 * 100.0)
            ));

            // Factor 11: MarketAux News Sentiment
            NewsSentimentScore newsSentiment = null;
            boolean f11 = false;
            try {
                MarketAuxResponse newsResponse = marketAuxClient.fetchNews(ticker);
                newsSentiment = marketAuxClient.computeSentiment(ticker, newsResponse.getData());
                // News confirms direction: bullish sentiment for longs, bearish for shorts
                f11 = directionIsLong
                        ? "bullish".equals(newsSentiment.signal())
                        : "bearish".equals(newsSentiment.signal());
            } catch (Exception e) {
                log.warning("MarketAux news fetch failed for " + ticker + ": " + e.getMessage());
                newsSentiment = new NewsSentimentScore(0, 0, 0, 0, 0, "neutral", "API error", java.util.Collections.emptyList());
            }
            if (f11) { weightedScore += WEIGHT_F11_NEWS_SENTIMENT; }
            // Build articles list for report (title + url + sentiment)
            List<Map<String, Object>> newsArticlesList = new ArrayList<>();
            for (var art : newsSentiment.articles()) {
                Map<String, Object> artMap = new LinkedHashMap<>();
                artMap.put("title", art.title());
                artMap.put("url", art.url());
                artMap.put("source", art.source());
                artMap.put("sentiment", art.sentimentLabel());
                newsArticlesList.add(artMap);
            }
            Map<String, Object> f11Details = new LinkedHashMap<>();
            f11Details.put("pass", f11);
            f11Details.put("signal", newsSentiment.signal());
            f11Details.put("avg_sentiment", r2(newsSentiment.avgSentiment()));
            f11Details.put("article_count", newsSentiment.articleCount());
            f11Details.put("bullish_articles", newsSentiment.bullishCount());
            f11Details.put("bearish_articles", newsSentiment.bearishCount());
            f11Details.put("neutral_articles", newsSentiment.neutralCount());
            f11Details.put("details", newsSentiment.details());
            f11Details.put("articles", newsArticlesList);
            factorDetails.put("11_news_sentiment", f11Details);

            boolean gateAtr = atrPct >= GATE_MIN_ATR_PCT && atrPct <= GATE_MAX_ATR_PCT;
            boolean gateAdx = adxValue >= GATE_MIN_ADX;
            boolean hardGatesPass = gateAtr && gateAdx;

            double dynamicThreshold = (bullishCross || bearishCross) ? WEIGHTED_SCORE_CROSS_TH : WEIGHTED_SCORE_THRESHOLD;
            if (!trendRegime) {
                dynamicThreshold += 0.8;
            }

            boolean signalFired = hardGatesPass && weightedScore >= dynamicThreshold;

            if (bearishCross && directionIsLong && weightedScore < WEIGHTED_SCORE_CROSS_TH) { directionIsLong = false; }
            if (bullishCross && !directionIsLong && weightedScore < WEIGHTED_SCORE_CROSS_TH) { directionIsLong = true; }

            String position = directionIsLong ? "LONG" : "SHORT";

            // ─── Bollinger Bands (20, 2σ) ───
            int bbPeriod = 20;
            double bbMean = 0, bbStd = 0;
            int bbStart = Math.max(0, n - bbPeriod);
            int bbCount = n - bbStart;
            for (int i = bbStart; i < n; i++) bbMean += closes[i];
            bbMean /= bbCount;
            for (int i = bbStart; i < n; i++) bbStd += Math.pow(closes[i] - bbMean, 2);
            bbStd = Math.sqrt(bbStd / bbCount);
            double bbUpper = bbMean + 2 * bbStd;
            double bbLower = bbMean - 2 * bbStd;
            double bbMiddle = bbMean;

            // ─── Fibonacci Retracement (swing high/low over lookback) ───
            int fibLookback = Math.min(50, n);
            double swingHigh = Double.MIN_VALUE, swingLow = Double.MAX_VALUE;
            for (int i = n - fibLookback; i < n; i++) {
                if (highs[i] > swingHigh) swingHigh = highs[i];
                if (lows[i] < swingLow) swingLow = lows[i];
            }
            double fibRange = swingHigh - swingLow;
            // In downtrend: retracement from high (levels = high - range * ratio → support below)
            // In uptrend: retracement from low (levels = low + range * ratio → resistance above)
            double fib236, fib382, fib500, fib618, fib786;
            if (!directionIsLong) {
                // Downtrend: Fib levels are potential support (bounce) zones below price
                fib236 = swingHigh - fibRange * 0.236;
                fib382 = swingHigh - fibRange * 0.382;
                fib500 = swingHigh - fibRange * 0.500;
                fib618 = swingHigh - fibRange * 0.618;
                fib786 = swingHigh - fibRange * 0.786;
            } else {
                // Uptrend: Fib levels are pullback support zones
                fib236 = swingHigh - fibRange * 0.236;
                fib382 = swingHigh - fibRange * 0.382;
                fib500 = swingHigh - fibRange * 0.500;
                fib618 = swingHigh - fibRange * 0.618;
                fib786 = swingHigh - fibRange * 0.786;
            }

            // ─── Key Support & Resistance ───
            // Support = strongest floor (max of BB lower, Fib 0.618)
            // Resistance = strongest ceiling (min of BB upper, Fib 0.236)
            double supportLevel, resistanceLevel;
            if (!directionIsLong) {
                // Downtrend: support is where price may bounce
                // Use the HIGHER of BB lower and Fib 0.618 (stronger support = closer to price)
                supportLevel = Math.max(bbLower, fib618);
                resistanceLevel = Math.min(bbUpper, fib236);
            } else {
                // Uptrend: support is pullback zone
                supportLevel = Math.max(bbLower, fib382);
                resistanceLevel = Math.min(bbUpper, swingHigh);
            }

            // ─── Entry / SL / TP (R-based) ───
            double entry = currentPrice; // bar-close entry

            double stopLoss = directionIsLong
                    ? entry - atr * ATR_SL_MULTIPLIER
                    : entry + atr * ATR_SL_MULTIPLIER;

            double riskPerShare = Math.abs(entry - stopLoss);  // = 1R
            double breakevenStop = entry;  // SL moves here after TP1 hit

            // TP1=1R, TP2=2R, TP3=3R, TP4=4R, TP5=5R
            double[] tp = new double[5];
            for (int i = 0; i < 5; i++) {
                double rMultiple = i + 1;
                tp[i] = directionIsLong
                        ? entry + riskPerShare * rMultiple
                        : entry - riskPerShare * rMultiple;
            }

            // ─── Confidence from confluence score ───
            // Base: (score/7)*100, boosted by crossover and factor agreement
            // ─── Logistic confidence (v2) ───
            boolean eventRisk = false; // placeholder until event calendar is integrated
            double z = -1.4
                    + 0.55 * weightedScore
                    + 0.35 * ((bullishCross || bearishCross) ? 1.0 : 0.0)
                    + 0.40 * (regimePass ? 1.0 : 0.0)
                    + 0.30 * (f11 ? 1.0 : 0.0)  // news sentiment boost
                    - 0.45 * (eventRisk ? 1.0 : 0.0);

            double probability = 1.0 / (1.0 + Math.exp(-z));
            double confidence = Math.round(clamp(probability * 100.0, 5.0, 95.0));
            if (!signalFired) confidence = Math.max(10.0, confidence - 15.0);

            double rrr = riskPerShare > 0 ? r2((Math.abs(tp[2] - entry)) / riskPerShare) : 0; // TP3 = 3R

            // ─── EMA Retest detection (orange candle) ───
            boolean retestCandle = false;
            if (emaBullish) {
                retestCandle = lows[n - 1] <= fastEma && closes[n - 1] > fastEma;
            } else if (emaBearish) {
                retestCandle = highs[n - 1] >= fastEma && closes[n - 1] < fastEma;
            }

            // ─── Build full reasoning ───
            Map<String, Object> reasoning = new LinkedHashMap<>();
            reasoning.put("ticker", ticker);
            reasoning.put("current_price", r2(currentPrice));

            reasoning.put("confluence_score", score);
            reasoning.put("confluence_threshold", MIN_CONFLUENCE_SCORE);
            reasoning.put("weighted_score", r2(weightedScore));
            reasoning.put("weighted_score_max", WEIGHTED_SCORE_MAX);
            reasoning.put("weighted_threshold", r2(dynamicThreshold));
            reasoning.put("signal_fired", signalFired);
            reasoning.put("hard_gates", Map.of(
                    "pass", hardGatesPass,
                    "atr_pct", r2(atrPct),
                    "atr_pct_range", GATE_MIN_ATR_PCT + "-" + GATE_MAX_ATR_PCT,
                    "atr_gate", gateAtr,
                    "adx", r2(adxValue),
                    "adx_min", GATE_MIN_ADX,
                    "adx_gate", gateAdx,
                    "regime", regime
            ));
            reasoning.put("confluence_factors", factorDetails);

            reasoning.put("ema_crossover", Map.of(
                    "fast_ema_period", FAST_EMA_PERIOD,
                    "slow_ema_period", SLOW_EMA_PERIOD,
                    "fast_ema", r2(fastEma), "slow_ema", r2(slowEma),
                    "bullish_crossover", bullishCross,
                    "bearish_crossover", bearishCross,
                    "retest_candle", retestCandle
            ));

            Map<String, Object> indicators = new LinkedHashMap<>();
            indicators.put("rsi_14", r2(rsi14));
            indicators.put("rsi_secondary_" + (RSI_PERIOD * SECONDARY_RSI_MULT), r2(rsiSecondary));
            indicators.put("macd", r2(macd));
            indicators.put("macd_signal", r2(signal));
            indicators.put("macd_histogram", r2(macdHist));
            indicators.put("atr_14", r2(atr));
            indicators.put("atr_pct", r2(atrPct));
            indicators.put("adx", r2(adxValue));
            indicators.put("plus_di", r2(plusDI));
            indicators.put("minus_di", r2(minusDI));
            indicators.put("vwap", r2(vwap));
            indicators.put("bb_upper", r2(bbUpper));
            indicators.put("bb_middle", r2(bbMiddle));
            indicators.put("bb_lower", r2(bbLower));
            indicators.put("fib_swing_high", r2(swingHigh));
            indicators.put("fib_swing_low", r2(swingLow));
            indicators.put("fib_236", r2(fib236));
            indicators.put("fib_382", r2(fib382));
            indicators.put("fib_500", r2(fib500));
            indicators.put("fib_618", r2(fib618));
            indicators.put("fib_786", r2(fib786));
            indicators.put("support_level", r2(supportLevel));
            indicators.put("resistance_level", r2(resistanceLevel));
            indicators.put("news_sentiment", r2(newsSentiment.avgSentiment()));
            indicators.put("news_signal", newsSentiment.signal());
            indicators.put("news_articles", newsSentiment.articleCount());
            reasoning.put("indicators", indicators);

            reasoning.put("risk_management", Map.of(
                    "atr_sl_multiplier", ATR_SL_MULTIPLIER,
                    "risk_per_share_1R", r2(riskPerShare),
                    "breakeven_stop", r2(breakevenStop),
                    "trailing_stop_rule", "SL moves to entry (breakeven) after TP1 is hit"
            ));

            Map<String, Object> levelsMap = new LinkedHashMap<>();
            levelsMap.put("entry", r2(entry));
            levelsMap.put("stop_loss", r2(stopLoss));
            levelsMap.put("TP1_1R", r2(tp[0]));
            levelsMap.put("TP2_2R", r2(tp[1]));
            levelsMap.put("TP3_3R", r2(tp[2]));
            levelsMap.put("TP4_4R", r2(tp[3]));
            levelsMap.put("TP5_5R", r2(tp[4]));
            levelsMap.put("risk_reward_ratio", rrr);
            levelsMap.put("bollinger_upper", r2(bbUpper));
            levelsMap.put("bollinger_middle", r2(bbMiddle));
            levelsMap.put("bollinger_lower", r2(bbLower));
            levelsMap.put("fib_swing_high", r2(swingHigh));
            levelsMap.put("fib_swing_low", r2(swingLow));
            levelsMap.put("fib_236", r2(fib236));
            levelsMap.put("fib_382", r2(fib382));
            levelsMap.put("fib_500", r2(fib500));
            levelsMap.put("fib_618", r2(fib618));
            levelsMap.put("fib_786", r2(fib786));
            levelsMap.put("support", r2(supportLevel));
            levelsMap.put("resistance", r2(resistanceLevel));
            reasoning.put("levels", levelsMap);

            // ─── Build TradingSignalResult ───
            // Note: Don't embed 'reasoning' inside TradingSignalResult to avoid
            // circular nesting (reasoning→trading_signal→reasoning) which causes
            // JSON bloat and truncation of TP levels in reports.
            TradingSignalResult tradingSignal = TradingSignalResult.builder()
                    .position(position)
                    .entryPoint(r2(entry))
                    .stopLoss(r2(stopLoss))
                    .tp1(r2(tp[0])).tp2(r2(tp[1])).tp3(r2(tp[2])).tp4(r2(tp[3])).tp5(r2(tp[4]))
                    .bollingerUpper(r2(bbUpper)).bollingerMiddle(r2(bbMiddle)).bollingerLower(r2(bbLower))
                    .fibSwingHigh(r2(swingHigh)).fibSwingLow(r2(swingLow))
                    .fib236(r2(fib236)).fib382(r2(fib382)).fib500(r2(fib500)).fib618(r2(fib618)).fib786(r2(fib786))
                    .supportLevel(r2(supportLevel)).resistanceLevel(r2(resistanceLevel))
                    .breakevenStop(r2(breakevenStop))
                    .riskPerShare(r2(riskPerShare))
                    .riskRewardRatio(rrr)
                    .confluenceScore(score)
                    .confluenceThreshold(MIN_CONFLUENCE_SCORE)
                    .signalFired(signalFired)
                    .confidence(confidence)
                    .algorithm("Sniper Confluence V2 (Weighted 11-Factor + News Sentiment + Regime/Gates + Logistic Confidence)")
                    .build();

            // Wrap in AnalystSignal
            String signalStr = directionIsLong ? "bullish" : "bearish";
            if (!signalFired) signalStr = "neutral";  // no signal fired = neutral

            Map<String, Object> fullReasoning = new LinkedHashMap<>(reasoning);
            fullReasoning.put("trading_signal", tradingSignal);

            results.put(ticker, AnalystSignal.builder()
                    .signal(signalStr)
                    .confidence(confidence)
                    .reasoning(fullReasoning)
                    .build());

            log.info(String.format(
                    "[%s] %s %s | Score: %d/7 | WScore: %.2f/%.1f (th %.2f) | Gates:%s | Fired:%s | Entry: %.2f | SL: %.2f | " +
                    "TP1: %.2f TP2: %.2f TP3: %.2f TP4: %.2f TP5: %.2f | R:R=%.1f | Conf=%.0f%%",
                    getAgentId(), position, ticker, score, weightedScore, WEIGHTED_SCORE_MAX, dynamicThreshold,
                    hardGatesPass, signalFired,
                    entry, stopLoss, tp[0], tp[1], tp[2], tp[3], tp[4], rrr, confidence));
        }
        return results;
    }

    // ════════════════════════════════════════════════════════════
    //  INDICATOR CALCULATIONS
    // ════════════════════════════════════════════════════════════

    /** EMA array — returns full-length array of EMA values */
    private double[] emaArray(double[] data, int period) {
        double k = 2.0 / (period + 1);
        double[] out = new double[data.length];
        out[0] = data[0];
        for (int i = 1; i < data.length; i++) {
            out[i] = (data[i] - out[i - 1]) * k + out[i - 1];
        }
        return out;
    }

    /** MACD line array = EMA(fast) - EMA(slow) */
    private double[] macdLineArray(double[] closes, int fast, int slow) {
        double[] f = emaArray(closes, fast);
        double[] s = emaArray(closes, slow);
        double[] m = new double[closes.length];
        for (int i = 0; i < closes.length; i++) m[i] = f[i] - s[i];
        return m;
    }

    /** RSI — Wilder's smoothed RSI */
    private double rsi(double[] closes, int period) {
        if (closes.length < period + 1) return 50;
        int start = closes.length - period - 1;
        double gainSum = 0, lossSum = 0;
        for (int i = start + 1; i <= start + period; i++) {
            double chg = closes[i] - closes[i - 1];
            if (chg > 0) gainSum += chg; else lossSum += -chg;
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        // Smooth through remaining bars
        for (int i = start + period + 1; i < closes.length; i++) {
            double chg = closes[i] - closes[i - 1];
            avgGain = (avgGain * (period - 1) + Math.max(chg, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-chg, 0)) / period;
        }
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    /** ATR — Average True Range */
    private double atr(double[] highs, double[] lows, double[] closes, int period) {
        int n = closes.length;
        if (n < 2) return 0;
        int cnt = Math.min(period, n - 1);
        double sum = 0;
        for (int i = n - cnt; i < n; i++) {
            double tr = Math.max(highs[i] - lows[i],
                        Math.max(Math.abs(highs[i] - closes[i - 1]),
                                 Math.abs(lows[i] - closes[i - 1])));
            sum += tr;
        }
        return sum / cnt;
    }

    /** VWAP — Volume Weighted Average Price */
    private double vwap(double[] closes, long[] vols, int lookback) {
        int start = Math.max(0, closes.length - lookback);
        double sumPV = 0; long sumV = 0;
        for (int i = start; i < closes.length; i++) {
            sumPV += closes[i] * vols[i];
            sumV += vols[i];
        }
        return sumV > 0 ? sumPV / sumV : closes[closes.length - 1];
    }

    /** Average volume over N bars */
    private double avgVolume(long[] vols, int period) {
        int start = Math.max(0, vols.length - period);
        long sum = 0;
        int cnt = 0;
        for (int i = start; i < vols.length; i++) { sum += vols[i]; cnt++; }
        return cnt > 0 ? (double) sum / cnt : 0;
    }

    /**
     * ADX with +DI and -DI (Wilder's method).
     * Returns double[3] = { ADX, +DI, -DI }.
     */
    private double[] adx(double[] highs, double[] lows, double[] closes, int period) {
        int n = closes.length;
        if (n < period + 1) return new double[]{0, 0, 0};

        double[] tr  = new double[n];
        double[] pdm = new double[n]; // +DM
        double[] mdm = new double[n]; // -DM

        for (int i = 1; i < n; i++) {
            tr[i] = Math.max(highs[i] - lows[i],
                    Math.max(Math.abs(highs[i] - closes[i - 1]),
                             Math.abs(lows[i] - closes[i - 1])));
            double upMove   = highs[i] - highs[i - 1];
            double downMove = lows[i - 1] - lows[i];
            pdm[i] = (upMove > downMove && upMove > 0) ? upMove : 0;
            mdm[i] = (downMove > upMove && downMove > 0) ? downMove : 0;
        }

        // Initial sums
        double atrSmooth = 0, pdmSmooth = 0, mdmSmooth = 0;
        for (int i = 1; i <= period; i++) {
            atrSmooth += tr[i];
            pdmSmooth += pdm[i];
            mdmSmooth += mdm[i];
        }

        // Wilder smooth
        double[] dx = new double[n];
        double plusDI = 0, minusDI = 0;

        for (int i = period + 1; i < n; i++) {
            atrSmooth = atrSmooth - (atrSmooth / period) + tr[i];
            pdmSmooth = pdmSmooth - (pdmSmooth / period) + pdm[i];
            mdmSmooth = mdmSmooth - (mdmSmooth / period) + mdm[i];

            plusDI  = atrSmooth > 0 ? (pdmSmooth / atrSmooth) * 100 : 0;
            minusDI = atrSmooth > 0 ? (mdmSmooth / atrSmooth) * 100 : 0;

            double diSum = plusDI + minusDI;
            dx[i] = diSum > 0 ? Math.abs(plusDI - minusDI) / diSum * 100 : 0;
        }

        // ADX = smoothed average of DX
        double adxVal = 0;
        int adxStart = period + 1;
        int adxCount = Math.min(period, n - adxStart);
        if (adxCount <= 0) return new double[]{0, plusDI, minusDI};

        for (int i = adxStart; i < adxStart + adxCount; i++) adxVal += dx[i];
        adxVal /= adxCount;

        for (int i = adxStart + adxCount; i < n; i++) {
            adxVal = (adxVal * (period - 1) + dx[i]) / period;
        }

        return new double[]{adxVal, plusDI, minusDI};
    }

    private double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private AnalystSignal fallbackSignal(String ticker) {
        return AnalystSignal.builder()
                .signal("neutral").confidence(0)
                .reasoning(Map.of("error", "Insufficient price data for " + ticker))
                .build();
    }
}

