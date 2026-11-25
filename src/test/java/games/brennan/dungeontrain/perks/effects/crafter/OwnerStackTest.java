package games.brennan.dungeontrain.perks.effects.crafter;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The Enchant Bonus owner gate: who is pushed, and that every enter/exit pair stays balanced. */
class OwnerStackTest {

    private final OwnerStack stack = new OwnerStack();
    private final Object player = new Object();
    private final Object mob = new Object();

    @Test
    void nonQualifierAtDepthZeroIsNeverPushed() {
        stack.enter(mob, false);
        assertEquals(0, stack.live());
        assertEquals(0, stack.localDepth());
        assertNull(stack.top());
        stack.exit();
        assertEquals(0, stack.live());
    }

    @Test
    void qualifierIsPushedAndPopped() {
        stack.enter(player, true);
        assertSame(player, stack.top());
        assertEquals(1, stack.live());
        stack.exit();
        assertEquals(0, stack.live());
        assertEquals(0, stack.localDepth());
    }

    @Test
    void rankDroppedBeforeExitStillBalances() {
        // Exit takes no "qualifies" at all: what was pushed is popped whatever the rank is now.
        stack.enter(player, true);
        stack.exit();
        assertEquals(0, stack.localDepth());
        assertEquals(0, stack.live());
    }

    @Test
    void skippedBracketNeverStealsAnOuterEntry() {
        Object other = new Object();
        stack.enter(other, true);        // outer: a perked player
        stack.enter(player, true);       // inner qualifies: pushed
        stack.enter(player, false);      // innermost: rank fell to 0 between brackets: masked, not skipped
        stack.exit();
        assertSame(player, stack.top());
        stack.exit();
        assertSame(other, stack.top());
        stack.exit();
        assertEquals(0, stack.live());
        assertEquals(0, stack.localDepth());
    }

    @Test
    void skippedAtDepthZeroThenQualifyingInsideStaysBalanced() {
        stack.enter(player, false);      // skipped (rank 0 at HEAD)
        stack.enter(player, true);       // rank rose: inner pushes
        stack.exit();
        stack.exit();                    // outer exit: nothing left to pop, no-op
        assertEquals(0, stack.live());
        assertEquals(0, stack.localDepth());
    }

    @Test
    void nestedNonQualifierMasksThenRestoresOwner() {
        stack.enter(player, true);
        stack.enter(mob, false);
        assertNull(stack.top());
        stack.exit();
        assertSame(player, stack.top());
        stack.exit();
        assertEquals(0, stack.live());
    }

    @Test
    void otherThreadSeesNoOwnerAndDoesNotUnbalance() throws InterruptedException {
        stack.enter(player, true);
        AtomicReference<Object> seen = new AtomicReference<>(player);
        Thread t = new Thread(() -> {
            seen.set(stack.top());
            stack.enter(mob, false);     // live > 0 but this thread is empty: skipped
            stack.exit();                // must not pop anything
        });
        t.start();
        t.join();
        assertNull(seen.get());
        assertEquals(1, stack.live());
        assertSame(player, stack.top());
        stack.exit();
        assertEquals(0, stack.live());
    }

    @Test
    void deepNestingReturnsToZero() {
        for (int i = 0; i < 50; i++) stack.enter(i % 3 == 0 ? player : mob, i % 3 == 0);
        for (int i = 0; i < 50; i++) stack.exit();
        assertEquals(0, stack.live());
        assertEquals(0, stack.localDepth());
        assertNull(stack.top());
    }
}
