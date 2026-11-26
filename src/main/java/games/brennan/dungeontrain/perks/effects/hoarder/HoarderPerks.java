package games.brennan.dungeontrain.perks.effects.hoarder;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkOverflow;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;
import net.minecraft.network.chat.Component;

import java.util.function.IntUnaryOperator;

/**
 * Declares the Hoarder's tier 3 perks ({@link HoarderLuck}, {@link HoarderLooting}). Both ride the overflow
 * curve, so each shows up to two chips: from 100% the level that is certain ("Luck I always"), then the
 * chance of the next level ("Luck I chance 5%", later "Luck II chance 5%").
 */
public final class HoarderPerks {

    private HoarderPerks() {}

    public static void declare() {
        BuiltPerks.markBuilt(HoarderLuck.PERK_ID, HoarderLooting.PERK_ID);
        overflowChips(HoarderLuck.PERK_ID, "luck", HoarderLuck::percent, HoarderPerks::roman);
        overflowChips(HoarderLooting.PERK_ID, "looting", HoarderLooting::percent, level -> level);
    }

    /**
     * The two overflow chips for {@code perkId}: {@code <prefix>_chance} (shown while there is a chance
     * left, or before the first level is certain) and {@code <prefix>_always} (shown from 100%). Each label
     * takes the level it names as {@code %s}, written by {@code label}.
     */
    static void overflowChips(String perkId, String prefix, IntUnaryOperator percentAt,
                              java.util.function.IntFunction<Object> label) {
        PerkStats.register(perkId, prefix + "_always",
            r -> new Object[] {label.apply(PerkOverflow.guaranteed(percentAt.applyAsInt(r)))},
            r -> PerkOverflow.guaranteed(percentAt.applyAsInt(r)) > 0 ? PerkStats.labelOnly() : null);
        PerkStats.register(perkId, prefix + "_chance",
            r -> new Object[] {label.apply(PerkOverflow.guaranteed(percentAt.applyAsInt(r)) + 1)},
            r -> {
                int p = percentAt.applyAsInt(r);
                if (PerkOverflow.guaranteed(p) > 0 && PerkOverflow.remainder(p) == 0) return null;
                return PerkStats.num(PerkOverflow.remainder(p), Unit.PERCENT);
            });
    }

    /** A Luck level as the game writes enchantment levels: I, II, III ... */
    private static Object roman(int level) {
        return Component.translatable("enchantment.level." + level);
    }
}
