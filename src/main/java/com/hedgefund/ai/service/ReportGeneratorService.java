package com.hedgefund.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedgefund.ai.model.HedgeFundResponse;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.agent.PortfolioDecision;
import com.hedgefund.ai.model.agent.TradingSignalResult;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * Generates a comprehensive, human-readable Markdown report from the
 * hedge fund analysis output by sending the structured data to the
 * configured LLM.
 */
@Service
public class ReportGeneratorService {

    private static final Logger log = Logger.getLogger(ReportGeneratorService.class.getName());
    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    public ReportGeneratorService(LlmService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    /**
     * Generate a full Markdown report from the hedge fund analysis results.
     *
     * @param response      the completed HedgeFundResponse (decisions + analyst signals)
     * @param tickers       tickers that were analyzed
     * @param modelProvider LLM provider to use for report generation
     * @param modelName     LLM model name to use
     * @return Markdown-formatted report string, or a fallback report if LLM fails
     */
    public String generateReport(HedgeFundResponse response, List<String> tickers,
                                  String modelProvider, String modelName) {
        log.info("Generating LLM-powered trading report for: " + tickers);

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(response, tickers);

        String llmReport = llmService.callRaw(systemPrompt, userPrompt, modelProvider, modelName);

        if (llmReport != null && !llmReport.isBlank()) {
            log.info("LLM report generated successfully (" + llmReport.length() + " chars)");
            return llmReport;
        }

        log.warning("LLM report generation failed, using fallback template");
        return buildFallbackReport(response, tickers);
    }

    private String buildSystemPrompt() {
        return """
            You are an expert financial analyst and report writer for a quantitative hedge fund.
            You produce clear, professional, and actionable trading reports in Markdown format.
            
            Your report MUST include ALL of these sections with the exact headers shown:
            
            # AI Hedge Fund — Sniper Confluence Trading Report
            
            ## Report Date & Tickers Analyzed
            
            ## Executive Summary
            A concise 2-3 sentence overview of the overall market stance, confluence score, and key actions.
            
            ## Sniper Confluence Dashboard (per ticker)
            For EACH ticker, create a sub-section with:
            - **Confluence Score**: X/7 (and whether it met the minimum threshold)
            - **Signal Fired**: Yes/No
            - **Position**: LONG or SHORT
            - **7-Factor Breakdown** as a table:
              Factor | Status | Details
              1. Price vs VWAP | PASS/FAIL | values
              2. RSI Momentum | PASS/FAIL | values
              3. MACD Trend | PASS/FAIL | values
              4. EMA Alignment | PASS/FAIL | values
              5. ADX + Direction | PASS/FAIL | values
              6. Volume Confirmation | PASS/FAIL | values
              7. Secondary TF RSI | PASS/FAIL | values
            
            ## Trading Levels (per ticker)
            Table with:
            - **Entry Point**: bar-close price
            - **Stop Loss**: ATR-based
            - **Risk (1R)**: dollar amount per share
            - **TP1 (1R)** through **TP5 (5R)**: R-multiple based targets
            - **Breakeven Stop**: entry price (activates after TP1 hit)
            - **Risk-to-Reward Ratio** at TP3
            
            ## Technical Indicators (per ticker)
            - EMA crossover status (fast/slow EMA, crossover detected, retest candle)
            - RSI (14) and secondary timeframe RSI
            - MACD, Signal, Histogram
            - ATR (14) with percentage
            - ADX with +DI / -DI
            - VWAP with distance from price
            
            ## Portfolio Decisions
            Table with Action | Quantity | Confidence | Reasoning for each ticker.
            
            ## Risk Assessment
            Risk management data — position limits, volatility, exposure.
            
            ## Analyst Consensus
            Summary table showing each analyst agent's signal and confidence per ticker.
            
            ## Conclusion & Recommendations
            Final actionable recommendation with key levels to watch.
            Include the trailing-stop-to-breakeven rule.
            
            RULES:
            - Use tables (Markdown) for all numeric data
            - Round all prices to 2 decimal places
            - Be specific — cite exact numbers from the data
            - Show PASS/FAIL for each of the 7 confluence factors
            - Highlight the confluence score prominently (e.g. "5/7 — SIGNAL FIRED")
            - Highlight any conflicting signals between analysts
            - Keep the tone professional but accessible
            - NEVER invent data — only use what is provided in the input
            """;
    }

    private String buildUserPrompt(HedgeFundResponse response, List<String> tickers) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate a comprehensive trading report from the following analysis data.\n\n");
        sb.append("Report Date: ").append(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)).append("\n");
        sb.append("Tickers Analyzed: ").append(String.join(", ", tickers)).append("\n\n");

        // Portfolio Decisions
        sb.append("=== PORTFOLIO DECISIONS ===\n");
        if (response.getDecisions() != null) {
            for (var entry : response.getDecisions().entrySet()) {
                PortfolioDecision d = entry.getValue();
                sb.append(String.format("Ticker: %s | Action: %s | Quantity: %d | Confidence: %d%% | Reasoning: %s\n",
                        entry.getKey(), d.getAction(), d.getQuantity(), d.getConfidence(), d.getReasoning()));
            }
        }

        // Analyst Signals
        sb.append("\n=== ANALYST SIGNALS ===\n");
        if (response.getAnalystSignals() != null) {
            for (var agentEntry : response.getAnalystSignals().entrySet()) {
                String agentName = agentEntry.getKey();
                sb.append("\n--- ").append(agentName).append(" ---\n");

                for (var tickerEntry : agentEntry.getValue().entrySet()) {
                    String ticker = tickerEntry.getKey();
                    AnalystSignal signal = tickerEntry.getValue();
                    sb.append(String.format("Ticker: %s | Signal: %s | Confidence: %.1f%%\n",
                            ticker, signal.getSignal(), signal.getConfidence()));

                    if (signal.getReasoning() != null) {
                        // For trading_signal agent, extract key fields explicitly
                        // so TP levels are NEVER lost to truncation
                        if ("trading_signal".equals(agentName) && signal.getReasoning() instanceof Map<?, ?> rm) {
                            appendTradingSignalDetails(sb, rm);
                        }

                        try {
                            String reasoningJson = objectMapper.writeValueAsString(signal.getReasoning());
                            if (reasoningJson.length() > 8000) {
                                reasoningJson = reasoningJson.substring(0, 8000) + "... [truncated]";
                            }
                            sb.append("Full Reasoning: ").append(reasoningJson).append("\n");
                        } catch (Exception e) {
                            sb.append("Reasoning: ").append(signal.getReasoning().toString()).append("\n");
                        }
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * Explicitly extract and append the critical trading signal fields (entry, SL, TP1-TP5)
     * so they are guaranteed to appear in the LLM prompt even if the full JSON gets truncated.
     */
    @SuppressWarnings("unchecked")
    private void appendTradingSignalDetails(StringBuilder sb, Map<?, ?> reasoning) {
        sb.append("Confluence Score: ").append(mapVal(reasoning, "confluence_score"))
          .append("/7 (threshold: ").append(mapVal(reasoning, "confluence_threshold"))
          .append(") Signal Fired: ").append(mapVal(reasoning, "signal_fired")).append("\n");

        Object tradingSig = reasoning.get("trading_signal");
        if (tradingSig instanceof TradingSignalResult ts) {
            sb.append("Position: ").append(ts.getPosition()).append("\n");
            sb.append("Entry: ").append(ts.getEntryPoint()).append("\n");
            sb.append("Stop Loss: ").append(ts.getStopLoss()).append("\n");
            sb.append("Risk (1R): ").append(ts.getRiskPerShare()).append("\n");
            sb.append("TP1 (1R): ").append(ts.getTp1()).append("\n");
            sb.append("TP2 (2R): ").append(ts.getTp2()).append("\n");
            sb.append("TP3 (3R): ").append(ts.getTp3()).append("\n");
            sb.append("TP4 (4R): ").append(ts.getTp4()).append("\n");
            sb.append("TP5 (5R): ").append(ts.getTp5()).append("\n");
            sb.append("Breakeven Stop: ").append(ts.getBreakevenStop()).append("\n");
            sb.append("R:R at TP3: ").append(ts.getRiskRewardRatio()).append("\n");
            sb.append("Algorithm: ").append(ts.getAlgorithm()).append("\n");
        } else if (tradingSig instanceof Map<?, ?> tsMap) {
            sb.append("Position: ").append(mapVal(tsMap, "position")).append("\n");
            sb.append("Entry: ").append(mapVal(tsMap, "entryPoint")).append("\n");
            sb.append("Stop Loss: ").append(mapVal(tsMap, "stopLoss")).append("\n");
            sb.append("Risk (1R): ").append(mapVal(tsMap, "riskPerShare")).append("\n");
            sb.append("TP1 (1R): ").append(mapVal(tsMap, "tp1")).append("\n");
            sb.append("TP2 (2R): ").append(mapVal(tsMap, "tp2")).append("\n");
            sb.append("TP3 (3R): ").append(mapVal(tsMap, "tp3")).append("\n");
            sb.append("TP4 (4R): ").append(mapVal(tsMap, "tp4")).append("\n");
            sb.append("TP5 (5R): ").append(mapVal(tsMap, "tp5")).append("\n");
            sb.append("Breakeven Stop: ").append(mapVal(tsMap, "breakevenStop")).append("\n");
            sb.append("R:R at TP3: ").append(mapVal(tsMap, "riskRewardRatio")).append("\n");
            sb.append("Algorithm: ").append(mapVal(tsMap, "algorithm")).append("\n");
        }

        Object levels = reasoning.get("levels");
        if (levels instanceof Map<?, ?> lv) {
            sb.append("Levels — Entry: ").append(mapVal(lv, "entry"));
            sb.append(" | SL: ").append(mapVal(lv, "stop_loss"));
            sb.append(" | TP1: ").append(mapVal(lv, "TP1_1R"));
            sb.append(" | TP2: ").append(mapVal(lv, "TP2_2R"));
            sb.append(" | TP3: ").append(mapVal(lv, "TP3_3R"));
            sb.append(" | TP4: ").append(mapVal(lv, "TP4_4R"));
            sb.append(" | TP5: ").append(mapVal(lv, "TP5_5R"));
            sb.append(" | R:R: ").append(mapVal(lv, "risk_reward_ratio")).append("\n");
        }
    }

    /**
     * Fallback report when LLM is unavailable — builds a structured report from raw data.
     */
    private String buildFallbackReport(HedgeFundResponse response, List<String> tickers) {
        StringBuilder sb = new StringBuilder();
        sb.append("# AI Hedge Fund — Sniper Confluence Trading Report\n\n");
        sb.append("## Report Date & Tickers\n");
        sb.append("- **Date**: ").append(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)).append("\n");
        sb.append("- **Tickers**: ").append(String.join(", ", tickers)).append("\n\n");

        sb.append("## Executive Summary\n");
        sb.append("*Report generated from Sniper Confluence 7-Factor analysis (LLM narrative unavailable).*\n\n");

        // Sniper Confluence Dashboard + Trading Levels
        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("trading_signal")) {
            Map<String, AnalystSignal> tradingSignals = response.getAnalystSignals().get("trading_signal");
            for (var entry : tradingSignals.entrySet()) {
                String ticker = entry.getKey();
                AnalystSignal sig = entry.getValue();

                sb.append("## Sniper Confluence Dashboard — ").append(ticker).append("\n\n");

                if (sig.getReasoning() instanceof Map<?, ?> reasoningMap) {
                    // Confluence score header
                    String scoreStr = mapVal(reasoningMap, "confluence_score");
                    String threshStr = mapVal(reasoningMap, "confluence_threshold");
                    String firedStr = mapVal(reasoningMap, "signal_fired");
                    sb.append("**Confluence Score: ").append(scoreStr).append("/7");
                    sb.append(" (Threshold: ").append(threshStr).append("/7)");
                    sb.append(" — Signal Fired: ").append("true".equals(firedStr) ? "YES" : "NO").append("**\n\n");

                    // 7-Factor table
                    Object factors = reasoningMap.get("confluence_factors");
                    if (factors instanceof Map<?, ?> factorsMap) {
                        sb.append("| # | Factor | Status | Details |\n");
                        sb.append("|---|--------|--------|--------|\n");
                        String[][] factorNames = {
                                {"1_price_vs_vwap", "Price vs VWAP"},
                                {"2_rsi_momentum", "RSI Momentum"},
                                {"3_macd_trend", "MACD Trend"},
                                {"4_ema_alignment", "EMA Alignment"},
                                {"5_adx_direction", "ADX + Direction"},
                                {"6_volume_confirmation", "Volume Confirmation"},
                                {"7_secondary_tf_rsi", "Secondary TF RSI"}
                        };
                        for (int i = 0; i < factorNames.length; i++) {
                            Object fObj = factorsMap.get(factorNames[i][0]);
                            String pass = "N/A";
                            String details = "";
                            if (fObj instanceof Map<?, ?> fMap) {
                                pass = "true".equals(String.valueOf(fMap.get("pass"))) ? "PASS" : "FAIL";
                                details = fMap.toString().replaceAll("[{}]", "");
                                if (details.length() > 80) details = details.substring(0, 80) + "...";
                            }
                            sb.append(String.format("| %d | %s | %s | %s |\n",
                                    i + 1, factorNames[i][1], pass, details));
                        }
                        sb.append("\n");
                    }

                    // Trading levels — handle both TradingSignalResult (object) and Map (serialized)
                    Object tradingSig = reasoningMap.get("trading_signal");
                    if (tradingSig instanceof TradingSignalResult ts) {
                        sb.append("### Trading Levels\n\n");
                        sb.append("| Field | Value |\n|---|---|\n");
                        sb.append("| Position | ").append(ts.getPosition()).append(" |\n");
                        sb.append("| Entry | ").append(ts.getEntryPoint()).append(" |\n");
                        sb.append("| Stop Loss | ").append(ts.getStopLoss()).append(" |\n");
                        sb.append("| Risk (1R) | ").append(ts.getRiskPerShare()).append(" |\n");
                        sb.append("| TP1 (1R) | ").append(ts.getTp1()).append(" |\n");
                        sb.append("| TP2 (2R) | ").append(ts.getTp2()).append(" |\n");
                        sb.append("| TP3 (3R) | ").append(ts.getTp3()).append(" |\n");
                        sb.append("| TP4 (4R) | ").append(ts.getTp4()).append(" |\n");
                        sb.append("| TP5 (5R) | ").append(ts.getTp5()).append(" |\n");
                        sb.append("| Breakeven Stop | ").append(ts.getBreakevenStop()).append(" |\n");
                        sb.append("| R:R at TP3 | ").append(ts.getRiskRewardRatio()).append(" |\n");
                        sb.append("| Confluence | ").append(ts.getConfluenceScore()).append("/7 |\n");
                        sb.append("| Signal Fired | ").append(ts.isSignalFired() ? "YES" : "NO").append(" |\n");
                        sb.append("| Algorithm | ").append(ts.getAlgorithm()).append(" |\n");
                        sb.append("| Confidence | ").append(String.format("%.0f%%", ts.getConfidence())).append(" |\n");
                    } else if (tradingSig instanceof Map<?, ?> tsMap) {
                        sb.append("### Trading Levels\n\n");
                        sb.append("| Field | Value |\n|---|---|\n");
                        sb.append("| Position | ").append(mapVal(tsMap, "position")).append(" |\n");
                        sb.append("| Entry | ").append(mapVal(tsMap, "entryPoint")).append(" |\n");
                        sb.append("| Stop Loss | ").append(mapVal(tsMap, "stopLoss")).append(" |\n");
                        sb.append("| Risk (1R) | ").append(mapVal(tsMap, "riskPerShare")).append(" |\n");
                        sb.append("| TP1 (1R) | ").append(mapVal(tsMap, "tp1")).append(" |\n");
                        sb.append("| TP2 (2R) | ").append(mapVal(tsMap, "tp2")).append(" |\n");
                        sb.append("| TP3 (3R) | ").append(mapVal(tsMap, "tp3")).append(" |\n");
                        sb.append("| TP4 (4R) | ").append(mapVal(tsMap, "tp4")).append(" |\n");
                        sb.append("| TP5 (5R) | ").append(mapVal(tsMap, "tp5")).append(" |\n");
                        sb.append("| Breakeven Stop | ").append(mapVal(tsMap, "breakevenStop")).append(" |\n");
                        sb.append("| R:R at TP3 | ").append(mapVal(tsMap, "riskRewardRatio")).append(" |\n");
                        sb.append("| Confluence | ").append(mapVal(tsMap, "confluenceScore")).append("/7 |\n");
                        sb.append("| Algorithm | ").append(mapVal(tsMap, "algorithm")).append(" |\n");
                        sb.append("| Confidence | ").append(String.format("%.0f%%", sig.getConfidence())).append(" |\n");
                    }

                    // Risk management
                    Object riskMgmt = reasoningMap.get("risk_management");
                    if (riskMgmt instanceof Map<?, ?> rmMap) {
                        sb.append("\n> **Trailing Stop Rule**: ").append(mapVal(rmMap, "trailing_stop_rule")).append("\n");
                    }
                }
                sb.append("\n");
            }
        }

        // Portfolio Decisions
        sb.append("## Portfolio Decisions\n\n");
        sb.append("| Ticker | Action | Quantity | Confidence | Reasoning |\n");
        sb.append("|--------|--------|----------|------------|----------|\n");
        if (response.getDecisions() != null) {
            for (var entry : response.getDecisions().entrySet()) {
                PortfolioDecision d = entry.getValue();
                sb.append(String.format("| %s | %s | %d | %d%% | %s |\n",
                        entry.getKey(), d.getAction(), d.getQuantity(), d.getConfidence(), d.getReasoning()));
            }
        }

        // Analyst Consensus
        sb.append("\n## Analyst Consensus\n\n");
        sb.append("| Analyst | ");
        for (String t : tickers) sb.append(t).append(" Signal | ").append(t).append(" Confidence | ");
        sb.append("\n|---------|");
        for (String ignored : tickers) sb.append("-----------|-------------|");
        sb.append("\n");

        if (response.getAnalystSignals() != null) {
            for (var agentEntry : response.getAnalystSignals().entrySet()) {
                sb.append("| ").append(agentEntry.getKey()).append(" | ");
                for (String t : tickers) {
                    AnalystSignal sig = agentEntry.getValue().get(t);
                    if (sig != null) {
                        sb.append(sig.getSignal()).append(" | ");
                        sb.append(String.format("%.0f%% | ", sig.getConfidence()));
                    } else {
                        sb.append("N/A | N/A | ");
                    }
                }
                sb.append("\n");
            }
        }

        sb.append("\n---\n*Report generated by AI Hedge Fund — Sniper Confluence 7-Factor Engine*\n");
        return sb.toString();
    }

    /** Safely get a value from a Map<?,?> with a default of "N/A". */
    private static String mapVal(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "N/A";
    }
}

