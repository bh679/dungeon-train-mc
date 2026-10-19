package games.brennan.dungeontrain.compat.photo.album;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * The pictures of photographs kept in an Ender Chest, outside any world
 * ({@code <gameDir>/dungeontrain/stash_photos/<hash>.png}), so a photo stashed in one run still
 * shows its picture in the next run's world ({@link StashPhotos} lends it there).
 *
 * <p>Separate from {@code albums/images/} on purpose: {@link AlbumStore} deletes every picture no
 * album names, and a stashed photo is in no album. Never pruned — which pictures are still stashed
 * is only known per ECP slot, and each picture is a few kilobytes.</p>
 */
final class StashPhotoStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String DIR = "stash_photos";

    private static volatile StashPhotoStore instance;

    private final Path root;

    StashPhotoStore(Path root) {
        this.root = root;
    }

    static StashPhotoStore get() {
        StashPhotoStore store = instance;
        if (store == null) {
            synchronized (StashPhotoStore.class) {
                if (instance == null) {
                    instance = new StashPhotoStore(FMLPaths.GAMEDIR.get().resolve(PlayerDataPaths.ROOT_DIR).resolve(DIR));
                }
                store = instance;
            }
        }
        return store;
    }

    boolean has(String hash) {
        return AlbumImageHash.isHash(hash) && Files.exists(file(hash));
    }

    /** The PNG for {@code hash}, if it was ever stashed on this computer. */
    Optional<byte[]> image(String hash) {
        if (!has(hash)) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(file(hash)));
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Stashed photo {} unreadable: {}", hash, e.toString());
            return Optional.empty();
        }
    }

    /** Keep this PNG under {@code hash}; a picture already held is left as it is. False when it could not be written. */
    boolean put(String hash, byte[] png) {
        if (!AlbumImageHash.isHash(hash)) return false;
        Path file = file(hash);
        if (Files.exists(file)) return true;
        try {
            Files.createDirectories(root);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, png);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Stashed photo {} not saved: {}", hash, e.toString());
            return false;
        }
    }

    private Path file(String hash) {
        return root.resolve(hash + ".png");
    }
}
