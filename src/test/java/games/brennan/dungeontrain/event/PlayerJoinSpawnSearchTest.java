package games.brennan.dungeontrain.event;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure helpers behind {@code PlayerJoinEvents.pickPlayerTarget}'s bounded,
 * non-loading spawn search: the chunk walk that lets {@code hasLineOfSight} refuse a ray before
 * {@code level.clip} would sync-load a chunk under it, the wall-clock budget, and the aim height
 * that keeps the line-of-sight check above the corridor slot on hilly terrain.
 *
 * <p>Context: a player log of 28 Sep 2026 showed the search holding the server thread for 61 s at
 * join (and 22 s at world create) while it force-generated ~330 chunk columns and then fell back to
 * the on-train spawn anyway, because every ray aimed into the slot hit the slot wall.</p>
 */
final class PlayerJoinSpawnSearchTest {

    private static long key(int cx, int cz) {
        return ChunkPos.asLong(cx, cz);
    }

    @Test
    @DisplayName("chunksAlongRay — a straight +X ray visits every chunk column it crosses, in order")
    void straightRayVisitsEachChunk() {
        Set<Long> chunks = PlayerJoinEvents.chunksAlongRay(0.5, 0.5, 40.5, 0.5);
        assertEquals(Set.of(key(0, 0), key(1, 0), key(2, 0)), chunks);
    }

    @Test
    @DisplayName("chunksAlongRay — a ray inside one chunk yields just that chunk")
    void sameChunkRay() {
        assertEquals(Set.of(key(3, 5)), PlayerJoinEvents.chunksAlongRay(50.2, 81.9, 62.7, 94.1));
        assertEquals(Set.of(key(3, 5)), PlayerJoinEvents.chunksAlongRay(50.2, 81.9, 50.2, 81.9), "zero-length ray");
    }

    @Test
    @DisplayName("chunksAlongRay — a diagonal through a corner includes both end chunks and a corner neighbour")
    void diagonalRayIsConservativeAtCorners() {
        Set<Long> chunks = PlayerJoinEvents.chunksAlongRay(0.5, 0.5, 31.5, 31.5);
        assertTrue(chunks.contains(key(0, 0)), "start chunk");
        assertTrue(chunks.contains(key(1, 1)), "end chunk");
        assertEquals(3, chunks.size(), "exactly one side chunk added at the shared corner");
    }

    @Test
    @DisplayName("chunksAlongRay — negative coordinates floor toward negative chunks")
    void negativeCoordinates() {
        Set<Long> chunks = PlayerJoinEvents.chunksAlongRay(-1.5, -1.5, -20.5, -1.5);
        assertEquals(Set.of(key(-1, -1), key(-2, -1)), chunks);
    }

    @Test
    @DisplayName("chunksAlongRay — a long ray never visits more chunks than its bounding box holds")
    void longRayStaysBounded() {
        Set<Long> chunks = PlayerJoinEvents.chunksAlongRay(-300.0, 40.0, 300.0, -40.0);
        // 38 chunk columns × 6 chunk rows is the box; the walk touches far fewer than that.
        assertTrue(chunks.size() <= 38 * 6, "bounded by the box");
        assertTrue(chunks.size() >= 38, "at least one chunk per column crossed");
        assertTrue(chunks.contains(key(-19, 2)) && chunks.contains(key(18, -3)), "both ends present");
    }

    @Test
    @DisplayName("spawnSearchExpired — trips exactly at the 20 ms budget")
    void budgetBoundary() {
        long start = 1_000_000_000L;
        assertFalse(PlayerJoinEvents.spawnSearchExpired(start, start), "no time spent");
        assertFalse(PlayerJoinEvents.spawnSearchExpired(start, start + PlayerJoinEvents.SPAWN_SEARCH_BUDGET_NANOS - 1), "just under");
        assertTrue(PlayerJoinEvents.spawnSearchExpired(start, start + PlayerJoinEvents.SPAWN_SEARCH_BUDGET_NANOS), "at budget");
        assertTrue(PlayerJoinEvents.spawnSearchExpired(start, start + 61_000_000_000L), "the 61 s case is long over");
    }

    @Test
    @DisplayName("aimYFor — flat terrain keeps the classic above-train aim")
    void aimOnFlatTerrain() {
        // Train at Y 78, rim at the bed (76): rim + 2 = 78 < 78 + 8.
        assertEquals(86.0, PlayerJoinEvents.aimYFor(78.0, 76));
        assertEquals(86.0, PlayerJoinEvents.aimYFor(78.0, Integer.MIN_VALUE), "no readable rim → above-train aim");
    }

    @Test
    @DisplayName("aimYFor — a surface above the slot lifts the aim above the rim")
    void aimOnHillyTerrain() {
        // The 28 Sep 2026 world: train Y 78, surface Y 94–110. Aiming at 86 hit the slot wall every time.
        assertEquals(96.0, PlayerJoinEvents.aimYFor(78.0, 94));
        assertEquals(112.0, PlayerJoinEvents.aimYFor(78.0, 110));
    }
}
