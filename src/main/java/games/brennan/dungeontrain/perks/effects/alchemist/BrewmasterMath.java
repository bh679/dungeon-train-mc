package games.brennan.dungeontrain.perks.effects.alchemist;

import games.brennan.dungeontrain.perks.PerkOverflow;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Brewmaster (Alchemist tier 3): potions the player brews come out stronger. Extra effect levels ride the
 * {@link PerkOverflow} curve at {@code 33⅓% × r} (Brennan, 2026-11-26): a 33% chance of +1 at 1 point,
 * +1 for sure at 3, +2 at 6. Rolled once per bottle when a brew finishes. Levels only, no duration.
 *
 * <p>The upgraded bottle keeps its potion (so its name, colour and further brewing still work) and gains
 * custom effects, each of the potion's effects at its level plus the bonus. {@link #merge} folds an effect
 * that appears twice into its strongest copy, so the potion reads and acts as "Strength II".</p>
 */
public final class BrewmasterMath {

    private BrewmasterMath() {}

    /** The overflow percent at {@code rank}: 33, 66, 100, 133 … ({@code floor(100 r / 3)}). */
    public static int percent(int rank) {
        return rank <= 0 ? 0 : (100 * rank) / 3;
    }

    /** Extra levels for one bottle at {@code rank}. */
    public static int roll(int rank, RandomSource random) {
        return PerkOverflow.rollSteps(percent(rank), random);
    }

    /**
     * {@code stack} with each of its potion's effects raised by {@code extra} levels, or {@code stack}
     * itself when there is nothing to raise (no bonus, not a potion, or a potion with no effects).
     */
    public static ItemStack upgrade(ItemStack stack, int extra) {
        if (extra <= 0 || stack.isEmpty()) return stack;
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return stack;
        Optional<Holder<Potion>> potion = contents.potion();
        if (potion.isEmpty() || potion.get().value().getEffects().isEmpty()) return stack;
        List<MobEffectInstance> custom = new ArrayList<>(contents.customEffects());
        for (MobEffectInstance e : potion.get().value().getEffects()) {
            custom.add(new MobEffectInstance(e.getEffect(), e.getDuration(), e.getAmplifier() + extra,
                e.isAmbient(), e.isVisible(), e.showIcon()));
        }
        ItemStack out = stack.copy();
        out.set(DataComponents.POTION_CONTENTS, new PotionContents(potion, contents.customColor(), List.copyOf(custom)));
        return out;
    }

    /**
     * {@code effects} with every effect that appears more than once folded into its strongest copy: the
     * highest level, then the longest duration. Order follows each effect's first appearance. A list with
     * no repeats comes back as it is.
     */
    public static List<MobEffectInstance> merge(List<MobEffectInstance> effects) {
        if (effects.size() < 2) return effects;
        Map<Holder<MobEffect>, MobEffectInstance> best = new LinkedHashMap<>();
        boolean repeated = false;
        for (MobEffectInstance e : effects) {
            MobEffectInstance had = best.get(e.getEffect());
            if (had == null) {
                best.put(e.getEffect(), e);
                continue;
            }
            repeated = true;
            if (stronger(e, had)) best.put(e.getEffect(), e);
        }
        return repeated ? List.copyOf(best.values()) : effects;
    }

    static boolean stronger(MobEffectInstance a, MobEffectInstance b) {
        if (a.getAmplifier() != b.getAmplifier()) return a.getAmplifier() > b.getAmplifier();
        if (a.isInfiniteDuration() != b.isInfiniteDuration()) return a.isInfiniteDuration();
        return a.getDuration() > b.getDuration();
    }
}
