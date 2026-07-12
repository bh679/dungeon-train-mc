package games.brennan.dungeontrain.registry;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.registry.effect.FreePlayEffect;
import games.brennan.dungeontrain.registry.effect.WarmthOfTheFireEffect;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Mod-side {@link MobEffect} registry. Mirrors {@link ModSounds}'s
 * {@link DeferredRegister} pattern.
 */
public final class ModMobEffects {

    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
        DeferredRegister.create(Registries.MOB_EFFECT, DtCore.MOD_ID);

    public static final DeferredHolder<MobEffect, WarmthOfTheFireEffect> WARMTH_OF_THE_FIRE =
        MOB_EFFECTS.register("warmth_of_the_fire", WarmthOfTheFireEffect::new);

    /** "Free Play" — run-scoped marker shown while the run is unranked. */
    public static final DeferredHolder<MobEffect, FreePlayEffect> FREE_PLAY =
        MOB_EFFECTS.register("free_play", FreePlayEffect::new);

    private ModMobEffects() {}

    /** Call from the mod constructor to attach the {@link DeferredRegister} to the mod-event bus. */
    public static void register(IEventBus modBus) {
        MOB_EFFECTS.register(modBus);
    }
}
