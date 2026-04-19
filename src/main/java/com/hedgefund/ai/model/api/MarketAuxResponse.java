package com.hedgefund.ai.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response model for MarketAux news API.
 * <a href="https://api.marketaux.com/v1/news/all">API Docs</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MarketAuxResponse {

    private Meta meta;
    private List<Article> data;

    public Meta getMeta() { return meta; }
    public void setMeta(Meta meta) { this.meta = meta; }
    public List<Article> getData() { return data; }
    public void setData(List<Article> data) { this.data = data; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Meta {
        private int found;
        private int returned;
        private int limit;
        private int page;

        public int getFound() { return found; }
        public void setFound(int found) { this.found = found; }
        public int getReturned() { return returned; }
        public void setReturned(int returned) { this.returned = returned; }
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Article {
        private String uuid;
        private String title;
        private String description;
        private String snippet;
        private String url;
        @JsonProperty("image_url")
        private String imageUrl;
        private String language;
        @JsonProperty("published_at")
        private String publishedAt;
        private String source;
        @JsonProperty("relevance_score")
        private Double relevanceScore;
        private List<Entity> entities;

        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getSnippet() { return snippet; }
        public void setSnippet(String snippet) { this.snippet = snippet; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public String getPublishedAt() { return publishedAt; }
        public void setPublishedAt(String publishedAt) { this.publishedAt = publishedAt; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public Double getRelevanceScore() { return relevanceScore; }
        public void setRelevanceScore(Double relevanceScore) { this.relevanceScore = relevanceScore; }
        public List<Entity> getEntities() { return entities; }
        public void setEntities(List<Entity> entities) { this.entities = entities; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entity {
        private String symbol;
        private String name;
        private String country;
        private String type;
        private String industry;
        @JsonProperty("match_score")
        private double matchScore;
        @JsonProperty("sentiment_score")
        private double sentimentScore;
        private List<Highlight> highlights;

        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getIndustry() { return industry; }
        public void setIndustry(String industry) { this.industry = industry; }
        public double getMatchScore() { return matchScore; }
        public void setMatchScore(double matchScore) { this.matchScore = matchScore; }
        public double getSentimentScore() { return sentimentScore; }
        public void setSentimentScore(double sentimentScore) { this.sentimentScore = sentimentScore; }
        public List<Highlight> getHighlights() { return highlights; }
        public void setHighlights(List<Highlight> highlights) { this.highlights = highlights; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Highlight {
        private String highlight;
        private double sentiment;
        @JsonProperty("highlighted_in")
        private String highlightedIn;

        public String getHighlight() { return highlight; }
        public void setHighlight(String highlight) { this.highlight = highlight; }
        public double getSentiment() { return sentiment; }
        public void setSentiment(double sentiment) { this.sentiment = sentiment; }
        public String getHighlightedIn() { return highlightedIn; }
        public void setHighlightedIn(String highlightedIn) { this.highlightedIn = highlightedIn; }
    }
}

