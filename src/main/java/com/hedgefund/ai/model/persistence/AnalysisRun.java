package com.hedgefund.ai.model.persistence;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Root entity for a single hedge fund analysis run.
 * Stores the request metadata and the generated report.
 */
@Entity
@Table(name = "analysis_runs", indexes = {
        @Index(name = "idx_run_date", columnList = "analysisDate"),
        @Index(name = "idx_run_created", columnList = "createdAt")
})
public class AnalysisRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Date the analysis was performed */
    private LocalDate analysisDate;

    /** Comma-separated ticker list */
    @Column(length = 500)
    private String tickers;

    /** Model provider used (OLLAMA, OPENAI) */
    @Column(length = 50)
    private String modelProvider;

    /** Model name used (e.g. gemma3:4b, gpt-4.1) */
    @Column(length = 100)
    private String modelName;

    /** Comma-separated selected analyst IDs */
    @Column(length = 500)
    private String selectedAnalysts;

    /** Full Markdown report */
    @Column(columnDefinition = "LONGTEXT")
    private String report;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "analysisRun", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TickerAnalysis> tickerAnalyses = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.analysisDate == null) this.analysisDate = LocalDate.now();
    }

    // --- Getters / Setters ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getAnalysisDate() { return analysisDate; }
    public void setAnalysisDate(LocalDate v) { this.analysisDate = v; }
    public String getTickers() { return tickers; }
    public void setTickers(String v) { this.tickers = v; }
    public String getModelProvider() { return modelProvider; }
    public void setModelProvider(String v) { this.modelProvider = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { this.modelName = v; }
    public String getSelectedAnalysts() { return selectedAnalysts; }
    public void setSelectedAnalysts(String v) { this.selectedAnalysts = v; }
    public String getReport() { return report; }
    public void setReport(String v) { this.report = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public List<TickerAnalysis> getTickerAnalyses() { return tickerAnalyses; }
    public void setTickerAnalyses(List<TickerAnalysis> v) { this.tickerAnalyses = v; }

    public void addTickerAnalysis(TickerAnalysis ta) {
        tickerAnalyses.add(ta);
        ta.setAnalysisRun(this);
    }
}

