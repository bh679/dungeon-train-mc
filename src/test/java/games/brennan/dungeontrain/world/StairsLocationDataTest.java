package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.world.StairsLocationData.Box;
import games.brennan.dungeontrain.world.StairsLocationData.Kind;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the stair spatial index behind the
 * {@code used_pillar_stairs} / {@code used_tunnel_stairs} advancements:
 * {@link StairsLocationData.Box#contains} geometry, {@link StairsLocationData#near}
 * X-window pruning, dedupe, and the NBT round-trip.
 *
 * <p>Bypasses the live {@code ServerLevel#getDataStorage()} path by
 * round-tripping a {@link CompoundTag} through
 * {@link StairsLocationData#load(CompoundTag)} /
 * {@link StairsLocationData#save(CompoundTag, net.minecraft.core.HolderLookup.Provider)},
 * mirroring {@code NarrativeProgressDataTest}. The {@code HolderLookup.Provider}
 * is unused for this store, so {@code null} is safe.</p>
 */
final class StairsLocationDataTest {

    // 3x8x3 pillar-stairs footprint anchored at (0,64,0).
    private static Box pillarBox() {
        return new Box(0, 64, 0, 2, 71, 2, Kind.PILLAR_STAIRS);
    }

    // --- Box.contains geometry ---

    @Test
    @DisplayName("contains: interior + the inclusive +1 block faces are inside")
    void containsInterior() {
        Box box = pillarBox();
        assertTrue(box.contains(1.5, 67.0, 1.5, 0.0), "centre is inside");
        assertTrue(box.contains(0.0, 64.0, 0.0, 0.0), "min corner is inside");
        // block maxX=2 fills up to x=3.0 (maxX+1); the far face is still inside.
        assertTrue(box.contains(3.0, 72.0, 3.0, 0.0), "far block faces (max+1) are inside");
    }

    @Test
    @DisplayName("contains: a point just beyond each axis is outside (pad 0)")
    void containsOutside() {
        Box box = pillarBox();
        assertFalse(box.contains(-0.1, 67, 1, 0.0), "below minX");
        assertFalse(box.contains(3.1, 67, 1, 0.0), "above maxX+1");
        assertFalse(box.contains(1, 63.9, 1, 0.0), "below minY");
        assertFalse(box.contains(1, 72.1, 1, 0.0), "above maxY+1");
        assertFalse(box.contains(1, 67, -0.1, 0.0), "below minZ");
        assertFalse(box.contains(1, 67, 3.1, 0.0), "above maxZ+1");
    }

    @Test
    @DisplayName("contains: pad widens the box on every side")
    void containsPad() {
        Box box = pillarBox();
        assertFalse(box.contains(-0.4, 67, 1, 0.0), "outside without pad");
        assertTrue(box.contains(-0.4, 67, 1, 0.5), "inside once padded");
    }

    // --- record / near pruning ---

    @Test
    @DisplayName("near() returns only boxes whose minX is within the window")
    void nearPrunesByX() {
        StairsLocationData data = StairsLocationData.load(new CompoundTag());
        Box close = new Box(10, 64, 0, 12, 71, 2, Kind.PILLAR_STAIRS);
        Box far = new Box(200, 64, 0, 202, 71, 2, Kind.TUNNEL_STAIRS);
        data.record(close);
        data.record(far);

        List<Box> near = data.near(10, 32);
        assertEquals(1, near.size(), "only the nearby box is returned");
        assertTrue(near.contains(close));
        assertFalse(near.contains(far));

        assertTrue(data.near(200, 32).contains(far), "the far box is found from its own X");
    }

    @Test
    @DisplayName("record() is idempotent on an exact-duplicate box")
    void recordDedups() {
        StairsLocationData data = StairsLocationData.load(new CompoundTag());
        Box box = pillarBox();
        data.record(box);
        data.record(box);
        assertEquals(1, data.near(0, 32).size(), "duplicate placement is not double-counted");
    }

    // --- NBT round-trip ---

    @Test
    @DisplayName("save → load round-trip preserves boxes and their kind")
    void roundTrip() {
        StairsLocationData original = StairsLocationData.load(new CompoundTag());
        Box pillar = new Box(10, 64, 0, 12, 71, 2, Kind.PILLAR_STAIRS);
        Box tunnel = new Box(120, 30, 5, 124, 37, 9, Kind.TUNNEL_STAIRS);
        original.record(pillar);
        original.record(tunnel);

        CompoundTag tag = original.save(new CompoundTag(), null);
        StairsLocationData reloaded = StairsLocationData.load(tag);

        assertTrue(reloaded.near(10, 4).contains(pillar), "pillar box survives the round-trip");
        List<Box> nearTunnel = reloaded.near(120, 4);
        assertTrue(nearTunnel.contains(tunnel), "tunnel box survives the round-trip");
        assertEquals(Kind.TUNNEL_STAIRS, nearTunnel.get(0).kind(), "kind is preserved");
    }
}
