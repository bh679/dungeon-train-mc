package games.brennan.dungeontrain.perks.tier1;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkEffects;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;

import static games.brennan.dungeontrain.perks.TierOnePerks.*;

/**
 * Declares the tier-1 perks: marks them built, registers their stats, and wires the two handlers
 * that must re-apply on rank change. The event-driven perks ({@link BiggerBackpacks},
 * {@link CraftLevelStats}, {@link DriftChance}, {@link Friendliness}, the client tooltip) subscribe
 * on their own and only query rank. Drift Chance stays Coming soon through {@code BuiltPerks.LOCKED}.
 */
public final class TierOneEffects {

    /** Vanilla wolf tame roll: one in three. */
    private static final int WOLF_TAME_BOUND = 3;

    private TierOneEffects() {}

    public static void declare() {
        BuiltPerks.markBuilt(HEALTH, SPEED, DAMAGE, STACKABLE_BOOK_QUILL, FRIENDLINESS,
            SEE_TRADE_VALUES, BIGGER_BACKPACKS, CRAFT_LEVEL_STATS, DRIFT_CHANCE);
        PerkEffects.register(new AttributePerks());
        PerkEffects.register(new BookQuillStacking());

        PerkStats.register(HEALTH, "health", r -> PerkStats.plus(AttributePerks.healthBonus(r), Unit.HEARTS));
        PerkStats.register(SPEED, "speed", r -> PerkStats.plus(AttributePerks.speedMultiplierBonus(r) * 100, Unit.PERCENT));
        PerkStats.register(DAMAGE, "damage", r -> PerkStats.plus(AttributePerks.damageBonus(r), Unit.PLAIN));
        PerkStats.register(STACKABLE_BOOK_QUILL, "stack", r -> PerkStats.num(BookQuillStacking.stackSizeFor(r), Unit.PLAIN));
        PerkStats.register(FRIENDLINESS, "tame_chance",
            r -> PerkStats.num(Friendliness.tameChance(WOLF_TAME_BOUND, r) * 100, Unit.PERCENT));
        PerkStats.register(FRIENDLINESS, "warmth", r -> PerkStats.plus(Friendliness.gapClosed(r) * 100, Unit.PERCENT));
        PerkStats.register(SEE_TRADE_VALUES, "shows_up_to",
            r -> PerkStats.num(Math.floor(SeeTradeValues.ceilingEmeralds(r)), Unit.EMERALDS));
        PerkStats.register(BIGGER_BACKPACKS, "extra_slots",
            r -> PerkStats.plus(BiggerBackpacks.extraExpectation(r) * 100, Unit.PERCENT));
        PerkStats.register(CRAFT_LEVEL_STATS, "best_roll", r -> PerkStats.num(CraftLevelStats.ceiling(r) * 100, Unit.PERCENT));
        PerkStats.register(CRAFT_LEVEL_STATS, "average", r -> PerkStats.num(CraftLevelStats.averageShare(r) * 100, Unit.PERCENT));
    }
}
