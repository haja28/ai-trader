package com.hedgefund.ai.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class ApiResponses {
    private ApiResponses() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PriceResponse {
        private String ticker;
        private List<Price> prices;
        public PriceResponse() {}
        public String getTicker() { return ticker; }
        public void setTicker(String v) { this.ticker = v; }
        public List<Price> getPrices() { return prices; }
        public void setPrices(List<Price> v) { this.prices = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FinancialMetricsResponse {
        @JsonProperty("financial_metrics") private List<FinancialMetrics> financialMetrics;
        public FinancialMetricsResponse() {}
        public List<FinancialMetrics> getFinancialMetrics() { return financialMetrics; }
        public void setFinancialMetrics(List<FinancialMetrics> v) { this.financialMetrics = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LineItemResponse {
        @JsonProperty("search_results") private List<LineItem> searchResults;
        public LineItemResponse() {}
        public List<LineItem> getSearchResults() { return searchResults; }
        public void setSearchResults(List<LineItem> v) { this.searchResults = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InsiderTradeResponse {
        @JsonProperty("insider_trades") private List<InsiderTrade> insiderTrades;
        public InsiderTradeResponse() {}
        public List<InsiderTrade> getInsiderTrades() { return insiderTrades; }
        public void setInsiderTrades(List<InsiderTrade> v) { this.insiderTrades = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompanyNewsResponse {
        private List<CompanyNews> news;
        public CompanyNewsResponse() {}
        public List<CompanyNews> getNews() { return news; }
        public void setNews(List<CompanyNews> v) { this.news = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompanyFactsResponse {
        @JsonProperty("company_facts") private CompanyFacts companyFacts;
        public CompanyFactsResponse() {}
        public CompanyFacts getCompanyFacts() { return companyFacts; }
        public void setCompanyFacts(CompanyFacts v) { this.companyFacts = v; }
    }
}
