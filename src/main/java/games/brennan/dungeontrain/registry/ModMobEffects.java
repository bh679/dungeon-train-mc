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
 *
 * <p><b>Stays on {@code DeferredRegister} (root), NOT converted to
 * {@link games.brennan.dungeontrain.platform.DtRegistrar}.</b> Although
 * {@code Registries.MOB_EFFECT} is a vanilla registry key (so registration
 * itself could route through {@code DtRegistrar}), every caller consumes the
 * entry as a {@code Holder<MobEffect>} — {@code new MobEffectInstance(HOLDER,…)}
 * and {@code Holder.is(…)} — which the {@code DeferredHolder} fields below
 * satisfy for free (a {@code DeferredHolder} IS a {@code Holder}), whereas
 * {@code DtRegistrar.register} returns only a bare {@code Supplier<T>}. Three
 * call sites additionally use NeoForge-specific {@code DeferredHolder.getId()}
 * (replaceable with vanilla {@code Holder.is(Holder)}, but incidental). None of
 * the five callers are on the Stage 4c core-loop critical path, so per the
 * Fabric-port annex this registry is left root and chipped around; a future
 * conversion would either widen {@code DtRegistrar} to hand back a
 * {@code Holder} for vanilla registries, or expose {@code ResourceKey<MobEffect>}
 * constants in {@code :common} resolved via {@code BuiltInRegistries} at use-site.</p>
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
