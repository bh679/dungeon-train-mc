package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.net.PortalPuppetsPacket;
import games.brennan.dungeontrain.net.PortalPuppetsPacket.Entry;
import games.brennan.dungeontrain.portal.PortalPuppetDelta.Sent;
import net.minecraft.SharedConstants;
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
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What a viewer is sent for a puppet they may already hold.
 *
 * <p>The cap on puppets per pair was a bandwidth cap: every puppet re-sent its synched data and five
 * item stacks every tick. {@link PortalPuppetDelta#classify} is what let it be raised past an
 * authored room, so what is pinned here is the whole of the saving — that an unchanged puppet costs
 * a key, a moved one a pose, and only a changed or unseen one its description — and the two ways
 * the saving could quietly fail: an item stack that never compares equal, and a client that dropped
 * everything and is never told again.</p>
 */
class PortalPuppetDeltaTest {

    private static final ResourceLocation ZOMBIE = ResourceLocation.withDefaultNamespace("zombie");
    private static final UUID CARRIAGE = new UUID(7, 7);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Entry mob(int key, UUID subLevel, double x, ItemStack mainHand,
                             List<SynchedEntityData.DataValue<?>> data) {
        return Entry.full(key, PortalPuppetsPacket.KIND_MOB, ZOMBIE, null, "", subLevel,
            x, 64.0, 8.0, 90f, 95f, 0f, data,
            mainHand, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
    }

    private static Entry mob(int key, double x) {
        return mob(key, null, x, ItemStack.EMPTY, List.of());
    }

    private static List<SynchedEntityData.DataValue<?>> itemData(ItemStack stack) {
        return List.of(new SynchedEntityData.DataValue<>(8, EntityDataSerializers.ITEM_STACK, stack));
    }

    @Test
    @DisplayName("A puppet the viewer has never seen is sent whole")
    void unseenIsFull() {
        Entry next = mob(1, 3.0);
        assertSame(next, PortalPuppetDelta.classify(null, next, 100));
    }

    @Test
    @DisplayName("Unchanged: the key alone")
    void unchangedIsHeld() {
        Entry first = mob(1, 3.0);
        Sent sent = new Sent(first, 100);

        Entry out = PortalPuppetDelta.classify(sent, mob(1, 3.0), 101);

        assertEquals(Entry.SHAPE_HELD, out.shape());
        assertEquals(1, out.key());
    }

    @Test
    @DisplayName("Moved: a pose, carrying the new position and nothing else")
    void movedIsPose() {
        Sent sent = new Sent(mob(1, 3.0), 100);

        Entry out = PortalPuppetDelta.classify(sent, mob(1, 3.5), 101);

        assertEquals(Entry.SHAPE_POSE, out.shape());
        assertEquals(3.5, out.x());
        assertEquals(0, out.data().size());
    }

    @Test
    @DisplayName("Picked up a sword: sent whole again")
    void equipmentChangeIsFull() {
        Sent sent = new Sent(mob(1, 3.0), 100);
        Entry armed = mob(1, null, 3.0, new ItemStack(Items.IRON_SWORD), List.of());

        assertEquals(Entry.SHAPE_FULL, PortalPuppetDelta.classify(sent, armed, 101).shape());
    }

    @Test
    @DisplayName("An item entity's stack compares by contents, not identity")
    void itemStackDataComparesByContents() {
        // SynchedEntityData copies an ItemStack every time it is read, so two ticks of the same
        // dropped item produce two stack instances. Identity comparison would call that a change on
        // every tick and the loot on the floor would be re-sent whole forever.
        Entry a = mob(1, null, 3.0, ItemStack.EMPTY, itemData(new ItemStack(Items.ROTTEN_FLESH, 3)));
        Entry b = mob(1, null, 3.0, ItemStack.EMPTY, itemData(new ItemStack(Items.ROTTEN_FLESH, 3)));
        Entry c = mob(1, null, 3.0, ItemStack.EMPTY, itemData(new ItemStack(Items.ROTTEN_FLESH, 4)));

        assertEquals(Entry.SHAPE_HELD, PortalPuppetDelta.classify(new Sent(a, 100), b, 101).shape());
        assertEquals(Entry.SHAPE_FULL, PortalPuppetDelta.classify(new Sent(a, 100), c, 101).shape());
    }

    @Test
    @DisplayName("Crossing into the carriage changes coordinate space, so it is sent whole")
    void subLevelChangeIsFull() {
        Sent sent = new Sent(mob(1, 3.0), 100);
        Entry onCarriage = mob(1, CARRIAGE, 3.0, ItemStack.EMPTY, List.of());

        assertEquals(Entry.SHAPE_FULL, PortalPuppetDelta.classify(sent, onCarriage, 101).shape());
    }

    @Test
    @DisplayName("Every REFRESH_TICKS the puppet is re-described even if nothing changed")
    void refreshIsFull() {
        Sent sent = new Sent(mob(1, 3.0), 100);

        assertEquals(Entry.SHAPE_HELD,
            PortalPuppetDelta.classify(sent, mob(1, 3.0), 100 + PortalPuppetDelta.REFRESH_TICKS - 1).shape());
        assertEquals(Entry.SHAPE_FULL,
            PortalPuppetDelta.classify(sent, mob(1, 3.0), 100 + PortalPuppetDelta.REFRESH_TICKS).shape());
    }

    @Test
    @DisplayName("The memory folds a pose onto the last full entry and keeps its refresh clock")
    void memoryAdvances() {
        Sent sent = new Sent(mob(1, 3.0), 100);

        Sent afterPose = sent.advance(PortalPuppetDelta.classify(sent, mob(1, 4.0), 101), 101);
        assertEquals(4.0, afterPose.lastFull().x());
        assertEquals(Entry.SHAPE_FULL, afterPose.lastFull().shape());
        assertEquals(100, afterPose.lastFullTick(), "a pose must not reset the refresh clock");

        // Now the source has not moved since that pose: held, and the memory still says x=4.
        assertEquals(Entry.SHAPE_HELD, PortalPuppetDelta.classify(afterPose, mob(1, 4.0), 102).shape());

        Sent afterFull = afterPose.advance(mob(1, 5.0), 140);
        assertEquals(140, afterFull.lastFullTick());
    }
}
