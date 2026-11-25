package games.brennan.dungeontrain.portal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.SharedConstants;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of the 3x3-chunk dimensional room: its size, its site test, its cut and its doors. */
class PortalChunkAreaTest {

    private static final int CHUNK = PortalChunkTerrain.SIZE;
    private static final int HEIGHT = PortalChunkTerrain.HEIGHT;
    private static final int WIDTH = 3 * CHUNK;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("A 48 x 48 room samples 3x3 chunks; a chunk room, an even grid and odd footprints sample one")
    void spanFromSize() {
        assertEquals(3, PortalChunkArea.span(new Vec3i(48, 32, 48)));
        assertEquals(5, PortalChunkArea.span(new Vec3i(80, 32, 80)));
        assertEquals(1, PortalChunkArea.span(new Vec3i(16, 32, 16)));
        assertEquals(1, PortalChunkArea.span(new Vec3i(64, 22, 64)), "the courtyard has no middle chunk");
        assertEquals(1, PortalChunkArea.span(new Vec3i(48, 32, 32)), "not square");
        assertEquals(1, PortalChunkArea.span(new Vec3i(47, 32, 47)), "not chunk-aligned");
        assertEquals(1, PortalChunkArea.span(null));
    }

    @Test
    @DisplayName("A site is taken only when all three chunk columns pass the stretch test and no chunk is voided")
    void siteNeedsEveryColumn() {
        ChunkPos centre = new ChunkPos(100, -40);
        assertTrue(PortalChunkArea.siteAccepted(centre, 1, pos -> true, pos -> false));
        // The stretch ends one chunk east of the middle: the room would straddle it.
        assertFalse(PortalChunkArea.siteAccepted(centre, 1, pos -> pos.x <= 100, pos -> false));
        // One corner chunk voided by a band.
        assertFalse(PortalChunkArea.siteAccepted(centre, 1, pos -> true,
            pos -> pos.x == 99 && pos.z == -39));

        Set<Long> asked = new HashSet<>();
        PortalChunkArea.siteAccepted(centre, 1, pos -> true, pos -> asked.add(pos.toLong()) && false);
        assertEquals(9, asked.size(), "every chunk of the grid is checked for a void");
    }

    @Test
    @DisplayName("World cells map to room-local cells across chunk seams, and outside the cut maps to nothing")
    void cutAcrossSeams() {
        int minX = -16 * 5;
        int minZ = 16 * 7;
        int lo = 60;
        // The first cell of the middle chunk, and the last cell of the far corner chunk.
        assertEquals(PortalChunkArea.index(WIDTH, 16, 9, 16),
            PortalChunkArea.windowIndex(WIDTH, minX, lo, minZ, minX + 16, lo + 9, minZ + 16));
        assertEquals(PortalChunkArea.index(WIDTH, 47, 31, 47),
            PortalChunkArea.windowIndex(WIDTH, minX, lo, minZ, minX + 47, lo + 31, minZ + 47));
        assertEquals(WIDTH * WIDTH * HEIGHT - 1, PortalChunkArea.index(WIDTH, 47, 31, 47));
        assertNull(PortalChunkArea.windowIndex(WIDTH, minX, lo, minZ, minX + 48, lo, minZ));
        assertNull(PortalChunkArea.windowIndex(WIDTH, minX, lo, minZ, minX, lo + 32, minZ));
        assertNull(PortalChunkArea.windowIndex(WIDTH, minX, lo, minZ, minX - 1, lo, minZ));

        // Every cell of the cut has its own index: nothing written twice, nothing skipped.
        Set<Integer> seen = new HashSet<>();
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < WIDTH; z++) {
                for (int x = 0; x < WIDTH; x++) {
                    assertTrue(seen.add(PortalChunkArea.index(WIDTH, x, y, z)));
                }
            }
        }
        assertEquals(WIDTH * WIDTH * HEIGHT, seen.size());
    }

    @Test
    @DisplayName("The anchor is the median ground, so one cliff does not move the whole room")
    void medianAnchor() {
        assertEquals(70, PortalChunkArea.median(List.of(70, 71, 69, 70, 120, 70, 68, 72, 70, 70, 3)));
        assertEquals(5, PortalChunkArea.median(List.of(9, 5)));
    }

    @Test
    @DisplayName("A 48-wide sample stands each door on the ground at its own end, in the middle of the face")
    void doorsAtEachEndMiddle() {
        // Ground at row 4 for the first half along X and row 12 for the second: the two ends differ.
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState[] states = new BlockState[WIDTH * WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < WIDTH; z++) {
                for (int x = 0; x < WIDTH; x++) {
                    int top = x < WIDTH / 2 ? 4 : 12;
                    states[PortalChunkArea.index(WIDTH, x, y, z)] = y <= top ? stone : air;
                }
            }
        }
        PortalChunkSlice slice = new PortalChunkSlice(PortalChunkTerrain.Source.OVERWORLD, WIDTH, HEIGHT,
            states, Map.of(), List.of());
        CarriageDims dims = CarriageDims.DEFAULT;
        PortalRoomSettings fitted = PortalChunkDoors.fit(
            PortalRoomSettings.parse("chunk_dimension/exact/off/off/off/day/sealed/0/9/0/9/dungeontrain:skybox_surface"),
            slice, dims, PortalCarriageBuilder.layoutFor(dims, PortalCorridorKind.DEFAULT),
            new Vec3i(WIDTH, HEIGHT, WIDTH));
        assertEquals(4, fitted.doorHeightOffset().value(), "entry stands on the west half's ground");
        assertEquals(12, fitted.exitDoorHeightOffset().value(), "exit stands on the east half's ground");
        assertEquals(0, fitted.doorOffset().value(), "door line stays centred on the room");
    }

    @Test
    @DisplayName("Every look has a 3x3 room in the chunk dimension group, at the agreed weights and levels")
    void groupCarriesEveryLook() throws Exception {
        JsonObject group;
        try (InputStream in = PortalChunkAreaTest.class.getResourceAsStream(
                "/data/dungeontrain/portals/room/chunk_dimension.group.json")) {
            assertNotNull(in);
            group = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Map<String, int[]> found = new HashMap<>();
        for (JsonElement e : group.getAsJsonArray("variants")) {
            JsonObject v = e.getAsJsonObject();
            String id = v.get("id").getAsString();
            if (!id.startsWith("chunk_3x3")) continue;
            found.put(id, new int[] {v.get("weight").getAsInt(), v.has("minLevel") ? v.get("minLevel").getAsInt() : 0});
        }
        Map<String, int[]> expected = Map.of(
            "chunk_3x3", new int[] {40, 5},
            "chunk_3x3_wwoo", new int[] {32, 5},
            "chunk_3x3_bop", new int[] {32, 15},
            "chunk_3x3_nether", new int[] {6, 5},
            "chunk_3x3_nether_better", new int[] {23, 11},
            "chunk_3x3_end", new int[] {2, 5},
            "chunk_3x3_end_better", new int[] {6, 13},
            "chunk_3x3_underwater", new int[] {16, 5});
        assertEquals(expected.keySet(), found.keySet());
        // A name the registry refuses is a room that silently never loads (a 33-character name did).
        for (String id : found.keySet()) {
            assertTrue(games.brennan.dungeontrain.track.variant.TrackVariantRegistry.NAME_PATTERN.matcher(id).matches(),
                id + " is a valid room name");
        }
        expected.forEach((id, want) -> {
            assertEquals(want[0], found.get(id)[0], id + " weight");
            assertEquals(want[1], found.get(id)[1], id + " minLevel");
        });
        // The look still comes from the name's suffix.
        assertEquals(PortalChunkTerrain.Source.NETHER_BETTER, PortalChunkTerrain.Source.of("chunk_3x3_nether_better"));
        assertEquals(PortalChunkTerrain.Source.OVERWORLD, PortalChunkTerrain.Source.of("chunk_3x3"));
    }

    @Test
    @DisplayName("Only a grid room's buried doorway dig is capped; one-chunk and authored rooms keep half their length")
    void doorwayDigCappedForGridRoomsOnly() {
        assertEquals(8, PortalChunkDimension.doorwayDigDepth(new Vec3i(48, 32, 48)));
        assertEquals(8, PortalChunkDimension.doorwayDigDepth(new Vec3i(16, 32, 16)));
        assertEquals(31, PortalChunkDimension.doorwayDigDepth(new Vec3i(62, 30, 60)), "tasuschristmascarriage");
        assertEquals(32, PortalChunkDimension.doorwayDigDepth(new Vec3i(64, 22, 64)), "the courtyard");
    }

    @Test
    @DisplayName("An arrival writes the columns within reach of where they land, held inside the room")
    void arrivalSpan() {
        // Standing in the entry corridor, just outside the end face: the first nine columns.
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {0, 8}, PortalChunkDimension.arrivalSpan(-3, 48));
        // On the door line across the room.
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {16, 32}, PortalChunkDimension.arrivalSpan(24, 48));
        // Coming in through the far end.
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {39, 47}, PortalChunkDimension.arrivalSpan(50, 48));
    }
}
