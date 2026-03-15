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

        String llmReport = llmService.callRaw(systemPrompt, userPrompt, modelProvider, modelName, 4000);

        if (llmReport != null && !llmReport.isBlank()) {
            log.info("LLM report generated successfully (" + llmReport.length() + " chars)");
            return llmReport;
        }

        log.warning("LLM report generation failed, using fallback template");
        return buildFallbackReport(response, tickers);
    }

    private String buildSystemPrompt() {
        return """
            You are an expert financial analyst for a quantitative hedge fund.
            Generate a concise, professional Markdown trading report with these EXACT sections:

            # Genisys Lab Analysis — Trading Report
            ## Report Date & Tickers Analyzed
            ## Executive Summary
            2-3 sentences: market stance, signal direction, confidence, portfolio action, key prices.

            ## Trading Signals (per ticker)
            CRITICAL: Create ONE SUBSECTION PER TICKER. Each ticker MUST have:
            ### TICKER
            Two-column table (Parameter | Value): Position, Entry Point, Stop Loss, Risk-to-Reward Ratio, Confidence, Algorithm Used.
            
            Immediately after, a SEPARATE two-column table (Take-Profit Level | Price):
            | **Take-Profit Level** | **Price** |
            |-----------------------|-----------|
            | TP1                   | X.XX      |
            | TP2                   | X.XX      |
            | TP3                   | X.XX      |
            | TP4                   | X.XX      |
            | TP5                   | X.XX      |

            Then use --- before next ticker's ### subsection. NEVER combine tickers into one table.

            ## Technical Analysis Breakdown (per ticker)
            ### TICKER Technical Indicators. ONE three-column table (Metric | Value | Interpretation) with bold category rows (empty value/interp): **Trend Analysis** (EMA values, MACD, MACD Histogram), **Momentum** (RSI 14), **Volatility** (ATR 14 with % of price), **VWAP Analysis** (VWAP with distance %), **Directional Movement** (ADX, +DI, -DI).

            ## Portfolio Decisions
            Table: Action | Quantity | Confidence | Reasoning

            ## Risk Assessment
            Two-column table: Metric | Value (position limit, exposure, remaining limit, annualized volatility). Then a **Key Risk Note:** paragraph.

            ## Analyst Consensus
            Table: Ticker | Analyst Agent | Signal | Confidence. Then > **Conflict Highlight:** blockquote.

            ## Conclusion & Recommendations
            Bold **Recommendation:** then **Key Levels to Watch:** bullet list with prices. End with italic monitoring note.

            RULES: Use --- between sections. Round prices to 2 decimals. Bold table labels. Extract all values from CRITICAL TRADING LEVELS section at top of user prompt. Never invent data. Be concise.
            """;
    }

    private String buildUserPrompt(HedgeFundResponse response, List<String> tickers) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate a comprehensive trading report from the following analysis data.\n\n");
        sb.append("Report Date: ").append(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)).append("\n");
        sb.append("Tickers Analyzed: ").append(String.join(", ", tickers)).append("\n\n");

        // ═══ CRITICAL TRADING LEVELS (extracted upfront for multi-ticker reliability) ═══
        sb.append("=== CRITICAL TRADING LEVELS (Priority Data - Use These Exact Values in Report) ===\n\n");
        sb.append("IMPORTANT: Each ticker below MUST have its OWN ### subsection in the Trading Signals section.\n");
        sb.append("Extract Position, Entry, Stop Loss, R:R, Confidence, Algorithm from the first table below.\n");
        sb.append("Extract TP1-TP5 from the Take-Profit table below. DO NOT merge tickers.\n\n");

        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("trading_signal")) {
            Map<String, AnalystSignal> tradingSignals = response.getAnalystSignals().get("trading_signal");
            for (var tickerEntry : tradingSignals.entrySet()) {
                String ticker = tickerEntry.getKey();
                AnalystSignal signal = tickerEntry.getValue();
                sb.append("━━━ ").append(ticker).append(" ━━━\n");
                if (signal.getReasoning() instanceof Map<?, ?> rm) {
                    appendTradingSignalDetails(sb, rm);
                }
                sb.append("\n");
            }
        }

        // Portfolio Decisions
        sb.append("=== PORTFOLIO DECISIONS ===\n");
        if (response.getDecisions() != null) {
            for (var entry : response.getDecisions().entrySet()) {
                PortfolioDecision d = entry.getValue();
                sb.append(String.format("Ticker: %s | Action: %s | Quantity: %d | Confidence: %d%% | Reasoning: %s\n",
                        entry.getKey(), d.getAction(), d.getQuantity(), d.getConfidence(), d.getReasoning()));
            }
        }

        // Analyst Signals (condensed for multi-ticker)
        int tickerCount = tickers.size();
        int perTickerBudget = tickerCount > 1 ? Math.max(800, 2000 / tickerCount) : 3000;

        sb.append("\n=== ANALYST SIGNALS (Supporting Context) ===\n");
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
                        try {
                            // For trading_signal: compact JSON with only essential context
                            String reasoningJson;
                            if ("trading_signal".equals(agentName) && signal.getReasoning() instanceof Map<?, ?> rm) {
                                Map<String, Object> compactReasoning = buildCompactReasoning(rm);
                                reasoningJson = objectMapper.writeValueAsString(compactReasoning);
                            } else {
                                reasoningJson = objectMapper.writeValueAsString(signal.getReasoning());
                            }

                            if (reasoningJson.length() > perTickerBudget) {
                                reasoningJson = reasoningJson.substring(0, perTickerBudget) + "... [truncated]";
                            }
                            sb.append("Context: ").append(reasoningJson).append("\n");
                        } catch (Exception e) {
                            sb.append("Context: ").append(signal.getReasoning().toString()).append("\n");
                        }
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * Build a compact reasoning map for trading_signal agent that excludes
     * verbose nested factor details to save prompt budget in multi-ticker reports.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildCompactReasoning(Map<?, ?> fullReasoning) {
        Map<String, Object> compact = new LinkedHashMap<>();

        // Keep only essential summary fields
        compact.put("ticker", fullReasoning.get("ticker"));
        compact.put("current_price", fullReasoning.get("current_price"));
        compact.put("confluence_score", fullReasoning.get("confluence_score"));
        compact.put("weighted_score", fullReasoning.get("weighted_score"));
        compact.put("weighted_threshold", fullReasoning.get("weighted_threshold"));
        compact.put("signal_fired", fullReasoning.get("signal_fired"));
        compact.put("hard_gates", fullReasoning.get("hard_gates"));

        // Keep indicators summary (already compact)
        compact.put("indicators", fullReasoning.get("indicators"));

        // Keep EMA crossover info (compact)
        compact.put("ema_crossover", fullReasoning.get("ema_crossover"));

        // Exclude verbose "confluence_factors" map to save space
        // (The critical TP levels are already extracted separately at top of prompt)

        // Keep risk management and levels
        compact.put("risk_management", fullReasoning.get("risk_management"));
        compact.put("levels", fullReasoning.get("levels"));

        // Keep trading_signal result object
        compact.put("trading_signal", fullReasoning.get("trading_signal"));

        return compact;
    }

    /**
     * Explicitly extract and append the critical trading signal fields (entry, SL, TP1-TP5)
     * in a pre-formatted table structure that the LLM can directly copy into the report.
     * This guarantees TP levels appear for every ticker.
     */
    @SuppressWarnings("unchecked")
    private void appendTradingSignalDetails(StringBuilder sb, Map<?, ?> reasoning) {
        Object tradingSig = reasoning.get("trading_signal");

        String position = "";
        String entry = "";
        String stopLoss = "";
        String rrr = "";
        String confidence = "";
        String algorithm = "";
        String tp1 = "", tp2 = "", tp3 = "", tp4 = "", tp5 = "";

        if (tradingSig instanceof TradingSignalResult ts) {
            position = ts.getPosition();
            entry = String.valueOf(ts.getEntryPoint());
            stopLoss = String.valueOf(ts.getStopLoss());
            rrr = String.valueOf(ts.getRiskRewardRatio());
            confidence = String.format("%.0f%%", ts.getConfidence());
            algorithm = ts.getAlgorithm();
            tp1 = String.valueOf(ts.getTp1());
            tp2 = String.valueOf(ts.getTp2());
            tp3 = String.valueOf(ts.getTp3());
            tp4 = String.valueOf(ts.getTp4());
            tp5 = String.valueOf(ts.getTp5());
        } else if (tradingSig instanceof Map<?, ?> tsMap) {
            position = mapVal(tsMap, "position");
            entry = mapVal(tsMap, "entryPoint");
            stopLoss = mapVal(tsMap, "stopLoss");
            rrr = mapVal(tsMap, "riskRewardRatio");
            confidence = String.format("%.0f%%", Double.parseDouble(mapVal(tsMap, "confidence")));
            algorithm = mapVal(tsMap, "algorithm");
            tp1 = mapVal(tsMap, "tp1");
            tp2 = mapVal(tsMap, "tp2");
            tp3 = mapVal(tsMap, "tp3");
            tp4 = mapVal(tsMap, "tp4");
            tp5 = mapVal(tsMap, "tp5");
        }

        // Pre-formatted main signal table
        sb.append("| **Parameter**            | **Value**                     |\n");
        sb.append("|--------------------------|-------------------------------|\n");
        sb.append("| Position                 | ").append(position).append("                          |\n");
        sb.append("| Entry Point              | ").append(entry).append("                        |\n");
        sb.append("| Stop Loss                | ").append(stopLoss).append("                        |\n");
        sb.append("| Risk-to-Reward Ratio     | ").append(rrr).append("                             |\n");
        sb.append("| Confidence               | ").append(confidence).append("                         |\n");
        sb.append("| Algorithm Used           | ").append(algorithm).append(" |\n");
        sb.append("\n");

        // Pre-formatted TP table
        sb.append("| **Take-Profit Level** | **Price** |\n");
        sb.append("|-----------------------|-----------|\n");
        sb.append("| TP1                   | ").append(tp1).append("      |\n");
        sb.append("| TP2                   | ").append(tp2).append("      |\n");
        sb.append("| TP3                   | ").append(tp3).append("      |\n");
        sb.append("| TP4                   | ").append(tp4).append("      |\n");
        sb.append("| TP5                   | ").append(tp5).append("      |\n");
    }

    /**
     * Fallback report when LLM is unavailable — builds a structured report from raw data.
     */
    private String buildFallbackReport(HedgeFundResponse response, List<String> tickers) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Genisys Lab Analysis — Trading Report\n\n");
        sb.append("## Report Date & Tickers Analyzed\n");
        sb.append("**Date:** ").append(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)).append("  \n");
        sb.append("**Tickers Analyzed:** ").append(String.join(", ", tickers)).append("  \n\n");

        sb.append("---\n\n");

        sb.append("## Executive Summary\n");
        sb.append("*Report generated from Sniper Confluence 7-Factor analysis (LLM narrative unavailable).*\n\n");

        sb.append("---\n\n");

        // ── Trading Signals ──
        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("trading_signal")) {
            sb.append("## Trading Signals (per ticker)\n\n");
            Map<String, AnalystSignal> tradingSignals = response.getAnalystSignals().get("trading_signal");

            for (var entry : tradingSignals.entrySet()) {
                String ticker = entry.getKey();
                AnalystSignal sig = entry.getValue();

                sb.append("### ").append(ticker).append("\n");

                if (sig.getReasoning() instanceof Map<?, ?> reasoningMap) {
                    Object tradingSig = reasoningMap.get("trading_signal");

                    // Main signal table (two-column: Parameter | Value)
                    sb.append("| **Parameter**               | **Value**   |\n");
                    sb.append("|-----------------------------|-------------|\n");

                    if (tradingSig instanceof TradingSignalResult ts) {
                        sb.append("| **Position**                | ").append(ts.getPosition()).append("       |\n");
                        sb.append("| **Entry Point**             | ").append(ts.getEntryPoint()).append("      |\n");
                        sb.append("| **Stop Loss**               | ").append(ts.getStopLoss()).append("      |\n");
                        sb.append("| **Risk-to-Reward Ratio**    | ").append(ts.getRiskRewardRatio()).append("        |\n");
                        sb.append("| **Confidence**              | ").append(String.format("%.1f%%", ts.getConfidence())).append("       |\n");
                        sb.append("| **Algorithm Used**          | ").append(ts.getAlgorithm()).append(" |\n");
                    } else if (tradingSig instanceof Map<?, ?> tsMap) {
                        sb.append("| **Position**                | ").append(mapVal(tsMap, "position")).append("       |\n");
                        sb.append("| **Entry Point**             | ").append(mapVal(tsMap, "entryPoint")).append("      |\n");
                        sb.append("| **Stop Loss**               | ").append(mapVal(tsMap, "stopLoss")).append("      |\n");
                        sb.append("| **Risk-to-Reward Ratio**    | ").append(mapVal(tsMap, "riskRewardRatio")).append("        |\n");
                        sb.append("| **Confidence**              | ").append(String.format("%.1f%%", sig.getConfidence())).append("       |\n");
                        sb.append("| **Algorithm Used**          | ").append(mapVal(tsMap, "algorithm")).append(" |\n");
                    }
                    sb.append("\n");

                    // Take-profit table (two-column: Level | Price)
                    sb.append("| **Take-Profit Level** | **Price** |\n");
                    sb.append("|----------------------|-----------|\n");
                    if (tradingSig instanceof TradingSignalResult ts) {
                        sb.append("| TP1                  | ").append(ts.getTp1()).append("    |\n");
                        sb.append("| TP2                  | ").append(ts.getTp2()).append("    |\n");
                        sb.append("| TP3                  | ").append(ts.getTp3()).append("    |\n");
                        sb.append("| TP4                  | ").append(ts.getTp4()).append("    |\n");
                        sb.append("| TP5                  | ").append(ts.getTp5()).append("    |\n");
                    } else if (tradingSig instanceof Map<?, ?> tsMap) {
                        sb.append("| TP1                  | ").append(mapVal(tsMap, "tp1")).append("    |\n");
                        sb.append("| TP2                  | ").append(mapVal(tsMap, "tp2")).append("    |\n");
                        sb.append("| TP3                  | ").append(mapVal(tsMap, "tp3")).append("    |\n");
                        sb.append("| TP4                  | ").append(mapVal(tsMap, "tp4")).append("    |\n");
                        sb.append("| TP5                  | ").append(mapVal(tsMap, "tp5")).append("    |\n");
                    }
                    sb.append("\n");
                }
                sb.append("---\n\n");
            }
        }

        // ── Technical Analysis Breakdown ──
        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("trading_signal")) {
            sb.append("## Technical Analysis Breakdown (per ticker)\n\n");
            Map<String, AnalystSignal> tradingSignals = response.getAnalystSignals().get("trading_signal");

            for (var entry : tradingSignals.entrySet()) {
                String ticker = entry.getKey();
                AnalystSignal sig = entry.getValue();

                sb.append("### ").append(ticker).append(" Technical Indicators\n");
                sb.append("| **Metric**               | **Value**       | **Interpretation**                     |\n");
                sb.append("|--------------------------|-----------------|----------------------------------------|\n");

                if (sig.getReasoning() instanceof Map<?, ?> reasoningMap) {
                    Object indicators = reasoningMap.get("indicators");
                    Object emaCross = reasoningMap.get("ema_crossover");
                    Object tradingSig = reasoningMap.get("trading_signal");

                    // Trend Analysis category
                    sb.append("| **Trend Analysis**       |                 |                                        |\n");
                    if (emaCross instanceof Map<?, ?> emaMap) {
                        String fastEma = mapVal(emaMap, "fast_ema");
                        String slowEma = mapVal(emaMap, "slow_ema");
                        sb.append("| EMA").append(mapVal(emaMap, "fast_ema_period")).append("                     | ").append(fastEma).append("          | Fast EMA                  |\n");
                        sb.append("| EMA").append(mapVal(emaMap, "slow_ema_period")).append("                    | ").append(slowEma).append("          | Slow EMA                  |\n");
                        String crossStatus = "true".equals(String.valueOf(emaMap.get("bullish_crossover"))) ? "Bullish crossover" :
                                "true".equals(String.valueOf(emaMap.get("bearish_crossover"))) ? "Bearish crossover" : "No crossover";
                        sb.append("| EMA Crossover            | ").append(crossStatus).append(" |                       |\n");
                    }
                    if (indicators instanceof Map<?, ?> ind) {
                        sb.append("| MACD                     | ").append(mapVal(ind, "macd")).append("           | |\n");
                        sb.append("| MACD Histogram           | ").append(mapVal(ind, "macd_histogram")).append("           | |\n");

                        // Momentum category
                        sb.append("| **Momentum**             |                 |                                        |\n");
                        sb.append("| RSI (14)                 | ").append(mapVal(ind, "rsi_14")).append("           | |\n");
                        // Secondary RSI — find the key dynamically
                        for (var indEntry : ((Map<?, ?>) ind).entrySet()) {
                            String key = String.valueOf(indEntry.getKey());
                            if (key.startsWith("rsi_secondary_")) {
                                sb.append("| RSI (Secondary TF)       | ").append(indEntry.getValue()).append("           | |\n");
                            }
                        }

                        // Volatility category
                        sb.append("| **Volatility**           |                 |                                        |\n");
                        String atrVal = mapVal(ind, "atr_14");
                        String atrPct = mapVal(ind, "atr_pct");
                        sb.append("| ATR (14)                 | ").append(atrVal).append("            | ").append(atrPct).append("% of price              |\n");

                        // VWAP Analysis category
                        sb.append("| **VWAP Analysis**        |                 |                                        |\n");
                        sb.append("| VWAP                     | ").append(mapVal(ind, "vwap")).append("          | |\n");

                        // ADX category
                        sb.append("| **Directional Movement** |                 |                                        |\n");
                        sb.append("| ADX                      | ").append(mapVal(ind, "adx")).append("           | |\n");
                        sb.append("| +DI                      | ").append(mapVal(ind, "plus_di")).append("           | |\n");
                        sb.append("| -DI                      | ").append(mapVal(ind, "minus_di")).append("           | |\n");
                    }
                }
                sb.append("\n---\n\n");
            }
        }

        // ── Portfolio Decisions ──
        sb.append("## Portfolio Decisions\n\n");
        sb.append("| **Action** | **Quantity** | **Confidence** | **Reasoning**                                      |\n");
        sb.append("|------------|--------------|----------------|----------------------------------------------------|\n");
        if (response.getDecisions() != null) {
            for (var entry : response.getDecisions().entrySet()) {
                PortfolioDecision d = entry.getValue();
                sb.append(String.format("| %s       | %d            | %d%%           | %s |\n",
                        d.getAction(), d.getQuantity(), d.getConfidence(), d.getReasoning()));
            }
        }

        sb.append("\n---\n\n");

        // ── Risk Assessment ──
        sb.append("## Risk Assessment\n");
        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("risk_management_agent")) {
            Map<String, AnalystSignal> riskSignals = response.getAnalystSignals().get("risk_management_agent");
            for (var entry : riskSignals.entrySet()) {
                AnalystSignal riskSig = entry.getValue();
                if (riskSig.getReasoning() instanceof Map<?, ?> riskMap) {
                    Object reasoningObj = riskMap.get("reasoning");
                    if (reasoningObj instanceof Map<?, ?> reasoning) {
                        sb.append("| **Metric**                     | **Value**       |\n");
                        sb.append("|--------------------------------|-----------------|\n");
                        String limitPct = mapVal(reasoning, "position_limit_pct");
                        String posLimit = mapVal(reasoning, "position_limit");
                        String exposure = mapVal(reasoning, "current_exposure");
                        String remaining = mapVal(reasoning, "remaining_limit");
                        String vol = mapVal(reasoning, "annualized_volatility");

                        try { posLimit = String.format("$%,.2f", Double.parseDouble(posLimit)); } catch (Exception ignored) {}
                        try { exposure = String.format("$%,.2f", Double.parseDouble(exposure)); } catch (Exception ignored) {}
                        try { remaining = String.format("$%,.2f", Double.parseDouble(remaining)); } catch (Exception ignored) {}
                        try { vol = String.format("%.2f%%", Double.parseDouble(vol) * 100); } catch (Exception ignored) {}

                        sb.append("| Position Limit (").append(String.format("%.0f%%", Double.parseDouble(limitPct) * 100)).append(" of portfolio) | ").append(posLimit).append("      |\n");
                        sb.append("| Current Exposure               | ").append(exposure).append("           |\n");
                        sb.append("| Remaining Position Limit       | ").append(remaining).append("      |\n");
                        sb.append("| Annualized Volatility          | ").append(vol).append("          |\n");

                        sb.append("| **Key Risk Note:** Current exposure status determines available position limit. Volatility (").append(vol).append(") is within acceptable range for short-term trading.\n");
                    }
                }
            }
        }

        sb.append("\n---\n\n");

        // ── Analyst Consensus ──
        sb.append("## Analyst Consensus\n\n");
        sb.append("| **Ticker** | **Analyst Agent**          | **Signal** | **Confidence** |\n");
        sb.append("|------------|----------------------------|------------|----------------|\n");
        if (response.getAnalystSignals() != null) {
            for (var agentEntry : response.getAnalystSignals().entrySet()) {
                String agentName = agentEntry.getKey();
                for (var tickerEntry : agentEntry.getValue().entrySet()) {
                    String ticker = tickerEntry.getKey();
                    AnalystSignal sig = tickerEntry.getValue();
                    if (sig != null) {
                        sb.append(String.format("| %s       | %s             | %s    | %.1f%%          |\n",
                                ticker, agentName, sig.getSignal(), sig.getConfidence()));
                    }
                }
            }
        }

        sb.append("\n---\n\n");

        // ── Conclusion & Recommendations ──
        sb.append("## Conclusion & Recommendations\n");
        sb.append("**Recommendation:** Review the analysis and trading levels above before entering positions.  \n\n");
        sb.append("**Key Levels to Watch:**  \n");
        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("trading_signal")) {
            Map<String, AnalystSignal> tradingSignals = response.getAnalystSignals().get("trading_signal");
            for (var entry : tradingSignals.entrySet()) {
                AnalystSignal sig = entry.getValue();
                if (sig.getReasoning() instanceof Map<?, ?> reasoningMap) {
                    Object tradingSigObj = reasoningMap.get("trading_signal");
                    if (tradingSigObj instanceof TradingSignalResult ts) {
                        sb.append("- **Stop Loss**: **").append(ts.getStopLoss()).append("** — Reassess if breached.  \n");
                        sb.append("- **TP1**: **").append(ts.getTp1()).append("** — First target.  \n");
                        sb.append("- **TP3**: **").append(ts.getTp3()).append("** — Primary target (3R).  \n");
                    } else if (tradingSigObj instanceof Map<?, ?> tsMap) {
                        sb.append("- **Stop Loss**: **").append(mapVal(tsMap, "stopLoss")).append("** — Reassess if breached.  \n");
                        sb.append("- **TP1**: **").append(mapVal(tsMap, "tp1")).append("** — First target.  \n");
                        sb.append("- **TP3**: **").append(mapVal(tsMap, "tp3")).append("** — Primary target (3R).  \n");
                    }
                    Object indicators = reasoningMap.get("indicators");
                    if (indicators instanceof Map<?, ?> ind) {
                        sb.append("- **VWAP**: **").append(mapVal(ind, "vwap")).append("** — Break above signals bullish shift; break below reinforces bearish structure.  \n");
                    }
                }
            }
        }
        sb.append("\n*Monitor price action relative to VWAP and support levels for next actionable signal.*\n");

        return sb.toString();
    }

    /** Safely get a value from a Map<?,?> with a default of "N/A". */
    private static String mapVal(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "N/A";
    }
}

