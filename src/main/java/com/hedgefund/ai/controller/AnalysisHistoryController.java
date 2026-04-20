package com.hedgefund.ai.controller;

import com.hedgefund.ai.model.persistence.AnalysisRun;
import com.hedgefund.ai.model.persistence.TickerAnalysis;
import com.hedgefund.ai.service.AnalysisPersistenceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * REST API for querying historical analysis data and reviewing forecast accuracy.
 * Only available when hedgefund.persistence.enabled=true.
 */
@RestController
@RequestMapping("/api/history")
@CrossOrigin(origins = "*")
@ConditionalOnProperty(name = "hedgefund.persistence.enabled", havingValue = "true")
public class AnalysisHistoryController {

    private final AnalysisPersistenceService persistenceService;

    public AnalysisHistoryController(AnalysisPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    /** GET /api/history/runs?limit=20 — Recent analysis runs */
    @GetMapping("/runs")
    public ResponseEntity<List<Map<String, Object>>> getRecentRuns(
            @RequestParam(defaultValue = "20") int limit) {
        List<AnalysisRun> runs = persistenceService.getRecentRuns(limit);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AnalysisRun run : runs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", run.getId());
            m.put("analysisDate", run.getAnalysisDate());
            m.put("tickers", run.getTickers());
            m.put("modelProvider", run.getModelProvider());
            m.put("modelName", run.getModelName());
            m.put("selectedAnalysts", run.getSelectedAnalysts());
            m.put("createdAt", run.getCreatedAt());
            m.put("tickerCount", run.getTickerAnalyses().size());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    /** GET /api/history/runs/{id} — Full analysis run with all ticker details */
    @GetMapping("/runs/{id}")
    public ResponseEntity<?> getRunById(@PathVariable Long id) {
        return persistenceService.getRunById(id)
                .map(run -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", run.getId());
                    m.put("analysisDate", run.getAnalysisDate());
                    m.put("tickers", run.getTickers());
                    m.put("modelProvider", run.getModelProvider());
                    m.put("modelName", run.getModelName());
                    m.put("createdAt", run.getCreatedAt());
                    m.put("report", run.getReport());
                    List<Map<String, Object>> tickers = new ArrayList<>();
                    for (TickerAnalysis ta : run.getTickerAnalyses()) {
                        tickers.add(tickerToMap(ta));
                    }
                    m.put("tickerAnalyses", tickers);
                    return ResponseEntity.ok(m);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** GET /api/history/runs/{id}/report — Get stored Markdown report */
    @GetMapping(value = "/runs/{id}/report", produces = "text/markdown; charset=UTF-8")
    public ResponseEntity<String> getRunReport(@PathVariable Long id) {
        return persistenceService.getRunById(id)
                .map(run -> ResponseEntity.ok(run.getReport()))
                .orElse(ResponseEntity.notFound().build());
    }

    /** GET /api/history/ticker/{ticker} — History for a specific ticker */
    @GetMapping("/ticker/{ticker}")
    public ResponseEntity<List<Map<String, Object>>> getTickerHistory(
            @PathVariable String ticker,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        List<TickerAnalysis> history;
        if (from != null && to != null) {
            history = persistenceService.getTickerHistory(ticker.toUpperCase(),
                    LocalDate.parse(from), LocalDate.parse(to));
        } else {
            history = persistenceService.getTickerHistory(ticker.toUpperCase());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (TickerAnalysis ta : history) {
            result.add(tickerToMap(ta));
        }
        return ResponseEntity.ok(result);
    }

    /** POST /api/history/review — Run weekly outcome review (fetches actual prices) */
    @PostMapping("/review")
    public ResponseEntity<Map<String, Object>> reviewOutcomes() {
        Map<String, Object> summary = persistenceService.reviewOutcomes();
        return ResponseEntity.ok(summary);
    }

    /** GET /api/history/accuracy?ticker=AAPL — Forecast accuracy stats */
    @GetMapping("/accuracy")
    public ResponseEntity<Map<String, Object>> getAccuracyStats(
            @RequestParam(required = false) String ticker) {
        Map<String, Object> stats = persistenceService.getAccuracyStats(
                ticker != null ? ticker.toUpperCase() : null);
        return ResponseEntity.ok(stats);
    }

    /** GET /api/history/compare?ticker=AAPL&from=2026-01-01&to=2026-04-20 — Comparative analysis */
    @GetMapping("/compare")
    public ResponseEntity<Map<String, Object>> comparativeAnalysis(
            @RequestParam String ticker,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LocalDate fromDate = from != null ? LocalDate.parse(from) : LocalDate.now().minusMonths(3);
        LocalDate toDate = to != null ? LocalDate.parse(to) : LocalDate.now();

        List<TickerAnalysis> history = persistenceService.getTickerHistory(
                ticker.toUpperCase(), fromDate, toDate);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticker", ticker.toUpperCase());
        result.put("period", fromDate + " to " + toDate);
        result.put("totalAnalyses", history.size());

        // Build trend data
        List<Map<String, Object>> trend = new ArrayList<>();
        int profits = 0, losses = 0;
        for (TickerAnalysis ta : history) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", ta.getAnalysisRun().getAnalysisDate());
            point.put("position", ta.getPosition());
            point.put("entryPoint", ta.getEntryPoint());
            point.put("confidence", ta.getConfidence());
            point.put("signalFired", ta.getSignalFired());
            point.put("outcome", ta.getOutcome());
            point.put("pnlPct", ta.getPnlPct());
            point.put("actualPrice1w", ta.getActualPrice1w());
            point.put("tp1Hit", ta.getTp1Hit());
            point.put("slHit", ta.getSlHit());
            trend.add(point);
            if ("profit".equals(ta.getOutcome())) profits++;
            else if ("loss".equals(ta.getOutcome())) losses++;
        }
        result.put("trend", trend);
        result.put("profits", profits);
        result.put("losses", losses);
        int reviewed = profits + losses;
        result.put("winRate", reviewed > 0 ? Math.round((double) profits / reviewed * 100) + "%" : "N/A");

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> tickerToMap(TickerAnalysis ta) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ta.getId());
        m.put("ticker", ta.getTicker());
        m.put("analysisDate", ta.getAnalysisRun().getAnalysisDate());
        m.put("position", ta.getPosition());
        m.put("currentPrice", ta.getCurrentPrice());
        m.put("entryPoint", ta.getEntryPoint());
        m.put("stopLoss", ta.getStopLoss());
        m.put("tp1", ta.getTp1());
        m.put("tp2", ta.getTp2());
        m.put("tp3", ta.getTp3());
        m.put("tp4", ta.getTp4());
        m.put("tp5", ta.getTp5());
        m.put("bollingerUpper", ta.getBollingerUpper());
        m.put("bollingerMiddle", ta.getBollingerMiddle());
        m.put("bollingerLower", ta.getBollingerLower());
        m.put("supportLevel", ta.getSupportLevel());
        m.put("resistanceLevel", ta.getResistanceLevel());
        m.put("fib618", ta.getFib618());
        m.put("rsi14", ta.getRsi14());
        m.put("macd", ta.getMacd());
        m.put("adx", ta.getAdx());
        m.put("confidence", ta.getConfidence());
        m.put("confluenceScore", ta.getConfluenceScore());
        m.put("signalFired", ta.getSignalFired());
        m.put("regime", ta.getRegime());
        m.put("newsSignal", ta.getNewsSignal());
        m.put("portfolioAction", ta.getPortfolioAction());
        m.put("techAction", ta.getTechAction());
        // Outcome review data
        m.put("actualPrice1w", ta.getActualPrice1w());
        m.put("actualPrice2w", ta.getActualPrice2w());
        m.put("actualPrice4w", ta.getActualPrice4w());
        m.put("tp1Hit", ta.getTp1Hit());
        m.put("slHit", ta.getSlHit());
        m.put("outcome", ta.getOutcome());
        m.put("pnlPct", ta.getPnlPct());
        return m;
    }
}

