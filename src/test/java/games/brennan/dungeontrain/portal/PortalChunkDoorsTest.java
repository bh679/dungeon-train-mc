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
