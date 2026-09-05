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

    /** The tag the shipped {@code chunk_dimension} parent variant carries in the portal room weights. */
    private static final String SHIPPED_TAG = "chunk_dimension/exact/off/off/off/cycle/sealed/0/9";

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
    @DisplayName("The shipped variant's tag stands its fallback door on the sampled surface row")
    void shippedTag_agreesWithTheSurfaceRow() {
        PortalRoomSettings settings = PortalRoomSettings.parse(SHIPPED_TAG);

        assertSame(PortalRoomMode.CHUNK_DIMENSION, settings.mode());
        assertEquals(PortalChunkTerrain.SURFACE_ROW, settings.doorHeightOffset().value(),
            "the authored offset is the fallback for a room stamped before its sample lands, and "
                + "belongs on the row the column is cut around");
        assertSame(PortalRoomContents.DEFAULT, settings.contents(),
            "a chunk of terrain is not furnished from the contents pool");
        assertSame(PortalRoomSky.CYCLE, settings.sky(),
            "the Overworld variant is lit by the world clock — the sky is authored per variant now");

        // Unclamped in the shipped box, which is what the second chunk of height bought: a door may
        // sit at most (height - minHeight) above a room's floor, and at 32 that is 25 rather than the
        // 9 a 16-tall box allowed — so the fit is free to put a mouth wherever its ground actually is
        // instead of pinning it against the ceiling.
        assertEquals(25, PortalRoomLayout.maxDoorHeightOffset(DEFAULT_DIMS, PortalChunkTerrain.HEIGHT));
        assertEquals(PortalChunkTerrain.SURFACE_ROW, PortalRoomLayout.clampDoorHeightOffset(
            DEFAULT_DIMS, PortalChunkTerrain.HEIGHT, settings.doorHeightOffset().value()));
    }

    @Test
    @DisplayName("The shipped box is one chunk of ground under one of sky, and clears every size floor")
    void shippedBox_clearsTheFloors() {
        assertEquals(2 * PortalChunkTerrain.SIZE, PortalChunkTerrain.HEIGHT,
            "the room is two chunk sections tall — ground below, sky above");
        assertTrue(PortalChunkTerrain.SIZE >= PortalRoomLayout.MIN_LENGTH);
        assertTrue(PortalChunkTerrain.HEIGHT >= PortalRoomLayout.minHeight(DEFAULT_DIMS));
        assertTrue(PortalChunkTerrain.SIZE >= PortalRoomLayout.minWidth(DEFAULT_DIMS));
        assertTrue(PortalChunkTerrain.SIZE <= PortalRoomLayout.MAX_LENGTH
            && PortalChunkTerrain.SIZE <= PortalRoomLayout.MAX_WIDTH
            && PortalChunkTerrain.HEIGHT <= PortalRoomLayout.MAX_HEIGHT);
    }

    @Test
    @DisplayName("Which dimension a room samples is its variant's name, not a roll")
    void dimension_comesFromTheVariantName() {
        assertSame(PortalChunkTerrain.Source.OVERWORLD,
            PortalChunkTerrain.Source.of("chunk_dimension"));
        assertSame(PortalChunkTerrain.Source.NETHER,
            PortalChunkTerrain.Source.of("chunk_dimension_nether"));
        assertSame(PortalChunkTerrain.Source.END,
            PortalChunkTerrain.Source.of("chunk_dimension_end"));

        // Total: a hand-edited sidecar naming something this build does not ship stamps a field
        // rather than failing the pair's stamp.
        assertSame(PortalChunkTerrain.Source.OVERWORLD, PortalChunkTerrain.Source.of(null));
        assertSame(PortalChunkTerrain.Source.OVERWORLD, PortalChunkTerrain.Source.of("labrynth"));
        assertSame(PortalChunkTerrain.Source.NETHER,
            PortalChunkTerrain.Source.of("  Chunk_Dimension_Nether "));
    }

    @Test
    @DisplayName("The three shipped tags light each room as the dimension it is a slice of")
    void sky_isAuthoredPerVariant() {
        assertSame(PortalRoomSky.CYCLE, PortalRoomSettings.parse(SHIPPED_TAG).sky());
        assertSame(PortalRoomSky.NETHER, PortalRoomSettings.parse(
            "chunk_dimension/exact/off/off/off/nether/sealed/0/9").sky());
        assertSame(PortalRoomSky.END, PortalRoomSettings.parse(
            "chunk_dimension/exact/off/off/off/end/sealed/0/9").sky());
    }

    @Test
    @DisplayName("Each doorway stands on the ground under it, and the two ends may differ")
    void doors_standOnTheGround() {
        // A column whose ground rises along the walk: 3 blocks deep at the entry end, 7 at the exit.
        int width = PortalChunkTerrain.SIZE;
        int height = PortalChunkTerrain.HEIGHT;
        net.minecraft.world.level.block.state.BlockState stone =
            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState air =
            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState[] states =
            new net.minecraft.world.level.block.state.BlockState[width * width * height];
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < width; z++) {
                for (int x = 0; x < width; x++) {
                    int ground = x < width / 2 ? 3 : 7;
                    states[(y * width + z) * width + x] = y < ground ? stone : air;
                }
            }
        }
        PortalChunkSlice slice =
            new PortalChunkSlice(PortalChunkTerrain.Source.OVERWORLD, width, height, states,
                java.util.Map.of());

        PortalRoomSettings fitted = PortalChunkDoors.fit(
            PortalRoomSettings.parse(SHIPPED_TAG), slice, DEFAULT_DIMS,
            PortalCarriageBuilder.layoutFor(DEFAULT_DIMS, PortalCorridorKind.DEFAULT),
            new net.minecraft.core.Vec3i(width, height, width));

        // The GROUND BLOCK's row, not the air above it: a door offset places the corridor's floor,
        // so ground filling rows 0..2 puts the doorway's floor on row 2 and a player's feet on 3.
        assertEquals(2, fitted.doorHeightOffset().value(), "the entry door stands on the low end");
        assertEquals(6, fitted.exitDoorHeightOffset().value(), "the exit door stands on the high end");
    }

    @Test
    @DisplayName("Ground higher than the room can spend clamps the door rather than moving the box")
    void doors_clampToWhatTheBoxAllows() {
        int width = PortalChunkTerrain.SIZE;
        int height = PortalChunkTerrain.HEIGHT;
        net.minecraft.world.level.block.state.BlockState stone =
            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState[] states =
            new net.minecraft.world.level.block.state.BlockState[width * width * height];
        java.util.Arrays.fill(states, stone);   // solid to the ceiling, everywhere
        PortalChunkSlice slice =
            new PortalChunkSlice(PortalChunkTerrain.Source.OVERWORLD, width, height, states,
                java.util.Map.of());

        PortalRoomSettings fitted = PortalChunkDoors.fit(
            PortalRoomSettings.parse(SHIPPED_TAG), slice, DEFAULT_DIMS,
            PortalCarriageBuilder.layoutFor(DEFAULT_DIMS, PortalCorridorKind.DEFAULT),
            new net.minecraft.core.Vec3i(width, height, width));

        int max = PortalRoomLayout.maxDoorHeightOffset(DEFAULT_DIMS, height);
        assertEquals(max, fitted.doorHeightOffset().value());
        assertEquals(max, fitted.exitDoorHeightOffset().value());
    }

    @Test
    @DisplayName("A slice carries a sampled block entity's NBT, which is what carries chest loot")
    void slice_carriesBlockEntities() {
        int width = 4;
        int height = 8;
        net.minecraft.world.level.block.state.BlockState[] states =
            new net.minecraft.world.level.block.state.BlockState[width * width * height];
        net.minecraft.nbt.CompoundTag chest = new net.minecraft.nbt.CompoundTag();
        chest.putString("LootTable", "minecraft:chests/village/village_weaponsmith");
        // Keyed exactly as the states are, so a cell's block and its block entity cannot drift apart.
        int index = (2 * width + 1) * width + 3;
        PortalChunkSlice slice = new PortalChunkSlice(PortalChunkTerrain.Source.OVERWORLD, width,
            height, states, java.util.Map.of(index, chest));

        assertEquals("minecraft:chests/village/village_weaponsmith",
            slice.blockEntityAt(3, 2, 1).getString("LootTable"));
        assertNull(slice.blockEntityAt(3, 3, 1), "no block entity on a cell that had none");
        assertNull(slice.blockEntityAt(-1, 2, 1), "outside the column");
    }

    @Test
    @DisplayName("A slice reads room-local and refuses cells outside the column")
    void slice_isRoomLocal() {
        int width = 4;
        int height = 8;
        net.minecraft.world.level.block.state.BlockState[] states =
            new net.minecraft.world.level.block.state.BlockState[width * width * height];
        PortalChunkSlice slice =
            new PortalChunkSlice(PortalChunkTerrain.Source.END, width, height, states,
                java.util.Map.of());

        assertEquals(width, slice.width());
        assertEquals(height, slice.height());
        assertSame(PortalChunkTerrain.Source.END, slice.source());
        assertNull(slice.at(-1, 0, 0));
        assertNull(slice.at(0, height, 0));
        assertNull(slice.at(0, 0, width));
        // A tall column is not a cube: the row above the footprint is still inside it.
        assertNull(slice.at(0, width + 1, 0), "empty backing array, but the index is in range");
        // In range: null only because the backing array is empty in this test, not because the cell
        // was refused — the point is that no index outside the column reaches the array at all.
        states[((height - 1) * width + (width - 1)) * width + (width - 1)] =
            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        assertNotNull(slice.at(width - 1, height - 1, width - 1));
    }
}
