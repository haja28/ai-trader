package com.hedgefund.ai.model.agent;

import java.util.*;

/**
 * The shared state that flows through the agent pipeline.
 * Equivalent to the Python AgentState TypedDict.
 */
public class AgentState {
    /** Tickers being analyzed */
    private List<String> tickers = new ArrayList<>();

    /** Start date for data window */
    private String startDate;
    /** End date for data window */
    private String endDate;

    /** Portfolio state */
    private Map<String, Object> portfolio = new HashMap<>();

    /**
     * Analyst signals: agentName -> ticker -> AnalystSignal.
     * Populated as each analyst agent runs.
     */
    private Map<String, Map<String, AnalystSignal>> analystSignals = new HashMap<>();

    /** Current prices discovered during risk management */
    private Map<String, Double> currentPrices = new HashMap<>();

    /** Final trading decisions: ticker -> PortfolioDecision */
    private Map<String, PortfolioDecision> decisions = new HashMap<>();

    // --- Metadata / config ---
    private boolean showReasoning;
    private String modelName;
    private String modelProvider;

    public AgentState() {}

    public List<String> getTickers() { return tickers; }
    public void setTickers(List<String> v) { this.tickers = v; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String v) { this.startDate = v; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String v) { this.endDate = v; }
    public Map<String, Object> getPortfolio() { return portfolio; }
    public void setPortfolio(Map<String, Object> v) { this.portfolio = v; }
    public Map<String, Map<String, AnalystSignal>> getAnalystSignals() { return analystSignals; }
    public void setAnalystSignals(Map<String, Map<String, AnalystSignal>> v) { this.analystSignals = v; }
    public Map<String, Double> getCurrentPrices() { return currentPrices; }
    public void setCurrentPrices(Map<String, Double> v) { this.currentPrices = v; }
    public Map<String, PortfolioDecision> getDecisions() { return decisions; }
    public void setDecisions(Map<String, PortfolioDecision> v) { this.decisions = v; }
    public boolean isShowReasoning() { return showReasoning; }
    public void setShowReasoning(boolean v) { this.showReasoning = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { this.modelName = v; }
    public String getModelProvider() { return modelProvider; }
    public void setModelProvider(String v) { this.modelProvider = v; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final AgentState s = new AgentState();
        public Builder tickers(List<String> v) { s.tickers = v; return this; }
        public Builder startDate(String v) { s.startDate = v; return this; }
        public Builder endDate(String v) { s.endDate = v; return this; }
        public Builder portfolio(Map<String, Object> v) { s.portfolio = v; return this; }
        public Builder showReasoning(boolean v) { s.showReasoning = v; return this; }
        public Builder modelName(String v) { s.modelName = v; return this; }
        public Builder modelProvider(String v) { s.modelProvider = v; return this; }
        public AgentState build() { return s; }
    }
}
