package games.brennan.dungeontrain.portal.chunkframe;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.portal.PortalRoomMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A chunk frame's dimension type: how it is stored, and the room mode it turns a chunk room into. */
class ChunkFrameDimensionTest {

    @Test
    @DisplayName("meta round-trips its dimension; a file without one reads as Bedrock")
    void metaJson() {
        ChunkFrameMeta meta = new ChunkFrameMeta(Set.of("chunk_dimension"), 3, ChunkFrameDimension.VOID);
        ChunkFrameMeta back = ChunkFrameMeta.fromJson(meta.toJson());
        assertEquals(meta, back);
        JsonObject old = new JsonObject();
        old.addProperty("weight", 2);
        assertSame(ChunkFrameDimension.BEDROCK, ChunkFrameMeta.fromJson(old).dimension());
        assertFalse(ChunkFrameMeta.DEFAULT.toJson().has("dimension"), "default stays out of the file");
    }

    @Test
    @DisplayName("editing rooms or weight keeps the dimension")
    void editsKeepDimension() {
        ChunkFrameMeta meta = ChunkFrameMeta.DEFAULT.withDimension(ChunkFrameDimension.WORLD_BORDER);
        assertSame(ChunkFrameDimension.WORLD_BORDER, meta.withWeight(5).dimension());
        assertSame(ChunkFrameDimension.WORLD_BORDER, meta.withNoRooms().dimension());
        assertSame(ChunkFrameDimension.WORLD_BORDER, meta.withAllRooms().dimension());
        assertSame(ChunkFrameDimension.WORLD_BORDER,
            meta.toggled("a", false, java.util.List.of("a", "b")).dimension());
    }

    @Test
    @DisplayName("parse is total and matches ids")
    void parse() {
        assertSame(ChunkFrameDimension.VOID, ChunkFrameDimension.parse(" Void "));
        assertSame(ChunkFrameDimension.WORLD_BORDER, ChunkFrameDimension.parse("world_border"));
        assertSame(ChunkFrameDimension.BEDROCK, ChunkFrameDimension.parse("nonsense"));
        assertSame(ChunkFrameDimension.BEDROCK, ChunkFrameDimension.parse(null));
    }

    @Test
    @DisplayName("only a plain chunk dimension changes mode")
    void roomMode() {
        assertSame(PortalRoomMode.CHUNK_DIMENSION, ChunkFrameDimension.BEDROCK.roomMode(PortalRoomMode.CHUNK_DIMENSION));
        assertSame(PortalRoomMode.CHUNK_VOID, ChunkFrameDimension.VOID.roomMode(PortalRoomMode.CHUNK_DIMENSION));
        assertSame(PortalRoomMode.CHUNK_WORLD_BORDER,
            ChunkFrameDimension.WORLD_BORDER.roomMode(PortalRoomMode.CHUNK_DIMENSION));
        assertSame(PortalRoomMode.BEDROCK, ChunkFrameDimension.VOID.roomMode(PortalRoomMode.BEDROCK));
    }

    @Test
    @DisplayName("the chunk void modes generate terrain, sweep instead of sealing, and stay out of the editor cycle")
    void chunkModes() {
        for (PortalRoomMode m : new PortalRoomMode[] {PortalRoomMode.CHUNK_VOID, PortalRoomMode.CHUNK_WORLD_BORDER}) {
            assertTrue(m.generatesTerrain(), m.name());
            assertTrue(m.clearsSurroundings(), m.name());
            assertTrue(m.fogs(), m.name());
            assertFalse(m.sealsRoomBox(), m.name());
            assertFalse(m.authored(), m.name());
            assertSame(m, PortalRoomMode.parse(m.id()), m.name());
        }
        assertTrue(PortalRoomMode.CHUNK_WORLD_BORDER.drawsBorder());
        assertFalse(PortalRoomMode.CHUNK_VOID.drawsBorder());
        // The editor cycle never lands on them.
        PortalRoomMode m = PortalRoomMode.BEDROCK;
        for (int i = 0; i < PortalRoomMode.values().length * 2; i++) {
            m = m.next();
            assertTrue(m.authored(), "cycle reached " + m);
        }
        assertNotEquals(PortalRoomMode.CHUNK_VOID, PortalRoomMode.WORLD_BORDER.next());
    }
}
