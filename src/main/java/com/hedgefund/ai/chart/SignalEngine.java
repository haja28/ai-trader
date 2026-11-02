package com.hedgefund.ai.chart;

import com.hedgefund.ai.model.api.Price;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Java port of the Pine Script confluence signal system.
 *
 * <p>Non-repainting by construction: every bar is processed once, in order, using only data up to
 * and including that bar. Pivots are confirmed {@code pivotLen} bars late, and the higher-timeframe
 * (weekly) trend only uses weeks that have fully closed.
 *
 * <p>Scoring (0-100, normalised to the points available): trend 25, momentum 20, volume 20,
 * structure 20, VWAP/volatility 15 (VWAP is skipped on daily data).
 */
public class SignalEngine {

    private static final int RECENT = 10;
    private static final int NEVER = -1_000_000;
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    /**
     * Three pivot levels nearest to {@code ref} on one side: above (resistance) or below (support).
     * Index 0 is the nearest. Levels closer than {@code minGap} to an already-picked level are skipped
     * so that clustered pivots do not fill all three slots. Missing slots are NaN.
     */
    static double[] nearestLevels(List<Double> pivots, double ref, boolean above, double minGap) {
        List<Double> side = new ArrayList<>();
        for (double p : pivots) {
            if (above ? p > ref : p < ref) side.add(p);
        }
        side.sort(above ? Comparator.naturalOrder() : Comparator.reverseOrder());
        double[] out = {Double.NaN, Double.NaN, Double.NaN};
        int found = 0;
        for (double p : side) {
            if (found > 0 && Math.abs(p - out[found - 1]) < minGap) continue;
            out[found++] = p;
            if (found == 3) break;
        }
        return out;
    }

    /** Seconds per timeframe; 0 = daily. */
    static int tfSeconds(String tf) {
        return switch (tf == null ? "1D" : tf.toLowerCase()) {
            case "5m" -> 300;
            case "15m" -> 900;
            case "1h" -> 3600;
            case "4h" -> 14400;
            default -> 0;
        };
    }

    /** Aggregate finer intraday bars (epoch-second time strings) into buckets of {@code seconds}. */
    public static List<Price> aggregate(List<Price> bars, int seconds) {
        List<Price> sorted = new ArrayList<>(bars);
        sorted.sort(Comparator.comparingLong(p -> Long.parseLong(p.getTime())));
        List<Price> out = new ArrayList<>();
        long curBucket = Long.MIN_VALUE;
        Price cur = null;
        for (Price p : sorted) {
            long bucket = Math.floorDiv(Long.parseLong(p.getTime()), (long) seconds);
            if (cur == null || bucket != curBucket) {
                if (cur != null) out.add(cur);
                curBucket = bucket;
                cur = new Price(p.getOpen(), p.getClose(), p.getHigh(), p.getLow(), p.getVolume(),
                        String.valueOf(bucket * seconds));
            } else {
                cur.setHigh(Math.max(cur.getHigh(), p.getHigh()));
                cur.setLow(Math.min(cur.getLow(), p.getLow()));
                cur.setClose(p.getClose());
                cur.setVolume(cur.getVolume() + p.getVolume());
            }
        }
        if (cur != null) out.add(cur);
        return out;
    }

    public ChartResult analyze(String ticker, List<Price> input, SignalConfig cfg) {
        return analyze(ticker, input, cfg, "1D");
    }

    public ChartResult analyze(String ticker, List<Price> input, SignalConfig cfg, String timeframe) {
        final boolean intraday = tfSeconds(timeframe) > 0;
        List<Price> bars = new ArrayList<>(input == null ? List.of() : input);
        if (intraday) bars.sort(Comparator.comparingLong(p -> Long.parseLong(p.getTime())));
        else bars.sort(Comparator.comparing(Price::getTime));
        int n = bars.size();

        List<ChartResult.Candle> candles = new ArrayList<>(n);
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        String[] t = new String[n];
        for (int i = 0; i < n; i++) {
            Price p = bars.get(i);
            o[i] = p.getOpen(); h[i] = p.getHigh(); l[i] = p.getLow(); c[i] = p.getClose(); v[i] = p.getVolume();
            String raw = p.getTime() == null ? "" : p.getTime();
            t[i] = intraday ? raw : (raw.length() > 10 ? raw.substring(0, 10) : raw);
            candles.add(new ChartResult.Candle(t[i], o[i], h[i], l[i], c[i], v[i]));
        }

        if (n < 60) {
            return new ChartResult(ticker, cfg.preset(), candles, nulls(n), nulls(n), nulls(n), nulls(n), nulls(n),
                    nulls(n), nulls(n), List.of(), List.of(), nulls(n), nulls(n), List.of(), List.of(),
                    new ChartResult.Summary("N/A", "N/A", 0, "N/A", 1, "N/A", "N/A", 0, 0, "NO TRADE", "FLAT", n,
                            "Insufficient history: need at least 60 bars, got " + n));
        }

        // ---------------- Indicators ----------------
        double[] e9 = ema(c, 9), e21 = ema(c, 21), e50 = ema(c, 50), e200 = ema(c, 200);
        double[] rsi = rsi(c, 14);
        double[] macd = sub(ema(c, 12), ema(c, 26));
        double[] hist = sub(macd, ema(macd, 9));
        double[] stK = sma(stoch(rsi, 14), 3);
        double[] stD = sma(stK, 3);
        double[] atr = atr(h, l, c, 14);
        double[] bbMid = sma(c, 20);
        double[] sd = stdev(c, 20);
        double[] bbw = new double[n];
        for (int i = 0; i < n; i++) bbw[i] = 4 * sd[i] / bbMid[i];
        double[] bbwMa = sma(bbw, 20);
        double[] volMa = sma(v, 20);

        // ---------------- Higher timeframe (completed HTF bars only) ----------------
        int[] htfDir = htfTrend(t, c, timeframe);
        String htfLabel = htfName(timeframe);

        // ---------------- Session VWAP (aligned to exchange day boundary; intraday only) ----------------
        double[] vwap = new double[n];
        Arrays.fill(vwap, Double.NaN);
        if (intraday) {
            long curDay = Long.MIN_VALUE;
            double pv = 0, vv = 0;
            for (int i = 0; i < n; i++) {
                long day;
                try {
                    long epoch = Long.parseLong(t[i]);
                    day = Instant.ofEpochSecond(epoch).atZone(MARKET_ZONE).toLocalDate().toEpochDay();
                } catch (Exception ex) {
                    day = Math.floorDiv(Long.parseLong(t[i]), 86400L);
                }
                if (day != curDay) { curDay = day; pv = 0; vv = 0; }
                pv += (h[i] + l[i] + c[i]) / 3.0 * v[i];
                vv += v[i];
                vwap[i] = vv > 0 ? pv / vv : Double.NaN;
            }
        }

        // ---------------- State ----------------
        double[] bullS = new double[n], bearS = new double[n];
        double[] resistance = new double[n], support = new double[n];
        Arrays.fill(resistance, Double.NaN);
        Arrays.fill(support, Double.NaN);

        double[][] resLv = new double[3][n], supLv = new double[3][n];
        for (int q = 0; q < 3; q++) { Arrays.fill(resLv[q], Double.NaN); Arrays.fill(supLv[q], Double.NaN); }
        double sh1 = Double.NaN, sh2 = Double.NaN, sh3 = Double.NaN, sl1 = Double.NaN, sl2 = Double.NaN, sl3 = Double.NaN;
        // All confirmed swing highs/lows (newest last). Any pivot below price acts as support,
        // any pivot above price as resistance, so broken levels flip sides automatically.
        List<Double> pivots = new ArrayList<>();
        double lastBrokenH = Double.NaN, lastBrokenL = Double.NaN;
        double retestUp = Double.NaN, retestDn = Double.NaN;
        int retestUpBar = 0, retestDnBar = 0, structTrend = 0;
        int lBullBO = NEVER, lBearBO = NEVER, lBullBr = NEVER, lBearBr = NEVER;
        int lBullRT = NEVER, lBearRT = NEVER, lBullX = NEVER, lBearX = NEVER;

        int dir = 0;
        double entry = 0, stop = 0, initStop = 0, tp1 = 0, tp2 = 0, tp3 = 0, risk = 0;
        int hits = 0;
        int entryIdx = 0;
        boolean entryStrong = false;
        List<ChartResult.Marker> markers = new ArrayList<>();
        List<ChartResult.Trade> trades = new ArrayList<>();
        int pl = cfg.pivotLen();

        for (int i = 0; i < n; i++) {
            // Confirmed pivots (candidate bar is i - pl)
            if (i >= 2 * pl) {
                int k = i - pl;
                boolean isHigh = true, isLow = true;
                for (int j = k - pl; j <= k + pl; j++) {
                    if (j == k) continue;
                    if (h[j] >= h[k]) isHigh = false;
                    if (l[j] <= l[k]) isLow = false;
                }
                if (isHigh) { sh3 = sh2; sh2 = sh1; sh1 = h[k]; pivots.add(h[k]); }
                if (isLow) { sl3 = sl2; sl2 = sl1; sl1 = l[k]; pivots.add(l[k]); }
                while (pivots.size() > 80) pivots.remove(0);
            }
            resistance[i] = sh1;
            support[i] = sl1;
            double levelGap = Double.isNaN(atr[i]) ? c[i] * 0.005 : 0.5 * atr[i];
            double[] sup3 = nearestLevels(pivots, c[i], false, levelGap);
            double[] res3 = nearestLevels(pivots, c[i], true, levelGap);
            for (int q = 0; q < 3; q++) { supLv[q][i] = sup3[q]; resLv[q][i] = res3[q]; }

            boolean hasVol = v[i] > 0 && !Double.isNaN(volMa[i]) && volMa[i] > 0;
            double rvol = hasVol ? v[i] / volMa[i] : 1.0;
            boolean has200 = i >= 200;
            boolean warm = i >= 55 && !Double.isNaN(atr[i]) && !Double.isNaN(e50[i]) && !Double.isNaN(bbMid[i]);
            if (i < 1) continue;
            boolean gap = cfg.blockGaps() && !Double.isNaN(atr[i - 1]) && Math.abs(o[i] - c[i - 1]) > 1.5 * atr[i - 1];

            // Structure events
            boolean bullBreak = !Double.isNaN(sh1) && c[i] > sh1 && c[i - 1] <= sh1 && sh1 != lastBrokenH;
            boolean bearBreak = !Double.isNaN(sl1) && c[i] < sl1 && c[i - 1] >= sl1 && sl1 != lastBrokenL;
            boolean bullCHoCH = bullBreak && structTrend == -1;
            boolean bearCHoCH = bearBreak && structTrend == 1;
            boolean bullBO = bullBreak && (!hasVol || rvol >= 1.2);
            boolean bearBO = bearBreak && (!hasVol || rvol >= 1.2);
            double a = Double.isNaN(atr[i]) ? 0 : atr[i];

            boolean bullRT = !Double.isNaN(retestUp) && i > retestUpBar && i - retestUpBar <= RECENT
                    && l[i] <= retestUp + 0.25 * a && c[i] > retestUp;
            boolean bearRT = !Double.isNaN(retestDn) && i > retestDnBar && i - retestDnBar <= RECENT
                    && h[i] >= retestDn - 0.25 * a && c[i] < retestDn;
            if (bullRT || (!Double.isNaN(retestUp) && (c[i] < retestUp - a || i - retestUpBar > RECENT))) retestUp = Double.NaN;
            if (bearRT || (!Double.isNaN(retestDn) && (c[i] > retestDn + a || i - retestDnBar > RECENT))) retestDn = Double.NaN;

            if (bullBreak) { lastBrokenH = sh1; retestUp = sh1; retestUpBar = i; structTrend = 1; lBullBr = i; }
            if (bearBreak) { lastBrokenL = sl1; retestDn = sl1; retestDnBar = i; structTrend = -1; lBearBr = i; }
            if (bullBO) lBullBO = i;
            if (bearBO) lBearBO = i;
            if (bullRT) lBullRT = i;
            if (bearRT) lBearRT = i;
            if (e9[i] > e21[i] && e9[i - 1] <= e21[i - 1]) lBullX = i;
            if (e9[i] < e21[i] && e9[i - 1] >= e21[i - 1]) lBearX = i;

            if (bullBreak) markers.add(new ChartResult.Marker(t[i], bullCHoCH ? "CHOCH" : "BOS", bullCHoCH ? "CHoCH" : "BOS", l[i]));
            if (bearBreak) markers.add(new ChartResult.Marker(t[i], bearCHoCH ? "CHOCH" : "BOS", bearCHoCH ? "CHoCH" : "BOS", h[i]));
            if (bullBreak) markers.add(new ChartResult.Marker(t[i], bullBO ? "BREAKOUT" : "WEAK_BREAKOUT", bullBO ? "BO" : "weak BO", h[i]));
            if (bearBreak) markers.add(new ChartResult.Marker(t[i], bearBO ? "BREAKOUT" : "WEAK_BREAKOUT", bearBO ? "BD" : "weak BD", l[i]));
            if (hasVol && rvol >= 2.0) markers.add(new ChartResult.Marker(t[i], "SPIKE", "V", v[i]));

            boolean hhhl = !Double.isNaN(sh2) && !Double.isNaN(sl2) && sh1 > sh2 && sl1 > sl2;
            boolean lhll = !Double.isNaN(sh2) && !Double.isNaN(sl2) && sh1 < sh2 && sl1 < sl2;
            boolean volExpand = !Double.isNaN(bbwMa[i]) && bbw[i] > bbwMa[i];

            // Scores
            double max = 18 + (has200 ? 7 : 0) + 20 + (hasVol ? 20 : 0) + 20 + 8;
            for (int side = 1; side >= -1; side -= 2) {
                double d = side;
                double s = 0;
                // Trend (25)
                s += d * (c[i] - e50[i]) > 0 ? 8 : 0;
                s += has200 && d * (c[i] - e200[i]) > 0 ? 7 : 0;
                boolean half = d * (e9[i] - e21[i]) > 0;
                boolean stack = half && d * (e21[i] - e50[i]) > 0;
                boolean recX = i - (side > 0 ? lBullX : lBearX) <= 3;
                s += stack ? 10 : half ? (recX ? 7 : 5) : 0;
                // Momentum (20)
                double m = 0;
                if (d * (rsi[i] - 50) > 0) m += d * (rsi[i] - rsi[i - 1]) > 0 ? 8 : 4;
                if (d * hist[i] > 0) m += d * (hist[i] - hist[i - 1]) > 0 ? 8 : 4;
                if (d * (stK[i] - stD[i]) > 0 && (side > 0 ? stK[i] < 80 : stK[i] > 20)) m += 4;
                if (side > 0 ? rsi[i] > 80 : rsi[i] < 20) m -= 4;
                s += Math.max(m, 0);
                // Volume (20) - only with a candle in the signal direction
                boolean candle = d * (c[i] - o[i]) > 0;
                if (hasVol && candle) {
                    s += v[i] > volMa[i] ? 8 : 0;
                    s += rvol >= cfg.rvolTh() ? 8 : 0;
                    s += d * (c[i] - c[i - 1]) > 0 && v[i] > v[i - 1] ? 4 : 0;
                }
                // Structure (20)
                s += (structTrend == side || (side > 0 ? hhhl : lhll)) ? 10 : 0;
                boolean brk = i - (side > 0 ? lBullBO : lBearBO) <= RECENT;
                boolean brkAny = i - (side > 0 ? lBullBr : lBearBr) <= RECENT;
                s += brk ? 6 : brkAny ? 3 : 0;
                s += i - (side > 0 ? lBullRT : lBearRT) <= RECENT ? 4 : 0;
                // Volatility (VWAP skipped on daily data)
                s += d * (c[i] - bbMid[i]) > 0 ? 4 : 0;
                s += volExpand && candle ? 4 : 0;
                double score = Math.min(100.0, s / max * 100.0);
                if (side > 0) bullS[i] = score; else bearS[i] = score;
            }

            // htfDir: 1 bull, -1 bear, 0 flat (blocks), 2 unknown (not enough HTF history, does not block)
            boolean htfBuyOk = !cfg.useHtf() || htfDir[i] == 1 || htfDir[i] == 2;
            boolean htfSellOk = !cfg.useHtf() || htfDir[i] == -1 || htfDir[i] == 2;
            boolean climax = a > 0 && (h[i] - l[i]) > 2.0 * a && (!hasVol || rvol >= 2.5);
            boolean volOk = !hasVol || rvol >= 1.2;
            boolean volStrong = !hasVol || rvol >= 1.5;
            boolean roomUp = Double.isNaN(resLv[0][i]) || a <= 0 || (resLv[0][i] - c[i]) >= a;
            boolean roomDn = Double.isNaN(supLv[0][i]) || a <= 0 || (c[i] - supLv[0][i]) >= a;
            boolean scoreBuy = warm && !gap && !climax && bullS[i] >= cfg.buyTh() && bullS[i] - bearS[i] >= cfg.minEdge();
            boolean scoreSell = warm && !gap && !climax && bearS[i] >= cfg.buyTh() && bearS[i] - bullS[i] >= cfg.minEdge();
            // A fresh structure break is early only. The arrow waits for the retest. No recent break = continuation.
            boolean bullAwait = (i - lBullBr) <= RECENT && (i - lBullRT) > RECENT;
            boolean bearAwait = (i - lBearBr) <= RECENT && (i - lBearRT) > RECENT;
            boolean gatedBuy = scoreBuy && htfBuyOk && volOk && roomUp;
            boolean gatedSell = scoreSell && htfSellOk && volOk && roomDn;
            boolean okBuy = gatedBuy && !bullAwait;
            boolean okSell = gatedSell && !bearAwait;
            boolean strongBuy = okBuy && bullS[i] >= cfg.strongTh() && volStrong && (!cfg.useHtf() || htfDir[i] == 1);
            boolean strongSell = okSell && bearS[i] >= cfg.strongTh() && volStrong && (!cfg.useHtf() || htfDir[i] == -1);
            if (gatedBuy && bullBreak) markers.add(new ChartResult.Marker(t[i], "EARLY_BUY", "early BUY", l[i]));
            if (gatedSell && bearBreak) markers.add(new ChartResult.Marker(t[i], "EARLY_SELL", "early SELL", h[i]));

            // ---------------- Trade management ----------------
            if (dir != 0) {
                boolean closed = false;
                double stopFill = dir == 1 ? Math.min(o[i], stop) : Math.max(o[i], stop);
                boolean stopHit = dir == 1 ? l[i] <= stop : h[i] >= stop;
                boolean tp3Hit = dir == 1 ? h[i] >= tp3 : l[i] <= tp3;

                // Collision resolution: if both SL and TP3 are touched on the same bar
                boolean checkTpFst = false;
                if (stopHit && tp3Hit) {
                    boolean gapStop = dir == 1 ? o[i] <= stop : o[i] >= stop;
                    boolean gapTp = dir == 1 ? o[i] >= tp3 : o[i] <= tp3;
                    if (gapTp && !gapStop) checkTpFst = true;
                    else if (!gapStop && !gapTp) checkTpFst = dir == 1 ? c[i] >= o[i] : c[i] <= o[i];
                }

                if (checkTpFst && tp3Hit) {
                    hits = 3;
                    double tp3Fill = dir == 1 ? Math.max(o[i], tp3) : Math.min(o[i], tp3);
                    markers.add(new ChartResult.Marker(t[i], "TP", "T3", tp3Fill));
                    trades.add(trade(dir, entryStrong, t[entryIdx], entry, initStop, tp1, tp2, tp3, 3, t[i], tp3Fill, "TARGET_3", risk));
                    closed = true;
                } else if (stopHit) {
                    boolean beStop = hits > 0;
                    markers.add(new ChartResult.Marker(t[i], "SL", beStop ? "SL (trail)" : "SL", stopFill));
                    trades.add(trade(dir, entryStrong, t[entryIdx], entry, initStop, tp1, tp2, tp3, hits, t[i], stopFill,
                            beStop ? "TRAIL_STOP" : "STOP_LOSS", risk));
                    closed = true;
                } else {
                    if (hits < 1 && (dir == 1 ? h[i] >= tp1 : l[i] <= tp1)) {
                        hits = 1;
                        stop = dir == 1 ? Math.max(stop, entry) : Math.min(stop, entry);
                        double tp1Fill = dir == 1 ? Math.max(o[i], tp1) : Math.min(o[i], tp1);
                        markers.add(new ChartResult.Marker(t[i], "TP", "T1", tp1Fill));
                    }
                    if (hits < 2 && (dir == 1 ? h[i] >= tp2 : l[i] <= tp2)) {
                        hits = 2;
                        double tp2Fill = dir == 1 ? Math.max(o[i], tp2) : Math.min(o[i], tp2);
                        markers.add(new ChartResult.Marker(t[i], "TP", "T2", tp2Fill));
                    }
                    if (hits < 3 && (dir == 1 ? h[i] >= tp3 : l[i] <= tp3)) {
                        hits = 3;
                        double tp3Fill = dir == 1 ? Math.max(o[i], tp3) : Math.min(o[i], tp3);
                        markers.add(new ChartResult.Marker(t[i], "TP", "T3", tp3Fill));
                        trades.add(trade(dir, entryStrong, t[entryIdx], entry, initStop, tp1, tp2, tp3, 3, t[i], tp3Fill, "TARGET_3", risk));
                        closed = true;
                    }
                    if (!closed && cfg.useTrail()) {
                        stop = dir == 1 ? Math.max(stop, c[i] - cfg.trailMult() * a) : Math.min(stop, c[i] + cfg.trailMult() * a);
                    }
                    if (!closed) {
                        double own = dir == 1 ? bullS[i] : bearS[i];
                        double opp = dir == 1 ? bearS[i] : bullS[i];
                        if (opp >= cfg.watchTh() || own < cfg.exitBelow()) {
                            markers.add(new ChartResult.Marker(t[i], "EXIT", "EXIT", c[i]));
                            trades.add(trade(dir, entryStrong, t[entryIdx], entry, initStop, tp1, tp2, tp3, hits, t[i], c[i], "SIGNAL_EXIT", risk));
                            closed = true;
                        }
                    }
                }
                if (closed) dir = 0;
            }

            if ((okBuy && dir != 1) || (okSell && dir != -1)) {
                // A fresh opposite signal replaces an open trade (closed at signal close).
                if (dir != 0) {
                    markers.add(new ChartResult.Marker(t[i], "EXIT", "REVERSE", c[i]));
                    trades.add(trade(dir, entryStrong, t[entryIdx], entry, initStop, tp1, tp2, tp3, hits, t[i], c[i], "REVERSAL", risk));
                }
                boolean buy = okBuy;
                dir = buy ? 1 : -1;
                entryStrong = buy ? strongBuy : strongSell;
                entryIdx = i;
                entry = c[i];
                risk = cfg.atrMult() * a;
                stop = initStop = entry - dir * risk;
                tp1 = entry + dir * risk;
                tp2 = entry + dir * 2 * risk;
                tp3 = entry + dir * 3 * risk;
                hits = 0;
                String type = buy ? (entryStrong ? "STRONG_BUY" : "BUY") : (entryStrong ? "STRONG_SELL" : "SELL");
                markers.add(new ChartResult.Marker(t[i], type, type.replace('_', ' '), entry));
            }
        }

        // Open trade, if any
        if (dir != 0) {
            trades.add(new ChartResult.Trade(dir == 1 ? "LONG" : "SHORT", entryStrong, t[entryIdx], entry, stop,
                    tp1, tp2, tp3, hits, null, stop, "OPEN", 0));
        }

        int last = n - 1;
        double bs = bullS[last], rs = bearS[last];
        double dom = Math.max(bs, rs);
        boolean bull = bs >= rs;
        boolean hv = v[last] > 0 && !Double.isNaN(volMa[last]) && volMa[last] > 0;
        String rec = dom >= cfg.strongTh() ? (bull ? "STRONG BUY" : "STRONG SELL")
                : dom >= cfg.buyTh() ? (bull ? "BUY" : "SELL")
                : dom >= cfg.watchTh() ? "WATCH" : "NO TRADE";
        double aLast = atr[last];
        if (last > 0) {
            double rv = hv ? v[last] / volMa[last] : 1.0;
            boolean gapLast = cfg.blockGaps() && !Double.isNaN(atr[last - 1]) && Math.abs(o[last] - c[last - 1]) > 1.5 * atr[last - 1];
            boolean climaxLast = aLast > 0 && (h[last] - l[last]) > 2.0 * aLast && (!hv || rv >= 2.5);
            if (gapLast || climaxLast) {
                rec = "NO TRADE · climax";
            } else if (dom >= cfg.buyTh()) {
                int side = bull ? 1 : -1;
                boolean htfOk = !cfg.useHtf() || htfDir[last] == side || htfDir[last] == 2;
                boolean volOkLast = !hv || rv >= 1.2;
                double lvl = side == 1 ? resLv[0][last] : supLv[0][last];
                double dist = side == 1 ? lvl - c[last] : c[last] - lvl;
                boolean room = Double.isNaN(lvl) || aLast <= 0 || dist >= aLast;
                int br = side == 1 ? lBullBr : lBearBr;
                int rt = side == 1 ? lBullRT : lBearRT;
                boolean await = (last - br) <= RECENT && (last - rt) > RECENT;
                if (!htfOk) rec = "NO TRADE · HTF";
                else if (!volOkLast) rec = "NO TRADE · RVOL";
                else if (!room) rec = side == 1 ? "NO TRADE · near R1" : "NO TRADE · near S1";
                else if (await) rec = "WATCH · retest";
                else if (dom >= cfg.strongTh() && hv && rv < 1.5) rec = bull ? "BUY" : "SELL";
            }
        }

        // Harmonize summary recommendation with active trade position
        if (dir != 0) {
            if (entryIdx == last) {
                rec = dir == 1 ? (entryStrong ? "STRONG BUY" : "BUY") : (entryStrong ? "STRONG SELL" : "SELL");
            } else {
                double ownScore = dir == 1 ? bs : rs;
                double oppScore = dir == 1 ? rs : bs;
                if (oppScore >= cfg.watchTh() || ownScore < cfg.exitBelow()) {
                    rec = dir == 1 ? "EXIT LONG" : "EXIT SHORT";
                } else if (hits > 0) {
                    rec = dir == 1 ? "HOLD LONG · trail" : "HOLD SHORT · trail";
                } else {
                    rec = dir == 1 ? "HOLD LONG" : "HOLD SHORT";
                }
            }
        }

        // Next trade setup calculation
        ChartResult.NextTrade nextTrade;
        double plannedRisk = Double.isNaN(aLast) || aLast <= 0 ? round(c[last] * 0.015) : round(cfg.atrMult() * aLast);
        if (dir != 0) {
            String act = dir == 1 ? (hits > 0 ? "HOLD LONG (trail)" : "HOLD LONG") : (hits > 0 ? "HOLD SHORT (trail)" : "HOLD SHORT");
            double rr = risk > 0 ? round(Math.abs(tp2 - entry) / risk) : 2.0;
            nextTrade = new ChartResult.NextTrade(act, round(entry), round(stop), round(tp1), round(tp2), round(tp3), round(risk), rr);
        } else if (rec.contains("BUY")) {
            double pEntry = round(c[last]);
            double pStop = round(pEntry - plannedRisk);
            double pTp1 = round(pEntry + plannedRisk);
            double pTp2 = round(pEntry + 2 * plannedRisk);
            double pTp3 = round(pEntry + 3 * plannedRisk);
            nextTrade = new ChartResult.NextTrade(rec.contains("STRONG") ? "STRONG BUY" : "BUY",
                    pEntry, pStop, pTp1, pTp2, pTp3, plannedRisk, 2.0);
        } else if (rec.contains("SELL")) {
            double pEntry = round(c[last]);
            double pStop = round(pEntry + plannedRisk);
            double pTp1 = round(pEntry - plannedRisk);
            double pTp2 = round(pEntry - 2 * plannedRisk);
            double pTp3 = round(pEntry - 3 * plannedRisk);
            nextTrade = new ChartResult.NextTrade(rec.contains("STRONG") ? "STRONG SELL" : "SELL",
                    pEntry, pStop, pTp1, pTp2, pTp3, plannedRisk, 2.0);
        } else if (rec.startsWith("WATCH")) {
            if (bull) {
                double pEntry = !Double.isNaN(resLv[0][last]) ? round(resLv[0][last]) : round(c[last]);
                double pStop = round(pEntry - plannedRisk);
                nextTrade = new ChartResult.NextTrade("WATCH LONG", pEntry, pStop,
                        round(pEntry + plannedRisk), round(pEntry + 2 * plannedRisk), round(pEntry + 3 * plannedRisk), plannedRisk, 2.0);
            } else {
                double pEntry = !Double.isNaN(supLv[0][last]) ? round(supLv[0][last]) : round(c[last]);
                double pStop = round(pEntry + plannedRisk);
                nextTrade = new ChartResult.NextTrade("WATCH SHORT", pEntry, pStop,
                        round(pEntry - plannedRisk), round(pEntry - 2 * plannedRisk), round(pEntry - 3 * plannedRisk), plannedRisk, 2.0);
            }
        } else {
            nextTrade = new ChartResult.NextTrade("NO TRADE", 0, 0, 0, 0, 0, 0, 0);
        }

        String trend = structTrend == 1 && e21[last] > e50[last] ? "BULLISH"
                : structTrend == -1 && e21[last] < e50[last] ? "BEARISH" : "SIDEWAYS";
        String align = e9[last] > e21[last] && e21[last] > e50[last] ? "BULL STACK"
                : e9[last] < e21[last] && e21[last] < e50[last] ? "BEAR STACK" : "MIXED";
        boolean hhhlN = !Double.isNaN(sh2) && !Double.isNaN(sl2) && sh1 > sh2 && sl1 > sl2;
        boolean lhllN = !Double.isNaN(sh2) && !Double.isNaN(sl2) && sh1 < sh2 && sl1 < sl2;
        String structure = structTrend == 1 ? (hhhlN ? "HH/HL" : "BULL") : structTrend == -1 ? (lhllN ? "LH/LL" : "BEAR") : "NEUTRAL";
        String htf = htfDir[last] == 1 ? "BULLISH (" + htfLabel + ")"
                : htfDir[last] == -1 ? "BEARISH (" + htfLabel + ")"
                : htfDir[last] == 2 ? "n/a (" + htfLabel + ")"
                : "FLAT (" + htfLabel + ")";
        String macdTxt = hist[last] > 0 ? (hist[last] > hist[last - 1] ? "BULL ↑" : "BULL ↓")
                : (hist[last] < hist[last - 1] ? "BEAR ↓" : "BEAR ↑");
        String status = dir == 1 ? "LONG" : dir == -1 ? "SHORT" : "FLAT";
        String warning = n < 200 ? "Fewer than 200 bars: EMA200 not used in scoring." : (hv ? null : "No volume data: volume scoring disabled.");

        ChartResult.InstitutionalData inst = InstitutionalIndicator.calculate(t, o, h, l, c, v, htfDir, htfLabel, resLv, supLv);

        return new ChartResult(ticker, cfg.preset(), candles, box(e9), box(e21), box(e50), box(e200), box(vwap),
                box(resistance), box(support),
                List.of(box(resLv[0]), box(resLv[1]), box(resLv[2])),
                List.of(box(supLv[0]), box(supLv[1]), box(supLv[2])), box(bullS), box(bearS), markers, trades,
                new ChartResult.Summary(trend, align, round(rsi[last]), macdTxt, round(hv ? v[last] / volMa[last] : 1.0),
                        structure, htf, round(bs), round(rs), rec, status, n, warning, nextTrade),
                inst);
    }

    private static ChartResult.Trade trade(int dir, boolean strong, String entryTime, double entry, double stop,
                                           double tp1, double tp2, double tp3, int hits, String exitTime,
                                           double exitPx, String reason, double risk) {
        double r = risk > 0 ? dir * (exitPx - entry) / risk : 0;
        return new ChartResult.Trade(dir == 1 ? "LONG" : "SHORT", strong, entryTime, entry, stop, tp1, tp2, tp3,
                hits, exitTime, exitPx, reason, Math.round(r * 100.0) / 100.0);
    }

    // ---------------- HTF trend from completed higher-timeframe bars only ----------------
    // 5m->15m, 15m->1h, 1h->4h, 4h->1D, 1D->1W
    private static String htfName(String tf) {
        return switch (tf == null ? "1D" : tf.toLowerCase()) {
            case "5m" -> "15m";
            case "15m" -> "1h";
            case "1h" -> "4h";
            case "4h" -> "1D";
            default -> "1W";
        };
    }

    private static int[] htfTrend(String[] t, double[] c, String tf) {
        int n = t.length;
        int[] out = new int[n];
        int base = tfSeconds(tf);
        long htfSec = switch (base) {
            case 300 -> 900L;
            case 900 -> 3600L;
            case 3600 -> 14400L;
            case 14400 -> 86400L;
            default -> 0L;
        };
        long curBucket = Long.MIN_VALUE;
        double lastClose = Double.NaN;
        double ema21 = Double.NaN, ema50 = Double.NaN, hClose = Double.NaN;
        int done = 0;
        for (int i = 0; i < n; i++) {
            long bucket;
            if (base > 0) {
                bucket = Math.floorDiv(Long.parseLong(t[i]), htfSec);
            } else {
                try {
                    LocalDate d = LocalDate.parse(t[i]);
                    bucket = d.get(IsoFields.WEEK_BASED_YEAR) * 100L + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                } catch (Exception e) {
                    bucket = i / 5;
                }
            }
            if (curBucket != Long.MIN_VALUE && bucket != curBucket) {
                // previous HTF bar is now complete
                hClose = lastClose;
                done++;
                ema21 = Double.isNaN(ema21) ? hClose : ema21 + (hClose - ema21) * 2.0 / 22.0;
                ema50 = Double.isNaN(ema50) ? hClose : ema50 + (hClose - ema50) * 2.0 / 51.0;
            }
            curBucket = bucket;
            lastClose = c[i];
            if (done < 50) out[i] = 2; // unknown: not enough closed HTF bars yet
            else out[i] = hClose > ema50 && ema21 > ema50 ? 1 : hClose < ema50 && ema21 < ema50 ? -1 : 0;
        }
        return out;
    }

    // ---------------- Indicator math (NaN until enough data) ----------------
    static double[] ema(double[] x, int len) {
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
        double k = 2.0 / (len + 1);
        for (int i = s + len; i < n; i++) {
            prev = x[i] * k + prev * (1 - k);
            out[i] = prev;
        }
        return out;
    }

    static double[] sma(double[] x, int len) {
        int n = x.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        for (int i = len - 1; i < n; i++) {
            double sum = 0;
            boolean ok = true;
            for (int j = i - len + 1; j <= i; j++) {
                if (Double.isNaN(x[j])) { ok = false; break; }
                sum += x[j];
            }
            if (ok) out[i] = sum / len;
        }
        return out;
    }

    static double[] stdev(double[] x, int len) {
        double[] m = sma(x, len);
        double[] out = new double[x.length];
        Arrays.fill(out, Double.NaN);
        for (int i = len - 1; i < x.length; i++) {
            double ss = 0;
            for (int j = i - len + 1; j <= i; j++) ss += (x[j] - m[i]) * (x[j] - m[i]);
            out[i] = Math.sqrt(ss / len);
        }
        return out;
    }

    static double[] rsi(double[] x, int len) {
        int n = x.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        if (n <= len) return out;
        double g = 0, ls = 0;
        for (int i = 1; i <= len; i++) {
            double d = x[i] - x[i - 1];
            if (d > 0) g += d; else ls -= d;
        }
        g /= len; ls /= len;
        out[len] = ls == 0 ? 100 : 100 - 100 / (1 + g / ls);
        for (int i = len + 1; i < n; i++) {
            double d = x[i] - x[i - 1];
            g = (g * (len - 1) + Math.max(d, 0)) / len;
            ls = (ls * (len - 1) + Math.max(-d, 0)) / len;
            out[i] = ls == 0 ? 100 : 100 - 100 / (1 + g / ls);
        }
        return out;
    }

    static double[] atr(double[] h, double[] l, double[] c, int len) {
        int n = h.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        if (n <= len) return out;
        double[] tr = new double[n];
        tr[0] = h[0] - l[0];
        for (int i = 1; i < n; i++)
            tr[i] = Math.max(h[i] - l[i], Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])));
        double sum = 0;
        for (int i = 0; i < len; i++) sum += tr[i];
        double prev = sum / len;
        out[len - 1] = prev;
        for (int i = len; i < n; i++) {
            prev = (prev * (len - 1) + tr[i]) / len;
            out[i] = prev;
        }
        return out;
    }

    static double[] stoch(double[] x, int len) {
        int n = x.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        for (int i = len - 1; i < n; i++) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            boolean ok = true;
            for (int j = i - len + 1; j <= i; j++) {
                if (Double.isNaN(x[j])) { ok = false; break; }
                lo = Math.min(lo, x[j]); hi = Math.max(hi, x[j]);
            }
            if (ok) out[i] = hi == lo ? 50 : (x[i] - lo) / (hi - lo) * 100;
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

