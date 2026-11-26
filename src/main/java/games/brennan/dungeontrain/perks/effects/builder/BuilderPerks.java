package games.brennan.dungeontrain.perks.effects.builder;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;

/**
 * Declares the Builder's built perks. Drift Gift Repaid is live ({@link DriftGiftPayout},
 * {@link DriftGiftContainerEvents}). Own Carriage Return and Drift Chance stay Coming soon through
 * {@code BuiltPerks.LOCKED}; Own Carriage Return's first build waits on its redesign (pick whether
 * drifting carriages favour your own builds or other players') and is not in this tree.
 */
public final class BuilderPerks {

    private BuilderPerks() {}

    public static void declare() {
        BuiltPerks.markBuilt(DriftGiftPayout.ID);
        PerkStats.register(DriftGiftPayout.ID, "repaid", r -> PerkStats.num(DriftGiftPayout.ratePercent(Math.max(1, r)), Unit.PERCENT));
    }
}
