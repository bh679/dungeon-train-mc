package games.brennan.dungeontrain.platform.neoforge;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.platform.DtRegistrar;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * NeoForge {@link DtRegistrar} impl, discovered via {@code ServiceLoader} (see
 * {@code META-INF/services/games.brennan.dungeontrain.platform.DtRegistrar}).
 *
 * <p>Lazily creates one {@code DeferredRegister} per distinct registry key the
 * first time a converted registry class calls {@link #register}, and defers
 * attaching any of them to the mod-event bus until {@link #attachAll} is
 * called — from {@link DungeonTrain}'s constructor, at exactly the code
 * position the individual {@code Mod*.register(modBus)} calls used to occupy,
 * so registration timing is unchanged.</p>
 *
 * <p>The register map is held in a {@code static} field, not an instance
 * field: {@code ServiceLoader} constructs its own instance via reflection on
 * every {@code DtRegistrar.get()} resolution path, so per-instance state would
 * not be shared with {@link #attachAll} (a plain static method, not reached
 * through the service interface). Statics sidestep that — every instance
 * (service-loaded or otherwise) reads/writes the same map.</p>
 */
public final class NeoForgeRegistrar implements DtRegistrar {

    private static final Map<ResourceKey<? extends Registry<?>>, DeferredRegister<?>> REGISTERS = new LinkedHashMap<>();

    public NeoForgeRegistrar() {}

    @Override
    public <T, I extends T> Supplier<I> register(ResourceKey<? extends Registry<T>> registryKey, String name, Supplier<I> factory) {
        return registerStatic(registryKey, name, factory);
    }

    @SuppressWarnings("unchecked")
    private static <T, I extends T> Supplier<I> registerStatic(ResourceKey<? extends Registry<T>> registryKey, String name, Supplier<I> factory) {
        DeferredRegister<T> register = (DeferredRegister<T>) REGISTERS.computeIfAbsent(
            registryKey, key -> DeferredRegister.create((ResourceKey<? extends Registry<T>>) key, DtCore.MOD_ID));
        return register.register(name, factory);
    }

    /**
     * Attach every {@code DeferredRegister} created so far to {@code modBus}.
     * Call once, from the mod constructor, after every converted registry
     * class has been touched (forcing its static field initializers, and thus
     * its {@link #register} calls, to run).
     */
    public static void attachAll(IEventBus modBus) {
        for (DeferredRegister<?> register : REGISTERS.values()) {
            register.register(modBus);
        }
    }
}
