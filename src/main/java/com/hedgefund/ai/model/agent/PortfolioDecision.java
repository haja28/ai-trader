package com.hedgefund.ai.model.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PortfolioDecision {
    private String action;
    private int quantity;
    private int confidence;
    private String reasoning;

    public PortfolioDecision() {}

    public PortfolioDecision(String action, int quantity, int confidence, String reasoning) {
        this.action = action;
        this.quantity = quantity;
        this.confidence = confidence;
        this.reasoning = reasoning;
    }

    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int v) { this.quantity = v; }
    public int getConfidence() { return confidence; }
    public void setConfidence(int v) { this.confidence = v; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String v) { this.reasoning = v; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String action;
        private int quantity;
        private int confidence;
        private String reasoning;
        public Builder action(String v) { this.action = v; return this; }
        public Builder quantity(int v) { this.quantity = v; return this; }
        public Builder confidence(int v) { this.confidence = v; return this; }
        public Builder reasoning(String v) { this.reasoning = v; return this; }
        public PortfolioDecision build() { return new PortfolioDecision(action, quantity, confidence, reasoning); }
    }
}
