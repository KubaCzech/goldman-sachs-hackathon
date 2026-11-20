package gs.alerting.core;

import java.util.Locale;
import java.util.Map;

/** Severity normalisation and ordering (SPEC 5.2). */
public final class Severity {
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("critical", "critical"), Map.entry("crit", "critical"), Map.entry("fatal", "critical"),
            Map.entry("emergency", "critical"), Map.entry("page", "critical"), Map.entry("p1", "critical"),
            Map.entry("error", "error"), Map.entry("err", "error"), Map.entry("high", "error"), Map.entry("p2", "error"),
            Map.entry("warning", "warning"), Map.entry("warn", "warning"), Map.entry("medium", "warning"), Map.entry("p3", "warning"),
            Map.entry("info", "info"), Map.entry("information", "info"), Map.entry("notice", "info"),
            Map.entry("low", "info"), Map.entry("debug", "info"), Map.entry("p4", "info"));

    private Severity() {}

    public static String normalise(String raw) {
        if (raw == null) return "unknown";
        return ALIASES.getOrDefault(raw.trim().toLowerCase(Locale.ROOT), "unknown");
    }

    public static int rank(String normalised) {
        return switch (normalised == null ? "unknown" : normalised) {
            case "critical" -> 4;
            case "error" -> 3;
            case "warning" -> 2;
            case "info" -> 1;
            default -> 0;
        };
    }

    public static String defaultColor(String normalised) {
        return switch (normalised == null ? "unknown" : normalised) {
            case "critical" -> "#b71c1c";
            case "error" -> "#d32f2f";
            case "warning" -> "#f9a825";
            case "info" -> "#1976d2";
            default -> "#757575";
        };
    }
}
