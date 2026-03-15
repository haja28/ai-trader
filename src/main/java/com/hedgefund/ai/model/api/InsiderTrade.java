package com.hedgefund.ai.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InsiderTrade {
    private String ticker;
    private String issuer;
    private String name;
    private String title;
    @JsonProperty("is_board_director") private Boolean isBoardDirector;
    @JsonProperty("transaction_date") private String transactionDate;
    @JsonProperty("transaction_shares") private Double transactionShares;
    @JsonProperty("transaction_price_per_share") private Double transactionPricePerShare;
    @JsonProperty("transaction_value") private Double transactionValue;
    @JsonProperty("shares_owned_before_transaction") private Double sharesOwnedBeforeTransaction;
    @JsonProperty("shares_owned_after_transaction") private Double sharesOwnedAfterTransaction;
    @JsonProperty("security_title") private String securityTitle;
    @JsonProperty("filing_date") private String filingDate;

    public InsiderTrade() {}

    public String getTicker() { return ticker; }
    public void setTicker(String v) { this.ticker = v; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String v) { this.issuer = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public Boolean getIsBoardDirector() { return isBoardDirector; }
    public void setIsBoardDirector(Boolean v) { this.isBoardDirector = v; }
    public String getTransactionDate() { return transactionDate; }
    public void setTransactionDate(String v) { this.transactionDate = v; }
    public Double getTransactionShares() { return transactionShares; }
    public void setTransactionShares(Double v) { this.transactionShares = v; }
    public Double getTransactionPricePerShare() { return transactionPricePerShare; }
    public void setTransactionPricePerShare(Double v) { this.transactionPricePerShare = v; }
    public Double getTransactionValue() { return transactionValue; }
    public void setTransactionValue(Double v) { this.transactionValue = v; }
    public Double getSharesOwnedBeforeTransaction() { return sharesOwnedBeforeTransaction; }
    public void setSharesOwnedBeforeTransaction(Double v) { this.sharesOwnedBeforeTransaction = v; }
    public Double getSharesOwnedAfterTransaction() { return sharesOwnedAfterTransaction; }
    public void setSharesOwnedAfterTransaction(Double v) { this.sharesOwnedAfterTransaction = v; }
    public String getSecurityTitle() { return securityTitle; }
    public void setSecurityTitle(String v) { this.securityTitle = v; }
    public String getFilingDate() { return filingDate; }
    public void setFilingDate(String v) { this.filingDate = v; }
}
