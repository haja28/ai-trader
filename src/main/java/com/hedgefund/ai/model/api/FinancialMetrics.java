package com.hedgefund.ai.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Financial metrics for a company (TTM or periodic).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FinancialMetrics {
    private String ticker;
    @JsonProperty("report_period") private String reportPeriod;
    private String period;
    private String currency;
    @JsonProperty("market_cap") private Double marketCap;
    @JsonProperty("enterprise_value") private Double enterpriseValue;
    @JsonProperty("price_to_earnings_ratio") private Double priceToEarningsRatio;
    @JsonProperty("price_to_book_ratio") private Double priceToBookRatio;
    @JsonProperty("price_to_sales_ratio") private Double priceToSalesRatio;
    @JsonProperty("enterprise_value_to_ebitda_ratio") private Double enterpriseValueToEbitdaRatio;
    @JsonProperty("enterprise_value_to_revenue_ratio") private Double enterpriseValueToRevenueRatio;
    @JsonProperty("free_cash_flow_yield") private Double freeCashFlowYield;
    @JsonProperty("peg_ratio") private Double pegRatio;
    @JsonProperty("gross_margin") private Double grossMargin;
    @JsonProperty("operating_margin") private Double operatingMargin;
    @JsonProperty("net_margin") private Double netMargin;
    @JsonProperty("return_on_equity") private Double returnOnEquity;
    @JsonProperty("return_on_assets") private Double returnOnAssets;
    @JsonProperty("return_on_invested_capital") private Double returnOnInvestedCapital;
    @JsonProperty("current_ratio") private Double currentRatio;
    @JsonProperty("debt_to_equity") private Double debtToEquity;
    @JsonProperty("revenue_growth") private Double revenueGrowth;
    @JsonProperty("earnings_growth") private Double earningsGrowth;
    @JsonProperty("book_value_growth") private Double bookValueGrowth;
    @JsonProperty("earnings_per_share_growth") private Double earningsPerShareGrowth;
    @JsonProperty("free_cash_flow_growth") private Double freeCashFlowGrowth;
    @JsonProperty("operating_income_growth") private Double operatingIncomeGrowth;
    @JsonProperty("ebitda_growth") private Double ebitdaGrowth;
    @JsonProperty("payout_ratio") private Double payoutRatio;
    @JsonProperty("earnings_per_share") private Double earningsPerShare;
    @JsonProperty("book_value_per_share") private Double bookValuePerShare;
    @JsonProperty("free_cash_flow_per_share") private Double freeCashFlowPerShare;

    public FinancialMetrics() {}

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }
    public String getReportPeriod() { return reportPeriod; }
    public void setReportPeriod(String reportPeriod) { this.reportPeriod = reportPeriod; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Double getMarketCap() { return marketCap; }
    public void setMarketCap(Double marketCap) { this.marketCap = marketCap; }
    public Double getEnterpriseValue() { return enterpriseValue; }
    public void setEnterpriseValue(Double enterpriseValue) { this.enterpriseValue = enterpriseValue; }
    public Double getPriceToEarningsRatio() { return priceToEarningsRatio; }
    public void setPriceToEarningsRatio(Double v) { this.priceToEarningsRatio = v; }
    public Double getPriceToBookRatio() { return priceToBookRatio; }
    public void setPriceToBookRatio(Double v) { this.priceToBookRatio = v; }
    public Double getPriceToSalesRatio() { return priceToSalesRatio; }
    public void setPriceToSalesRatio(Double v) { this.priceToSalesRatio = v; }
    public Double getEnterpriseValueToEbitdaRatio() { return enterpriseValueToEbitdaRatio; }
    public void setEnterpriseValueToEbitdaRatio(Double v) { this.enterpriseValueToEbitdaRatio = v; }
    public Double getEnterpriseValueToRevenueRatio() { return enterpriseValueToRevenueRatio; }
    public void setEnterpriseValueToRevenueRatio(Double v) { this.enterpriseValueToRevenueRatio = v; }
    public Double getFreeCashFlowYield() { return freeCashFlowYield; }
    public void setFreeCashFlowYield(Double v) { this.freeCashFlowYield = v; }
    public Double getPegRatio() { return pegRatio; }
    public void setPegRatio(Double v) { this.pegRatio = v; }
    public Double getGrossMargin() { return grossMargin; }
    public void setGrossMargin(Double v) { this.grossMargin = v; }
    public Double getOperatingMargin() { return operatingMargin; }
    public void setOperatingMargin(Double v) { this.operatingMargin = v; }
    public Double getNetMargin() { return netMargin; }
    public void setNetMargin(Double v) { this.netMargin = v; }
    public Double getReturnOnEquity() { return returnOnEquity; }
    public void setReturnOnEquity(Double v) { this.returnOnEquity = v; }
    public Double getReturnOnAssets() { return returnOnAssets; }
    public void setReturnOnAssets(Double v) { this.returnOnAssets = v; }
    public Double getReturnOnInvestedCapital() { return returnOnInvestedCapital; }
    public void setReturnOnInvestedCapital(Double v) { this.returnOnInvestedCapital = v; }
    public Double getCurrentRatio() { return currentRatio; }
    public void setCurrentRatio(Double v) { this.currentRatio = v; }
    public Double getDebtToEquity() { return debtToEquity; }
    public void setDebtToEquity(Double v) { this.debtToEquity = v; }
    public Double getRevenueGrowth() { return revenueGrowth; }
    public void setRevenueGrowth(Double v) { this.revenueGrowth = v; }
    public Double getEarningsGrowth() { return earningsGrowth; }
    public void setEarningsGrowth(Double v) { this.earningsGrowth = v; }
    public Double getBookValueGrowth() { return bookValueGrowth; }
    public void setBookValueGrowth(Double v) { this.bookValueGrowth = v; }
    public Double getEarningsPerShareGrowth() { return earningsPerShareGrowth; }
    public void setEarningsPerShareGrowth(Double v) { this.earningsPerShareGrowth = v; }
    public Double getFreeCashFlowGrowth() { return freeCashFlowGrowth; }
    public void setFreeCashFlowGrowth(Double v) { this.freeCashFlowGrowth = v; }
    public Double getOperatingIncomeGrowth() { return operatingIncomeGrowth; }
    public void setOperatingIncomeGrowth(Double v) { this.operatingIncomeGrowth = v; }
    public Double getEbitdaGrowth() { return ebitdaGrowth; }
    public void setEbitdaGrowth(Double v) { this.ebitdaGrowth = v; }
    public Double getPayoutRatio() { return payoutRatio; }
    public void setPayoutRatio(Double v) { this.payoutRatio = v; }
    public Double getEarningsPerShare() { return earningsPerShare; }
    public void setEarningsPerShare(Double v) { this.earningsPerShare = v; }
    public Double getBookValuePerShare() { return bookValuePerShare; }
    public void setBookValuePerShare(Double v) { this.bookValuePerShare = v; }
    public Double getFreeCashFlowPerShare() { return freeCashFlowPerShare; }
    public void setFreeCashFlowPerShare(Double v) { this.freeCashFlowPerShare = v; }
}
