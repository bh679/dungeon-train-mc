package games.brennan.dungeontrain.data;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * The secret {@link SealedJson} signs the player's progress files with: 32 random bytes made the
 * first time this install seals anything, kept at {@code <gameDir>/dungeontrain/.install-key}.
 *
 * <p>Inside the player-data root on purpose: Dungeon Backup archives that folder wholesale, so a
 * restored archive brings the key its files were sealed with. One key per install, never sent
 * anywhere.</p>
 *
 * <p>{@link #sealedBefore} is what the stores ask when they meet a file with no seal: an install that
 * had never sealed when this session started is upgrading, and its unsigned files are its own
 * history. One that had already sealed should not be meeting unsigned files, so those are kept but
 * not vouched for. The answer is fixed for the whole session, because the first write of the
 * session creates the key and every store read after it would otherwise see "sealed before".</p>
 *
 * <p>Honest limit: the player owns this file as much as the files it signs. It stops hand edits,
 * not someone who reads this code.</p>
 */
public final class InstallKey {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String FILE_NAME = ".install-key";
    private static final int KEY_BYTES = 32;

    private static volatile Supplier<Path> resolver = () -> PlayerDataPaths.root().resolve(FILE_NAME);
    private static volatile byte[] key;
    /** Did the key file exist when this session first looked? Decided once, on the first read. */
    private static volatile Boolean existedAtStart;

    private InstallKey() {}

    /** The key, created on first use. Never null; empty only when the file could not be written or read. */
    public static synchronized byte[] key() {
        byte[] loaded = key;
        if (loaded != null) return loaded;
        Path path = resolver.get();
        noteExisted(path);
        loaded = readKey(path);
        if (loaded.length == 0) loaded = createKey(path);
        key = loaded;
        return loaded;
    }

    /** Had this install already sealed something before this session began? */
    public static synchronized boolean sealedBefore() {
        noteExisted(resolver.get());
        return Boolean.TRUE.equals(existedAtStart);
    }

    private static void noteExisted(Path path) {
        if (existedAtStart == null) existedAtStart = Files.isRegularFile(path);
    }

    private static byte[] readKey(Path path) {
        if (!Files.isRegularFile(path)) return new byte[0];
        try {
            byte[] decoded = Base64.getDecoder().decode(Files.readString(path, StandardCharsets.US_ASCII).trim());
            if (decoded.length >= KEY_BYTES) return decoded;
            LOGGER.warn("[DungeonTrain] InstallKey: {} is too short; making a new one", path);
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("[DungeonTrain] InstallKey: couldn't read {}: {}", path, e.toString());
        }
        return new byte[0];
    }

    private static byte[] createKey(Path path) {
        byte[] fresh = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(fresh);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, Base64.getEncoder().encodeToString(fresh), StandardCharsets.US_ASCII);
            LOGGER.info("[DungeonTrain] InstallKey: created {}", path);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] InstallKey: couldn't write {}: {}", path, e.toString());
        }
        return fresh;
    }

    /** Tests only: keep the key under {@code dir}, starting from no key and no memory of one. */
    public static synchronized void useDirectoryForTests(Path dir) {
        resolver = () -> dir.resolve(FILE_NAME);
        key = null;
        existedAtStart = null;
    }

    /** Tests only: back to the game directory, forgetting everything. */
    public static synchronized void resetForTests() {
        resolver = () -> PlayerDataPaths.root().resolve(FILE_NAME);
        key = null;
        existedAtStart = null;
    }
}
