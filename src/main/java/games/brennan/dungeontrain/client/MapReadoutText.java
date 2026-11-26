package games.brennan.dungeontrain.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The arithmetic and text rewrites the approved-mod position hooks share ({@link CoordinateDisguise}).
 * No Minecraft imports, so it unit-tests without a bootstrap, as {@link DebugScreenDepthLines} does.
 *
 * <p>Same bargain as the debug screen: a line that is not exactly the shape expected passes through
 * untouched, so a mod update degrades to "the readout tells the truth", never to a mangled line.</p>
 */
public final class MapReadoutText {

    /**
     * Xaero's World Map cursor line: {@code X: 12}, then optionally {@code  Y: 64} and a second Y in
     * brackets ({@code  (70)}), then {@code  Z: -5}. Built by plain concatenation in {@code GuiMap.render}.
     */
    private static final Pattern XAERO_CURSOR = Pattern.compile(
        "^X: (-?\\d+)(?: Y: (-?\\d+))?(?: \\((-?\\d+)\\))? Z: (-?\\d+)$");

    private MapReadoutText() {}

    /**
     * {@code value + delta}, held to an int. Mods that keep positions as ints cannot print the Far
     * Lands' later laps, which pass two billion; the readout then saturates at the int's edge, which
     * still reads as the Far Lands rather than the train's real distance.
     */
    public static int add(int value, long delta) {
        long sum = (long) value + delta;
        if (sum > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (sum < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) sum;
    }

    /**
     * The fallback for how far a carriage block's X and Z are moved off Sable's plot, for a world whose
     * train spawned before the real offset was recorded ({@link ClientPlotReadoutOffset}). Sable stores a
     * carriage's blocks about 20.48 million blocks out; 20.5 million brings them near the world. Y is
     * never moved: the plot keeps the carriage's heights.
     */
    public static final long PLOT_OFFSET = 20_500_000L;

    /** An X or Z on a carriage's plot, moved by {@code offset} (plot minus real, at the train's spawn). */
    public static int fromPlot(int value, long offset) {
        return add(value, -offset);
    }

    /** The vertical chunk section JourneyMap prints in brackets after a Y: {@code y >> 4}. */
    public static int section(int y) {
        return y >> 4;
    }

    /** Xaero's World Map cursor line with each figure moved by {@code shift}; any other text unchanged. */
    public static String xaeroCursor(String line, DebugScreenDepthLines.Shift shift) {
        if (line == null || shift == null || shift.isZero()) return line;
        Matcher m = XAERO_CURSOR.matcher(line);
        if (!m.matches()) return line;
        try {
            StringBuilder out = new StringBuilder("X: ")
                .append(Long.parseLong(m.group(1)) + shift.x());
            if (m.group(2) != null) out.append(" Y: ").append(Long.parseLong(m.group(2)) + shift.y());
            if (m.group(3) != null) out.append(" (").append(Long.parseLong(m.group(3)) + shift.y()).append(')');
            return out.append(" Z: ").append(Long.parseLong(m.group(4)) + shift.z()).toString();
        } catch (NumberFormatException e) {
            return line;
        }
    }
}
