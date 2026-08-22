package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DeathHeadSkins#mix} — the determinism the whole feature rests on: a carriage
 * regenerated after a cull must dress its heads exactly as it did the first time, and two heads in
 * the same carriage must not all end up wearing one face.
 *
 * <p>{@code pick} itself is left to the in-game Gate 2 check: it reads the config and PlayerMob's
 * skin registry, neither of which exists without a Forge bootstrap this source set avoids.</p>
 */
final class DeathHeadSkinsTest {

    private static final long SEED = 0x1234_5678_9ABC_DEF0L;

    @Test
    @DisplayName("same seed, carriage and cell always mix to the same state")
    void mix_isDeterministic() {
        BlockPos pos = new BlockPos(3, 1, 4);
        assertEquals(DeathHeadSkins.mix(SEED, 7, pos), DeathHeadSkins.mix(SEED, 7, pos));
    }

    @Test
    @DisplayName("each input independently changes the result")
    void mix_variesWithEveryInput() {
        BlockPos pos = new BlockPos(3, 1, 4);
        long base = DeathHeadSkins.mix(SEED, 7, pos);
        assertNotEquals(base, DeathHeadSkins.mix(SEED + 1, 7, pos));
        assertNotEquals(base, DeathHeadSkins.mix(SEED, 8, pos));
        assertNotEquals(base, DeathHeadSkins.mix(SEED, 7, new BlockPos(4, 1, 4)));
        assertNotEquals(base, DeathHeadSkins.mix(SEED, 7, new BlockPos(3, 2, 4)));
        assertNotEquals(base, DeathHeadSkins.mix(SEED, 7, new BlockPos(3, 1, 5)));
    }

    @Test
    @DisplayName("neighbouring cells spread across a small pool instead of collapsing onto one skin")
    void mix_spreadsNeighbouringCells() {
        // Six heads in one carriage, drawing from a four-skin pool: the index math is
        // floorMod(state, size), so a weak mix would show as every cell landing on one skin.
        Set<Integer> picked = new HashSet<>();
        for (int x = 0; x < 6; x++) {
            picked.add((int) Math.floorMod(DeathHeadSkins.mix(SEED, 2, new BlockPos(x, 1, 0)), 4));
        }
        assertTrue(picked.size() >= 3, "expected varied skins across cells, got " + picked);
    }

    @Test
    @DisplayName("the pool index is always in range, including for negative mix states")
    void mix_indexAlwaysInRange() {
        for (int x = -8; x < 8; x++) {
            for (int z = -8; z < 8; z++) {
                int idx = (int) Math.floorMod(DeathHeadSkins.mix(SEED, 5, new BlockPos(x, 1, z)), 3);
                assertTrue(idx >= 0 && idx < 3, "index out of range: " + idx);
            }
        }
    }
}
