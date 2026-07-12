package games.brennan.dungeontrain.fabric;

import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.platform.DtRegistrar;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Fabric {@link DtRegistrar} impl, discovered via {@code ServiceLoader} (see
 * {@code META-INF/services/games.brennan.dungeontrain.platform.DtRegistrar}).
 *
 * <p>Where NeoForge's impl defers registration to a mod-bus event, Fabric's
 * vanilla registries are open during mod init (the exact window {@code register}
 * runs — driven by the {@code Mod*.init()} static-field forcing in
 * {@code DungeonTrainFabric}), so this registers the value <em>immediately</em>
 * into the vanilla {@code Registry<T>} resolved from {@code registryKey}, and hands
 * back a {@link Supplier} of the now-resolved value (matching the deferred-resolution
 * contract of NeoForge's {@code DeferredHolder}). All DT registry keys are vanilla
 * ({@code ITEM}, {@code BLOCK}, {@code CREATIVE_MODE_TAB}, {@code FEATURE},
 * {@code MOB_EFFECT}, {@code SOUND_EVENT}, {@code TRIGGER_TYPE}), so the root-registry
 * lookup always resolves.</p>
 */
public final class FabricRegistrar implements DtRegistrar {

    public FabricRegistrar() {}

    @Override
    public <T, I extends T> Supplier<I> register(
            ResourceKey<? extends Registry<T>> registryKey, String name, Supplier<I> factory) {
        Registry<T> registry = registryFor(registryKey);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, name);
        I value = factory.get();
        Registry.register(registry, id, value);
        return () -> value;
    }

    @Override
    // FQN net.minecraft.core.Holder: DtRegistrar's inherited nested ServiceLoader Holder
    // class shadows the simple name here (same reason NeoForgeRegistrar does it).
    public <T, I extends T> net.minecraft.core.Holder<T> registerForHolder(
            ResourceKey<? extends Registry<T>> registryKey, String name, Supplier<I> factory) {
        Registry<T> registry = registryFor(registryKey);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, name);
        I value = factory.get();
        // Vanilla register-and-hand-back-a-Holder primitive — the Fabric analogue of
        // NeoForge's DeferredHolder (see DtRegistrar#registerForHolder Javadoc).
        return Registry.registerForHolder(registry, id, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> Registry<T> registryFor(ResourceKey<? extends Registry<T>> registryKey) {
        Registry<?> registry = BuiltInRegistries.REGISTRY.get(registryKey.location());
        if (registry == null) {
            throw new IllegalStateException(
                "No vanilla registry for key " + registryKey.location()
                    + " — DtRegistrar only supports vanilla Registry<T> keys.");
        }
        return (Registry<T>) registry;
    }
}
