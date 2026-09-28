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
 * the same carriage must not all end up wearing one face — and for {@link DeathHeadSkins#poolIndexFor},
 * the carriage → group-anchor mapping that decides which relay pool a head draws from.
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
    @DisplayName("every carriage in a group resolves to the group's anchor pool")
    void poolIndexFor_snapsToGroupAnchor() {
        // Deaths are recorded at the group anchor the boarding scan reports, so the relay has pools
        // at 0, 3, 6, … — carriage 4 must look in pool 3, not ask for an index nobody died at.
        assertEquals(0, DeathHeadSkins.poolIndexFor(0, 3));
        assertEquals(0, DeathHeadSkins.poolIndexFor(1, 3));
        assertEquals(0, DeathHeadSkins.poolIndexFor(2, 3));
        assertEquals(3, DeathHeadSkins.poolIndexFor(3, 3));
        assertEquals(3, DeathHeadSkins.poolIndexFor(4, 3));
        assertEquals(3, DeathHeadSkins.poolIndexFor(5, 3));
        assertEquals(6, DeathHeadSkins.poolIndexFor(6, 3));
        assertEquals(12, DeathHeadSkins.poolIndexFor(14, 4));
    }

    @Test
    @DisplayName("backward carriages share the pool of their forward twin")
    void poolIndexFor_isAbsolute() {
        // A death's carriage is an absolute count of carriages traversed, so a backward run's
        // death at -4 is filed under 3 — exactly where the heads at -4 should look.
        assertEquals(3, DeathHeadSkins.poolIndexFor(-4, 3));
        assertEquals(0, DeathHeadSkins.poolIndexFor(-2, 3));
        assertEquals(DeathHeadSkins.poolIndexFor(7, 3), DeathHeadSkins.poolIndexFor(-7, 3));
    }

    @Test
    @DisplayName("a group size of one is the identity, and a bad group size is treated as one")
    void poolIndexFor_degenerateGroupSizes() {
        assertEquals(5, DeathHeadSkins.poolIndexFor(5, 1));
        assertEquals(5, DeathHeadSkins.poolIndexFor(5, 0));
        assertEquals(5, DeathHeadSkins.poolIndexFor(5, -3));
    }

    @Test
    @DisplayName("the famous roll is deterministic in seed, carriage and cell")
    void isFamousCell_isDeterministic() {
        for (int x = 0; x < 40; x++) {
            BlockPos pos = new BlockPos(x, 1, x % 7);
            assertEquals(DeathHeadSkins.isFamousCell(SEED, 4, pos), DeathHeadSkins.isFamousCell(SEED, 4, pos));
        }
    }

    @Test
    @DisplayName("about one cell in twenty rolls famous")
    void isFamousCell_isAboutOneInTwenty() {
        int famous = 0, total = 0;
        for (int carriage = 0; carriage < 5; carriage++) {
            for (int x = 0; x < 40; x++) {
                for (int z = 0; z < 40; z++) {
                    total++;
                    if (DeathHeadSkins.isFamousCell(SEED, carriage, new BlockPos(x, 1, z))) famous++;
                }
            }
        }
        double rate = famous / (double) total;
        assertTrue(rate > 0.03 && rate < 0.07, "expected ~5% famous, got " + rate);
    }

    @Test
    @DisplayName("the famous roll does not line up with the death-pool roll")
    void isFamousCell_isIndependentOfPoolRoll() {
        // If the two rolls shared a salt, every cell whose pool roll is a multiple of 20 would also
        // be famous and the famous heads would all be the same "slot" of the death pool.
        boolean poolHitButNotFamous = false, famousButNotPoolHit = false;
        for (int x = 0; x < 60 && !(poolHitButNotFamous && famousButNotPoolHit); x++) {
            for (int z = 0; z < 60; z++) {
                BlockPos pos = new BlockPos(x, 1, z);
                boolean poolHit = Math.floorMod(DeathHeadSkins.mix(SEED, 3, pos), 20) == 0;
                boolean famous = DeathHeadSkins.isFamousCell(SEED, 3, pos);
                if (poolHit && !famous) poolHitButNotFamous = true;
                if (famous && !poolHit) famousButNotPoolHit = true;
            }
        }
        assertTrue(poolHitButNotFamous && famousButNotPoolHit, "famous roll is correlated with the pool roll");
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
