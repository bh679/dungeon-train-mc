package games.brennan.dungeontrain.perks.tier1;

import games.brennan.dungeontrain.perks.PerkEffects;
import games.brennan.dungeontrain.perks.PerkRegistry;

/**
 * Wires the tier-1 effect handlers into {@link PerkEffects}. Called once from the mod
 * constructor. The event-driven perks ({@link BiggerBackpacks}, {@link CraftLevelStats},
 * {@link DriftChance}, {@link Friendliness}, the client tooltip) subscribe on their own and only
 * query rank, so only the two that must re-apply on rank change register here.
 */
public final class TierOneEffects {

    private TierOneEffects() {}

    public static void install() {
        PerkRegistry.bootstrap();
        PerkEffects.register(new AttributePerks());
        PerkEffects.register(new BookQuillStacking());
    }
}
