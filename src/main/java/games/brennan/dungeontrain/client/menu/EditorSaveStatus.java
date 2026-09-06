package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorUnsavedRequestPacket;

import java.util.List;

/**
 * Whether the plot the player is standing in has edits nobody has saved — what the header Save
 * icon colours itself by.
 *
 * <p>The answer comes from the server's {@link EditorDirtyCheck#findDirty} scan, the same one the
 * unsaved-changes screen shows, fetched with {@link EditorUnsavedRequestPacket} and cached in
 * {@link EditorStatusHudOverlay#unsavedList()}. It is asked for on demand — when the menu opens
 * and a few ticks after a menu command that could change the plot — never per tick: the scan
 * compares every plot's blocks against its snapshot, which is fine once per click and not sixty
 * times a second.</p>
 *
 * <p>The scan keys its rows the way {@link EditorDirtyCheck#dirtyKeyFor} does, so
 * {@link #dirtyKey} has to produce exactly that shape from the HUD's {@code modelId} /
 * {@code modelName} pair. Parts and architecture have no scan rows, so they read as clean.</p>
 */
public final class EditorSaveStatus {

    /** Steady blue: the plot matches what is on disk. */
    static final int CLEAN_TINT = 0xFF4FA8FF;

    /** Green, pulsed by {@link #pulse(long)}: something here is not saved yet. */
    static final int DIRTY_TINT = 0xFF55E07A;

    /** One pulse per second, between these brightness factors. */
    static final float PULSE_MIN = 0.55f;
    static final float PULSE_MAX = 1.0f;
    private static final double PULSE_PERIOD_MS = 1000.0;

    private EditorSaveStatus() {}

    /** Ask the server for a fresh dirty list; the reply lands in the HUD overlay's cache. */
    public static void request() {
        DungeonTrainNet.sendToServer(new EditorUnsavedRequestPacket());
    }

    /** True when the current plot has unsaved edits, per the last reply received. */
    public static boolean currentPlotDirty() {
        PlotCategory category = PlotCategory.fromId(EditorStatusHudOverlay.category()).orElse(null);
        if (category == null) return false;
        String key = dirtyKey(category, EditorStatusHudOverlay.modelId(), EditorStatusHudOverlay.modelName());
        return isDirty(EditorStatusHudOverlay.unsavedList(), category.id(), key);
    }

    /**
     * The {@link EditorDirtyCheck.DirtyEntry#modelId()} key for a plot, or null for a category the
     * scan does not cover.
     */
    public static String dirtyKey(PlotCategory category, String modelId, String modelName) {
        if (category == null || modelId == null || modelId.isEmpty()) return null;
        return switch (category) {
            case CARRIAGES, CONTENTS -> modelId;
            case TRACKS, PORTALS -> (modelName == null || modelName.isEmpty())
                ? modelId : modelId + "." + modelName;
            case ARCHITECTURE, PARTS -> null;
        };
    }

    /** True when {@code rows} holds an unsaved (not merely unpromoted) entry for that plot. */
    public static boolean isDirty(List<EditorDirtyCheck.DirtyEntry> rows, String categoryId, String key) {
        if (rows == null || key == null || categoryId == null) return false;
        for (EditorDirtyCheck.DirtyEntry row : rows) {
            if (row.isUnsaved() && categoryId.equals(row.categoryId()) && key.equals(row.modelId())) {
                return true;
            }
        }
        return false;
    }

    /** The icon's tint right now: steady blue when clean, breathing green when not. */
    public static int tint(boolean dirty, long nowMillis) {
        return dirty ? scale(DIRTY_TINT, pulse(nowMillis)) : CLEAN_TINT;
    }

    /**
     * Brightness factor of the pulse at {@code millis}, in [{@link #PULSE_MIN}, {@link #PULSE_MAX}].
     *
     * <p>Public because it is the breathing of this screen rather than of one button: the Submit icon
     * pulses on the same wave, and two buttons breathing out of step would read as two unrelated
     * animations rather than one screen asking for attention.</p>
     */
    public static float pulse(long millis) {
        double phase = (millis % (long) PULSE_PERIOD_MS) / PULSE_PERIOD_MS;
        double wave = (Math.sin(phase * 2.0 * Math.PI) + 1.0) / 2.0;   // 0..1
        return (float) (PULSE_MIN + (PULSE_MAX - PULSE_MIN) * wave);
    }

    /** Scale the RGB channels of an ARGB colour by {@code f}, leaving alpha alone. */
    public static int scale(int argb, float f) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((argb & 0xFF) * f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
