package com.hedgefund.ai.chart;

/**
 * Tunable parameters of the confluence engine. Presets mirror the Pine Script version.
 */
public record SignalConfig(String preset, int pivotLen, double atrMult, double trailMult, double rvolTh,
                           double strongTh, double buyTh, double watchTh, double minEdge,
                           double exitBelow, boolean useHtf, boolean useTrail, boolean blockGaps) {

    public static SignalConfig of(String preset) {
        String p = preset == null ? "swing" : preset.toLowerCase();
        return switch (p) {
            case "intraday" -> new SignalConfig("Intraday", 3, 1.5, 2.0, 1.5, 85, 70, 55, 15, 40, true, true, true);
            case "position" -> new SignalConfig("Position", 8, 3.0, 4.0, 1.2, 85, 70, 55, 15, 40, true, true, true);
            default -> new SignalConfig("Swing", 5, 2.0, 3.0, 1.3, 85, 70, 55, 15, 40, true, true, true);
        };
    }
}

