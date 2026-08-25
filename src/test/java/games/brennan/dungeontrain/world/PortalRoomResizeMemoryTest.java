package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.portal.PortalRoomResize;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The per-world record of rows a portal-room shrink cropped.
 *
 * <p>The blocks are opaque here on purpose — the class carries whatever {@code StructureTemplate}
 * handed it and never reads inside — so these cover the part that is this class's own: that a record
 * survives the save file, that the matching grow is the only thing that takes it back, and that a
 * save or a clear forgets it.</p>
 */
class PortalRoomResizeMemoryTest {

    private static PortalRoomResizeMemory.Slab slab(String marker) {
        CompoundTag blocks = new CompoundTag();
        blocks.putString("marker", marker);
        return new PortalRoomResizeMemory.Slab(blocks, "{\"variants\":{}}", "{\"pools\":{}}");
    }

    private static PortalRoomResizeMemory roundTrip(PortalRoomResizeMemory source) {
        return PortalRoomResizeMemory.load(source.save(new CompoundTag(), null));
    }

    @Test
    @DisplayName("A filed row survives the world save file")
    void slab_roundTripsThroughNbt() {
        PortalRoomResizeMemory memory = new PortalRoomResizeMemory();
        memory.put("default", PortalRoomResize.Axis.WIDTH, 13, slab("w13"));
        memory.put("default", PortalRoomResize.Axis.LENGTH, 11, slab("l11"));

        PortalRoomResizeMemory loaded = roundTrip(memory);

        assertEquals(2, loaded.countFor("default"));
        PortalRoomResizeMemory.Slab back = loaded.take("default", PortalRoomResize.Axis.WIDTH, 13);
        assertNotNull(back);
        assertEquals("w13", back.blocks().getString("marker"));
        assertEquals("{\"variants\":{}}", back.variantsJson());
        assertEquals("{\"pools\":{}}", back.contentsJson());
    }

    @Test
    @DisplayName("Only the matching axis and size takes a row back")
    void take_isKeyedByAxisAndSize() {
        PortalRoomResizeMemory memory = new PortalRoomResizeMemory();
        memory.put("default", PortalRoomResize.Axis.WIDTH, 13, slab("w13"));

        assertNull(memory.take("default", PortalRoomResize.Axis.LENGTH, 13), "wrong axis");
        assertNull(memory.take("default", PortalRoomResize.Axis.WIDTH, 14), "wrong size");
        assertNull(memory.take("other", PortalRoomResize.Axis.WIDTH, 13), "wrong room");
        assertNotNull(memory.take("default", PortalRoomResize.Axis.WIDTH, 13));
    }

    @Test
    @DisplayName("Taking a row consumes it — a second grow gets the built-in shell, not a stale copy")
    void take_consumesTheRecord() {
        PortalRoomResizeMemory memory = new PortalRoomResizeMemory();
        memory.put("default", PortalRoomResize.Axis.HEIGHT, 7, slab("h7"));

        assertNotNull(memory.take("default", PortalRoomResize.Axis.HEIGHT, 7));
        assertNull(memory.take("default", PortalRoomResize.Axis.HEIGHT, 7));
        assertEquals(0, memory.countFor("default"));
    }

    @Test
    @DisplayName("A save or a clear forgets every row for that room, and only that room")
    void forget_isPerRoom() {
        PortalRoomResizeMemory memory = new PortalRoomResizeMemory();
        memory.put("default", PortalRoomResize.Axis.WIDTH, 13, slab("a"));
        memory.put("default", PortalRoomResize.Axis.LENGTH, 11, slab("b"));
        memory.put("library", PortalRoomResize.Axis.WIDTH, 13, slab("c"));

        assertEquals(2, memory.forget("default"));
        assertEquals(0, memory.countFor("default"));
        assertEquals(1, memory.countFor("library"));
        assertEquals(0, memory.forget("gone"), "forgetting an unknown room is not an error");
    }

    @Test
    @DisplayName("A record naming an axis this build does not have is dropped, not fatal")
    void load_survivesAnUnknownAxis() {
        PortalRoomResizeMemory memory = new PortalRoomResizeMemory();
        memory.put("default", PortalRoomResize.Axis.WIDTH, 13, slab("keep"));

        CompoundTag tag = memory.save(new CompoundTag(), null);
        CompoundTag rogue = new CompoundTag();
        rogue.putString("room", "default");
        rogue.putString("axis", "DEPTH");
        rogue.putInt("size", 9);
        rogue.put("blocks", new CompoundTag());
        rogue.putString("variants", "");
        rogue.putString("contents", "");
        tag.getList("slabs", net.minecraft.nbt.Tag.TAG_COMPOUND).add(rogue);

        PortalRoomResizeMemory loaded = PortalRoomResizeMemory.load(tag);
        assertEquals(1, loaded.countFor("default"));
        assertNotNull(loaded.take("default", PortalRoomResize.Axis.WIDTH, 13));
    }
}
