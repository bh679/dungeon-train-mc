package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.enderchestpersistence.EnderChestStore;
import org.slf4j.Logger;

import java.nio.file.Files;
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
