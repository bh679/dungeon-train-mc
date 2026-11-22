package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The condition last changed in each block variant cell, so the If popup can prefill an empty row
 * with it ({@code BlockVariantEditPacket.Op.PREFILL_COND}). Kept per cell, or per lock group when
 * the cell is in one, since edits already reach the whole group. Server-session only: after a
 * restart {@link #fallback} picks the nearest row that has a condition instead.
 */
public final class VariantConditionMemory {

    private static final Map<String, VariantCondition> LAST = new ConcurrentHashMap<>();

    private VariantConditionMemory() {}

    /** Key for a cell: its lock group when it has one, else its position in the plot. */
    public static String key(String plotKey, BlockPos localPos, int lockId) {
        return lockId > 0 ? plotKey + "#g" + lockId : plotKey + "@" + localPos.asLong();
    }

    /** Remember {@code condition} as the cell's last change; a condition with no values is not one. */
    public static void record(String key, VariantCondition condition) {
        if (condition == null || condition.isDefault()) return;
        LAST.put(key, condition);
    }

    @Nullable
    public static VariantCondition recall(String key) {
        return LAST.get(key);
    }

    /**
     * The condition an empty {@code row} should take: the remembered one, else the nearest other
     * row's (above first, then below), else {@code null} when no row has one.
     */
    @Nullable
    public static VariantCondition prefillFor(@Nullable VariantCondition remembered,
                                              List<VariantCondition> rows, int row) {
        if (remembered != null && !remembered.isDefault()) return remembered;
        return fallback(rows, row);
    }

    /** Nearest row above {@code row} with a condition, else the nearest below; {@code null} if none. */
    @Nullable
    public static VariantCondition fallback(List<VariantCondition> rows, int row) {
        for (int i = Math.min(row, rows.size()) - 1; i >= 0; i--) {
            if (!rows.get(i).isDefault()) return rows.get(i);
        }
        for (int i = row + 1; i < rows.size(); i++) {
            if (!rows.get(i).isDefault()) return rows.get(i);
        }
        return null;
    }

    /** Test hook. */
    static void clear() {
        LAST.clear();
    }
}
