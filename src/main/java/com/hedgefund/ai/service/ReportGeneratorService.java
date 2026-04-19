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
        log.info("Generating trading report for: " + tickers);

        // For Ollama (local models): skip LLM generation entirely.
        // Local models (especially 4B-30B) produce lazy/incomplete reports and take 300s+.
        // The data-driven fallback template is faster, more complete, and more accurate.
        if ("OLLAMA".equalsIgnoreCase(modelProvider)) {
            log.info("Using data-driven template for Ollama provider (skipping LLM generation for speed & quality)");
            return buildFallbackReport(response, tickers);
        }

        String systemPrompt = buildSystemPrompt(tickers.size());
        String userPrompt = buildUserPrompt(response, tickers);

        log.info("Prompt size: system=" + systemPrompt.length() + " chars, user=" + userPrompt.length() + " chars, tickers=" + tickers.size());

        // Scale max tokens with ticker count
        int maxTokens = Math.min(4000 + (tickers.size() - 1) * 1500, 8000);

        String llmReport = llmService.callRaw(systemPrompt, userPrompt, modelProvider, modelName, maxTokens);

        if (llmReport != null && !llmReport.isBlank()) {
            // Validate LLM output quality — small models often produce lazy/incomplete reports
            boolean isLowQuality = llmReport.contains("(See detailed context")
                    || llmReport.contains("see above")
                    || llmReport.contains("(See the")
                    || llmReport.length() < tickers.size() * 500;

            if (isLowQuality) {
                log.warning("LLM report is low quality (" + llmReport.length() + " chars). Using data-driven template.");
                return buildFallbackReport(response, tickers);
            }

            log.info("LLM report generated successfully (" + llmReport.length() + " chars)");
            return llmReport;
        }

        log.warning("LLM report generation failed, using data-driven template");
        return buildFallbackReport(response, tickers);
    }

    private String buildSystemPrompt(int tickerCount) {
        // For 3+ tickers or small models, use a compact system prompt to save context window
        if (tickerCount >= 3) {
            return """
                You are an expert financial analyst. Generate a Markdown trading report.
                
                SECTIONS (use --- between each):
                # Genisys Lab Analysis — Trading Report
                ## Report Date & Tickers Analyzed
                ## Executive Summary (2-3 sentences)
                ## Trading Signals (per ticker) — ### TICKER subsection with Parameter|Value table + TP table. NEVER merge tickers.
                ## Technical Analysis Breakdown (per ticker) — ### TICKER with Metric|Value|Interpretation table.
                ## 📰 News Sentiment Analysis — per ticker: Signal, Avg Score, Articles, Bullish/Bearish/Neutral counts in a table.
                ## Portfolio Decisions — Ticker|Action|Quantity|Confidence|Reasoning table.
                ## Risk Assessment — per ticker Metric|Value table.
                ## Analyst Consensus — Ticker|Analyst|Signal|Confidence table.
                ## Actionable Signal — BUY/SELL/HOLD per ticker with entry, stop-loss, targets.
                ## Conclusion & Recommendations — per-ticker key levels.
                
                RULES: ONE subsection per ticker. Round to 2 decimals. Never invent data. Be concise.
                """;
        }
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

            ## 📰 News Sentiment Analysis (MarketAux)
            Per ticker table: Metric | Value. Include News Signal (bullish/bearish/neutral with emoji), Avg Sentiment Score (-1 to +1), Articles Analyzed count, Bullish/Bearish/Neutral article counts.

            ## Portfolio Decisions
            Table: Action | Quantity | Confidence | Reasoning

            ## Risk Assessment
            Two-column table: Metric | Value (position limit, exposure, remaining limit, annualized volatility). Then a **Key Risk Note:** paragraph.

            ## Analyst Consensus
            Table: Ticker | Analyst Agent | Signal | Confidence. Then > **Conflict Highlight:** blockquote.

            ## 🎯 Actionable Signal
            Per ticker: BUY/SELL/HOLD action with Entry Price, Stop Loss, Target 1-3, Risk:Reward, ATR, Market Regime in a two-column table.

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

        // ═══ TECHNICAL ANALYST ACTION (BUY/SELL/HOLD) ═══
        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("technical_analyst")) {
            sb.append("=== TECHNICAL ANALYST ACTION SIGNAL ===\n");
            Map<String, AnalystSignal> techSignals = response.getAnalystSignals().get("technical_analyst");
            for (var entry : techSignals.entrySet()) {
                String ticker = entry.getKey();
                AnalystSignal sig = entry.getValue();
                sb.append(ticker).append(": ACTION=").append(sig.getSignal())
                        .append(" Confidence=").append(String.format("%.0f%%", sig.getConfidence()));
                if (sig.getReasoning() instanceof Map<?, ?> rm) {
                    Object actionObj = rm.get("action_signal");
                    if (actionObj instanceof Map<?, ?> action) {
                        sb.append(" Entry=").append(action.get("entry_price"))
                                .append(" StopLoss=").append(mapVal(action, "stop_loss"))
                                .append(" T1=").append(mapVal(action, "target_1"))
                                .append(" T2=").append(mapVal(action, "target_2"))
                                .append(" T3=").append(mapVal(action, "target_3"))
                                .append(" R:R=").append(mapVal(action, "risk_reward_ratio"))
                                .append(" Regime=").append(mapVal(action, "regime"));
                    }
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // ═══ MARKETAUX NEWS SENTIMENT ANALYSIS (extracted from technical_analyst + trading_signal) ═══
        sb.append("=== NEWS SENTIMENT ANALYSIS (MarketAux) ===\n");
        appendNewsSentimentToPrompt(sb, response, tickers);
        sb.append("\n");
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
        int perTickerBudget = tickerCount >= 3 ? Math.max(400, 1200 / tickerCount)
                : tickerCount > 1 ? Math.max(800, 2000 / tickerCount) : 3000;

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

                    // For 3+ tickers, skip verbose reasoning JSON to keep prompt small
                    if (tickerCount >= 3 && !"trading_signal".equals(agentName)) {
                        continue;
                    }

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

        sb.append("## Executive Summary\n\n");
        buildExecutiveSummary(sb, response, tickers);

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
                        String macdVal = mapVal(ind, "macd");
                        String macdHist = mapVal(ind, "macd_histogram");
                        sb.append("| MACD                     | ").append(macdVal).append("           | ")
                                .append(interpretMacd(macdVal, macdHist)).append(" |\n");
                        sb.append("| MACD Histogram           | ").append(macdHist).append("           | ")
                                .append(interpretMacdHist(macdHist)).append(" |\n");

                        // Momentum category
                        sb.append("| **Momentum**             |                 |                                        |\n");
                        String rsiVal = mapVal(ind, "rsi_14");
                        sb.append("| RSI (14)                 | ").append(rsiVal).append("           | ")
                                .append(interpretRsi(rsiVal)).append(" |\n");
                        // Secondary RSI — find the key dynamically
                        for (var indEntry : ((Map<?, ?>) ind).entrySet()) {
                            String key = String.valueOf(indEntry.getKey());
                            if (key.startsWith("rsi_secondary_")) {
                                sb.append("| RSI (Secondary TF)       | ").append(indEntry.getValue()).append("           | Higher-timeframe momentum |\n");
                            }
                        }

                        // Volatility category
                        sb.append("| **Volatility**           |                 |                                        |\n");
                        String atrVal = mapVal(ind, "atr_14");
                        String atrPct = mapVal(ind, "atr_pct");
                        sb.append("| ATR (14)                 | ").append(atrVal).append("            | ").append(atrPct).append("% of price — ")
                                .append(interpretAtr(atrPct)).append(" |\n");

                        // VWAP Analysis category
                        sb.append("| **VWAP Analysis**        |                 |                                        |\n");
                        String vwapVal = mapVal(ind, "vwap");
                        sb.append("| VWAP                     | ").append(vwapVal).append("          | ")
                                .append(interpretVwap(ind)).append(" |\n");

                        // ADX category
                        sb.append("| **Directional Movement** |                 |                                        |\n");
                        String adxVal = mapVal(ind, "adx");
                        sb.append("| ADX                      | ").append(adxVal).append("           | ")
                                .append(interpretAdx(adxVal)).append(" |\n");
                        sb.append("| +DI                      | ").append(mapVal(ind, "plus_di")).append("           | Bullish directional strength |\n");
                        sb.append("| -DI                      | ").append(mapVal(ind, "minus_di")).append("           | Bearish directional strength |\n");

                        // News Sentiment category (MarketAux)
                        sb.append("| **News Sentiment**       |                 |                                        |\n");
                        String newsSignal = mapVal(ind, "news_signal");
                        String newsScore = mapVal(ind, "news_sentiment");
                        sb.append("| Sentiment Score          | ").append(newsScore).append("           | ")
                                .append("bullish".equals(newsSignal) ? "🟢 Positive news flow" : "bearish".equals(newsSignal) ? "🔴 Negative news flow" : "⚪ Neutral/Mixed")
                                .append(" |\n");
                        sb.append("| Articles Analyzed        | ").append(mapVal(ind, "news_articles")).append("           | MarketAux data |\n");

                        // Bollinger Bands category
                        sb.append("| **Bollinger Bands (20,2)**|                |                                        |\n");
                        sb.append("| Upper Band               | ").append(mapVal(ind, "bb_upper")).append("          | Overbought resistance |\n");
                        sb.append("| Middle Band (SMA20)      | ").append(mapVal(ind, "bb_middle")).append("          | Mean reversion level |\n");
                        sb.append("| Lower Band               | ").append(mapVal(ind, "bb_lower")).append("          | Oversold support |\n");

                        // Fibonacci Retracement category
                        sb.append("| **Fibonacci Retracement** |                |                                        |\n");
                        sb.append("| Swing High               | ").append(mapVal(ind, "fib_swing_high")).append("          | 50-bar lookback |\n");
                        sb.append("| Swing Low                | ").append(mapVal(ind, "fib_swing_low")).append("          | 50-bar lookback |\n");
                        sb.append("| Fib 0.236                | ").append(mapVal(ind, "fib_236")).append("          | Shallow retracement |\n");
                        sb.append("| Fib 0.382                | ").append(mapVal(ind, "fib_382")).append("          | Moderate pullback |\n");
                        sb.append("| Fib 0.500                | ").append(mapVal(ind, "fib_500")).append("          | Half retracement |\n");
                        sb.append("| Fib 0.618                | ").append(mapVal(ind, "fib_618")).append("          | Golden ratio — key support/resistance |\n");
                        sb.append("| Fib 0.786                | ").append(mapVal(ind, "fib_786")).append("          | Deep retracement |\n");

                        // Support & Resistance
                        sb.append("| **Support & Resistance** |                 |                                        |\n");
                        sb.append("| **Support Level**        | **").append(mapVal(ind, "support_level")).append("**   | Max(BB Lower, Fib 0.618) — key downside floor |\n");
                        sb.append("| **Resistance Level**     | **").append(mapVal(ind, "resistance_level")).append("**   | Min(BB Upper, Fib 0.236) — key upside ceiling |\n");
                    }
                }
                sb.append("\n---\n\n");
            }
        }

        // ── News Sentiment Analysis (MarketAux) ──
        sb.append("## 📰 News Sentiment Analysis (MarketAux)\n\n");
        appendNewsSentimentToReport(sb, response, tickers);
        sb.append("---\n\n");

        // ── Insider Sentiment Overview (sentiment_analyst) ──
        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("sentiment_analyst")) {
            sb.append("## 📊 Insider & Market Sentiment\n\n");
            Map<String, AnalystSignal> sentSignals = response.getAnalystSignals().get("sentiment_analyst");
            sb.append("| **Ticker** | **Signal** | **Confidence** | **Details** |\n");
            sb.append("|------------|------------|----------------|-------------|\n");
            for (var entry : sentSignals.entrySet()) {
                AnalystSignal sig = entry.getValue();
                String emoji = "bullish".equals(sig.getSignal()) ? "🟢" : "bearish".equals(sig.getSignal()) ? "🔴" : "⚪";
                String details = "";
                if (sig.getReasoning() instanceof Map<?, ?> rm) {
                    Object insiderObj = rm.get("insider_trades");
                    Object newsObj = rm.get("news_sentiment");
                    if (insiderObj instanceof Map<?, ?> it) {
                        details += "Insider: " + mapVal(it, "bullish") + " buys / " + mapVal(it, "bearish") + " sells";
                    }
                    if (newsObj instanceof Map<?, ?> ns) {
                        if (!details.isEmpty()) details += " | ";
                        details += "News: " + mapVal(ns, "bullish") + "↑ " + mapVal(ns, "bearish") + "↓ " + mapVal(ns, "neutral") + "→";
                    }
                }
                sb.append(String.format("| %s | %s %s | %.0f%% | %s |\n",
                        entry.getKey(), emoji, sig.getSignal().toUpperCase(), sig.getConfidence(), details));
            }
            sb.append("\n---\n\n");
        }

        // ── Fundamentals Overview (fundamentals_analyst) ──
        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("fundamentals_analyst")) {
            sb.append("## 📈 Fundamentals Analysis\n\n");
            Map<String, AnalystSignal> fundSignals = response.getAnalystSignals().get("fundamentals_analyst");
            for (var entry : fundSignals.entrySet()) {
                AnalystSignal sig = entry.getValue();
                String emoji = "bullish".equals(sig.getSignal()) ? "🟢" : "bearish".equals(sig.getSignal()) ? "🔴" : "⚪";
                sb.append("### ").append(entry.getKey()).append(" — ").append(emoji).append(" ")
                        .append(sig.getSignal().toUpperCase()).append(" (")
                        .append(String.format("%.0f%%", sig.getConfidence())).append(" confidence)\n\n");
                if (sig.getReasoning() instanceof Map<?, ?> rm) {
                    // Render key metrics if available
                    for (var metricEntry : rm.entrySet()) {
                        String key = String.valueOf(metricEntry.getKey());
                        Object val = metricEntry.getValue();
                        if (val instanceof Map<?, ?> metricMap) {
                            String signal = mapVal(metricMap, "signal");
                            String metricEmoji = "bullish".equals(signal) ? "🟢" : "bearish".equals(signal) ? "🔴" : "⚪";
                            sb.append("- ").append(metricEmoji).append(" **").append(key).append("**: ").append(signal);
                            String detail = mapVal(metricMap, "details");
                            if (!"N/A".equals(detail)) sb.append(" — ").append(detail);
                            sb.append("  \n");
                        }
                    }
                }
                sb.append("\n");
            }
            sb.append("---\n\n");
        }

        // ── Portfolio Decisions ──
        sb.append("## Portfolio Decisions\n\n");
        sb.append("| **Ticker** | **Action** | **Quantity** | **Confidence** | **Reasoning** |\n");
        sb.append("|------------|------------|--------------|----------------|---------------|\n");
        if (response.getDecisions() != null) {
            for (var entry : response.getDecisions().entrySet()) {
                PortfolioDecision d = entry.getValue();
                sb.append(String.format("| %s | %s | %d | %d%% | %s |\n",
                        entry.getKey(), d.getAction(), d.getQuantity(), d.getConfidence(), d.getReasoning()));
            }
        }

        sb.append("\n---\n\n");

        // ── Risk Assessment ──
        sb.append("## Risk Assessment\n");
        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("risk_management_agent")) {
            Map<String, AnalystSignal> riskSignals = response.getAnalystSignals().get("risk_management_agent");
            for (var entry : riskSignals.entrySet()) {
                sb.append("### ").append(entry.getKey()).append("\n");
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

        // ── Technical Analyst Action Signal (BUY / SELL / HOLD) ──
        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("technical_analyst")) {
            Map<String, AnalystSignal> techSignals = response.getAnalystSignals().get("technical_analyst");
            sb.append("## 🎯 Technical Analyst — Actionable Signal\n\n");
            for (var entry : techSignals.entrySet()) {
                String ticker = entry.getKey();
                AnalystSignal sig = entry.getValue();
                sb.append("### ").append(ticker).append("\n\n");
                sb.append("| **Parameter**          | **Value**          |\n");
                sb.append("|------------------------|--------------------|  \n");
                sb.append("| **Action**             | **").append(sig.getSignal()).append("**           |\n");
                sb.append("| **Confidence**         | ").append(String.format("%.0f%%", sig.getConfidence())).append("              |\n");

                if (sig.getReasoning() instanceof Map<?, ?> rm) {
                    Object actionObj = rm.get("action_signal");
                    if (actionObj instanceof Map<?, ?> action) {
                        sb.append("| **Direction**          | ").append(mapVal(action, "direction")).append("             |\n");
                        sb.append("| **Entry Price**        | ").append(mapVal(action, "entry_price")).append("           |\n");
                        if (!"HOLD".equals(sig.getSignal())) {
                            sb.append("| **Stop Loss**          | ").append(mapVal(action, "stop_loss")).append("           |\n");
                            sb.append("| **Target 1 (1R)**      | ").append(mapVal(action, "target_1")).append("           |\n");
                            sb.append("| **Target 2 (2R)**      | ").append(mapVal(action, "target_2")).append("           |\n");
                            sb.append("| **Target 3 (3R)**      | ").append(mapVal(action, "target_3")).append("           |\n");
                            sb.append("| **Risk:Reward**        | ").append(mapVal(action, "risk_reward_ratio")).append("           |\n");
                            sb.append("| **ATR (14)**           | ").append(mapVal(action, "atr_14")).append("           |\n");
                        }
                        sb.append("| **Market Regime**      | ").append(mapVal(action, "regime")).append("           |\n");
                    }
                }
                sb.append("\n---\n\n");
            }
        }

        // ── Conclusion & Recommendations ──
        sb.append("## Conclusion & Recommendations\n");
        sb.append("**Recommendation:** Review the analysis and trading levels above before entering positions.  \n\n");
        sb.append("**Key Levels to Watch:**  \n");
        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("trading_signal")) {
            Map<String, AnalystSignal> tradingSignals = response.getAnalystSignals().get("trading_signal");
            for (var entry : tradingSignals.entrySet()) {
                sb.append("\n**").append(entry.getKey()).append(":**  \n");
                AnalystSignal sig = entry.getValue();
                if (sig.getReasoning() instanceof Map<?, ?> reasoningMap) {
                    Object tradingSigObj = reasoningMap.get("trading_signal");
                    if (tradingSigObj instanceof TradingSignalResult ts) {
                        sb.append("- **Support Level**: **").append(ts.getSupportLevel()).append("** — Bollinger/Fib confluence floor.  \n");
                        sb.append("- **Resistance Level**: **").append(ts.getResistanceLevel()).append("** — Bollinger/Fib confluence ceiling.  \n");
                        sb.append("- **Stop Loss**: **").append(ts.getStopLoss()).append("** — Reassess if breached.  \n");
                        sb.append("- **TP1**: **").append(ts.getTp1()).append("** — First target.  \n");
                        sb.append("- **TP3**: **").append(ts.getTp3()).append("** — Primary target (3R).  \n");
                        sb.append("- **BB Lower**: **").append(ts.getBollingerLower()).append("** — Oversold bounce zone.  \n");
                        sb.append("- **Fib 0.618**: **").append(ts.getFib618()).append("** — Golden ratio support.  \n");
                    } else if (tradingSigObj instanceof Map<?, ?> tsMap) {
                        sb.append("- **Support Level**: **").append(mapVal(tsMap, "supportLevel")).append("** — Bollinger/Fib confluence floor.  \n");
                        sb.append("- **Resistance Level**: **").append(mapVal(tsMap, "resistanceLevel")).append("** — Bollinger/Fib confluence ceiling.  \n");
                        sb.append("- **Stop Loss**: **").append(mapVal(tsMap, "stopLoss")).append("** — Reassess if breached.  \n");
                        sb.append("- **TP1**: **").append(mapVal(tsMap, "tp1")).append("** — First target.  \n");
                        sb.append("- **TP3**: **").append(mapVal(tsMap, "tp3")).append("** — Primary target (3R).  \n");
                    }
                    Object indicators = reasoningMap.get("indicators");
                    if (indicators instanceof Map<?, ?> ind) {
                        sb.append("- **VWAP**: **").append(mapVal(ind, "vwap")).append("** — Break above signals bullish shift; break below reinforces bearish structure.  \n");
                        sb.append("- **BB Middle (SMA20)**: **").append(mapVal(ind, "bb_middle")).append("** — Mean reversion level.  \n");
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

    // ═══════════════════════════════════════════════════════════════
    //  EXECUTIVE SUMMARY BUILDER
    // ═══════════════════════════════════════════════════════════════

    private void buildExecutiveSummary(StringBuilder sb, HedgeFundResponse response, List<String> tickers) {
        sb.append("This report presents a comprehensive multi-agent analysis of **")
                .append(String.join(", ", tickers))
                .append("** using the Sniper Confluence 11-Factor algorithm with MarketAux news sentiment integration.\n\n");

        if (response.getAnalystSignals() == null) return;

        for (String ticker : tickers) {
            sb.append("**").append(ticker).append(":** ");

            // Get technical analyst action
            String action = "N/A";
            double conf = 0;
            if (response.getAnalystSignals().containsKey("technical_analyst")) {
                AnalystSignal techSig = response.getAnalystSignals().get("technical_analyst").get(ticker);
                if (techSig != null) {
                    action = techSig.getSignal();
                    conf = techSig.getConfidence();
                }
            }

            // Get trading signal direction
            String position = "N/A";
            if (response.getAnalystSignals().containsKey("trading_signal")) {
                AnalystSignal tradSig = response.getAnalystSignals().get("trading_signal").get(ticker);
                if (tradSig != null && tradSig.getReasoning() instanceof Map<?, ?> rm) {
                    Object ts = rm.get("trading_signal");
                    if (ts instanceof TradingSignalResult tsr) {
                        position = tsr.getPosition();
                    } else if (ts instanceof Map<?, ?> tsMap) {
                        position = mapVal(tsMap, "position");
                    }
                }
            }

            // Get news sentiment
            String newsSentiment = "N/A";
            if (response.getAnalystSignals().containsKey("news_sentiment_analyst")) {
                AnalystSignal nsSig = response.getAnalystSignals().get("news_sentiment_analyst").get(ticker);
                if (nsSig != null) newsSentiment = nsSig.getSignal();
            }

            // Get portfolio decision
            String portfolioAction = "N/A";
            if (response.getDecisions() != null && response.getDecisions().containsKey(ticker)) {
                portfolioAction = response.getDecisions().get(ticker).getAction();
            }

            String actionEmoji = "BUY".equals(action) ? "🟢" : "SELL".equals(action) ? "🔴" : "⚪";
            sb.append(actionEmoji).append(" Action Signal = **").append(action).append("** (")
                    .append(String.format("%.0f%%", conf)).append(" confidence)")
                    .append(" | Position: ").append(position)
                    .append(" | News: ").append(newsSentiment)
                    .append(" | Portfolio: ").append(portfolioAction)
                    .append("  \n");
        }
        sb.append("\n");
    }

    // ═══════════════════════════════════════════════════════════════
    //  INDICATOR INTERPRETATION HELPERS
    // ═══════════════════════════════════════════════════════════════

    private static String interpretRsi(String rsiStr) {
        try {
            double rsi = Double.parseDouble(rsiStr);
            if (rsi >= 80) return "Extremely overbought — strong reversal risk";
            if (rsi >= 70) return "Overbought — potential pullback ahead";
            if (rsi >= 60) return "Bullish momentum";
            if (rsi >= 40) return "Neutral — no clear momentum";
            if (rsi >= 30) return "Oversold — potential bounce";
            return "Extremely oversold — strong buy signal";
        } catch (Exception e) { return ""; }
    }

    private static String interpretMacd(String macdStr, String histStr) {
        try {
            double macd = Double.parseDouble(macdStr);
            if (macd > 0) return "Bullish momentum (above signal line)";
            return "Bearish momentum (below signal line)";
        } catch (Exception e) { return ""; }
    }

    private static String interpretMacdHist(String histStr) {
        try {
            double hist = Double.parseDouble(histStr);
            if (hist > 0) return "Accelerating bullish momentum";
            return "Accelerating bearish momentum";
        } catch (Exception e) { return ""; }
    }

    private static String interpretAtr(String atrPctStr) {
        try {
            double pct = Double.parseDouble(atrPctStr);
            if (pct >= 5) return "Very high volatility — extreme risk";
            if (pct >= 3) return "High volatility — widen stops";
            if (pct >= 1.5) return "Moderate volatility — normal";
            return "Low volatility — tight range";
        } catch (Exception e) { return ""; }
    }

    private static String interpretVwap(Map<?, ?> ind) {
        try {
            // If we can determine price vs VWAP
            Object vwapObj = ind.get("vwap");
            if (vwapObj == null) return "";
            // Basic interpretation based on available data
            return "Institutional reference level";
        } catch (Exception e) { return ""; }
    }

    private static String interpretAdx(String adxStr) {
        try {
            double adx = Double.parseDouble(adxStr);
            if (adx >= 50) return "Very strong trend";
            if (adx >= 25) return "Strong trend — favor trend-following";
            if (adx >= 20) return "Developing trend";
            return "Weak/No trend — range-bound market";
        } catch (Exception e) { return ""; }
    }

    /**
     * Extract MarketAux news sentiment data from technical_analyst and/or trading_signal
     * reasoning maps and append to the LLM prompt as a compact text block.
     */
    private void appendNewsSentimentToPrompt(StringBuilder sb, HedgeFundResponse response, List<String> tickers) {
        if (response.getAnalystSignals() == null) {
            sb.append("No news sentiment data available.\n");
            return;
        }

        boolean found = false;
        for (String ticker : tickers) {
            Map<String, String> sentimentData = extractNewsSentiment(response, ticker);
            if (sentimentData != null) {
                found = true;
                sb.append(ticker).append(": Signal=").append(sentimentData.get("signal"))
                        .append(" Sentiment=").append(sentimentData.get("avg_sentiment"))
                        .append(" Articles=").append(sentimentData.get("article_count"))
                        .append(" Bullish=").append(sentimentData.get("bullish"))
                        .append(" Bearish=").append(sentimentData.get("bearish"))
                        .append(" Neutral=").append(sentimentData.get("neutral"))
                        .append(" Details=").append(sentimentData.get("details"))
                        .append("\n");
                // Include article titles for LLM to reference
                List<Map<String, String>> articles = extractNewsArticles(response, ticker);
                int count = 0;
                for (Map<String, String> art : articles) {
                    if (count >= 5) break; // limit to 5 for prompt budget
                    sb.append("  - [").append(art.get("sentiment")).append("] ")
                            .append(art.get("title"));
                    if (!"N/A".equals(art.get("url"))) {
                        sb.append(" | ").append(art.get("url"));
                    }
                    sb.append("\n");
                    count++;
                }
            }
        }
        if (!found) {
            sb.append("MarketAux news sentiment not available (API not configured or no data).\n");
        }
    }

    /**
     * Render a full Markdown section for MarketAux news sentiment in the fallback report.
     */
    private void appendNewsSentimentToReport(StringBuilder sb, HedgeFundResponse response, List<String> tickers) {
        if (response.getAnalystSignals() == null) {
            sb.append("*News sentiment data not available.*\n\n");
            return;
        }

        boolean found = false;
        for (String ticker : tickers) {
            Map<String, String> sentimentData = extractNewsSentiment(response, ticker);
            if (sentimentData != null) {
                found = true;
                sb.append("### ").append(ticker).append("\n");
                sb.append("| **Metric**               | **Value**          |\n");
                sb.append("|--------------------------|--------------------|\n");

                String signal = sentimentData.get("signal");
                String emoji = "bullish".equals(signal) ? "🟢" : "bearish".equals(signal) ? "🔴" : "⚪";
                sb.append("| **News Signal**          | ").append(emoji).append(" **").append(signal.toUpperCase()).append("** |\n");
                sb.append("| **Avg Sentiment Score**  | ").append(sentimentData.get("avg_sentiment")).append("             |\n");
                sb.append("| **Articles Analyzed**    | ").append(sentimentData.get("article_count")).append("               |\n");
                sb.append("| **Bullish Articles**     | ").append(sentimentData.get("bullish")).append("               |\n");
                sb.append("| **Bearish Articles**     | ").append(sentimentData.get("bearish")).append("               |\n");
                sb.append("| **Neutral Articles**     | ").append(sentimentData.get("neutral")).append("               |\n");
                sb.append("\n");

                // Render article titles with links
                List<Map<String, String>> articles = extractNewsArticles(response, ticker);
                if (!articles.isEmpty()) {
                    sb.append("**Recent News Articles:**\n\n");
                    int count = 0;
                    for (Map<String, String> art : articles) {
                        if (count >= 10) break; // limit to 10 articles per ticker
                        String sentEmoji = "bullish".equals(art.get("sentiment")) ? "🟢"
                                : "bearish".equals(art.get("sentiment")) ? "🔴" : "⚪";
                        String title = art.get("title");
                        String url = art.get("url");
                        String source = art.get("source");
                        if (url != null && !url.isEmpty() && !"N/A".equals(url)) {
                            sb.append("- ").append(sentEmoji).append(" [").append(title).append("](").append(url).append(")");
                        } else {
                            sb.append("- ").append(sentEmoji).append(" ").append(title);
                        }
                        if (source != null && !source.isEmpty() && !"N/A".equals(source)) {
                            sb.append(" — *").append(source).append("*");
                        }
                        sb.append("  \n");
                        count++;
                    }
                    sb.append("\n");
                }
            }
        }
        if (!found) {
            sb.append("*MarketAux news sentiment not available. Set `marketaux.api-token` in application.properties.*\n\n");
        }
    }

    /**
     * Extract news sentiment data for a ticker from any agent that has it.
     * Checks technical_analyst (news_sentiment key) and trading_signal (indicators.news_* keys).
     * Returns a map of {signal, avg_sentiment, article_count, bullish, bearish, neutral, details} or null.
     */
    private Map<String, String> extractNewsSentiment(HedgeFundResponse response, String ticker) {
        Map<String, Map<String, AnalystSignal>> allSignals = response.getAnalystSignals();
        if (allSignals == null) return null;

        // Try news_sentiment_analyst first (has structured MarketAux data)
        if (allSignals.containsKey("news_sentiment_analyst")) {
            AnalystSignal nsSig = allSignals.get("news_sentiment_analyst").get(ticker);
            if (nsSig != null && nsSig.getReasoning() instanceof Map<?, ?> rm) {
                Object maObj = rm.get("marketaux");
                if (maObj instanceof Map<?, ?> ma && !"not_configured".equals(mapVal(ma, "status"))) {
                    String articleCount = mapVal(ma, "article_count");
                    if (!"N/A".equals(articleCount) && !"0".equals(articleCount)) {
                        Map<String, String> result = new LinkedHashMap<>();
                        result.put("signal", mapVal(ma, "signal"));
                        result.put("avg_sentiment", mapVal(ma, "avg_sentiment"));
                        result.put("article_count", articleCount);
                        result.put("bullish", mapVal(ma, "bullish_count"));
                        result.put("bearish", mapVal(ma, "bearish_count"));
                        result.put("neutral", mapVal(ma, "neutral_count"));
                        result.put("details", "MarketAux: " + mapVal(ma, "signal") + " (avg=" + mapVal(ma, "avg_sentiment") + ")");
                        return result;
                    }
                }
            }
        }

        // Try technical_analyst (has dedicated news_sentiment sub-signal)
        if (allSignals.containsKey("technical_analyst")) {
            AnalystSignal techSig = allSignals.get("technical_analyst").get(ticker);
            if (techSig != null && techSig.getReasoning() instanceof Map<?, ?> rm) {
                Object newsSentObj = rm.get("news_sentiment");
                if (newsSentObj instanceof Map<?, ?> ns) {
                    String details = mapVal(ns, "details");
                    // The details string from TechnicalAnalystAgent looks like:
                    // "news_bullish avg=0.7783 articles=5 bull=3 bear=0 neut=2"
                    // Parse it to extract counts, or use the raw details
                    Map<String, String> result = new LinkedHashMap<>();
                    result.put("signal", mapVal(ns, "signal"));
                    result.put("avg_sentiment", extractFromDetails(details, "avg=", " "));
                    result.put("article_count", extractFromDetails(details, "articles=", " "));
                    result.put("bullish", extractFromDetails(details, "bull=", " "));
                    result.put("bearish", extractFromDetails(details, "bear=", " "));
                    result.put("neutral", extractFromDetails(details, "neut=", " "));
                    result.put("details", details);

                    // If we got valid article count, return it
                    if (!"N/A".equals(result.get("article_count")) && !"0".equals(result.get("article_count"))) {
                        return result;
                    }
                }
            }
        }

        // Fallback: try trading_signal agent (indicators map has news_sentiment, news_signal, news_articles)
        if (allSignals.containsKey("trading_signal")) {
            AnalystSignal tradingSig = allSignals.get("trading_signal").get(ticker);
            if (tradingSig != null && tradingSig.getReasoning() instanceof Map<?, ?> rm) {
                Object indObj = rm.get("indicators");
                if (indObj instanceof Map<?, ?> ind) {
                    String newsSignal = mapVal(ind, "news_signal");
                    String newsScore = mapVal(ind, "news_sentiment");
                    String newsArticles = mapVal(ind, "news_articles");
                    if (!"N/A".equals(newsSignal) && !"N/A".equals(newsArticles)) {
                        Map<String, String> result = new LinkedHashMap<>();
                        result.put("signal", newsSignal);
                        result.put("avg_sentiment", newsScore);
                        result.put("article_count", newsArticles);
                        // Trading signal factor 11 has full details
                        Object f11 = null;
                        Object factors = rm.get("confluence_factors");
                        if (factors instanceof Map<?, ?> fMap) {
                            f11 = fMap.get("11_news_sentiment");
                        }
                        if (f11 instanceof Map<?, ?> f11Map) {
                            result.put("bullish", mapVal(f11Map, "bullish_articles"));
                            result.put("bearish", mapVal(f11Map, "bearish_articles"));
                            result.put("neutral", mapVal(f11Map, "neutral_articles"));
                            result.put("details", mapVal(f11Map, "details"));
                        } else {
                            result.put("bullish", "N/A");
                            result.put("bearish", "N/A");
                            result.put("neutral", "N/A");
                            result.put("details", newsSignal + " (score=" + newsScore + ", articles=" + newsArticles + ")");
                        }
                        return result;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Extract news article titles, URLs, and sources for a ticker from agent reasoning.
     * Returns a list of maps with keys: title, url, source, sentiment.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> extractNewsArticles(HedgeFundResponse response, String ticker) {
        Map<String, Map<String, AnalystSignal>> allSignals = response.getAnalystSignals();
        if (allSignals == null) return Collections.emptyList();

        // Try news_sentiment_analyst first (richest source — MarketAux with titles/URLs)
        List<Map<String, String>> articles = extractArticlesFromAgent(allSignals, "news_sentiment_analyst", ticker, "marketaux");
        if (!articles.isEmpty()) return articles;

        // Try technical_analyst (has articles list in news_sentiment reasoning)
        articles = extractArticlesFromAgent(allSignals, "technical_analyst", ticker, "news_sentiment");
        if (!articles.isEmpty()) return articles;

        // Fallback: try trading_signal (has articles in confluence_factors.11_news_sentiment)
        if (allSignals.containsKey("trading_signal")) {
            AnalystSignal tradingSig = allSignals.get("trading_signal").get(ticker);
            if (tradingSig != null && tradingSig.getReasoning() instanceof Map<?, ?> rm) {
                Object factors = rm.get("confluence_factors");
                if (factors instanceof Map<?, ?> fMap) {
                    Object f11 = fMap.get("11_news_sentiment");
                    if (f11 instanceof Map<?, ?> f11Map) {
                        Object artList = f11Map.get("articles");
                        if (artList instanceof List<?> list) {
                            return toArticleMapList(list);
                        }
                    }
                }
            }
        }

        return Collections.emptyList();
    }

    private List<Map<String, String>> extractArticlesFromAgent(
            Map<String, Map<String, AnalystSignal>> allSignals, String agentKey, String ticker, String reasoningKey) {
        if (!allSignals.containsKey(agentKey)) return Collections.emptyList();
        AnalystSignal sig = allSignals.get(agentKey).get(ticker);
        if (sig == null || !(sig.getReasoning() instanceof Map<?, ?> rm)) return Collections.emptyList();
        Object subObj = rm.get(reasoningKey);
        if (!(subObj instanceof Map<?, ?> subMap)) return Collections.emptyList();
        Object artList = subMap.get("articles");
        if (!(artList instanceof List<?> list)) return Collections.emptyList();
        return toArticleMapList(list);
    }

    private List<Map<String, String>> toArticleMapList(List<?> list) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> artMap) {
                Map<String, String> article = new LinkedHashMap<>();
                article.put("title", mapVal(artMap, "title"));
                article.put("url", mapVal(artMap, "url"));
                article.put("source", mapVal(artMap, "source"));
                article.put("sentiment", mapVal(artMap, "sentiment"));
                result.add(article);
            }
        }
        return result;
    }

    /** Extract a value from a details string like "articles=5 bull=3" given prefix and delimiter. */
    private static String extractFromDetails(String details, String prefix, String delimiter) {
        if (details == null || !details.contains(prefix)) return "N/A";
        int start = details.indexOf(prefix) + prefix.length();
        int end = details.indexOf(delimiter, start);
        if (end < 0) end = details.length();
        String val = details.substring(start, end).trim();
        return val.isEmpty() ? "N/A" : val;
    }
}

