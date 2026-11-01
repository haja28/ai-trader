package com.hedgefund.ai.controller;

import com.hedgefund.ai.chart.ChartResult;
import com.hedgefund.ai.chart.SignalConfig;
import com.hedgefund.ai.chart.SignalEngine;
import com.hedgefund.ai.model.api.Price;
import com.hedgefund.ai.service.api.FinancialDataClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * Serves candlestick data plus BUY/SELL confluence signals for a ticker.
 */
@RestController
@RequestMapping("/api/chart")
@CrossOrigin(origins = "*")
public class ChartController {

    private final FinancialDataClient apiClient;
    private final SignalEngine engine = new SignalEngine();

    public ChartController(FinancialDataClient apiClient) {
        this.apiClient = apiClient;
    }

    private static final java.util.Set<String> TIMEFRAMES = java.util.Set.of("5m", "15m", "1h", "4h", "1D");

    @GetMapping("/{ticker}")
    public ChartResult chart(@PathVariable String ticker,
                             @RequestParam(defaultValue = "1D") String timeframe,
                             @RequestParam(required = false) Integer days,
                             @RequestParam(defaultValue = "swing") String preset) {
        if (!ticker.matches("[A-Za-z0-9.\\-^=]{1,15}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ticker");
        }
        String tf = timeframe.equalsIgnoreCase("1d") ? "1D" : timeframe.toLowerCase();
        if (!TIMEFRAMES.contains(tf)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported timeframe: " + timeframe);
        }

        // Provider intraday history limits: 5m ~120 days per request, 1h ~600 days.
        int maxDays = switch (tf) { case "5m", "15m" -> 100; case "1h", "4h" -> 500; default -> 3650; };
        int defDays = switch (tf) { case "5m" -> 10; case "15m" -> 30; case "1h" -> 90; case "4h" -> 365; default -> 730; };
        int d = Math.max(5, Math.min(days == null ? defDays : days, maxDays));

        String sym = ticker.toUpperCase();
        LocalDate end = LocalDate.now();
        String from = end.minusDays(d).toString();
        String to = end.toString();

        List<Price> prices;
        if (tf.equals("1D")) {
            prices = apiClient.getPrices(sym, from, to);
        } else {
            String base = (tf.equals("5m") || tf.equals("15m")) ? "5m" : "1h";
            prices = apiClient.getIntradayPrices(sym, base, from, to);
            if (prices == null || prices.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No intraday data for " + sym + ". Intraday requires the EODHD provider and a plan with intraday access.");
            }
            if (tf.equals("15m")) prices = SignalEngine.aggregate(prices, 900);
            if (tf.equals("4h")) prices = SignalEngine.aggregate(prices, 14400);
        }
        if (prices == null || prices.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No price data for " + sym);
        }
        return engine.analyze(sym, prices, SignalConfig.of(preset), tf);
    }
}

