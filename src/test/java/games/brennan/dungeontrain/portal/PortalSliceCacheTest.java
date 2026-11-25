package games.brennan.dungeontrain.portal;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The byte-budgeted LRU behind dimensional rooms' sampled terrain, and what it pins. */
class PortalSliceCacheTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A cache of 100 bytes holding keys 1..n at 30 bytes each, all unpinned. */
    private static PortalSliceCache<String> filled(int n) {
        PortalSliceCache<String> cache = new PortalSliceCache<>(100);
        for (int key = 1; key <= n; key++) cache.put(key, "v" + key, 30);
        cache.retainPins(Set.of());
        return cache;
    }

    @Test
    @DisplayName("Over budget, the least recently put goes first, one at a time")
    void evictsOldestFirst() {
        PortalSliceCache<String> cache = filled(3);
        assertEquals(List.of(1), cache.put(4, "v4", 30));
        assertEquals(3, cache.size());
        assertEquals(90, cache.bytes());
        assertNull(cache.peek(1));
    }

    @Test
    @DisplayName("Only as many are dropped as it takes to fit")
    void evictsOnlyWhatIsNeeded() {
        PortalSliceCache<String> cache = filled(3);
        assertEquals(List.of(1, 2), cache.put(4, "big", 60));
        assertEquals(90, cache.bytes());
        assertEquals("v3", cache.peek(3));
    }

    @Test
    @DisplayName("A read counts as a use; a peek does not")
    void getRefreshesPeekDoesNot() {
        PortalSliceCache<String> cache = filled(3);
        cache.peek(1);
        cache.get(2);
        cache.retainPins(Set.of());
        cache.retainPins(Set.of());
        // 1 was only peeked, so it is still the oldest; 2 was read, so 3 is next after 1.
        assertEquals(List.of(1), cache.put(4, "v4", 30));
        assertEquals(List.of(3), cache.put(5, "v5", 30));
    }

    @Test
    @DisplayName("A pinned cube is never dropped, even when the pins alone are over budget")
    void pinnedNeverEvicted() {
        PortalSliceCache<String> cache = new PortalSliceCache<>(50);
        cache.get(1);
        cache.get(2);
        cache.put(1, "a", 30);
        cache.put(2, "b", 30);
        cache.retainPins(Set.of(1, 2));
        assertEquals(List.of(), cache.put(3, "c", 30));
        assertEquals(3, cache.size());
        assertTrue(cache.overBudget());
        // Once one is let go it is the one dropped.
        cache.retainPins(Set.of(2));
        assertEquals(List.of(1, 3), cache.put(4, "d", 1));
        assertEquals("b", cache.peek(2));
    }

    @Test
    @DisplayName("The key just put is never its own eviction")
    void newKeyNeverSelfEvicted() {
        PortalSliceCache<String> cache = new PortalSliceCache<>(10);
        assertEquals(List.of(), cache.put(1, "huge", 500));
        assertEquals("huge", cache.peek(1));
    }

    @Test
    @DisplayName("Asking for a cube that is not there yet pins it, so the one the sampler publishes is kept")
    void missPinsTheComingCube() {
        PortalSliceCache<String> cache = filled(3);
        assertNull(cache.get(9));
        assertTrue(cache.isPinned(9));
        cache.put(9, "sampled", 30);
        // Pressure from other publishes drops unpinned old cubes, never 9.
        cache.put(10, "x", 30);
        cache.put(11, "y", 30);
        cache.put(12, "z", 30);
        assertEquals("sampled", cache.peek(9));
    }

    @Test
    @DisplayName("A pin asked since the last retain survives one more; then only the kept ones stay")
    void retainKeepsRecentPinsOneRound() {
        PortalSliceCache<String> cache = filled(2);
        cache.get(1);
        cache.retainPins(Set.of(2));
        assertTrue(cache.isPinned(1));
        assertTrue(cache.isPinned(2));
        cache.retainPins(Set.of(2));
        assertFalse(cache.isPinned(1));
        assertTrue(cache.isPinned(2));
    }

    @Test
    @DisplayName("An evicted cube misses, and its resample is held again with its bytes")
    void resampleAfterEviction() {
        PortalSliceCache<String> cache = filled(3);
        cache.put(4, "v4", 30);
        assertNull(cache.get(1));
        cache.put(1, "resampled", 30);
        assertEquals("resampled", cache.peek(1));
        assertEquals(90, cache.bytes());
    }

    @Test
    @DisplayName("Replacing ground with its decorated cube counts the bytes once")
    void replaceAdjustsTotal() {
        PortalSliceCache<String> cache = new PortalSliceCache<>(1000);
        cache.put(1, "ground", 300);
        cache.put(1, "decorated", 320);
        assertEquals(1, cache.size());
        assertEquals(320, cache.bytes());
    }

    @Test
    @DisplayName("remove takes only the expected value; clear drops entries, pins and bytes")
    void removeAndClear() {
        PortalSliceCache<String> cache = new PortalSliceCache<>(1000);
        String held = "held";
        cache.put(1, held, 40);
        assertFalse(cache.remove(1, "other"));
        assertTrue(cache.remove(1, held));
        assertEquals(0, cache.bytes());
        cache.put(2, "x", 40);
        cache.get(2);
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.bytes());
        assertFalse(cache.isPinned(2));
    }

    @Test
    @DisplayName("A budget must be positive")
    void rejectsNonPositiveBudget() {
        assertThrows(IllegalArgumentException.class, () -> new PortalSliceCache<String>(0));
    }

    @Test
    @DisplayName("Budget override: property, then env, then the 64 MiB default; junk ignored")
    void budgetOverride() {
        assertEquals(400, PortalChunkTerrain.parseBudgetKb("400", "9"));
        assertEquals(9, PortalChunkTerrain.parseBudgetKb(null, "9"));
        assertEquals(9, PortalChunkTerrain.parseBudgetKb("nope", "9"));
        assertEquals(PortalChunkTerrain.DEFAULT_BUDGET_KB, PortalChunkTerrain.parseBudgetKb("-1", " "));
        assertEquals(64L * 1024, PortalChunkTerrain.DEFAULT_BUDGET_KB);
    }

    @SuppressWarnings("unchecked")
    private static PortalChunkSlice slice(int width, int height) {
        int q = PortalChunkSlice.quarts(width);
        Holder<Biome>[] biomes = new Holder[q * q * PortalChunkSlice.quarts(height)];
        return new PortalChunkSlice(PortalChunkTerrain.Source.OVERWORLD, width, height,
            new BlockState[width * width * height], Map.of(), List.of(), biomes);
    }

    @Test
    @DisplayName("A one-chunk cube is about 34 KB, a 3x3 one about 300 KB")
    void sliceSizes() {
        long one = slice(16, 32).approxBytes();
        long grid = slice(48, 32).approxBytes();
        assertTrue(one > 32 * 1024 && one < 36 * 1024, "one-chunk " + one);
        assertTrue(grid > 290 * 1024 && grid < 300 * 1024, "3x3 " + grid);
    }

    @Test
    @DisplayName("Block entity and occupant NBT count toward a cube's size")
    void nbtCounts() {
        CompoundTag chest = new CompoundTag();
        chest.putString("LootTable", "minecraft:chests/village/village_plains_house");
        PortalChunkSlice bare = new PortalChunkSlice(PortalChunkTerrain.Source.OVERWORLD, 16, 32,
            new BlockState[16 * 16 * 32], Map.of(), List.of());
        PortalChunkSlice withNbt = new PortalChunkSlice(PortalChunkTerrain.Source.OVERWORLD, 16, 32,
            new BlockState[16 * 16 * 32], Map.of(5, chest),
            List.of(new PortalChunkSlice.Occupant(chest.copy(), 1, 2, 3)));
        assertEquals(bare.approxBytes() + 2L * (PortalChunkSlice.ENTRY_OVERHEAD_BYTES + chest.sizeInBytes()),
            withNbt.approxBytes());
    }

    @Test
    @DisplayName("The same value object comes back from get")
    void getReturnsHeldValue() {
        PortalSliceCache<String> cache = new PortalSliceCache<>(100);
        String v = new String("v");
        cache.put(1, v, 1);
        assertSame(v, cache.get(1));
    }
}
