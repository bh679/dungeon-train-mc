package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.platform.DtRegistrar;
import games.brennan.dungeontrain.registry.effect.FreePlayEffect;
import games.brennan.dungeontrain.registry.effect.WarmthOfTheFireEffect;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;

/**
 * Mod-side {@link MobEffect} registry. Registered via {@link DtRegistrar}
 * (loader-neutral) — see {@link ModSounds} /
 * {@link games.brennan.dungeontrain.advancement.ModAdvancementTriggers} for the
 * pattern and the root attach timing.
 *
 * <p>{@code Registries.MOB_EFFECT} is a vanilla registry key, and every caller
 * consumes each entry as a {@code Holder<MobEffect>} — {@code new
 * MobEffectInstance(HOLDER, …)} and {@code Holder.is(HOLDER)} — so registration
 * routes through {@link DtRegistrar#registerForHolder}, which hands back a
 * vanilla {@link Holder} (the NeoForge impl returns the underlying
 * {@code DeferredHolder}, which IS a {@code Holder}; a Fabric impl returns the
 * {@code Holder} from {@code Registry.registerForHolder}). The former
 * {@code DeferredHolder.getId()} call sites now use vanilla
 * {@code Holder.is(Holder)} instead.</p>
 */
public final class ModMobEffects {

    public static final Holder<MobEffect> WARMTH_OF_THE_FIRE = DtRegistrar.get().registerForHolder(
        Registries.MOB_EFFECT, "warmth_of_the_fire", WarmthOfTheFireEffect::new);

    /** "Free Play" — run-scoped marker shown while the run is unranked. */
    public static final Holder<MobEffect> FREE_PLAY = DtRegistrar.get().registerForHolder(
        Registries.MOB_EFFECT, "free_play", FreePlayEffect::new);

    private ModMobEffects() {}

    /** Call from the mod constructor to force this class's static fields (and their registrations) to run. */
    public static void init() {}
}
