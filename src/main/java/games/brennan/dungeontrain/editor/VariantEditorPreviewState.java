package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-(plotKey, localPos) "pinned entry" state for the editor preview
 * ticker. When the author clicks any rotation control on a variant row,
 * the controller pins that row's entry index — the preview ticker stops
 * cycling entries on that cell and stays on the pinned entry. The
 * direction cycle within that entry continues for RANDOM / OPTIONS modes.
 *
 * <p>Pin persists until either (a) the cell is dropped (CLEAR / REMOVE
 * shrinks below MIN_STATES_PER_ENTRY), or (b) the player clicks a
 * different row's rotation control (which re-pins to the new row).</p>
 *
 * <p>Server-side singleton; never queried from the client. Memory cost
 * is one int per pinned cell — bounded by the number of cells the author
 * has actively edited rotation on.</p>
 */
public final class VariantEditorPreviewState {

    /** plotKey → localPos → pinned entry index. */
    private static final Map<String, Map<BlockPos, Integer>> PINNED = new HashMap<>();

    private VariantEditorPreviewState() {}

    public static synchronized void setPinned(String plotKey, BlockPos localPos, int entryIndex) {
        if (plotKey == null || localPos == null || entryIndex < 0) return;
        PINNED.computeIfAbsent(plotKey, k -> new HashMap<>()).put(localPos.immutable(), entryIndex);
    }

    public static synchronized void clearPinned(String plotKey, BlockPos localPos) {
        if (plotKey == null || localPos == null) return;
        Map<BlockPos, Integer> inner = PINNED.get(plotKey);
        if (inner == null) return;
        inner.remove(localPos);
        if (inner.isEmpty()) PINNED.remove(plotKey);
    }

    /** Pinned entry index, or {@code null} if the cell is in cycle mode. */
    @Nullable
    public static synchronized Integer pinnedEntry(String plotKey, BlockPos localPos) {
        if (plotKey == null || localPos == null) return null;
        Map<BlockPos, Integer> inner = PINNED.get(plotKey);
        return inner == null ? null : inner.get(localPos);
    }
}
