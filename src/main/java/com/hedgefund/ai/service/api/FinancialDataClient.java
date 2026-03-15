package com.hedgefund.ai.service.api;

import com.hedgefund.ai.model.api.*;

import java.util.List;

/**
 * Abstraction over financial data providers.
 * <p>
 * Implementations:
 * <ul>
 *   <li>{@link FinancialDatasetsClient} — financialdatasets.ai</li>
 *   <li>{@link com.hedgefund.ai.service.api.EodhdClient}            — EODHD (eodhd.com)</li>
 * </ul>
 */
public interface FinancialDataClient {

    /** Fetch daily OHLCV prices for a ticker in the given date range. */
    List<Price> getPrices(String ticker, String startDate, String endDate);

    /** Fetch financial metrics (ratios, margins, growth, etc.) for a ticker. */
    List<FinancialMetrics> getFinancialMetrics(String ticker, String endDate, String period, int limit);

    /** Search for specific financial line items (income-statement / balance-sheet / cash-flow). */
    List<LineItem> searchLineItems(String ticker, List<String> lineItems,
                                   String endDate, String period, int limit);

    /** Fetch insider / officer trades for a ticker. */
    List<InsiderTrade> getInsiderTrades(String ticker, String endDate, int limit);

    /** Fetch recent company news for a ticker. */
    List<CompanyNews> getCompanyNews(String ticker, String endDate, int limit);

    /** Fetch the market capitalisation (in USD) for a ticker as of a date. */
    Double getMarketCap(String ticker, String endDate);
}

