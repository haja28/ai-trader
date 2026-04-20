package com.hedgefund.ai.model.persistence;

import jakarta.persistence.*;

/**
 * Individual analyst signal for a ticker within an analysis run.
 */
@Entity
@Table(name = "analyst_results")
public class AnalystResult {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticker_analysis_id", nullable = false)
    private TickerAnalysis tickerAnalysis;

    @Column(length = 50, nullable = false)
    private String agentId;

    @Column(length = 20, name = "`signal`")
    private String signal;

    private Double confidence;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public TickerAnalysis getTickerAnalysis() { return tickerAnalysis; }
    public void setTickerAnalysis(TickerAnalysis v) { this.tickerAnalysis = v; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String v) { this.agentId = v; }
    public String getSignal() { return signal; }
    public void setSignal(String v) { this.signal = v; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double v) { this.confidence = v; }
}

