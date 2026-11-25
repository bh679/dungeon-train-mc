package games.brennan.dungeontrain.portal;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.biome.Biome;

import java.util.Arrays;
import java.util.Objects;

/**
 * What the decoration pass changed in a sampled room: the cells where the decorated cube differs
 * from the ground cube the room was first written from.
 *
 * <p>Worked out on the sampler thread, so the server thread never compares a whole cube. A room
 * standing from its ground then has only those cells written ({@code PortalChunkDimension}): the
 * trees, the grass and the structure. Before this, the decoration landing started the pour again
 * from its first row, and once a room was poured it poured and dressed the whole room a second
 * time.</p>
 *
 * <p>A cell counts as changed when its state differs, or its block entity's saved NBT does — a
 * chest that was already a chest but now carries its loot table is still a change worth writing.</p>
 */
final class PortalChunkDecorationDiff {

    private PortalChunkDecorationDiff() {}

    /**
     * {@code decorated}, carrying the cells it differs from {@code ground} at. Returned unchanged
     * when the two cannot be compared — no ground, or a different shape — so the room falls back to
     * being poured whole.
     */
    static PortalChunkSlice attach(PortalChunkSlice ground, PortalChunkSlice decorated) {
        if (ground == null || decorated == null || ground == decorated) return decorated;
        if (ground.width() != decorated.width() || ground.height() != decorated.height()
                || ground.cellCount() != decorated.cellCount()) {
            return decorated;
        }
        return decorated.withChanges(ground, changedCells(ground, decorated), biomesDiffer(ground, decorated));
    }

    /** Cell indices where the two slices differ, ascending. */
    static int[] changedCells(PortalChunkSlice ground, PortalChunkSlice decorated) {
        int cells = decorated.cellCount();
        int[] out = new int[64];
        int n = 0;
        for (int i = 0; i < cells; i++) {
            if (differs(ground, decorated, i)) {
                if (n == out.length) out = Arrays.copyOf(out, out.length * 2);
                out[n++] = i;
            }
        }
        return Arrays.copyOf(out, n);
    }

    private static boolean differs(PortalChunkSlice ground, PortalChunkSlice decorated, int i) {
        // States are registry singletons, so identity is equality.
        if (ground.stateAtIndex(i) != decorated.stateAtIndex(i)) return true;
        CompoundTag before = ground.blockEntityAtIndex(i);
        CompoundTag after = decorated.blockEntityAtIndex(i);
        return before != after && !Objects.equals(before, after);
    }

    /** Whether any quart's biome differs; true when either side has none to compare. */
    static boolean biomesDiffer(PortalChunkSlice ground, PortalChunkSlice decorated) {
        Holder<Biome>[] a = ground.biomesRaw();
        Holder<Biome>[] b = decorated.biomesRaw();
        if (a == null || b == null) return a != b;
        if (a.length != b.length) return true;
        for (int i = 0; i < a.length; i++) {
            if (!Objects.equals(a[i], b[i])) return true;
        }
        return false;
    }
}
