package games.brennan.dungeontrain.perks.effects.alchemist;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkEffects;
import games.brennan.dungeontrain.perks.PerkOverflow;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;

/**
 * The Alchemist class (tree class {@code calchemist}), designed with Brennan 2026-11-26
 * ({@code docs/design/perks-alchemist.md}): {@link PotionStacking Stack Potions} (tier 1),
 * {@link PocketBrewing Standless Brewing} (tier 2, moved here from Warlock) and {@link Brewmaster}
 * (tier 3). Every chip is the effect's own maths.
 */
public final class AlchemistPerks {

    public static final String STACK_POTIONS = "stack-potions";
    public static final String STANDLESS_BREWING = "standless-brewing";
    public static final String BREWMASTER = "brewmaster";

    private AlchemistPerks() {}

    public static void declare() {
        BuiltPerks.markBuilt(STACK_POTIONS, STANDLESS_BREWING, BREWMASTER);
        PerkEffects.register(new PotionStacking());

        PerkStats.register(STACK_POTIONS, "stack", r -> PerkStats.num(PotionStacking.stackSizeFor(r), Unit.PLAIN));

        PerkStats.registerStepped(STANDLESS_BREWING, "bottle_slots",
            r -> PerkStats.num(PocketBrewingMath.bottleSlots(Math.max(1, r)), Unit.PLAIN));
        PerkStats.register(STANDLESS_BREWING, "brew_time",
            r -> PerkStats.num(PocketBrewingMath.brewSeconds(Math.max(1, r)), Unit.SECONDS));

        PerkStats.register(BREWMASTER, "level_always",
            r -> new Object[] {PerkOverflow.guaranteed(BrewmasterMath.percent(r))},
            r -> PerkOverflow.guaranteed(BrewmasterMath.percent(r)) > 0 ? PerkStats.labelOnly() : null);
        PerkStats.register(BREWMASTER, "level_chance",
            r -> new Object[] {PerkOverflow.guaranteed(BrewmasterMath.percent(Math.max(1, r))) + 1},
            r -> {
                int p = BrewmasterMath.percent(Math.max(1, r));
                if (PerkOverflow.remainder(p) == 0) return null;
                return PerkStats.num(PerkOverflow.remainder(p), Unit.PERCENT);
            });
    }
}
