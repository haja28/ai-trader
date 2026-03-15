package com.hedgefund.ai.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CompanyNews {
    private String ticker;
    private String title;
    private String author;
    private String source;
    private String date;
    private String url;
    private String sentiment;

    public CompanyNews() {}

    public String getTicker() { return ticker; }
    public void setTicker(String v) { this.ticker = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getAuthor() { return author; }
    public void setAuthor(String v) { this.author = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getDate() { return date; }
    public void setDate(String v) { this.date = v; }
    public String getUrl() { return url; }
    public void setUrl(String v) { this.url = v; }
    public String getSentiment() { return sentiment; }
    public void setSentiment(String v) { this.sentiment = v; }
}
