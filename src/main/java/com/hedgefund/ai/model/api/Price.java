package com.hedgefund.ai.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Stock price data point.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Price {
    private double open;
    private double close;
    private double high;
    private double low;
    private long volume;
    private String time;

    public Price() {}

    public Price(double open, double close, double high, double low, long volume, String time) {
        this.open = open;
        this.close = close;
        this.high = high;
        this.low = low;
        this.volume = volume;
        this.time = time;
    }

    public double getOpen() { return open; }
    public void setOpen(double open) { this.open = open; }
    public double getClose() { return close; }
    public void setClose(double close) { this.close = close; }
    public double getHigh() { return high; }
    public void setHigh(double high) { this.high = high; }
    public double getLow() { return low; }
    public void setLow(double low) { this.low = low; }
    public long getVolume() { return volume; }
    public void setVolume(long volume) { this.volume = volume; }
    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
}
