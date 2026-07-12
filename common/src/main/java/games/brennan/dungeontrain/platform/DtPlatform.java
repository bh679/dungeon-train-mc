package games.brennan.dungeontrain.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import net.minecraft.server.MinecraftServer;

/**
 * Loader-neutral stand-in for the handful of NeoForge {@code ModList} /
 * {@code FMLEnvironment} / {@code FMLLoader} queries Dungeon Train's game logic
 * makes — "is mod X loaded?", "what version?", "am I on the client / a dedicated
 * server / in dev?". Sized to exactly those needs, no more.
 *
 * <p>Resolved once via {@link ServiceLoader}, mirroring
 * {@code games.brennan.dungeontrain.net.platform.DtNetSender}: the NeoForge
 * module registers {@code NeoForgePlatform} via
 * {@code META-INF/services/games.brennan.dungeontrain.platform.DtPlatform} in
 * its OWN resources (the impl is loader-specific, so it cannot live in
 * {@code :common}). A future Fabric module registers an equivalent impl over
 * {@code FabricLoader}.</p>
 */
public interface DtPlatform {

    /** Whether a mod with the given id is present. Mirrors {@code ModList.isLoaded}. */
    boolean isModLoaded(String modId);

    /** The version string of a loaded mod, or empty if it is not present. */
    Optional<String> getModVersion(String modId);

    /** Every loaded mod as a loader-neutral {@link DtModInfo}. */
    List<DtModInfo> getLoadedMods();

    /** True on the physical client (integrated server included). Mirrors {@code FMLEnvironment.dist.isClient}. */
    boolean isClient();

    /** True on a dedicated server. Mirrors {@code FMLEnvironment.dist.isDedicatedServer}. */
    boolean isDedicatedServer();

    /** True in a development / non-production runtime. Mirrors {@code !FMLEnvironment.production}. */
    boolean isDevelopmentEnvironment();

    /** The Minecraft instance directory (world saves, mods, logs). Mirrors {@code FMLPaths.GAMEDIR.get()}. */
    Path gameDir();

    /** The mod config directory ({@code <gameDir>/config}). Mirrors {@code FMLPaths.CONFIGDIR.get()}. */
    Path configDir();

    /**
     * The running {@link MinecraftServer}, or {@code null} when none is running (title screen,
     * between worlds). Mirrors {@code ServerLifecycleHooks.getCurrentServer()}.
     */
    MinecraftServer getCurrentServer();

    /** The loader-provided singleton, resolved lazily on first use. */
    static DtPlatform get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        static final DtPlatform INSTANCE = load();

        private Holder() {}

        private static DtPlatform load() {
            ServiceLoader<DtPlatform> loader =
                ServiceLoader.load(DtPlatform.class, DtPlatform.class.getClassLoader());
            for (DtPlatform impl : loader) {
                return impl;
            }
            throw new IllegalStateException(
                "No DtPlatform implementation found via ServiceLoader — the loader module "
                    + "must provide META-INF/services/" + DtPlatform.class.getName());
        }
    }
}
