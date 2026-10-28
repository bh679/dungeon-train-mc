package games.brennan.dungeontrain.client.perks;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.Supplier;

/**
 * Where the perks menu stands when it opens on death, ahead of the death recap. The menu builds up one
 * layer at a time ({@link MenuReveal}); the perks that held points this life light up in their own
 * layer, still holding the points they had at death. Once everything is up their rings empty over
 * {@link #DRAIN_MS}, leaving the tree as the next life starts it. The menu is unlocking only.
 *
 * <p>The menu takes no input until the build-up is done; a click or Enter during it brings everything
 * up at once. The points shown are the ones held at death, frozen when the screen opens; unlocks are
 * read live, so ones made in the menu show at once.</p>
 */
@OnlyIn(Dist.CLIENT)
final class DeathPerksPhase {

    /** The rings empty over this long once the menu is up. */
    static final long DRAIN_MS = 1000;

    private final PerkMenuSnapshot carried;
    private final Supplier<Screen> next;
    private final MenuReveal reveal;

    DeathPerksPhase(PerkMenuSnapshot atDeath, Supplier<Screen> next, long openedAt) {
        this.carried = atDeath;
        this.next = next;
        this.reveal = new MenuReveal(openedAt, atDeath.ranksBought() > 0);
    }

    /** The points held at death, as the screen opened. */
    PerkMenuSnapshot carried() {
        return carried;
    }

    MenuReveal reveal() {
        return reveal;
    }

    /** The menu's controls take input once every layer is up. */
    boolean menuLive(long now) {
        return reveal.done(now);
    }

    /** A click or key during the build-up: bring everything up now. */
    void advance(long now) {
        reveal.skip(now);
    }

    /** Share of each ring still filled: full through the build-up, then emptying to 0. */
    double ringsLeft(long now) {
        if (!reveal.done(now)) return 1;
        long t = now - reveal.doneAt();
        return t >= DRAIN_MS ? 0 : 1 - t / (double) DRAIN_MS;
    }

    /** What the sky draws this frame: the live unlocks, the points held at death as they drain, no points to spend. */
    PerkMenuSnapshot view(PerkMenuSnapshot live, long now) {
        return new PerkMenuSnapshot(live.unlocks(), carried.drained(ringsLeft(now)).active(), live.unlockPoints(), 0);
    }

    /** The death recap, opened when the player moves on from the menu. */
    Screen next() {
        return next.get();
    }
}
