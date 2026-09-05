package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure half of the chunk dimension: the mode's own answers, the shipped variant's tag, and the
 * per-pair roll. What the sampler does with a live generator needs a world and is tested in game.
 */
class PortalChunkDimensionTest {

    private static final CarriageDims DEFAULT_DIMS = CarriageDims.DEFAULT;   // 9 × 7 × 7

    /** The tag the shipped {@code chunk_dimension} variant carries in the portal room weights. */
    private static final String SHIPPED_TAG = "chunk_dimension/exact/off/off/off/none/sealed/0/9";

    @Test
    @DisplayName("The mode seals its box and its corridors, and is the one that generates terrain")
    void mode_sealsAndGenerates() {
        PortalRoomMode mode = PortalRoomMode.CHUNK_DIMENSION;

        assertSame(mode, PortalRoomMode.parse("chunk_dimension"));
        assertTrue(mode.sealsRoomBox(), "a sampled hillside runs into the box's faces — it needs the skin");
        assertTrue(mode.sealsCorridors());
        assertTrue(mode.generatesTerrain());

        // Nothing else generates, and Bedrock Lock still seals exactly what it always did.
        for (PortalRoomMode other : PortalRoomMode.values()) {
            if (other != mode) assertTrue(!other.generatesTerrain(), other + " must not generate terrain");
        }
        assertTrue(PortalRoomMode.BEDROCK_LOCK.sealsRoomBox());
        assertTrue(PortalRoomMode.BEDROCK_LOCK.sealsCorridors());
        assertTrue(!PortalRoomMode.BEDROCKLESS.sealsRoomBox());
        assertTrue(!PortalRoomMode.ENDLESS_OPEN.sealsCorridors());

        // A generated room is one box, not a grid of them, and there is nowhere to fog or tile to.
        assertTrue(!mode.tiles());
        assertTrue(!mode.fogs());
        assertTrue(!mode.copiesApply());
        assertTrue(!mode.exitsApply());
    }

    @Test
    @DisplayName("The shipped variant's tag puts the door on the row the sampler slices the surface onto")
    void shippedTag_agreesWithTheSurfaceRow() {
        PortalRoomSettings settings = PortalRoomSettings.parse(SHIPPED_TAG);

        assertSame(PortalRoomMode.CHUNK_DIMENSION, settings.mode());
        assertEquals(PortalChunkTerrain.SURFACE_ROW, settings.doorHeightOffset().value(),
            "the door must stand on the row the slice puts the surface on");
        assertSame(PortalRoomContents.DEFAULT, settings.contents(),
            "a chunk of terrain is not furnished from the contents pool");

        // And the box can actually spend that offset: a door may sit at most (height - minHeight)
        // above a room's floor, which in the shipped 16-tall box is exactly the surface row. If a
        // future geometry change lowers that ceiling, the room would clamp its door DOWN and the
        // surface would land above the doorway rather than on it.
        assertEquals(PortalChunkTerrain.SURFACE_ROW,
            PortalRoomLayout.maxDoorHeightOffset(DEFAULT_DIMS, PortalChunkTerrain.SIZE));
        assertEquals(PortalChunkTerrain.SURFACE_ROW, PortalRoomLayout.clampDoorHeightOffset(
            DEFAULT_DIMS, PortalChunkTerrain.SIZE, settings.doorHeightOffset().value()));
    }

    @Test
    @DisplayName("The shipped box clears every size floor a room is validated against")
    void shippedBox_clearsTheFloors() {
        int size = PortalChunkTerrain.SIZE;
        assertTrue(size >= PortalRoomLayout.MIN_LENGTH);
        assertTrue(size >= PortalRoomLayout.minHeight(DEFAULT_DIMS));
        assertTrue(size >= PortalRoomLayout.minWidth(DEFAULT_DIMS));
        assertTrue(size <= PortalRoomLayout.MAX_LENGTH && size <= PortalRoomLayout.MAX_WIDTH
            && size <= PortalRoomLayout.MAX_HEIGHT);
    }

    @Test
    @DisplayName("A pair's dimension is rolled once and stays rolled, and all three come up")
    void roll_isStableAndSpreads() {
        long seed = 0x5EEDL;

        // Pure: the same pair asks a hundred times and gets the same answer, which is what lets a
        // room be re-stamped from a cached cube every time the train drifts.
        PortalChunkTerrain.Source first = PortalChunkTerrain.rollFor(seed, 7);
        for (int i = 0; i < 100; i++) assertSame(first, PortalChunkTerrain.rollFor(seed, 7));

        Map<PortalChunkTerrain.Source, Integer> counts = new EnumMap<>(PortalChunkTerrain.Source.class);
        for (int pairKey = 0; pairKey < 600; pairKey++) {
            counts.merge(PortalChunkTerrain.rollFor(seed, pairKey), 1, Integer::sum);
        }
        for (PortalChunkTerrain.Source source : PortalChunkTerrain.Source.values()) {
            int seen = counts.getOrDefault(source, 0);
            assertTrue(seen > 100, source + " came up only " + seen + " times in 600 pairs");
        }
    }

    @Test
    @DisplayName("Each dimension carries the sky a room sampled from it is lit under")
    void sky_followsTheRolledDimension() {
        assertSame(PortalRoomSky.CYCLE, PortalChunkTerrain.Source.OVERWORLD.sky());
        assertSame(PortalRoomSky.NETHER, PortalChunkTerrain.Source.NETHER.sky());
        assertSame(PortalRoomSky.END, PortalChunkTerrain.Source.END.sky());
        assertSame(PortalChunkTerrain.rollFor(1L, 3).sky(), PortalChunkTerrain.skyFor(1L, 3));
    }

    @Test
    @DisplayName("A slice reads room-local and refuses cells outside the cube")
    void slice_isRoomLocal() {
        int size = 4;
        net.minecraft.world.level.block.state.BlockState[] states =
            new net.minecraft.world.level.block.state.BlockState[size * size * size];
        PortalChunkSlice slice = new PortalChunkSlice(PortalChunkTerrain.Source.END, size, states);

        assertEquals(size, slice.size());
        assertSame(PortalChunkTerrain.Source.END, slice.source());
        assertNull(slice.at(-1, 0, 0));
        assertNull(slice.at(0, size, 0));
        assertNull(slice.at(0, 0, size));
        // In range: null only because the backing array is empty in this test, not because the cell
        // was refused — the point is that no index outside the cube reaches the array at all.
        assertNull(slice.at(size - 1, size - 1, size - 1));
        states[((size - 1) * size + (size - 1)) * size + (size - 1)] =
            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        assertNotNull(slice.at(size - 1, size - 1, size - 1));
    }
}
