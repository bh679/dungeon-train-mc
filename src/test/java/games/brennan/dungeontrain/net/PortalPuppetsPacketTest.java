package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.PortalPuppetsPacket.Entry;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire round-trip for the three entry shapes, mixed in one snapshot.
 *
 * <p>The flag byte is the load-bearing part: a pose entry that decoded as held would freeze every
 * moving puppet, and a full entry whose plot-space flag was dropped would put a carriage puppet at
 * shipyard-local coordinates in the world, somewhere near the origin.</p>
 */
final class PortalPuppetsPacketTest {

    private static final ResourceLocation ZOMBIE = ResourceLocation.withDefaultNamespace("zombie");
    private static final UUID CARRIAGE = new UUID(3, 9);
    private static final UUID STEVE = new UUID(1, 2);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("full, pose and held entries survive the wire in order, with their shapes")
    void roundTripMixed() {
        List<SynchedEntityData.DataValue<?>> data = List.of(
            new SynchedEntityData.DataValue<>(16, EntityDataSerializers.BOOLEAN, true),
            new SynchedEntityData.DataValue<>(8, EntityDataSerializers.ITEM_STACK,
                new ItemStack(Items.ROTTEN_FLESH, 2)));

        Entry full = Entry.full(10, PortalPuppetsPacket.KIND_MOB, ZOMBIE, null, "", CARRIAGE,
            2.5, 1.0, -7.25, 90f, 100f, -5f, data,
            new ItemStack(Items.IRON_SWORD), ItemStack.EMPTY, new ItemStack(Items.IRON_CHESTPLATE),
            ItemStack.EMPTY, ItemStack.EMPTY);
        Entry player = Entry.full(11, PortalPuppetsPacket.KIND_PLAYER,
            ResourceLocation.withDefaultNamespace("player"), STEVE, "Steve", null,
            100.0, 64.0, 200.0, 0f, 10f, 20f, List.of(),
            ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
        Entry pose = Entry.pose(12, null, 1.0, 2.0, 3.0, 45f, 50f, 5f);
        Entry posePlot = Entry.pose(13, CARRIAGE, 1.0, 2.0, 3.0, 45f, 50f, 5f);
        Entry held = Entry.held(14);

        PortalPuppetsPacket decoded = roundTrip(new PortalPuppetsPacket(
            List.of(full, player, pose, posePlot, held)));

        assertEquals(5, decoded.entries().size());

        Entry f = decoded.entries().get(0);
        assertEquals(Entry.SHAPE_FULL, f.shape());
        assertEquals(10, f.key());
        assertEquals(ZOMBIE, f.typeId());
        assertEquals(CARRIAGE, f.subLevel());
        assertEquals(-7.25, f.z());
        assertEquals(100f, f.headYaw());
        assertEquals(2, f.data().size());
        assertEquals(true, f.data().get(0).value());
        assertTrue(ItemStack.matches(new ItemStack(Items.ROTTEN_FLESH, 2), (ItemStack) f.data().get(1).value()));
        assertTrue(ItemStack.matches(new ItemStack(Items.IRON_SWORD), f.mainHand()));
        assertTrue(ItemStack.matches(new ItemStack(Items.IRON_CHESTPLATE), f.chest()));
        assertTrue(f.head().isEmpty());

        Entry p = decoded.entries().get(1);
        assertTrue(p.isPlayer());
        assertEquals(STEVE, p.sourceId());
        assertEquals("Steve", p.name());
        assertNull(p.subLevel());

        Entry m = decoded.entries().get(2);
        assertEquals(Entry.SHAPE_POSE, m.shape());
        assertEquals(12, m.key());
        assertNull(m.subLevel());
        assertEquals(3.0, m.z());
        assertEquals(50f, m.headYaw());

        Entry mp = decoded.entries().get(3);
        assertEquals(Entry.SHAPE_POSE, mp.shape());
        assertEquals(CARRIAGE, mp.subLevel());

        Entry h = decoded.entries().get(4);
        assertEquals(Entry.SHAPE_HELD, h.shape());
        assertEquals(14, h.key());
    }

    @Test
    @DisplayName("the empty snapshot decodes as empty")
    void emptyRoundTrips() {
        assertTrue(roundTrip(PortalPuppetsPacket.empty()).isEmpty());
    }

    @Test
    @DisplayName("held entries cost bytes, not kilobytes")
    void heldIsTiny() {
        RegistryFriendlyByteBuf buf = buf();
        Entry[] held = new Entry[64];
        for (int i = 0; i < held.length; i++) held[i] = Entry.held(1000 + i);
        PortalPuppetsPacket.STREAM_CODEC.encode(buf, new PortalPuppetsPacket(List.of(held)));

        // Sixty-four puppets that have not moved: a count, then a varint key and a flag byte each.
        assertTrue(buf.readableBytes() <= 1 + 64 * 3, "held snapshot was " + buf.readableBytes() + " bytes");
    }

    private static RegistryFriendlyByteBuf buf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    private static PortalPuppetsPacket roundTrip(PortalPuppetsPacket original) {
        RegistryFriendlyByteBuf buf = buf();
        PortalPuppetsPacket.STREAM_CODEC.encode(buf, original);
        return PortalPuppetsPacket.STREAM_CODEC.decode(buf);
    }
}
