package com.hedgefund.ai.model.api;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LineItem {
    private String ticker;
    @JsonProperty("report_period") private String reportPeriod;
    private String period;
    private String currency;
    private Map<String, Object> extras = new HashMap<>();

    public LineItem() {}

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }
    public String getReportPeriod() { return reportPeriod; }
    public void setReportPeriod(String reportPeriod) { this.reportPeriod = reportPeriod; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    @JsonAnySetter
    public void setExtra(String key, Object value) { extras.put(key, value); }

    @JsonAnyGetter
    public Map<String, Object> getExtras() { return extras; }

    public Double getNumericField(String fieldName) {
        Object val = extras.get(fieldName);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return null;
    }
}
