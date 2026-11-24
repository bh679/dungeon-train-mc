package games.brennan.dungeontrain.portal.chunkframe;

import net.minecraft.SharedConstants;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frames of more than one size: which sizes are frames, which rooms they fit, and the shipped 3x3 ones. */
class ChunkFrameSizesTest {

    private static final Vec3i THREE_BY_THREE = new Vec3i(50, 34, 50);
    private static final String[] BUNDLED_3X3 = {"stone_frame_bays", "stone_frame_hall", "stone_frame_courtyard"};

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("A frame fits the room one block smaller on every side; one-chunk and 3x3 frames are valid, others not")
    void sizes() {
        assertEquals(ChunkFrame.SIZE, ChunkFrame.sizeFor(ChunkFrame.ROOM_SIZE));
        assertEquals(THREE_BY_THREE, ChunkFrame.sizeFor(new Vec3i(48, 32, 48)));
        assertEquals(new Vec3i(48, 32, 48), ChunkFrame.roomFor(THREE_BY_THREE));
        assertTrue(ChunkFrame.validSize(ChunkFrame.SIZE));
        assertTrue(ChunkFrame.validSize(THREE_BY_THREE));
        assertFalse(ChunkFrame.validSize(new Vec3i(34, 34, 34)), "a 2x2 room has no middle chunk, so no grid room is that size");
        assertFalse(ChunkFrame.validSize(new Vec3i(50, 24, 50)), "the generated rooms are 32 tall");
        assertFalse(ChunkFrame.validSize(new Vec3i(50, 34, 18)), "not square");
        assertFalse(ChunkFrame.validSize(null));
    }

    @Test
    @DisplayName("The shell of a 3x3 frame is its outer layer, as for a one-chunk frame")
    void shellOfAWideFrame() {
        assertTrue(ChunkFrame.isShell(49, 10, 20, THREE_BY_THREE));
        assertTrue(ChunkFrame.isShell(20, 33, 20, THREE_BY_THREE));
        assertFalse(ChunkFrame.isShell(17, 10, 20, THREE_BY_THREE), "x=17 is the edge of a one-chunk frame, inside a 3x3 one");
    }

    @Test
    @DisplayName("Each shipped 3x3 frame is 50x34x50, with a sidecar inside its box and a meta file")
    void bundled3x3Frames() throws Exception {
        for (String name : BUNDLED_3X3) {
            CompoundTag tag;
            try (InputStream in = getClass().getResourceAsStream("/data/dungeontrain/chunk_frames/" + name + ".nbt")) {
                assertNotNull(in, name + ".nbt ships");
                tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            }
            var size = tag.getList("size", Tag.TAG_INT);
            assertEquals(THREE_BY_THREE, new Vec3i(size.getInt(0), size.getInt(1), size.getInt(2)), name);

            String json;
            try (InputStream in = getClass().getResourceAsStream("/data/dungeontrain/chunk_frames/" + name + ".variants.json")) {
                assertNotNull(in, name + ".variants.json ships");
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            var doc = games.brennan.dungeontrain.track.variant.TrackVariantBlocks.fromJsonText(json, null, name, THREE_BY_THREE);
            assertFalse(doc.entries().isEmpty(), name + " keeps stone_frame's block rolls");
            for (var entry : doc.entries()) {
                var p = entry.localPos();
                assertTrue(p.getX() >= 0 && p.getX() < 50 && p.getY() >= 0 && p.getY() < 34 && p.getZ() >= 0 && p.getZ() < 50,
                    name + " roll at " + p + " is inside the frame");
            }
            try (InputStream in = getClass().getResourceAsStream("/data/dungeontrain/chunk_frames/" + name + ".frame.json")) {
                assertNotNull(in, name + ".frame.json ships");
                ChunkFrameMeta meta = ChunkFrameMeta.fromJson(com.google.gson.JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                assertTrue(meta.allRooms(), name + " dresses every room of its size");
            }
        }
    }
}
