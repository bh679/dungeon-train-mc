package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional minimum stack on a {@link ContainerContentsEntry} ("golden apple, min 2, max 5"):
 * 1 by default on every existing constructor and on disk, clamped to {@code [1, count]}, carried
 * through every copy, rolled in {@code [min, max]}, and written only when it was raised so
 * existing pool files re-save unchanged.
 */
final class ContainerContentsEntryMinCountTest {

    private static final ResourceLocation APPLE = ResourceLocation.fromNamespaceAndPath("minecraft", "golden_apple");
    private static final ResourceLocation VAULT = ResourceLocation.withDefaultNamespace("vault");

    @Test
    void legacyConstructors_rollFromOne() {
        assertEquals(1, new ContainerContentsEntry(APPLE, 5, 1).minCount());
        assertEquals(1, new ContainerContentsEntry(APPLE, 5, 1, true, 100, true, 25).minCount());
        assertFalse(new ContainerContentsEntry(APPLE, 5, 1).hasMinCount());
    }

    @Test
    void minCount_clampsIntoOneToCount() {
        ContainerContentsEntry e = new ContainerContentsEntry(APPLE, 5, 1);
        assertEquals(2, e.withMinCount(2).minCount());
        assertEquals(5, e.withMinCount(9).minCount());
        assertEquals(1, e.withMinCount(0).minCount());
        assertEquals(1, e.withMinCount(-3).minCount());
    }

    @Test
    void loweringTheMax_dragsTheMinDown() {
        ContainerContentsEntry e = new ContainerContentsEntry(APPLE, 5, 1).withMinCount(4);
        ContainerContentsEntry lowered = e.withCount(3);
        assertEquals(3, lowered.count());
        assertEquals(3, lowered.minCount());
        // Raising it back leaves the min where it was dragged to.
        assertEquals(3, lowered.withCount(10).minCount());
    }

    @Test
    void withCountRange_setsBothAtOnce() {
        ContainerContentsEntry e = new ContainerContentsEntry(APPLE, 1, 1).withCountRange(2, 5);
        assertEquals(2, e.minCount());
        assertEquals(5, e.count());
        // Max is applied first, so a range above the old max still keeps its min.
        ContainerContentsEntry up = new ContainerContentsEntry(APPLE, 3, 1).withCountRange(10, 20);
        assertEquals(10, up.minCount());
        assertEquals(20, up.count());
        ContainerContentsEntry bad = e.withCountRange(7, 5);
        assertEquals(5, bad.minCount());
    }

    @Test
    void otherCopies_carryMinThrough() {
        ContainerContentsEntry e = new ContainerContentsEntry(APPLE, 5, 1).withMinCount(2);
        assertEquals(2, e.withWeight(9).minCount());
        assertEquals(2, e.withRandomDurability(false).minCount());
        assertEquals(2, e.withDurabilityChance(5).minCount());
        assertEquals(2, e.withRandomEnchantment(false).minCount());
        assertEquals(2, e.withEnchantmentChance(5).minCount());
        assertEquals(2, e.cycleSlotOverride().minCount());
        assertEquals(2, e.withScaleWithDistance(false).minCount());
        assertEquals(2, e.cyclePotionForm().minCount());
    }

    /** The roll every pool used before entries had a minimum — {@code [1, max]} with the same salt. */
    private static int legacyRoll(int max, BlockPos p, long seed, int carriage, int slot) {
        if (max <= 1) return 1;
        long state = seed
            ^ ((long) p.getX() * 0x9E3779B97F4A7C15L)
            ^ ((long) p.getY() * 0xBF58476D1CE4E5B9L)
            ^ ((long) p.getZ() * 0x94D049BB133111EBL)
            ^ ((long) carriage * 0xCAFEBABE12345678L)
            ^ ((long) slot * 0xC6BC279692B5C323L)
            ^ 0xA5A5A5A5DEADBEEFL;
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        return 1 + (int) ((state & 0x7FFFFFFFFFFFFFFFL) % max);
    }

    @Test
    void minOfOne_rollsExactlyAsBefore() {
        for (int i = 0; i < 400; i++) {
            BlockPos p = new BlockPos(i % 7, i % 5, i % 11);
            long seed = 0x1234_5678L * (i + 1);
            int max = 1 + i % 64;
            assertEquals(legacyRoll(max, p, seed, i, i % 27),
                ContainerContentsRoller.rollItemCount(1, max, p, seed, i, i % 27),
                "roll " + i);
        }
    }

    @Test
    void raisedMin_staysInRangeAndReachesBothEnds() {
        boolean sawMin = false;
        boolean sawMax = false;
        for (int i = 0; i < 500; i++) {
            int n = ContainerContentsRoller.rollItemCount(2, 5, new BlockPos(i, 0, i * 3), 99L + i, i, i % 9);
            assertTrue(n >= 2 && n <= 5, "rolled " + n);
            sawMin |= n == 2;
            sawMax |= n == 5;
        }
        assertTrue(sawMin && sawMax);
        assertEquals(4, ContainerContentsRoller.rollItemCount(4, 4, BlockPos.ZERO, 7L, 0, 0));
    }

    @Test
    void prefabText_writesMinOnlyWhenRaised() {
        ContainerContentsPool plain = new ContainerContentsPool(List.of(new ContainerContentsEntry(APPLE, 5, 1)));
        String plainText = LootPrefabStore.poolText(plain, VAULT);
        assertFalse(plainText.contains("minCount"));
        assertEquals(1, LootPrefabStore.poolFromText(plainText).orElseThrow().entries().get(0).minCount());

        ContainerContentsPool ranged = new ContainerContentsPool(
            List.of(new ContainerContentsEntry(APPLE, 5, 1).withMinCount(2)));
        ContainerContentsEntry back = LootPrefabStore.poolFromText(LootPrefabStore.poolText(ranged, VAULT))
            .orElseThrow().entries().get(0);
        assertEquals(2, back.minCount());
        assertEquals(5, back.count());
    }

    @Test
    void storeText_writesMinOnlyWhenRaised() {
        String legacy = "{ \"schemaVersion\": 1, \"pools\": { \"1,2,3\": { \"entries\": ["
            + " { \"id\": \"minecraft:golden_apple\", \"count\": 5, \"weight\": 1 } ] } } }";
        ContainerContentsStore store = ContainerContentsStore.fromJsonText("test", legacy);
        ContainerContentsEntry e = store.poolAt(new BlockPos(1, 2, 3)).entries().get(0);
        assertEquals(1, e.minCount());
        assertFalse(store.asJsonText().contains("minCount"));

        String withMin = legacy.replace("\"count\": 5,", "\"count\": 5, \"minCount\": 2,");
        ContainerContentsStore ranged = ContainerContentsStore.fromJsonText("test", withMin);
        assertEquals(2, ranged.poolAt(new BlockPos(1, 2, 3)).entries().get(0).minCount());
        assertTrue(ranged.asJsonText().contains("\"minCount\": 2"));

        String tooHigh = legacy.replace("\"count\": 5,", "\"count\": 5, \"minCount\": 9,");
        assertEquals(5, ContainerContentsStore.fromJsonText("test", tooHigh)
            .poolAt(new BlockPos(1, 2, 3)).entries().get(0).minCount());
    }
}
