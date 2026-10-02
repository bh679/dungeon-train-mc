package games.brennan.dungeontrain.compat;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reflective seam of {@link TrashSlotReturnOnClose}, against stand-ins for TrashSlot's classes. */
class TrashSlotReturnOnCloseTest {

    /** Same shape as {@code net.blay09.mods.trashslot.TrashHelper}. */
    public static final class HelperStandIn {
        public static ItemStack getTrashItem(Player player) { return null; }
        public static void setTrashItem(Player player, ItemStack stack) {}
    }

    /** Same shape as {@code net.blay09.mods.trashslot.network.MessageTrashSlotContent}. */
    public record MessageStandIn(ItemStack stack) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() { return null; }
    }

    public static final class WrongReturnHelper {
        public static Object getTrashItem(Player player) { return null; }
        public static void setTrashItem(Player player, ItemStack stack) {}
    }

    public record NotAPayload(ItemStack stack) {}

    @Test
    void linksTrashSlotShapedClasses() {
        Optional<TrashSlotReturnOnClose.Hooks> hooks =
                TrashSlotReturnOnClose.link(HelperStandIn.class, MessageStandIn.class);
        assertTrue(hooks.isPresent());
        assertEquals("getTrashItem", hooks.get().getTrashItem().getName());
        assertEquals("setTrashItem", hooks.get().setTrashItem().getName());
        assertEquals(MessageStandIn.class, hooks.get().contentMessage().getDeclaringClass());
    }

    @Test
    void missingHelperMethodsDoNotLink() {
        assertTrue(TrashSlotReturnOnClose.link(Object.class, MessageStandIn.class).isEmpty());
    }

    @Test
    void getterMustReturnAnItemStack() {
        assertTrue(TrashSlotReturnOnClose.link(WrongReturnHelper.class, MessageStandIn.class).isEmpty());
    }

    @Test
    void messageMustBeAPayload() {
        assertTrue(TrashSlotReturnOnClose.link(HelperStandIn.class, NotAPayload.class).isEmpty());
    }

    @Test
    void absentTrashSlotResolvesToNothing() {
        // The test classpath carries no TrashSlot jar.
        assertTrue(TrashSlotReturnOnClose.resolveHooks().isEmpty());
    }
}
