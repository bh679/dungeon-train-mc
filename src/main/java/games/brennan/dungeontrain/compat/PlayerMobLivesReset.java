package games.brennan.dungeontrain.compat;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Clears PlayerMob's cross-world record of the player's past lives for the Video Tools profile
 * reset — the death history that later runs draw on to spawn Echoes and PlayerMobs of who you used
 * to be. Filming a first run with your own previous lives walking the train is not a first run.
 *
 * <p><b>This deletes another mod's file by hardcoded path.</b> PlayerMob's {@code GlobalLifeStore}
 * exposes no seam we can use here: its path field and {@code save}/{@code load} are private, and
 * {@code get()} wants a live {@code MinecraftServer}, which does not exist at the title screen. If
 * PMOB ever grows a {@code file(UUID)}/{@code evict} pair like EnderChestPersistence has, move this
 * onto it — see {@link EnderChestResetBridge} for the shape. Until then this fails safe: a
 * relocated file simply isn't found, the item isn't listed, and nothing else changes.</p>
 *
 * <p>No cache eviction needed, unlike every other store in the reset. {@code GlobalLifeStore}
 * caches per-{@code MinecraftServer} and reloads whenever it is handed a different one, so the next
 * world reads the now-missing file as an empty history. Nothing can flush the old records back from
 * the title screen.</p>
 *
 * <p>The history is a single shared file rather than one per player, so the {@code uuid} argument
 * is accepted only to match the other reset seams — on a singleplayer install the file is this
 * player's history.</p>
 */
public final class PlayerMobLivesReset {

    /** {@code <minecraft>/playermob/lives.dat} — PMOB's {@code DIR}/{@code FILE} constants. */
    private static final String DIR_NAME = "playermob";
    private static final String FILE_NAME = "lives.dat";

    private PlayerMobLivesReset() {}

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve(DIR_NAME).resolve(FILE_NAME);
    }

    /** True when a past-lives record exists on disk. */
    public static boolean hasLives(UUID uuid) {
        return Files.isRegularFile(file());
    }

    /**
     * Forget every past life.
     *
     * @return true when a file was actually removed
     */
    public static boolean clear(UUID uuid) throws Exception {
        return Files.deleteIfExists(file());
    }
}
