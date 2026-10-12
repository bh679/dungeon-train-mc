package games.brennan.dungeontrain.client.live;

import java.util.ArrayDeque;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * Slot bookkeeping for {@link LiveFrameGrabber}'s ring of pixel-pack buffers, kept free of GL so it
 * can be tested. A slot is free, or pending: a read was issued into it and its fence has not been
 * collected. Pending slots complete in the order they were issued, like the fences guarding them.
 */
final class LivePboRing {

    private final boolean[] busy;
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();

    LivePboRing(int slots) {
        if (slots < 1) throw new IllegalArgumentException("slots must be positive: " + slots);
        this.busy = new boolean[slots];
    }

    int slots() { return busy.length; }

    /** A free slot, marked pending in issue order; -1 if every slot is still waiting on the GPU. */
    int issue() {
        for (int i = 0; i < busy.length; i++) {
            if (!busy[i]) {
                busy[i] = true;
                pending.addLast(i);
                return i;
            }
        }
        return -1;
    }

    /**
     * Frees every pending slot, oldest first, until one is not {@code ready}; each freed slot goes to
     * {@code released}. Returns the newest freed slot (the freshest finished frame), or -1.
     */
    int collect(IntPredicate ready, IntConsumer released) {
        int newest = -1;
        while (!pending.isEmpty() && ready.test(pending.peekFirst())) {
            int slot = pending.removeFirst();
            busy[slot] = false;
            released.accept(slot);
            newest = slot;
        }
        return newest;
    }

    /** Frees every pending slot, finished or not. */
    void drain(IntConsumer released) {
        collect(slot -> true, released);
    }
}
