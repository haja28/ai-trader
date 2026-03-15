package com.hedgefund.ai.service.agents;

import com.hedgefund.ai.model.agent.AgentState;
import com.hedgefund.ai.model.agent.AnalystSignal;

import java.util.Map;

/**
 * Interface for all analyst agents.
 * Each agent analyzes tickers and produces signals per ticker.
 */
public interface AnalystAgent {

    /**
     * Unique key identifying this agent (e.g. "fundamentals_analyst").
     */
    String getAgentId();

    /**
     * Human-readable display name.
     */
    String getDisplayName();

    /**
     * Short description of the analyst's style.
     */
    String getDescription();

    /**
     * Run analysis on all tickers in the state.
     *
     * @param state shared pipeline state
     * @return ticker -> AnalystSignal map
     */
    Map<String, AnalystSignal> analyze(AgentState state);
}

