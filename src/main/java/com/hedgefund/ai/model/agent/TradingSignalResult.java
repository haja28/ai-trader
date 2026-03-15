package com.hedgefund.ai.model.agent;

import java.util.Map;

/**
 * Represents a complete Sniper Confluence trading signal with entry,
 * take-profit levels (R-based), stop loss, and 7-factor confluence score.
 */
public class TradingSignalResult {

    /** "LONG" or "SHORT" */
    private String position;

    /** Recommended entry price */
    private double entryPoint;

    /** Stop-loss price (ATR-based) */
    private double stopLoss;

    /** Five take-profit levels: TP1=1R, TP2=2R, TP3=3R, TP4=4R, TP5=5R */
    private double tp1;
    private double tp2;
    private double tp3;
    private double tp4;
    private double tp5;

    /** Breakeven stop-loss price (SL moves here after TP1 is hit) */
    private double breakevenStop;

    /** Risk per share (entry minus stop-loss distance) */
    private double riskPerShare;

    /** Risk-to-reward ratio at TP3 (primary target) */
    private double riskRewardRatio;

    /** Confluence score 0-7 */
    private int confluenceScore;

    /** Minimum confluence threshold used */
    private int confluenceThreshold;

    /** Whether the signal passed the confluence gate */
    private boolean signalFired;

    /** Overall confidence 0-100 */
    private double confidence;

    /** The algorithm / strategy that produced these levels */
    private String algorithm;

    /** Per-factor confluence breakdown and full reasoning */
    private Map<String, Object> reasoning;

    public TradingSignalResult() {}

    // --- Getters / Setters ---
    public String getPosition() { return position; }
    public void setPosition(String v) { this.position = v; }
    public double getEntryPoint() { return entryPoint; }
    public void setEntryPoint(double v) { this.entryPoint = v; }
    public double getStopLoss() { return stopLoss; }
    public void setStopLoss(double v) { this.stopLoss = v; }
    public double getTp1() { return tp1; }
    public void setTp1(double v) { this.tp1 = v; }
    public double getTp2() { return tp2; }
    public void setTp2(double v) { this.tp2 = v; }
    public double getTp3() { return tp3; }
    public void setTp3(double v) { this.tp3 = v; }
    public double getTp4() { return tp4; }
    public void setTp4(double v) { this.tp4 = v; }
    public double getTp5() { return tp5; }
    public void setTp5(double v) { this.tp5 = v; }
    public double getBreakevenStop() { return breakevenStop; }
    public void setBreakevenStop(double v) { this.breakevenStop = v; }
    public double getRiskPerShare() { return riskPerShare; }
    public void setRiskPerShare(double v) { this.riskPerShare = v; }
    public double getRiskRewardRatio() { return riskRewardRatio; }
    public void setRiskRewardRatio(double v) { this.riskRewardRatio = v; }
    public int getConfluenceScore() { return confluenceScore; }
    public void setConfluenceScore(int v) { this.confluenceScore = v; }
    public int getConfluenceThreshold() { return confluenceThreshold; }
    public void setConfluenceThreshold(int v) { this.confluenceThreshold = v; }
    public boolean isSignalFired() { return signalFired; }
    public void setSignalFired(boolean v) { this.signalFired = v; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double v) { this.confidence = v; }
    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String v) { this.algorithm = v; }
    public Map<String, Object> getReasoning() { return reasoning; }
    public void setReasoning(Map<String, Object> v) { this.reasoning = v; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final TradingSignalResult r = new TradingSignalResult();
        public Builder position(String v) { r.position = v; return this; }
        public Builder entryPoint(double v) { r.entryPoint = v; return this; }
        public Builder stopLoss(double v) { r.stopLoss = v; return this; }
        public Builder tp1(double v) { r.tp1 = v; return this; }
        public Builder tp2(double v) { r.tp2 = v; return this; }
        public Builder tp3(double v) { r.tp3 = v; return this; }
        public Builder tp4(double v) { r.tp4 = v; return this; }
        public Builder tp5(double v) { r.tp5 = v; return this; }
        public Builder breakevenStop(double v) { r.breakevenStop = v; return this; }
        public Builder riskPerShare(double v) { r.riskPerShare = v; return this; }
        public Builder riskRewardRatio(double v) { r.riskRewardRatio = v; return this; }
        public Builder confluenceScore(int v) { r.confluenceScore = v; return this; }
        public Builder confluenceThreshold(int v) { r.confluenceThreshold = v; return this; }
        public Builder signalFired(boolean v) { r.signalFired = v; return this; }
        public Builder confidence(double v) { r.confidence = v; return this; }
        public Builder algorithm(String v) { r.algorithm = v; return this; }
        public Builder reasoning(Map<String, Object> v) { r.reasoning = v; return this; }
        public TradingSignalResult build() { return r; }
    }
}
