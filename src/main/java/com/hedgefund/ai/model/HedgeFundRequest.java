package com.hedgefund.ai.model;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public class HedgeFundRequest {
    @NotEmpty(message = "At least one ticker is required")
    private List<String> tickers;
    private String startDate;
    private String endDate;
    private double initialCash = 100_000.0;
    private double marginRequirement = 0.5;
    private List<String> selectedAnalysts;
    private boolean showReasoning = false;
    private String modelName;
    private String modelProvider;

    public HedgeFundRequest() {}

    public List<String> getTickers() { return tickers; }
    public void setTickers(List<String> v) { this.tickers = v; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String v) { this.startDate = v; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String v) { this.endDate = v; }
    public double getInitialCash() { return initialCash; }
    public void setInitialCash(double v) { this.initialCash = v; }
    public double getMarginRequirement() { return marginRequirement; }
    public void setMarginRequirement(double v) { this.marginRequirement = v; }
    public List<String> getSelectedAnalysts() { return selectedAnalysts; }
    public void setSelectedAnalysts(List<String> v) { this.selectedAnalysts = v; }
    public boolean isShowReasoning() { return showReasoning; }
    public void setShowReasoning(boolean v) { this.showReasoning = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { this.modelName = v; }
    public String getModelProvider() { return modelProvider; }
    public void setModelProvider(String v) { this.modelProvider = v; }
}
