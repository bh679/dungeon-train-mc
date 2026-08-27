package games.brennan.dungeontrain.narrative;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The burn predicate, for the two book kinds that most recently joined it: leaderboard boards and
 * Faulthurst stat notes.
 *
 * <p>Both are HELD-gated, and that gate is the whole test. A chest full of loot spills its contents
 * when it is broken and a portal Stat Room stands its whole catalogue on open shelves — if the
 * predicate answered on identity alone, both would go up in flames with nobody there to have read
 * them. So each kind is asserted twice: inert as it comes out of the factory, burnable only once a
 * player's hand has been on it.</p>
 */
class BurnableBookTagTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ItemStack book() {
        return new ItemStack(Items.WRITTEN_BOOK);
    }

    @Test
    @DisplayName("A leaderboard book burns only once it has been held")
    void leaderboardBookBurnsOnlyWhenHeld() {
        ItemStack stack = book();
        LeaderboardBookTag.stamp(stack);
        assertFalse(BurnableBookTag.isBurnable(stack), "shelved / spilled, never picked up");

        LeaderboardBookTag.markHeld(stack);
        assertTrue(BurnableBookTag.isBurnable(stack));
    }

    @Test
    @DisplayName("A stat note burns only once it has been held")
    void statBookBurnsOnlyWhenHeld() {
        ItemStack stack = book();
        RunStatBookTag.stamp(stack, 0x5EEDL);
        assertFalse(BurnableBookTag.isBurnable(stack), "baked at the container, never picked up");

        RunStatBookTag.markHeld(stack);
        assertTrue(BurnableBookTag.isBurnable(stack));
    }

    @Test
    @DisplayName("Held is not the same question as read: an unopened note still burns")
    void heldStatBookBurnsWithoutBeingOpened() {
        ItemStack stack = book();
        RunStatBookTag.stamp(stack, 1L);
        RunStatBookTag.markHeld(stack);
        assertFalse(RunStatBookTag.isLocked(stack), "never opened");
        assertTrue(BurnableBookTag.isBurnable(stack), "carrying one around unread must not fireproof it");
    }

    @Test
    @DisplayName("A held marker alone does not make a foreign book burnable")
    void heldMarkerWithoutIdentityIsNotBurnable() {
        ItemStack stack = book();
        LeaderboardBookTag.markHeld(stack);
        RunStatBookTag.markHeld(stack);
        assertFalse(BurnableBookTag.isBurnable(stack));
    }

    @Test
    @DisplayName("Plain and empty stacks stay untouched")
    void plainStacksAreNotBurnable() {
        assertFalse(BurnableBookTag.isBurnable(book()));
        assertFalse(BurnableBookTag.isBurnable(ItemStack.EMPTY));
        assertFalse(BurnableBookTag.isBurnable(null));
    }
}
