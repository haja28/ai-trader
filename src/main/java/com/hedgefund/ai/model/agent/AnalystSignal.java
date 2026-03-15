package com.hedgefund.ai.model.agent;

public class AnalystSignal {
    private String signal;
    private double confidence;
    private Object reasoning;

    public AnalystSignal() {}

    public AnalystSignal(String signal, double confidence, Object reasoning) {
        this.signal = signal;
        this.confidence = confidence;
        this.reasoning = reasoning;
    }

    public String getSignal() { return signal; }
    public void setSignal(String v) { this.signal = v; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double v) { this.confidence = v; }
    public Object getReasoning() { return reasoning; }
    public void setReasoning(Object v) { this.reasoning = v; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String signal;
        private double confidence;
        private Object reasoning;
        public Builder signal(String v) { this.signal = v; return this; }
        public Builder confidence(double v) { this.confidence = v; return this; }
        public Builder reasoning(Object v) { this.reasoning = v; return this; }
        public AnalystSignal build() { return new AnalystSignal(signal, confidence, reasoning); }
    }
}
