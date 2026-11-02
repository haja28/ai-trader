package com.hedgefund.ai.chart;

import com.hedgefund.ai.model.api.Price;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SignalEngineTest {

    private List<Price> series(int n, double drift) {
        List<Price> out = new ArrayList<>();
        double px = 100;
        LocalDate d = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < n; i++) {
            double wave = Math.sin(i / 7.0) * 2;
            double next = px + drift + wave * 1.0;
            double hi = Math.max(px, next) + 0.8, lo = Math.min(px, next) - 0.8;
            out.add(new Price(px, next, hi, lo, next > px ? 2_500_000L + (i % 5) * 100_000L : 800_000L, d.plusDays(i).toString()));
            px = next;
        }
        return out;
    }

    @Test
    void shortHistoryReturnsWarningNotException() {
        ChartResult r = new SignalEngine().analyze("X", series(30, 0.1), SignalConfig.of("swing"));
        assertNotNull(r.summary().warning());
        assertTrue(r.markers().isEmpty());
    }

    @Test
    void uptrendProducesBuysAndNoNaNOutput() {
        ChartResult r = new SignalEngine().analyze("X", series(400, 0.4), SignalConfig.of("swing"));
        assertEquals(400, r.candles().size());
        assertTrue(r.markers().stream().anyMatch(m -> m.type().contains("BUY")),
                "max bull=" + r.bullScore().stream().filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max()
                        + " max bear=" + r.bearScore().stream().filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max()
                        + " summary=" + r.summary());
        assertTrue(r.bullScore().stream().noneMatch(d -> d != null && d.isNaN()));
    }

    @Test
    void aggregatesFiveMinuteBarsInto15m() {
        List<Price> five = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            five.add(new Price(100 + i, 101 + i, 102 + i, 99 + i, 10, String.valueOf(1_700_000_100L + i * 300L)));
        }
        List<Price> agg = SignalEngine.aggregate(five, 900);
        assertEquals(2, agg.size());
        assertEquals(30, agg.get(0).getVolume());
        assertEquals(104, agg.get(0).getHigh());
        assertEquals(99, agg.get(0).getLow());
    }

    @Test
    void intradayRunsWithVwapAndHtf() {
        List<Price> out = new ArrayList<>();
        double px = 100;
        long ts = 1_700_000_000L;
        for (int i = 0; i < 1500; i++) {
            double next = px + 0.05 + Math.sin(i / 9.0);
            out.add(new Price(px, next, Math.max(px, next) + 0.5, Math.min(px, next) - 0.5,
                    next > px ? 3_000_000L : 900_000L, String.valueOf(ts + i * 900L)));
            px = next;
        }
        ChartResult r = new SignalEngine().analyze("X", out, SignalConfig.of("intraday"), "15m");
        assertEquals(1500, r.candles().size());
        assertTrue(r.vwap().stream().anyMatch(java.util.Objects::nonNull));
        assertTrue(r.summary().htfTrend().contains("1h"));
    }

    @Test
    void missingVolumeStillWorks() {
        List<Price> s = series(300, 0.4);
        s.forEach(p -> p.setVolume(0));
        ChartResult r = new SignalEngine().analyze("X", s, SignalConfig.of("swing"));
        assertTrue(r.summary().warning().contains("volume"));
    }

    @Test
    void climaxGapDoesNotSell() {
        List<Price> s = series(120, -0.05);
        Price last = s.get(s.size() - 1);
        double prev = last.getClose();
        s.add(new Price(prev * 0.72, prev * 0.70, prev * 0.74, prev * 0.68, 8_000_000L,
                LocalDate.parse(last.getTime()).plusDays(1).toString()));
        ChartResult r = new SignalEngine().analyze("X", s, SignalConfig.of("swing"));
        assertTrue(r.summary().recommendation().startsWith("NO TRADE"), r.summary().recommendation());
        assertTrue(r.trades().stream().noneMatch(t -> "SHORT".equals(t.direction()) && t.entryTime().equals(s.get(s.size() - 1).getTime())));
    }

    @Test
    void institutionalTrendCyclePopulated() {
        ChartResult r = new SignalEngine().analyze("X", series(400, 0.4), SignalConfig.of("swing"));
        assertNotNull(r.institutional());
        ChartResult.InstitutionalSummary is = r.institutional().summary();
        assertNotNull(is);
        assertEquals(400, r.institutional().ema20().size());
        assertEquals(400, r.institutional().ema50().size());
        assertEquals(400, r.institutional().ema200().size());
        assertEquals(400, r.institutional().trailStop().size());
        assertEquals(400, r.institutional().rsi().size());
        assertEquals(400, r.institutional().adx().size());
        assertEquals(400, r.institutional().rvol().size());
        assertEquals(400, r.institutional().upperResistance().size());
        assertEquals(400, r.institutional().lowerSupport().size());
        assertTrue(is.rsi() > 0);
        assertTrue(is.rvol() > 0);
        assertTrue(is.atrStopLoss() > 0);
        assertTrue(is.atrTakeProfit2() > 0);
        assertTrue(is.minRiskRewardRatio() >= 2.0);
        assertNotNull(is.divergence());
        assertNotNull(is.breakoutStatus());
        assertNotNull(is.htfTrend());
        assertNotNull(is.scoreRecommendation());
        assertTrue(is.bullScore() >= 0 && is.bullScore() <= 100);
        assertTrue(is.bearScore() >= 0 && is.bearScore() <= 100);
        long bearDivCount = r.institutional().markers().stream().filter(m -> "INST_BEAR_DIV".equals(m.type())).count();
        assertTrue(bearDivCount <= 10, "Bearish divergence markers should not spam every bar, got: " + bearDivCount);
    }
}
