package games.brennan.dungeontrain.platform;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * Loader-neutral stand-in for NeoForge's {@code DeferredRegister}. Game logic
 * (moving to {@code :common} over the Fabric port) declares registry entries
 * here instead of touching {@code DeferredRegister} directly; a loader-specific
 * impl in the root module wraps the real registration mechanism.
 *
 * <p>Resolved once via {@link ServiceLoader}, mirroring {@link DtPlatform}: the
 * NeoForge module registers {@code NeoForgeRegistrar} via
 * {@code META-INF/services/games.brennan.dungeontrain.platform.DtRegistrar} in
 * its OWN resources (the impl is loader-specific, so it cannot live in
 * {@code :common}). A future Fabric module registers an equivalent impl over
 * Fabric's {@code Registry.register}.</p>
 *
 * <p>Entries are declared at class-init time (static field initializers), same
 * as the {@code DeferredRegister} fields they replace — the returned
 * {@link Supplier} resolves lazily, after the registry has been populated. The
 * NeoForge impl lazily creates one internal {@code DeferredRegister} per
 * distinct {@code registryKey} and defers attaching them to the mod-event bus
 * until the root module explicitly does so (same timing as today — see
 * {@code NeoForgeRegistrar.attachAll}).</p>
 *
 * <p>Only the plain "register a supplier under a name" shape is exposed —
 * NOT the specialized {@code DeferredRegister.Items} / {@code .Blocks}
 * convenience helpers (e.g. {@code registerSimpleBlock}), since no converted
 * registry class in this codebase used those. A class whose callers need
 * NeoForge {@code DeferredHolder}-specific API (e.g. {@code getId()}) stays on
 * {@code DeferredRegister} directly instead of converting — see
 * {@code ModMobEffects}.</p>
 */
public interface DtRegistrar {

    /**
     * Register {@code factory} under {@code name} in the registry identified by
     * {@code registryKey}. Returns a {@link Supplier} that resolves to the
     * created value once the registry event has fired — exactly the contract
     * NeoForge's {@code DeferredHolder}/{@code DeferredItem}/{@code DeferredBlock}
     * already satisfy (they ARE suppliers), so existing {@code .get()} call
     * sites are unaffected by the conversion.
     *
     * <p>Two type parameters (registry element type {@code T}, concrete
     * registered type {@code I extends T}) mirror NeoForge's own
     * {@code DeferredRegister<T>.register(String, Supplier<? extends I>)} —
     * several converted registry classes declare fields as the concrete
     * subtype (e.g. {@code Supplier<UniqueChestsOpenedTrigger>} against the
     * {@code CriterionTrigger<?>} registry), which a single-type-param
     * signature cannot express.</p>
     */
    <T, I extends T> Supplier<I> register(ResourceKey<? extends Registry<T>> registryKey, String name, Supplier<I> factory);

    /** The loader-provided singleton, resolved lazily on first use. */
    static DtRegistrar get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        static final DtRegistrar INSTANCE = load();

        private Holder() {}

        private static DtRegistrar load() {
            ServiceLoader<DtRegistrar> loader =
                ServiceLoader.load(DtRegistrar.class, DtRegistrar.class.getClassLoader());
            for (DtRegistrar impl : loader) {
                return impl;
            }
            throw new IllegalStateException(
                "No DtRegistrar implementation found via ServiceLoader — the loader module "
                    + "must provide META-INF/services/" + DtRegistrar.class.getName());
        }
    }
}
