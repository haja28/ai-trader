package com.hedgefund.ai.chart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Port and extension of Pine Script v6 "Institutional Trend & Cycle BUY SELL".
 *
 * <p>Features:
 * <ul>
 *   <li>Fast EMA (20), Medium EMA (50), Long EMA (200)</li>
 *   <li>RSI (14), MACD (12, 26, 9), Volume & Relative Volume (RVOL)</li>
 *   <li>Trend Strength: DMI/ADX (14, 14) and Bull Trend detection</li>
 *   <li>ATR (14) & Trailing Stop: highest(high, 22) - 3 * atr</li>
 *   <li>Bullish & Bearish Divergence Detection (RSI & MACD)</li>
 *   <li>Support & Resistance Zones</li>
 *   <li>Breakout & False Breakout (Bull Trap / Bear Trap) Detection</li>
 *   <li>ATR-based Stop-Loss & Take-Profit (1:1, 1:2 min, 1:3 runner)</li>
 *   <li>Risk/Reward Calculation with minimum 1:2 R:R</li>
 *   <li>Multi-timeframe Trend Confirmation (HTF)</li>
 *   <li>BUY / STRONG BUY / SELL / STRONG SELL Confluence Scoring</li>
 * </ul>
 */
public class InstitutionalIndicator {

    public static ChartResult.InstitutionalData empty(int n) {
        return new ChartResult.InstitutionalData(
                nulls(n), nulls(n), nulls(n), nulls(n), nulls(n), nulls(n), nulls(n),
                nulls(n), nulls(n), nulls(n), nulls(n),
                List.of(),
                new ChartResult.InstitutionalSummary(
                        false, false, false, false, false, false, "NO TRADE",
                        0, 0, 0, 0, false, false,
                        1.0, "NONE", "NORMAL", 0, 0, 0, 0, 0, 0, 0, 0, 2.0, "N/A", false, 50, 50, "WATCH"));
    }

    public static ChartResult.InstitutionalData calculate(String[] t, double[] o, double[] h, double[] l, double[] c, double[] v) {
        return calculate(t, o, h, l, c, v, null, "1W", null, null);
    }

    public static ChartResult.InstitutionalData calculate(String[] t, double[] o, double[] h, double[] l, double[] c, double[] v,
                                                          int[] htfDir, String htfLabel, double[][] resLv, double[][] supLv) {
        int n = c.length;
        if (n < 20) return empty(n);

        // ===== MOVING AVERAGES =====
        double[] ema20 = SignalEngine.ema(c, 20);
        double[] ema50 = SignalEngine.ema(c, 50);
        double[] ema200 = SignalEngine.ema(c, 200);

        // ===== MOMENTUM =====
        double[] rsi = SignalEngine.rsi(c, 14);
        double[] ema12 = SignalEngine.ema(c, 12);
        double[] ema26 = SignalEngine.ema(c, 26);
        double[] macdLine = sub(ema12, ema26);
        double[] signalLine = SignalEngine.ema(macdLine, 9);
        double[] hist = sub(macdLine, signalLine);

        // ===== VOLUME & RVOL =====
        double[] volAvg = SignalEngine.sma(v, 20);
        double[] rvol = new double[n];
        boolean[] volumeStrong = new boolean[n];
        for (int i = 0; i < n; i++) {
            rvol[i] = (v[i] > 0 && !Double.isNaN(volAvg[i]) && volAvg[i] > 0) ? v[i] / volAvg[i] : 1.0;
            volumeStrong[i] = rvol[i] >= 1.2 || (v[i] == 0 && (Double.isNaN(volAvg[i]) || volAvg[i] == 0));
        }

        // ===== TREND STRENGTH (DMI/ADX) =====
        double[] adx = adx(h, l, c, 14);

        // bullTrend = ema20 > ema50 and ema50 > ema200 and ema50 > ema50[5]
        boolean[] bullTrend = new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean valid = !Double.isNaN(ema20[i]) && !Double.isNaN(ema50[i]) && !Double.isNaN(ema200[i]);
            boolean rising50 = i >= 5 && !Double.isNaN(ema50[i - 5]) && (ema50[i] > ema50[i - 5]);
            bullTrend[i] = valid && (ema20[i] > ema50[i]) && (ema50[i] > ema200[i]) && rising50;
        }

        // ===== ATR =====
        double[] atr = SignalEngine.atr(h, l, c, 14);

        // ===== ATR TRAILING STOP =====
        double[] trailStop = new double[n];
        Arrays.fill(trailStop, Double.NaN);
        for (int i = 0; i < n; i++) {
            if (bullTrend[i] && !Double.isNaN(atr[i])) {
                double maxHigh = -Double.MAX_VALUE;
                int lookback = Math.min(22, i + 1);
                for (int k = i - lookback + 1; k <= i; k++) {
                    maxHigh = Math.max(maxHigh, h[k]);
                }
                trailStop[i] = maxHigh - (3.0 * atr[i]);
            }
        }

        // ===== SUPPORT & RESISTANCE ZONES =====
        double[] upperRes = new double[n];
        double[] lowerSup = new double[n];
        for (int i = 0; i < n; i++) {
            if (resLv != null && !Double.isNaN(resLv[0][i])) {
                upperRes[i] = resLv[0][i];
            } else {
                double hi = -Double.MAX_VALUE;
                int start = Math.max(0, i - 20);
                for (int k = start; k <= i; k++) hi = Math.max(hi, h[k]);
                upperRes[i] = hi;
            }

            if (supLv != null && !Double.isNaN(supLv[0][i])) {
                lowerSup[i] = supLv[0][i];
            } else {
                double lo = Double.MAX_VALUE;
                int start = Math.max(0, i - 20);
                for (int k = start; k <= i; k++) lo = Math.min(lo, l[k]);
                lowerSup[i] = lo;
            }
        }

        // ===== SIGNALS, DIVERGENCES & BREAKOUTS =====
        boolean[] earlyBuy = new boolean[n];
        boolean[] pullbackBuy = new boolean[n];
        boolean[] breakoutBuy = new boolean[n];
        boolean[] takeProfit = new boolean[n];
        boolean[] trendExit = new boolean[n];
        boolean[] bullTrap = new boolean[n];
        boolean[] bearTrap = new boolean[n];
        boolean[] bullDiv = new boolean[n];
        boolean[] bearDiv = new boolean[n];
        List<ChartResult.Marker> markers = new ArrayList<>();

        int lastBullDiv = -100;
        int lastBearDiv = -100;

        for (int i = 1; i < n; i++) {
            if (Double.isNaN(ema20[i]) || Double.isNaN(rsi[i]) || Double.isNaN(macdLine[i]) || Double.isNaN(signalLine[i])) {
                continue;
            }

            // Early buy: crossover(close, ema20) + ema20 > ema20[3] + rsi > 50 + macd > signal + volumeStrong
            boolean crossOverEma20 = c[i - 1] <= ema20[i - 1] && c[i] > ema20[i];
            boolean ema20Rising = i >= 3 && !Double.isNaN(ema20[i - 3]) && ema20[i] > ema20[i - 3];
            earlyBuy[i] = crossOverEma20 && ema20Rising && rsi[i] > 50 && (macdLine[i] > signalLine[i]) && volumeStrong[i];

            // Pullback buy: bullTrend + low <= ema20 + close > ema20 + close[1] <= ema20[1] + rsi > 50 + macd > signal
            pullbackBuy[i] = bullTrend[i] && (l[i] <= ema20[i]) && (c[i] > ema20[i]) && (c[i - 1] <= ema20[i - 1])
                    && rsi[i] > 50 && (macdLine[i] > signalLine[i]);

            // 20-bar lookback for breakouts & false breakouts
            double highestPast = -Double.MAX_VALUE;
            double lowestPast = Double.MAX_VALUE;
            if (i >= 20) {
                for (int k = i - 20; k <= i - 1; k++) {
                    highestPast = Math.max(highestPast, h[k]);
                    lowestPast = Math.min(lowestPast, l[k]);
                }
            }

            // Breakout buy: close > highest(high, 20)[1] + volumeStrong + rsi > 55 + macd > signal + adx > 18 + close > ema50
            if (i >= 20 && !Double.isNaN(ema50[i])) {
                boolean adxOk = Double.isNaN(adx[i]) || adx[i] > 18.0;
                breakoutBuy[i] = (c[i] > highestPast) && volumeStrong[i] && (rsi[i] > 55)
                        && (macdLine[i] > signalLine[i]) && adxOk && (c[i] > ema50[i]);
            }

            // False breakout detection (Bull Trap / Bear Trap)
            if (i >= 20 && highestPast > -Double.MAX_VALUE && lowestPast < Double.MAX_VALUE) {
                // Bull Trap: previous bar reached or closed above breakout level, but current bar closes back below with bearish rejection
                bullTrap[i] = (h[i - 1] > highestPast || c[i - 1] > highestPast)
                        && c[i] < highestPast && c[i] < o[i];

                // Bear Trap: previous bar dipped below support, but current bar closes back above with bullish rejection
                bearTrap[i] = (l[i - 1] < lowestPast || c[i - 1] < lowestPast)
                        && c[i] > lowestPast && c[i] > o[i];
            }

            // Divergence detection (RSI & MACD histogram across local swing pivots)
            if (i >= 15 && !Double.isNaN(atr[i])) {
                // Bearish Divergence: requires cooldown, distinct prior pivot high, true higher high in price, lower RSI, and intervening valley
                if (i - lastBearDiv >= 8) {
                    boolean isLocalPeak = (h[i] >= h[i - 1] && c[i] < o[i]) || (h[i - 1] >= h[i - 2] && h[i - 1] >= h[i] && c[i] < c[i - 1]);
                    if (isLocalPeak) {
                        double curPeakHi = Math.max(h[i], h[i - 1]);
                        double curRsi = rsi[i];
                        int highIdx = -1;
                        double maxPriorHi = -Double.MAX_VALUE;
                        for (int k = Math.max(2, i - 30); k <= i - 5; k++) {
                            boolean isPivot = h[k] >= h[k - 1] && h[k] >= h[k + 1] && h[k] >= h[k - 2] && h[k] >= h[k + 2];
                            if (isPivot && h[k] > maxPriorHi) {
                                maxPriorHi = h[k];
                                highIdx = k;
                            }
                        }

                        if (highIdx >= 0 && curPeakHi > maxPriorHi) {
                            double minValleyRsi = Double.MAX_VALUE;
                            for (int m = highIdx + 1; m < i - 1; m++) {
                                minValleyRsi = Math.min(minValleyRsi, rsi[m]);
                            }

                            boolean rsiDivergence = rsi[highIdx] >= 60.0
                                    && curRsi <= rsi[highIdx] - 3.5
                                    && minValleyRsi <= rsi[highIdx] - 5.0
                                    && hist[i] < hist[highIdx];

                            if (rsiDivergence) {
                                bearDiv[i] = true;
                                lastBearDiv = i;
                            }
                        }
                    }
                }

                // Bullish Divergence: requires cooldown, distinct prior pivot low, true lower low in price, higher RSI, and intervening peak
                if (i - lastBullDiv >= 8) {
                    boolean isLocalTrough = (l[i] <= l[i - 1] && c[i] > o[i]) || (l[i - 1] <= l[i - 2] && l[i - 1] <= l[i] && c[i] > c[i - 1]);
                    if (isLocalTrough) {
                        double curTroughLo = Math.min(l[i], l[i - 1]);
                        double curRsi = rsi[i];
                        int lowIdx = -1;
                        double minPriorLo = Double.MAX_VALUE;
                        for (int k = Math.max(2, i - 30); k <= i - 5; k++) {
                            boolean isPivot = l[k] <= l[k - 1] && l[k] <= l[k + 1] && l[k] <= l[k - 2] && l[k] <= l[k + 2];
                            if (isPivot && l[k] < minPriorLo) {
                                minPriorLo = l[k];
                                lowIdx = k;
                            }
                        }

                        if (lowIdx >= 0 && curTroughLo < minPriorLo) {
                            double maxPeakRsi = -Double.MAX_VALUE;
                            for (int m = lowIdx + 1; m < i - 1; m++) {
                                maxPeakRsi = Math.max(maxPeakRsi, rsi[m]);
                            }

                            boolean rsiDivergence = rsi[lowIdx] <= 40.0
                                    && curRsi >= rsi[lowIdx] + 3.5
                                    && maxPeakRsi >= rsi[lowIdx] + 5.0
                                    && hist[i] > hist[lowIdx];

                            if (rsiDivergence) {
                                bullDiv[i] = true;
                                lastBullDiv = i;
                            }
                        }
                    }
                }
            }

            // Extended: close > ema20 + 2*atr
            boolean extended = !Double.isNaN(atr[i]) && (c[i] > ema20[i] + 2.0 * atr[i]);

            // Take profit: extended + crossunder(rsi, 70) + hist < hist[1]
            boolean rsiCrossUnder70 = !Double.isNaN(rsi[i - 1]) && rsi[i - 1] >= 70 && rsi[i] < 70;
            boolean histFalling = !Double.isNaN(hist[i - 1]) && hist[i] < hist[i - 1];
            takeProfit[i] = extended && rsiCrossUnder70 && histFalling;

            // Trend exit: crossunder(close, ema50)
            if (!Double.isNaN(ema50[i]) && !Double.isNaN(ema50[i - 1])) {
                trendExit[i] = c[i - 1] >= ema50[i - 1] && c[i] < ema50[i];
            }

            // Create markers
            if (earlyBuy[i]) markers.add(new ChartResult.Marker(t[i], "INST_EARLY_BUY", "EARLY BUY", l[i]));
            if (pullbackBuy[i]) markers.add(new ChartResult.Marker(t[i], "INST_BUY", "BUY", l[i]));
            if (breakoutBuy[i]) markers.add(new ChartResult.Marker(t[i], "INST_BREAKOUT", "BREAKOUT", l[i]));
            if (bullTrap[i]) markers.add(new ChartResult.Marker(t[i], "INST_BULL_TRAP", "BULL TRAP", h[i]));
            if (bearTrap[i]) markers.add(new ChartResult.Marker(t[i], "INST_BEAR_TRAP", "BEAR TRAP", l[i]));
            if (bullDiv[i]) markers.add(new ChartResult.Marker(t[i], "INST_BULL_DIV", "BULL DIV", l[i]));
            if (bearDiv[i]) markers.add(new ChartResult.Marker(t[i], "INST_BEAR_DIV", "BEAR DIV", h[i]));
            if (takeProfit[i]) markers.add(new ChartResult.Marker(t[i], "INST_TAKE_PROFIT", "PARTIAL SELL", h[i]));
            if (trendExit[i]) markers.add(new ChartResult.Marker(t[i], "INST_EXIT", "EXIT", h[i]));
        }

        // ===== SUMMARY & CONFLUENCE SCORING =====
        int last = n - 1;
        String latestSig = "HOLD / WAIT";
        if (breakoutBuy[last]) latestSig = "BREAKOUT BUY";
        else if (pullbackBuy[last]) latestSig = "PULLBACK BUY";
        else if (earlyBuy[last]) latestSig = "EARLY BUY";
        else if (bearTrap[last]) latestSig = "BEAR TRAP BUY";
        else if (bullTrap[last]) latestSig = "BULL TRAP SELL";
        else if (takeProfit[last]) latestSig = "PARTIAL SELL";
        else if (trendExit[last]) latestSig = "TREND EXIT";
        else if (bullTrend[last]) latestSig = "BULL TREND";

        boolean lastExtended = !Double.isNaN(atr[last]) && !Double.isNaN(ema20[last]) && (c[last] > ema20[last] + 2.0 * atr[last]);

        String divStatus = (last - lastBullDiv <= 5) ? "BULLISH" : (last - lastBearDiv <= 5) ? "BEARISH" : "NONE";
        String boStatus = bullTrap[last] ? "FALSE BREAKOUT (Bull Trap)"
                : bearTrap[last] ? "FALSE BREAKDOWN (Bear Trap)"
                : breakoutBuy[last] ? "CONFIRMED BREAKOUT" : "NORMAL";

        double curAtr = Double.isNaN(atr[last]) || atr[last] <= 0 ? c[last] * 0.015 : atr[last];
        double riskDist = round(1.5 * curAtr);

        // Multi-timeframe trend confirmation
        int htfVal = htfDir != null && last < htfDir.length ? htfDir[last] : 0;
        String htfTrendStr = htfVal == 1 ? "BULLISH (" + htfLabel + ")"
                : htfVal == -1 ? "BEARISH (" + htfLabel + ")"
                : "FLAT (" + htfLabel + ")";
        boolean htfConfirmed = (htfVal == 1 || htfVal == 2);

        // Scoring (0-100 Bull and Bear)
        double bullPts = 0, bearPts = 0;

        // Trend (25 pts)
        if (bullTrend[last]) bullPts += 15;
        else if (c[last] > ema50[last]) bullPts += 8;
        if (last >= 3 && !Double.isNaN(ema20[last - 3]) && ema20[last] > ema20[last - 3]) bullPts += 5;
        if (c[last] > ema20[last]) bullPts += 5;

        if (!Double.isNaN(ema20[last]) && !Double.isNaN(ema50[last]) && ema20[last] < ema50[last]) bearPts += 15;
        if (c[last] < ema50[last]) bearPts += 5;
        if (c[last] < ema20[last]) bearPts += 5;

        // Momentum (25 pts)
        if (rsi[last] >= 50 && rsi[last] <= 70) bullPts += 10;
        else if (rsi[last] > 70) bullPts += 5;
        if (rsi[last] > rsi[last - 1]) bullPts += 5;
        if (macdLine[last] > signalLine[last]) bullPts += 5;
        if (hist[last] > 0 && hist[last] >= hist[last - 1]) bullPts += 5;

        if (rsi[last] < 50) bearPts += 10;
        if (rsi[last] < rsi[last - 1]) bearPts += 5;
        if (macdLine[last] < signalLine[last]) bearPts += 5;
        if (hist[last] < 0 && hist[last] <= hist[last - 1]) bearPts += 5;

        // Volume & RVOL (20 pts)
        if (rvol[last] >= 1.2) bullPts += 10;
        if (rvol[last] >= 1.5) bullPts += 5;
        if (c[last] >= o[last]) bullPts += 5;

        if (rvol[last] >= 1.2 && c[last] < o[last]) bearPts += 15;
        if (c[last] < o[last]) bearPts += 5;

        // Structure & Traps (15 pts)
        if (breakoutBuy[last] || pullbackBuy[last] || earlyBuy[last]) bullPts += 10;
        if (bearTrap[last]) bullPts += 10;
        if (bullTrap[last]) { bullPts = Math.max(0, bullPts - 15); bearPts += 15; }
        if (trendExit[last]) bearPts += 10;

        // HTF & Divergences (15 pts)
        if (htfConfirmed) bullPts += 10;
        else if (htfVal == -1) { bullPts = Math.max(0, bullPts - 10); bearPts += 10; }
        if ("BULLISH".equals(divStatus)) bullPts += 5;
        if ("BEARISH".equals(divStatus)) { bullPts = Math.max(0, bullPts - 10); bearPts += 10; }

        double bullScore = Math.min(100.0, Math.max(0.0, bullPts));
        double bearScore = Math.min(100.0, Math.max(0.0, bearPts));

        String scoreRec;
        if (bullScore >= 75 && htfConfirmed && volumeStrong[last]) scoreRec = "STRONG BUY";
        else if (bullScore >= 60) scoreRec = "BUY";
        else if (bearScore >= 75 && htfVal == -1 && volumeStrong[last]) scoreRec = "STRONG SELL";
        else if (bearScore >= 60) scoreRec = "SELL";
        else scoreRec = "WATCH";

        // Stop-loss, Take-profit & Risk/Reward calculation (minimum 1:2 R:R)
        boolean isBearish = scoreRec.contains("SELL");
        double entryPx = round(c[last]);
        double sl = isBearish ? round(entryPx + riskDist) : round(entryPx - riskDist);
        double tp1 = isBearish ? round(entryPx - riskDist) : round(entryPx + riskDist);
        double tp2 = isBearish ? round(entryPx - 2.0 * riskDist) : round(entryPx + 2.0 * riskDist);
        double tp3 = isBearish ? round(entryPx - 3.0 * riskDist) : round(entryPx + 3.0 * riskDist);
        double pRisk = riskDist;
        double pReward = round(2.0 * riskDist); // Target 2 guarantees minimum 1:2 ratio

        ChartResult.InstitutionalSummary summary = new ChartResult.InstitutionalSummary(
                bullTrend[last],
                earlyBuy[last],
                pullbackBuy[last],
                breakoutBuy[last],
                takeProfit[last],
                trendExit[last],
                latestSig,
                round(rsi[last]),
                round(adx[last]),
                round(hist[last]),
                round(trailStop[last]),
                volumeStrong[last],
                lastExtended,
                round(rvol[last]),
                divStatus,
                boStatus,
                round(upperRes[last]),
                round(lowerSup[last]),
                sl,
                tp1,
                tp2,
                tp3,
                pRisk,
                pReward,
                2.0,
                htfTrendStr,
                htfConfirmed,
                round(bullScore),
                round(bearScore),
                scoreRec
        );

        return new ChartResult.InstitutionalData(
                box(ema20),
                box(ema50),
                box(ema200),
                box(trailStop),
                box(rsi),
                box(adx),
                box(hist),
                box(rvol),
                box(atr),
                box(upperRes),
                box(lowerSup),
                markers,
                summary
        );
    }

    /** Directional Movement Index ADX (14, 14) via Wilder's RMA */
    static double[] adx(double[] h, double[] l, double[] c, int len) {
        int n = h.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        if (n < len + 1) return out;

        double[] tr = new double[n];
        double[] plusDM = new double[n];
        double[] minusDM = new double[n];

        tr[0] = h[0] - l[0];
        plusDM[0] = 0;
        minusDM[0] = 0;

        for (int i = 1; i < n; i++) {
            tr[i] = Math.max(h[i] - l[i], Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])));
            double up = h[i] - h[i - 1];
            double down = l[i - 1] - l[i];
            plusDM[i] = (up > down && up > 0) ? up : 0;
            minusDM[i] = (down > up && down > 0) ? down : 0;
        }

        double[] smTR = rma(tr, len);
        double[] smPlusDM = rma(plusDM, len);
        double[] smMinusDM = rma(minusDM, len);

        double[] dx = new double[n];
        Arrays.fill(dx, Double.NaN);

        for (int i = len - 1; i < n; i++) {
            double str = smTR[i];
            if (Double.isNaN(str) || str == 0) continue;
            double pDI = 100.0 * smPlusDM[i] / str;
            double mDI = 100.0 * smMinusDM[i] / str;
            double sum = pDI + mDI;
            dx[i] = sum == 0 ? 0 : 100.0 * Math.abs(pDI - mDI) / sum;
        }

        return rma(dx, len);
    }

    /** Wilder's RMA smoothing */
    static double[] rma(double[] x, int len) {
        int n = x.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        int s = 0;
        while (s < n && Double.isNaN(x[s])) s++;
        if (s + len > n) return out;
        double sum = 0;
        for (int i = s; i < s + len; i++) sum += x[i];
        double prev = sum / len;
        out[s + len - 1] = prev;
        for (int i = s + len; i < n; i++) {
            prev = (prev * (len - 1) + x[i]) / len;
            out[i] = prev;
        }
        return out;
    }

    private static double[] sub(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] - b[i];
        return out;
    }

    private static double round(double x) {
        return Double.isNaN(x) ? 0 : Math.round(x * 100.0) / 100.0;
    }

    private static List<Double> box(double[] a) {
        List<Double> out = new ArrayList<>(a.length);
        for (double d : a) out.add(Double.isNaN(d) || Double.isInfinite(d) ? null : Math.round(d * 10000.0) / 10000.0);
        return out;
    }


    private static List<Double> nulls(int n) {
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(null);
        return out;
    }
}


