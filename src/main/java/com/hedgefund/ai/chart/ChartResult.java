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
        Summary summary,
        InstitutionalData institutional) {

    public record Candle(String time, double open, double high, double low, double close, double volume) {}

    /** type: BUY, STRONG_BUY, SELL, STRONG_SELL, EXIT, SL, TP, BOS, CHOCH, BREAKOUT, WEAK_BREAKOUT, SPIKE, INST_EARLY_BUY, INST_BUY, INST_BREAKOUT, INST_TAKE_PROFIT, INST_EXIT. */
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

    public record InstitutionalData(
            List<Double> ema20,
            List<Double> ema50,
            List<Double> ema200,
            List<Double> trailStop,
            List<Double> rsi,
            List<Double> adx,
            List<Double> macdHist,
            List<Double> rvol,
            List<Double> atr,
            List<Double> upperResistance,
            List<Double> lowerSupport,
            List<Marker> markers,
            InstitutionalSummary summary) {}

    public record InstitutionalSummary(
            boolean bullTrend,
            boolean earlyBuy,
            boolean pullbackBuy,
            boolean breakoutBuy,
            boolean takeProfit,
            boolean trendExit,
            String latestSignal,
            double rsi,
            double adx,
            double macdHist,
            double trailStop,
            boolean volumeStrong,
            boolean extended,
            double rvol,
            String divergence,
            String breakoutStatus,
            double nearestResistance,
            double nearestSupport,
            double atrStopLoss,
            double atrTakeProfit1,
            double atrTakeProfit2,
            double atrTakeProfit3,
            double plannedRisk,
            double plannedReward,
            double minRiskRewardRatio,
            String htfTrend,
            boolean htfConfirmed,
            double bullScore,
            double bearScore,
            String scoreRecommendation) {

        public InstitutionalSummary(
                boolean bullTrend, boolean earlyBuy, boolean pullbackBuy, boolean breakoutBuy,
                boolean takeProfit, boolean trendExit, String latestSignal,
                double rsi, double adx, double macdHist, double trailStop,
                boolean volumeStrong, boolean extended) {
            this(bullTrend, earlyBuy, pullbackBuy, breakoutBuy, takeProfit, trendExit, latestSignal,
                    rsi, adx, macdHist, trailStop, volumeStrong, extended,
                    1.0, "NONE", "NORMAL", 0, 0, 0, 0, 0, 0, 0, 0, 2.0, "N/A", false, 50, 50, "WATCH");
        }
    }

    public ChartResult(String ticker, String preset, List<Candle> candles,
                       List<Double> ema9, List<Double> ema21, List<Double> ema50, List<Double> ema200,
                       List<Double> vwap, List<Double> resistance, List<Double> support,
                       List<List<Double>> resistanceLevels, List<List<Double>> supportLevels,
                       List<Double> bullScore, List<Double> bearScore,
                       List<Marker> markers, List<Trade> trades, Summary summary) {
        this(ticker, preset, candles, ema9, ema21, ema50, ema200, vwap, resistance, support,
                resistanceLevels, supportLevels, bullScore, bearScore, markers, trades, summary, null);
    }
}

