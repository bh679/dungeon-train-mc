package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.alchemy.Potion;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Mod-side {@link Potion} registry. Mirrors {@link ModMobEffects}'s
 * {@link DeferredRegister} pattern.
 */
public final class ModPotions {

    public static final DeferredRegister<Potion> POTIONS =
        DeferredRegister.create(Registries.POTION, DungeonTrain.MOD_ID);

    /**
     * Luck II, which vanilla never brews. Its base name is vanilla's {@code "luck"}, the way
     * {@code strong_leaping} reuses {@code "leaping"}, so it reads "Potion of Luck" with a
     * "Luck II" effect line and needs no lang keys. Half vanilla Luck's 5:00, as vanilla's
     * strong potions halve theirs. Luck II is what unlocks the bigger chest bonus and the 2x
     * vault reward ({@code LuckyBonusRoller}).
     */
    public static final DeferredHolder<Potion, Potion> STRONG_LUCK =
        POTIONS.register("strong_luck", () -> new Potion("luck", new MobEffectInstance(MobEffects.LUCK, 3000, 1)));

    private ModPotions() {}

    /** Call from the mod constructor to attach the {@link DeferredRegister} to the mod-event bus. */
    public static void register(IEventBus modBus) {
        POTIONS.register(modBus);
    }
}
