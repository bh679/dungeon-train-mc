package games.brennan.dungeontrain.narrative;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-stack memory a Faulthurst stat book carries.
 *
 * <p>The lock is the load-bearing field. Once a book has been opened its page is a record of the run
 * as it stood at that moment, and a refresh that ran anyway would quietly rewrite the reader's
 * memento — so {@link RunStatBookTag#isLocked} must survive everything a stack survives, copies
 * included (a book moved between inventory slots is a copy).</p>
 *
 * <p>The rendered-value field is the other half of the design: the once-a-second refresh compares
 * against it and skips the write when nothing has moved, which is what keeps a carried book from
 * churning its stack sixty times a minute.</p>
 */
class RunStatBookTagTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ItemStack book() {
        return new ItemStack(Items.WRITTEN_BOOK);
    }

    @Test
    @DisplayName("An unstamped stack is not a stat book")
    void plainStackIsNotAStatBook() {
        assertFalse(RunStatBookTag.is(book()));
        assertFalse(RunStatBookTag.is(ItemStack.EMPTY));
        assertFalse(RunStatBookTag.isLocked(book()));
        assertEquals(-1L, RunStatBookTag.seed(book(), -1L), "no seed stamped");
        assertTrue(RunStatBookTag.subject(book()).isEmpty());
        assertEquals("", RunStatBookTag.renderedValue(book()));
    }

    @Test
    @DisplayName("A stamped book remembers its seed, subject and last-rendered number")
    void roundTrips() {
        ItemStack stack = book();
        RunStatBookTag.stamp(stack, 0x5EEDL);

        assertTrue(RunStatBookTag.is(stack));
        assertEquals(0x5EEDL, RunStatBookTag.seed(stack, 0L));
        assertTrue(RunStatBookTag.subject(stack).isEmpty(), "no subject until it meets a reader");

        RunStatBookTag.recordBaked(stack, RunStatSubject.CHESTS, "14");
        assertEquals(RunStatSubject.CHESTS, RunStatBookTag.subject(stack).orElse(null));
        assertEquals("14", RunStatBookTag.renderedValue(stack));
        assertEquals(0x5EEDL, RunStatBookTag.seed(stack, 0L), "baking must not disturb the seed");
    }

    @Test
    @DisplayName("An unrecognised subject id reads as no subject rather than throwing")
    void unknownSubjectIsAbsent() {
        ItemStack stack = book();
        RunStatBookTag.stamp(stack, 1L);
        RunStatBookTag.recordBaked(stack, RunStatSubject.ECHOES, "3");
        assertTrue(RunStatBookTag.subject(stack).isPresent());
        // A subject retired in a later version must not crash a save that still holds one.
        assertTrue(RunStatSubject.byId("a_subject_that_was_removed").isEmpty());
    }

    @Test
    @DisplayName("The lock survives a copy — a book moved between slots stays frozen")
    void lockSurvivesCopy() {
        ItemStack stack = book();
        RunStatBookTag.stamp(stack, 9L);
        RunStatBookTag.recordBaked(stack, RunStatSubject.PLAYTIME, "2h 14m");
        assertFalse(RunStatBookTag.isLocked(stack), "not opened yet");

        RunStatBookTag.lock(stack);
        assertTrue(RunStatBookTag.isLocked(stack));

        ItemStack moved = stack.copy();
        assertTrue(RunStatBookTag.isLocked(moved), "moving a slot must not thaw a read book");
        assertEquals("2h 14m", RunStatBookTag.renderedValue(moved));
        assertEquals(RunStatSubject.PLAYTIME, RunStatBookTag.subject(moved).orElse(null));
        assertEquals(9L, RunStatBookTag.seed(moved, 0L));
    }

    @Test
    @DisplayName("The held marker survives a copy and disturbs nothing else")
    void heldMarkerSurvivesCopyAndLeavesTheRestAlone() {
        ItemStack stack = book();
        RunStatBookTag.stamp(stack, 11L);
        RunStatBookTag.recordBaked(stack, RunStatSubject.CHESTS, "7");
        assertFalse(RunStatBookTag.isHeld(stack), "baked at the container, nobody has touched it");

        RunStatBookTag.markHeld(stack);
        assertTrue(RunStatBookTag.isHeld(stack));
        assertFalse(RunStatBookTag.isLocked(stack), "held is not read");
        assertEquals(RunStatSubject.CHESTS, RunStatBookTag.subject(stack).orElse(null));
        assertEquals("7", RunStatBookTag.renderedValue(stack));
        assertEquals(11L, RunStatBookTag.seed(stack, 0L));

        // A book put down in a chest and taken out again is a different stack object. It has still
        // been held, and must still burn.
        assertTrue(RunStatBookTag.isHeld(stack.copy()));
    }
}
