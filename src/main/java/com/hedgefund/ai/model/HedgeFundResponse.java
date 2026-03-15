package com.hedgefund.ai.model;

import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.agent.PortfolioDecision;
import java.util.Map;

public class HedgeFundResponse {
    private Map<String, PortfolioDecision> decisions;
    private Map<String, Map<String, AnalystSignal>> analystSignals;
    /** LLM-generated Markdown trading report */
    private String report;

    public HedgeFundResponse() {}

    public HedgeFundResponse(Map<String, PortfolioDecision> decisions,
                             Map<String, Map<String, AnalystSignal>> analystSignals,
                             String report) {
        this.decisions = decisions;
        this.analystSignals = analystSignals;
        this.report = report;
    }

    public Map<String, PortfolioDecision> getDecisions() { return decisions; }
    public void setDecisions(Map<String, PortfolioDecision> v) { this.decisions = v; }
    public Map<String, Map<String, AnalystSignal>> getAnalystSignals() { return analystSignals; }
    public void setAnalystSignals(Map<String, Map<String, AnalystSignal>> v) { this.analystSignals = v; }
    public String getReport() { return report; }
    public void setReport(String v) { this.report = v; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Map<String, PortfolioDecision> decisions;
        private Map<String, Map<String, AnalystSignal>> analystSignals;
        private String report;
        public Builder decisions(Map<String, PortfolioDecision> v) { this.decisions = v; return this; }
        public Builder analystSignals(Map<String, Map<String, AnalystSignal>> v) { this.analystSignals = v; return this; }
        public Builder report(String v) { this.report = v; return this; }
        public HedgeFundResponse build() { return new HedgeFundResponse(decisions, analystSignals, report); }
    }
}
