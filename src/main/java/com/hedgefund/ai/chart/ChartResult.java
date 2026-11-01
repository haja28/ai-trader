package com.hedgefund.ai.chart;

import java.util.List;

/**
 * Result of the confluence signal analysis, shaped for the charting front end.
 * Numeric series are aligned 1:1 with {@code candles}; undefined values are null.
 */
public record ChartResult(
        String ticker,
        String preset,
        List<Candle> candles,
        List<Double> ema9,
        List<Double> ema21,
        List<Double> ema50,
        List<Double> ema200,
        List<Double> vwap,
        List<Double> resistance,
        List<Double> support,
        /** Three most recent confirmed swing highs, index 0 = R1 (newest) .. 2 = R3. */
        List<List<Double>> resistanceLevels,
        /** Three most recent confirmed swing lows, index 0 = S1 (newest) .. 2 = S3. */
        List<List<Double>> supportLevels,
        List<Double> bullScore,
        List<Double> bearScore,
        List<Marker> markers,
        List<Trade> trades,
        Summary summary) {

    public record Candle(String time, double open, double high, double low, double close, double volume) {}

    /** type: BUY, STRONG_BUY, SELL, STRONG_SELL, EXIT, SL, TP, BOS, CHOCH, BREAKOUT, WEAK_BREAKOUT, SPIKE. */
    public record Marker(String time, String type, String text, double price) {}

    /** A managed trade. exitTime is null while the trade is still open. */
    public record Trade(String direction, boolean strong, String entryTime, double entry, double stop,
                        double tp1, double tp2, double tp3, int targetsHit,
                        String exitTime, double exitPrice, String exitReason, double rMultiple) {}

    /** Proposed or active setup for the next trade. */
    public record NextTrade(String action, double entry, double stop,
                            double tp1, double tp2, double tp3, double risk, double riskReward) {}

    public record Summary(String trend, String emaAlignment, double rsi, String macd, double rvol,
                          String structure, String htfTrend, double bullScore, double bearScore,
                          String recommendation, String tradeStatus, int barsAnalyzed, String warning,
                          NextTrade nextTrade) {
        public Summary(String trend, String emaAlignment, double rsi, String macd, double rvol,
                       String structure, String htfTrend, double bullScore, double bearScore,
                       String recommendation, String tradeStatus, int barsAnalyzed, String warning) {
            this(trend, emaAlignment, rsi, macd, rvol, structure, htfTrend, bullScore, bearScore,
                    recommendation, tradeStatus, barsAnalyzed, warning, null);
        }
    }
}

