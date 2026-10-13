package games.brennan.dungeontrain.item;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Live Eye's id is stamped once and kept, so a relog claims with the same id and a new one differs. */
final class LiveEyeIdTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("ensure stamps a UUID once and returns it again; other stacks get their own")
    void stampedOnceAndKept() {
        ItemStack eye = new ItemStack(Items.STICK);
        assertEquals("", LiveEyeId.get(eye));
        String id = LiveEyeId.ensure(eye);
        UUID.fromString(id);
        assertEquals(id, LiveEyeId.ensure(eye));
        assertEquals(id, LiveEyeId.get(eye.copy()), "travels with the item");
        assertNotEquals(id, LiveEyeId.ensure(new ItemStack(Items.STICK)));
    }

    @Test
    @DisplayName("the pending loot marker and the id live side by side")
    void keepsPendingMarker() {
        ItemStack eye = new ItemStack(Items.STICK);
        LiveHeadpiecePendingTag.markPending(eye);
        LiveEyeId.ensure(eye);
        assertTrue(LiveHeadpiecePendingTag.isPending(eye));
    }

    @Test
    @DisplayName("empty or null stacks get no id")
    void emptyStacks() {
        assertEquals("", LiveEyeId.ensure(ItemStack.EMPTY));
        assertEquals("", LiveEyeId.ensure(null));
    }
}
