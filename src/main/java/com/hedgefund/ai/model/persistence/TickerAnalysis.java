package com.hedgefund.ai.model.persistence;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ticker_analyses", indexes = {
        @Index(name = "idx_ta_ticker", columnList = "ticker"),
        @Index(name = "idx_ta_run", columnList = "analysis_run_id")
})
public class TickerAnalysis {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_run_id", nullable = false)
    private AnalysisRun analysisRun;

    @Column(length = 20, nullable = false) private String ticker;
    @Column(length = 10) private String position;
    private Double entryPoint;
    private Double stopLoss;
    private Double tp1;
    private Double tp2;
    private Double tp3;
    private Double tp4;
    private Double tp5;
    private Double bollingerUpper;
    private Double bollingerMiddle;
    private Double bollingerLower;
    private Double fibSwingHigh;
    private Double fibSwingLow;
    private Double fib236;
    private Double fib382;
    private Double fib500;
    private Double fib618;
    private Double fib786;
    private Double supportLevel;
    private Double resistanceLevel;
    private Double rsi14;
    private Double macd;
    private Double macdHistogram;
    private Double atr14;
    private Double atrPct;
    private Double adx;
    private Double vwap;
    private Double currentPrice;
    private Double confidence;
    private Integer confluenceScore;
    private Double weightedScore;
    private Boolean signalFired;
    @Column(length = 200) private String algorithm;
    @Column(length = 30) private String regime;
    private Double newsSentiment;
    @Column(length = 20) private String newsSignal;
    private Integer newsArticleCount;
    @Column(length = 20) private String portfolioAction;
    private Integer portfolioQuantity;
    private Integer portfolioConfidence;
    @Column(length = 500) private String portfolioReasoning;
    @Column(length = 10) private String techAction;
    private Double techConfidence;
    private Double actualPrice1w;
    private Double actualPrice2w;
    private Double actualPrice4w;
    private Boolean tp1Hit;
    private Boolean slHit;
    @Column(length = 20) private String outcome;
    private Double pnlPct;

    @OneToMany(mappedBy = "tickerAnalysis", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AnalystResult> analystResults = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public AnalysisRun getAnalysisRun() { return analysisRun; }
    public void setAnalysisRun(AnalysisRun v) { this.analysisRun = v; }
    public String getTicker() { return ticker; }
    public void setTicker(String v) { this.ticker = v; }
    public String getPosition() { return position; }
    public void setPosition(String v) { this.position = v; }
    public Double getEntryPoint() { return entryPoint; }
    public void setEntryPoint(Double v) { this.entryPoint = v; }
    public Double getStopLoss() { return stopLoss; }
    public void setStopLoss(Double v) { this.stopLoss = v; }
    public Double getTp1() { return tp1; }
    public void setTp1(Double v) { this.tp1 = v; }
    public Double getTp2() { return tp2; }
    public void setTp2(Double v) { this.tp2 = v; }
    public Double getTp3() { return tp3; }
    public void setTp3(Double v) { this.tp3 = v; }
    public Double getTp4() { return tp4; }
    public void setTp4(Double v) { this.tp4 = v; }
    public Double getTp5() { return tp5; }
    public void setTp5(Double v) { this.tp5 = v; }
    public Double getBollingerUpper() { return bollingerUpper; }
    public void setBollingerUpper(Double v) { this.bollingerUpper = v; }
    public Double getBollingerMiddle() { return bollingerMiddle; }
    public void setBollingerMiddle(Double v) { this.bollingerMiddle = v; }
    public Double getBollingerLower() { return bollingerLower; }
    public void setBollingerLower(Double v) { this.bollingerLower = v; }
    public Double getFibSwingHigh() { return fibSwingHigh; }
    public void setFibSwingHigh(Double v) { this.fibSwingHigh = v; }
    public Double getFibSwingLow() { return fibSwingLow; }
    public void setFibSwingLow(Double v) { this.fibSwingLow = v; }
    public Double getFib236() { return fib236; }
    public void setFib236(Double v) { this.fib236 = v; }
    public Double getFib382() { return fib382; }
    public void setFib382(Double v) { this.fib382 = v; }
    public Double getFib500() { return fib500; }
    public void setFib500(Double v) { this.fib500 = v; }
    public Double getFib618() { return fib618; }
    public void setFib618(Double v) { this.fib618 = v; }
    public Double getFib786() { return fib786; }
    public void setFib786(Double v) { this.fib786 = v; }
    public Double getSupportLevel() { return supportLevel; }
    public void setSupportLevel(Double v) { this.supportLevel = v; }
    public Double getResistanceLevel() { return resistanceLevel; }
    public void setResistanceLevel(Double v) { this.resistanceLevel = v; }
    public Double getRsi14() { return rsi14; }
    public void setRsi14(Double v) { this.rsi14 = v; }
    public Double getMacd() { return macd; }
    public void setMacd(Double v) { this.macd = v; }
    public Double getMacdHistogram() { return macdHistogram; }
    public void setMacdHistogram(Double v) { this.macdHistogram = v; }
    public Double getAtr14() { return atr14; }
    public void setAtr14(Double v) { this.atr14 = v; }
    public Double getAtrPct() { return atrPct; }
    public void setAtrPct(Double v) { this.atrPct = v; }
    public Double getAdx() { return adx; }
    public void setAdx(Double v) { this.adx = v; }
    public Double getVwap() { return vwap; }
    public void setVwap(Double v) { this.vwap = v; }
    public Double getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(Double v) { this.currentPrice = v; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double v) { this.confidence = v; }
    public Integer getConfluenceScore() { return confluenceScore; }
    public void setConfluenceScore(Integer v) { this.confluenceScore = v; }
    public Double getWeightedScore() { return weightedScore; }
    public void setWeightedScore(Double v) { this.weightedScore = v; }
    public Boolean getSignalFired() { return signalFired; }
    public void setSignalFired(Boolean v) { this.signalFired = v; }
    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String v) { this.algorithm = v; }
    public String getRegime() { return regime; }
    public void setRegime(String v) { this.regime = v; }
    public Double getNewsSentiment() { return newsSentiment; }
    public void setNewsSentiment(Double v) { this.newsSentiment = v; }
    public String getNewsSignal() { return newsSignal; }
    public void setNewsSignal(String v) { this.newsSignal = v; }
    public Integer getNewsArticleCount() { return newsArticleCount; }
    public void setNewsArticleCount(Integer v) { this.newsArticleCount = v; }
    public String getPortfolioAction() { return portfolioAction; }
    public void setPortfolioAction(String v) { this.portfolioAction = v; }
    public Integer getPortfolioQuantity() { return portfolioQuantity; }
    public void setPortfolioQuantity(Integer v) { this.portfolioQuantity = v; }
    public Integer getPortfolioConfidence() { return portfolioConfidence; }
    public void setPortfolioConfidence(Integer v) { this.portfolioConfidence = v; }
    public String getPortfolioReasoning() { return portfolioReasoning; }
    public void setPortfolioReasoning(String v) { this.portfolioReasoning = v; }
    public String getTechAction() { return techAction; }
    public void setTechAction(String v) { this.techAction = v; }
    public Double getTechConfidence() { return techConfidence; }
    public void setTechConfidence(Double v) { this.techConfidence = v; }
    public Double getActualPrice1w() { return actualPrice1w; }
    public void setActualPrice1w(Double v) { this.actualPrice1w = v; }
    public Double getActualPrice2w() { return actualPrice2w; }
    public void setActualPrice2w(Double v) { this.actualPrice2w = v; }
    public Double getActualPrice4w() { return actualPrice4w; }
    public void setActualPrice4w(Double v) { this.actualPrice4w = v; }
    public Boolean getTp1Hit() { return tp1Hit; }
    public void setTp1Hit(Boolean v) { this.tp1Hit = v; }
    public Boolean getSlHit() { return slHit; }
    public void setSlHit(Boolean v) { this.slHit = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public Double getPnlPct() { return pnlPct; }
    public void setPnlPct(Double v) { this.pnlPct = v; }
    public List<AnalystResult> getAnalystResults() { return analystResults; }
    public void setAnalystResults(List<AnalystResult> v) { this.analystResults = v; }

    public void addAnalystResult(AnalystResult ar) {
        analystResults.add(ar);
        ar.setTickerAnalysis(this);
    }
}

