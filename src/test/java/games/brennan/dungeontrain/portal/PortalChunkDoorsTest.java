package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.SharedConstants;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where {@link PortalChunkDoors#fit} stands a chunk dimension's two doorways: on the sampled
 * ground where the sample reaches the mouth, and where the author put them where it does not.
 */
class PortalChunkDoorsTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final int CHUNK = PortalChunkTerrain.SIZE;
    private static final int HEIGHT = PortalChunkTerrain.HEIGHT;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A one-chunk sample: stone up to and including {@code topRow}, air above (none for all rock). */
    private static PortalChunkSlice flatSlice(int topRow) {
        BlockState[] states = new BlockState[CHUNK * HEIGHT * CHUNK];
        for (int y = 0; y < HEIGHT; y++) {
            BlockState s = y <= topRow ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            for (int i = 0; i < CHUNK * CHUNK; i++) states[y * CHUNK * CHUNK + i] = s;
        }
        return new PortalChunkSlice(PortalChunkTerrain.Source.OVERWORLD, CHUNK, HEIGHT, states,
            Map.of(), List.of());
    }

    private static PortalRoomSettings fit(String tag, PortalChunkSlice slice, Vec3i size) {
        return PortalChunkDoors.fit(PortalRoomSettings.parse(tag), slice, DIMS,
            PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.DEFAULT), size);
    }

    /** A 3x3 sample: stone up to {@code topRow} only in columns {@code zFrom..zTo} (every x), air elsewhere. */
    private static PortalChunkSlice stripSlice(int width, int topRow, int zFrom, int zTo) {
        BlockState[] states = new BlockState[width * HEIGHT * width];
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < width; z++) {
                for (int x = 0; x < width; x++) {
                    boolean stone = y <= topRow && z >= zFrom && z <= zTo;
                    states[PortalChunkArea.index(width, x, y, z)] = stone
                        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                }
            }
        }
        return new PortalChunkSlice(PortalChunkTerrain.Source.OVERWORLD, width, HEIGHT, states,
            Map.of(), List.of());
    }

    private static final String TAG_3X3 =
        "chunk_dimension/exact/off/off/off/none/sealed/0/0/0/0/dungeontrain:skybox_surface";

    @Test
    @DisplayName("A 3x3 door with no ground in its mouth slides sideways onto ground, each end on its own")
    void threeByThree_slidesDoorSidewaysOntoGround() {
        Vec3i size = new Vec3i(3 * CHUNK, 22, 3 * CHUNK);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.DEFAULT);
        int limit = PortalRoomLayout.maxDoorOffset(DIMS, size.getZ());
        assertTrue(limit > 0, "a 3x3 room leaves the door room to move");
        int[] far = PortalChunkDoors.doorwayColumns(DIMS, layout, size, limit);
        int[] centre = PortalChunkDoors.doorwayColumns(DIMS, layout, size, 0);
        // Ground only under the far-offset doorway's columns that the centred doorway does not cover.
        int zFrom = Math.max(far[0], centre[1] + 1);
        int zTo = far[1];
        assertTrue(zFrom <= zTo, "the far doorway reaches columns the centred one does not");
        PortalChunkSlice slice = stripSlice(size.getX(), 5, zFrom, zTo);

        PortalRoomSettings fitted = fit(TAG_3X3, slice, size);

        assertNotEquals(0, fitted.doorOffset().value(), "entry moved off the empty centre");
        assertNotEquals(0, fitted.exitDoorOffset().value(), "exit moved off the empty centre");
        int[] entry = PortalChunkDoors.doorwayColumns(DIMS, layout, size, fitted.doorOffset().value());
        assertTrue(PortalChunkDoors.mouthHasGround(slice, 0, 1, entry[0], entry[1], 22),
            "the moved entry starts against a block");
    }

    @Test
    @DisplayName("A 3x3 door stays put when its mouth already has ground, or when no offset has any")
    void threeByThree_keepsDoorWhenGroundedOrHopeless() {
        Vec3i size = new Vec3i(3 * CHUNK, 22, 3 * CHUNK);
        PortalRoomSettings grounded = fit(TAG_3X3, stripSlice(size.getX(), 5, 0, size.getZ() - 1), size);
        assertEquals(0, grounded.doorOffset().value());
        assertEquals(0, grounded.exitDoorOffset().value());

        PortalRoomSettings empty = fit(TAG_3X3, stripSlice(size.getX(), -1, 0, 0), size);
        assertEquals(0, empty.doorOffset().value());
        assertEquals(0, empty.exitDoorOffset().value());
    }

    @Test
    @DisplayName("A room bigger than a chunk keeps its authored doorways where the sample does not reach")
    void bigRoom_keepsAuthoredDoors() {
        // The courtyard: 64 x 22 x 64, both doors on its own floor, door line two off centre.
        Vec3i size = new Vec3i(64, 22, 64);
        PortalRoomSettings fitted = fit(
            "chunk_dimension/exact/off/off/off/none/sealed/-2/0/-2/0/dungeontrain:skybox_surface",
            flatSlice(5), size);

        assertEquals(0, fitted.doorHeightOffset().value(), "entry mouth is off the sample (z ~30)");
        assertEquals(0, fitted.exitDoorHeightOffset().value(), "exit mouth is off the sample (x 62-63)");
    }

    @Test
    @DisplayName("Each end keeps its own authored height when both lie outside the sample")
    void bigRoom_keepsEachEndsOwnHeight() {
        Vec3i size = new Vec3i(64, 22, 64);
        PortalRoomSettings fitted = fit(
            "chunk_dimension/exact/off/off/off/none/sealed/-2/3/-2/4/dungeontrain:skybox_surface",
            flatSlice(5), size);

        assertEquals(3, fitted.doorHeightOffset().value());
        assertEquals(4, fitted.exitDoorHeightOffset().value());
    }

    @Test
    @DisplayName("A one-chunk room still stands both doors on the sampled ground")
    void chunkRoom_fitsToTerrain() {
        Vec3i size = new Vec3i(CHUNK, HEIGHT, CHUNK);
        PortalRoomSettings fitted = fit("chunk_dimension/exact/off/off/off/day/sealed/0/9/0/9",
            flatSlice(5), size);

        assertEquals(5, fitted.doorHeightOffset().value());
        assertEquals(5, fitted.exitDoorHeightOffset().value());
    }

    @Test
    @DisplayName("A one-chunk mouth cut into solid rock still goes on the anchored ground row")
    void chunkRoom_allRock_takesGroundRow() {
        Vec3i size = new Vec3i(CHUNK, HEIGHT, CHUNK);
        PortalRoomSettings fitted = fit("chunk_dimension/exact/off/off/off/day/sealed/0/2/0/2",
            flatSlice(HEIGHT), size);

        assertEquals(PortalChunkTerrain.SURFACE_ROW - 1, fitted.doorHeightOffset().value());
        assertEquals(PortalChunkTerrain.SURFACE_ROW - 1, fitted.exitDoorHeightOffset().value());
    }

    @Test
    @DisplayName("An underwater room opens both doors at the middle of the box, whatever the seabed")
    void underwater_doorsAtMiddle() {
        Vec3i size = new Vec3i(CHUNK, HEIGHT, CHUNK);
        int max = PortalRoomLayout.maxDoorHeightOffset(DIMS, HEIGHT);
        for (int seabed : new int[] {0, 5, 8}) {
            PortalChunkSlice flat = flatSlice(seabed);
            BlockState[] states = new BlockState[CHUNK * HEIGHT * CHUNK];
            for (int y = 0; y < HEIGHT; y++) {
                for (int i = 0; i < CHUNK * CHUNK; i++) states[y * CHUNK * CHUNK + i] = flat.at(0, y, 0);
            }
            PortalChunkSlice sea = new PortalChunkSlice(PortalChunkTerrain.Source.UNDERWATER, CHUNK, HEIGHT,
                states, Map.of(), List.of());
            PortalRoomSettings fitted = fit("chunk_dimension/exact/off/off/off/day/sealed/0/9/0/9", sea, size);
            assertEquals(max / 2, fitted.doorHeightOffset().value(), "seabed " + seabed);
            assertEquals(max / 2, fitted.exitDoorHeightOffset().value(), "seabed " + seabed);
        }
    }
}
