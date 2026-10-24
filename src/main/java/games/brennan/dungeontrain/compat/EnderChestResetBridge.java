package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.enderchestpersistence.AppDataDir;
import games.brennan.enderchestpersistence.ConfigDir;
import games.brennan.enderchestpersistence.EcpConfig;
import games.brennan.enderchestpersistence.EnderChestStore;
import games.brennan.enderchestpersistence.StoreLocation;
import games.brennan.enderchestpersistence.StoreMode;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and clears the player's persistent Ender Chest stash for the Video Tools profile reset.
 *
 * <p>EnderChestPersistence (ECP) keeps one file per player outside any world save, holding every
 * game-mode and difficulty slot at once (see {@link EnderChestLockBridge} for who gets which slot).
 * That file survives a new world, which is exactly why the reset has to name it: a stash that
 * follows you into the "fresh" world is not a fresh world.</p>
 *
 * <p>The hard reference to ECP's seam lives only in here, behind {@code catch (Throwable)} —
 * mirroring {@link EnderChestLockBridge}. An ECP build that relocates {@code file}/{@code evict}
 * degrades to "Ender Chest not listed, not cleared" instead of taking the reset screen down with
 * it. {@code enderchestpersistence_min_version} currently equals the pinned version, so the seam is
 * guaranteed at the floor; the guard is for the version after next.</p>
 */
public final class EnderChestResetBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EnderChestResetBridge() {}

    /**
     * The two folders a stash can be in.
     *
     * @param store    where ECP keeps every player's stash this launch: outside the instance (the OS's
     *                 app-data folder) on a client by default since ECP 0.3.0, else {@code instance}
     * @param instance {@code config/enderchestpersistence/}, where ECP stored before 0.3.0 and still
     *                 does under {@code inside}, on a dedicated server, or when the outside folder
     *                 can't be written
     */
    public record EcpDirs(Path store, Path instance) {
        /** Whether the store is somewhere other than the instance folder. */
        public boolean storesOutside() {
            return !store.toAbsolutePath().normalize().equals(instance.toAbsolutePath().normalize());
        }
    }

    /**
     * ECP's store folders, for {@code DungeonTrainBackup} to archive. Empty on any ECP-side failure,
     * so a moved seam degrades to "stash not backed up" rather than a failed registration.
     *
     * <p>Works out the store from ECP's own inputs instead of asking {@code StoreLocation.dir()}: that
     * only answers correctly after ECP's server-start {@code init}, but this is read from DT's mod
     * constructor (and the title-screen restore runs before any server starts). Before init it
     * answers the instance folder, which is how every backup came to hold the abandoned folder and
     * not the player's chest.</p>
     */
    public static Optional<EcpDirs> storeDirs() {
        try {
            Path configDir = ConfigDir.get();
            boolean dedicated = FMLEnvironment.dist.isDedicatedServer();
            Path store = resolveStore(EcpConfig.readStoreMode(configDir), dedicated, configDir, AppDataDir.resolve());
            return Optional.of(new EcpDirs(store, configDir.resolve(StoreLocation.DIR_NAME)));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] EnderChestResetBridge: could not resolve ECP stash folder", t);
            return Optional.empty();
        }
    }

    /**
     * Where ECP's {@code StoreLocation.resolve} puts the store, minus its "outside isn't writable"
     * fallback (which needs to create the folder; a backup must not). That fallback lands in the
     * instance folder, which is archived too.
     */
    public static Path resolveStore(StoreMode mode, boolean dedicatedServer, Path configDir, Path appDataRoot) {
        if (mode == StoreMode.OUTSIDE && !dedicatedServer) {
            return appDataRoot.resolve(StoreLocation.DIR_NAME);
        }
        return configDir.resolve(StoreLocation.DIR_NAME);
    }

    /** True when this player has a stash file on disk. False on any ECP-side failure. */
    public static boolean hasStash(UUID uuid) {
        try {
            return Files.isRegularFile(EnderChestStore.file(uuid));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] EnderChestResetBridge: could not read ECP stash path", t);
            return false;
        }
    }

    /**
     * Drop the stash. Evicts ECP's in-memory cache <em>before</em> unlinking, because ECP flushes
     * that cache on server stop — deleting the file alone would let the next flush write the whole
     * stash straight back over the deletion.
     *
     * @return true when a file was actually removed
     */
    public static boolean clear(UUID uuid) throws Exception {
        EnderChestStore.evict(uuid);
        return Files.deleteIfExists(EnderChestStore.file(uuid));
    }
}
