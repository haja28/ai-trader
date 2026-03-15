package com.hedgefund.ai.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CompanyFacts {
    private String ticker;
    private String name;
    private String cik;
    private String industry;
    private String sector;
    private String category;
    private String exchange;
    @JsonProperty("is_active") private Boolean isActive;
    @JsonProperty("listing_date") private String listingDate;
    private String location;
    @JsonProperty("market_cap") private Double marketCap;
    @JsonProperty("number_of_employees") private Integer numberOfEmployees;
    @JsonProperty("website_url") private String websiteUrl;

    public CompanyFacts() {}

    public String getTicker() { return ticker; }
    public void setTicker(String v) { this.ticker = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getCik() { return cik; }
    public void setCik(String v) { this.cik = v; }
    public String getIndustry() { return industry; }
    public void setIndustry(String v) { this.industry = v; }
    public String getSector() { return sector; }
    public void setSector(String v) { this.sector = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getExchange() { return exchange; }
    public void setExchange(String v) { this.exchange = v; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean v) { this.isActive = v; }
    public String getListingDate() { return listingDate; }
    public void setListingDate(String v) { this.listingDate = v; }
    public String getLocation() { return location; }
    public void setLocation(String v) { this.location = v; }
    public Double getMarketCap() { return marketCap; }
    public void setMarketCap(Double v) { this.marketCap = v; }
    public Integer getNumberOfEmployees() { return numberOfEmployees; }
    public void setNumberOfEmployees(Integer v) { this.numberOfEmployees = v; }
    public String getWebsiteUrl() { return websiteUrl; }
    public void setWebsiteUrl(String v) { this.websiteUrl = v; }
}
