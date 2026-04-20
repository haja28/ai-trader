package com.hedgefund.ai.service;

import com.hedgefund.ai.model.HedgeFundRequest;
import com.hedgefund.ai.model.HedgeFundResponse;
import com.hedgefund.ai.model.agent.AnalystSignal;
import com.hedgefund.ai.model.agent.PortfolioDecision;
import com.hedgefund.ai.model.agent.TradingSignalResult;
import com.hedgefund.ai.model.persistence.AnalysisRun;
import com.hedgefund.ai.model.persistence.AnalystResult;
import com.hedgefund.ai.model.persistence.TickerAnalysis;
import com.hedgefund.ai.repository.AnalysisRunRepository;
import com.hedgefund.ai.repository.TickerAnalysisRepository;
import com.hedgefund.ai.service.api.FinancialDataClient;
import com.hedgefund.ai.model.api.Price;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists analysis results to the database. Only active when
 * hedgefund.persistence.enabled=true in application.properties.
 */
@Service
@ConditionalOnProperty(name = "hedgefund.persistence.enabled", havingValue = "true")
public class AnalysisPersistenceService {

    private static final Logger log = Logger.getLogger(AnalysisPersistenceService.class.getName());
    private final AnalysisRunRepository runRepo;
    private final TickerAnalysisRepository tickerRepo;
    private final FinancialDataClient apiClient;

    public AnalysisPersistenceService(AnalysisRunRepository runRepo,
                                       TickerAnalysisRepository tickerRepo,
                                       FinancialDataClient apiClient) {
        this.runRepo = runRepo;
        this.tickerRepo = tickerRepo;
        this.apiClient = apiClient;
        log.info("✓ Analysis persistence enabled — results will be stored in database");
    }

    // ═══════════════════════════════════════
    //  SAVE ANALYSIS
    // ═══════════════════════════════════════

    @Transactional
    public AnalysisRun saveAnalysis(HedgeFundRequest request, HedgeFundResponse response) {
        try {
            AnalysisRun run = new AnalysisRun();
            run.setTickers(String.join(",", request.getTickers()));
            run.setModelProvider(request.getModelProvider());
            run.setModelName(request.getModelName());
            run.setSelectedAnalysts(request.getSelectedAnalysts() != null
                    ? String.join(",", request.getSelectedAnalysts()) : "all");
            run.setReport(response.getReport());

            for (String ticker : request.getTickers()) {
                TickerAnalysis ta = buildTickerAnalysis(ticker, response);
                run.addTickerAnalysis(ta);
            }

            AnalysisRun saved = runRepo.save(run);
            log.info("Saved analysis run #" + saved.getId() + " with " +
                     saved.getTickerAnalyses().size() + " tickers");
            return saved;
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to persist analysis: " + e.getMessage(), e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private TickerAnalysis buildTickerAnalysis(String ticker, HedgeFundResponse response) {
        TickerAnalysis ta = new TickerAnalysis();
        ta.setTicker(ticker);
        ta.setOutcome("pending");

        // Extract trading signal data
        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("trading_signal")) {
            AnalystSignal tradSig = response.getAnalystSignals().get("trading_signal").get(ticker);
            if (tradSig != null && tradSig.getReasoning() instanceof Map<?, ?> rm) {
                Object tsObj = rm.get("trading_signal");
                if (tsObj instanceof TradingSignalResult ts) {
                    ta.setPosition(ts.getPosition());
                    ta.setEntryPoint(ts.getEntryPoint());
                    ta.setStopLoss(ts.getStopLoss());
                    ta.setTp1(ts.getTp1());
                    ta.setTp2(ts.getTp2());
                    ta.setTp3(ts.getTp3());
                    ta.setTp4(ts.getTp4());
                    ta.setTp5(ts.getTp5());
                    ta.setBollingerUpper(ts.getBollingerUpper());
                    ta.setBollingerMiddle(ts.getBollingerMiddle());
                    ta.setBollingerLower(ts.getBollingerLower());
                    ta.setFibSwingHigh(ts.getFibSwingHigh());
                    ta.setFibSwingLow(ts.getFibSwingLow());
                    ta.setFib236(ts.getFib236());
                    ta.setFib382(ts.getFib382());
                    ta.setFib500(ts.getFib500());
                    ta.setFib618(ts.getFib618());
                    ta.setFib786(ts.getFib786());
                    ta.setSupportLevel(ts.getSupportLevel());
                    ta.setResistanceLevel(ts.getResistanceLevel());
                    ta.setConfidence(ts.getConfidence());
                    ta.setConfluenceScore(ts.getConfluenceScore());
                    ta.setSignalFired(ts.isSignalFired());
                    ta.setAlgorithm(ts.getAlgorithm());
                } else if (tsObj instanceof Map<?, ?> tsMap) {
                    ta.setPosition(str(tsMap, "position"));
                    ta.setEntryPoint(dbl(tsMap, "entryPoint"));
                    ta.setStopLoss(dbl(tsMap, "stopLoss"));
                    ta.setTp1(dbl(tsMap, "tp1"));
                    ta.setTp2(dbl(tsMap, "tp2"));
                    ta.setTp3(dbl(tsMap, "tp3"));
                    ta.setTp4(dbl(tsMap, "tp4"));
                    ta.setTp5(dbl(tsMap, "tp5"));
                    ta.setConfidence(dbl(tsMap, "confidence"));
                    ta.setSignalFired(bool(tsMap, "signalFired"));
                }

                // Indicators
                Object indObj = rm.get("indicators");
                if (indObj instanceof Map<?, ?> ind) {
                    ta.setRsi14(dbl(ind, "rsi_14"));
                    ta.setMacd(dbl(ind, "macd"));
                    ta.setMacdHistogram(dbl(ind, "macd_histogram"));
                    ta.setAtr14(dbl(ind, "atr_14"));
                    ta.setAtrPct(dbl(ind, "atr_pct"));
                    ta.setAdx(dbl(ind, "adx"));
                    ta.setVwap(dbl(ind, "vwap"));
                    ta.setNewsSentiment(dbl(ind, "news_sentiment"));
                    ta.setNewsSignal(str(ind, "news_signal"));
                    ta.setNewsArticleCount(intVal(ind, "news_articles"));
                }

                ta.setCurrentPrice(dbl(rm, "current_price"));
                ta.setWeightedScore(dbl(rm, "weighted_score"));

                // Regime from hard_gates
                Object gates = rm.get("hard_gates");
                if (gates instanceof Map<?, ?> gm) {
                    ta.setRegime(str(gm, "regime"));
                }
            }
        }

        // Technical analyst action
        if (response.getAnalystSignals() != null && response.getAnalystSignals().containsKey("technical_analyst")) {
            AnalystSignal techSig = response.getAnalystSignals().get("technical_analyst").get(ticker);
            if (techSig != null) {
                ta.setTechAction(techSig.getSignal());
                ta.setTechConfidence(techSig.getConfidence());
            }
        }

        // Portfolio decision
        if (response.getDecisions() != null && response.getDecisions().containsKey(ticker)) {
            PortfolioDecision d = response.getDecisions().get(ticker);
            ta.setPortfolioAction(d.getAction());
            ta.setPortfolioQuantity(d.getQuantity());
            ta.setPortfolioConfidence(d.getConfidence());
            ta.setPortfolioReasoning(d.getReasoning());
        }

        // All analyst signals
        if (response.getAnalystSignals() != null) {
            for (var agentEntry : response.getAnalystSignals().entrySet()) {
                AnalystSignal sig = agentEntry.getValue().get(ticker);
                if (sig != null) {
                    AnalystResult ar = new AnalystResult();
                    ar.setAgentId(agentEntry.getKey());
                    ar.setSignal(sig.getSignal());
                    ar.setConfidence(sig.getConfidence());
                    ta.addAnalystResult(ar);
                }
            }
        }

        return ta;
    }

    // ═══════════════════════════════════════
    //  WEEKLY OUTCOME REVIEW
    // ═══════════════════════════════════════

    /**
     * Reviews all pending analyses older than 7 days, fetches actual prices,
     * and evaluates whether forecasts were accurate.
     */
    @Transactional
    public Map<String, Object> reviewOutcomes() {
        List<TickerAnalysis> pending = tickerRepo.findPendingOutcomes();
        int reviewed = 0, profit = 0, loss = 0, breakeven = 0, skipped = 0;

        LocalDate today = LocalDate.now();

        for (TickerAnalysis ta : pending) {
            LocalDate analysisDate = ta.getAnalysisRun().getAnalysisDate();
            if (analysisDate == null) { skipped++; continue; }

            long daysSince = java.time.temporal.ChronoUnit.DAYS.between(analysisDate, today);
            if (daysSince < 7) { skipped++; continue; } // too early to review

            try {
                // Fetch actual prices at 1w, 2w, 4w
                String endDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
                List<Price> prices = apiClient.getPrices(ta.getTicker(),
                        analysisDate.format(DateTimeFormatter.ISO_LOCAL_DATE), endDate);

                if (prices == null || prices.isEmpty()) { skipped++; continue; }

                // Find prices at approximately 1w, 2w, 4w
                Double price1w = findPriceAtOffset(prices, analysisDate, 7);
                Double price2w = findPriceAtOffset(prices, analysisDate, 14);
                Double price4w = daysSince >= 28 ? findPriceAtOffset(prices, analysisDate, 28) : null;

                ta.setActualPrice1w(price1w);
                ta.setActualPrice2w(price2w);
                ta.setActualPrice4w(price4w);

                // Evaluate against entry/TP/SL
                Double entry = ta.getEntryPoint();
                Double reviewPrice = price2w != null ? price2w : price1w;
                if (entry != null && entry > 0 && reviewPrice != null) {
                    boolean isLong = "LONG".equalsIgnoreCase(ta.getPosition());
                    double pnl = isLong ? (reviewPrice - entry) / entry * 100
                                        : (entry - reviewPrice) / entry * 100;
                    ta.setPnlPct(Math.round(pnl * 100) / 100.0);

                    // Check if TP1 was hit (check all prices in the range)
                    if (ta.getTp1() != null) {
                        boolean tp1Hit = prices.stream().anyMatch(p ->
                                isLong ? p.getHigh() >= ta.getTp1() : p.getLow() <= ta.getTp1());
                        ta.setTp1Hit(tp1Hit);
                    }

                    // Check if SL was hit
                    if (ta.getStopLoss() != null) {
                        boolean slHit = prices.stream().anyMatch(p ->
                                isLong ? p.getLow() <= ta.getStopLoss() : p.getHigh() >= ta.getStopLoss());
                        ta.setSlHit(slHit);
                    }

                    if (pnl > 1.0) { ta.setOutcome("profit"); profit++; }
                    else if (pnl < -1.0) { ta.setOutcome("loss"); loss++; }
                    else { ta.setOutcome("breakeven"); breakeven++; }
                    reviewed++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warning("Failed to review outcome for " + ta.getTicker() + ": " + e.getMessage());
                skipped++;
            }
        }

        tickerRepo.saveAll(pending);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_pending", pending.size());
        summary.put("reviewed", reviewed);
        summary.put("profit", profit);
        summary.put("loss", loss);
        summary.put("breakeven", breakeven);
        summary.put("skipped_too_early", skipped);
        summary.put("accuracy_pct", reviewed > 0 ? Math.round((double) profit / reviewed * 100) : 0);
        return summary;
    }

    private Double findPriceAtOffset(List<Price> prices, LocalDate analysisDate, int daysOffset) {
        LocalDate target = analysisDate.plusDays(daysOffset);
        // Find closest price to target date
        return prices.stream()
                .filter(p -> p.getTime() != null)
                .filter(p -> !LocalDate.parse(p.getTime().substring(0, 10)).isBefore(target.minusDays(2)))
                .filter(p -> !LocalDate.parse(p.getTime().substring(0, 10)).isAfter(target.plusDays(2)))
                .findFirst()
                .map(Price::getClose)
                .orElse(null);
    }

    // ═══════════════════════════════════════
    //  QUERY METHODS
    // ═══════════════════════════════════════

    public List<AnalysisRun> getRecentRuns(int limit) {
        return runRepo.findRecentRuns(PageRequest.of(0, limit));
    }

    public Optional<AnalysisRun> getRunById(Long id) {
        return runRepo.findById(id);
    }

    public List<AnalysisRun> getRunsByDateRange(LocalDate from, LocalDate to) {
        return runRepo.findByAnalysisDateBetweenOrderByCreatedAtDesc(from, to);
    }

    public List<TickerAnalysis> getTickerHistory(String ticker) {
        return tickerRepo.findByTickerOrderByAnalysisRun_CreatedAtDesc(ticker);
    }

    public List<TickerAnalysis> getTickerHistory(String ticker, LocalDate from, LocalDate to) {
        return tickerRepo.findByTickerAndDateRange(ticker, from, to);
    }

    public Map<String, Object> getAccuracyStats(String ticker) {
        List<Object[]> stats = ticker != null
                ? tickerRepo.countByOutcomeForTicker(ticker)
                : tickerRepo.countByOutcome();

        Map<String, Object> result = new LinkedHashMap<>();
        long total = 0, profits = 0;
        for (Object[] row : stats) {
            String outcome = (String) row[0];
            Long count = (Long) row[1];
            result.put(outcome, count);
            total += count;
            if ("profit".equals(outcome)) profits = count;
        }
        result.put("total", total);
        result.put("accuracy_pct", total > 0 ? Math.round((double) profits / total * 100) : 0);
        return result;
    }

    // --- Helpers ---
    private static String str(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v != null ? v.toString() : null;
    }
    private static Double dbl(Map<?, ?> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) try { return Double.parseDouble(v.toString()); } catch (Exception ignored) {}
        return null;
    }
    private static Integer intVal(Map<?, ?> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.intValue();
        if (v != null) try { return Integer.parseInt(v.toString()); } catch (Exception ignored) {}
        return null;
    }
    private static Boolean bool(Map<?, ?> m, String k) {
        Object v = m.get(k);
        if (v instanceof Boolean b) return b;
        if (v != null) return Boolean.parseBoolean(v.toString());
        return null;
    }
}

