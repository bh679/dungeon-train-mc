package games.brennan.dungeontrain.perks.effects.builder;

import games.brennan.dungeontrain.perks.PerkClass;
import games.brennan.dungeontrain.perks.PerkRegistry;

/**
 * The Builder class's tier 2 perks, registered beside {@code TierOnePerks}' Drift Chance. Loaded (and
 * so registered) from {@link DriftGiftContainerEvents}' static initialiser, which the event-bus scan
 * runs during mod construction.
 */
public final class BuilderPerks {

    static {
        PerkRegistry.register(OwnCarriageReturn.ID, PerkClass.BUILDER, 2);
        PerkRegistry.register(DriftGiftPayout.ID, PerkClass.BUILDER, 2);
    }

    private BuilderPerks() {}

    /** Forces class initialisation (and so registration). */
    public static void ensureRegistered() {
        // the static block did the work
    }
}
