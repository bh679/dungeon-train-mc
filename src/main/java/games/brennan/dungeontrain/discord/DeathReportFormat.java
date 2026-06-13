package games.brennan.dungeontrain.discord;

/**
 * Pure formatters for the Discord death/run-summary embed fields, matching the
 * in-game death-screen presentation (e.g. {@code "28 m"}, {@code "0:29"}).
 * Kept side-effect-free so they are unit-testable without a running game.
 */
public final class DeathReportFormat {

    private DeathReportFormat() {}

    /** Distance rounded to whole blocks, e.g. {@code 28.4 -> "28 m"}. */
    public static String distance(double blocks) {
        return Math.round(blocks) + " m";
    }

    /**
     * Run time from server ticks (20/sec) as {@code M:SS}, or {@code H:MM:SS}
     * once it passes an hour. Negative input clamps to zero.
     */
    public static String time(long ticks) {
        long totalSeconds = Math.max(0L, ticks) / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%d:%02d", minutes, seconds);
    }
}
