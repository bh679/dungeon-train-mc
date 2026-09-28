package games.brennan.dungeontrain.client;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * How many times this client has opened the death screen's donation page — the index the rotating
 * A/B arm walks its pair by (see {@code DonateCards#pairFor}).
 *
 * <p>Advanced once per death screen, not per frame: the page is redrawn every frame and a counter
 * tied to drawing would cycle the cards under the player's cursor.</p>
 *
 * <p><b>Per launch, not per lifetime.</b> The count starts at zero each time the game starts, so a
 * player who quits and returns may see a pair they have seen before. Persisting it would mean
 * writing to disk on every death for a cosmetic ordering detail; what the arm is testing is
 * whether the page changes between deaths, and it does either way.</p>
 */
public final class DonateRotationCounter {

    private static final AtomicInteger OPENS = new AtomicInteger();

    private DonateRotationCounter() {}

    /** The index for this visit, advancing the counter. Call once per death screen. */
    public static int next() {
        return OPENS.getAndIncrement();
    }

    /** Test seam — reset between cases. */
    public static void reset() {
        OPENS.set(0);
    }
}
