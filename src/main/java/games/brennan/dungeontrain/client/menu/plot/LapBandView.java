package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.worldgen.LapBand;

/**
 * Which row's band cell is showing one lap's letters instead of the four laps — client view state
 * only, like {@link StagesSort}. One row at a time: opening a lap on another row closes the last.
 * Rows are identified by a caller-built key ({@link #rowKey}).
 */
public final class LapBandView {

    private LapBandView() {}

    private static volatile String openKey = null;
    private static volatile LapBand.Lap openLap = null;

    /** Stable identity of a type-menu row: menu + template (+ name for tracks). */
    public static String rowKey(String menuName, String modelId, String modelName) {
        return menuName + "|" + modelId + "|" + (modelName == null ? "" : modelName);
    }

    /** The lap open on {@code key}'s row, or null when it shows the four laps. */
    public static LapBand.Lap openLap(String key) {
        String k = openKey;
        return k != null && k.equals(key) ? openLap : null;
    }

    /** Show {@code lap}'s letters on {@code key}'s row (closing any other row). */
    public static void open(String key, LapBand.Lap lap) {
        openLap = lap;
        openKey = key;
    }

    /** Back to the four laps. */
    public static void close() {
        openKey = null;
        openLap = null;
    }
}
