package games.brennan.dungeontrain.client.live;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The report keys the relay hands this client with each {@code /discord} link code ({@code POST /community/link/start}),
 * kept so a Live chat bot report ({@link LiveBotReport}) can prove it comes from the player whose Discord is linked.
 *
 * <p>Two per player uuid: {@code current}, the key of the link that is in place, and {@code pending}, the key of the
 * newest code, which only becomes the link's key if the player runs that code with {@code /dtlink}. A report tries
 * {@code current} first, then {@code pending}; whichever the relay accepts becomes {@code current}.</p>
 *
 * <p>One small JSON file, {@code <gameDir>/dungeontrain/.live-report-key.json} ({@link PlayerDataPaths#REPORT_KEY}),
 * beside the install key. Never logged, never sent anywhere but the relay. Owner read/write only where the disk has
 * POSIX permissions (on Windows the user profile's own ACL applies). Dungeon Backup leaves it out of its archives:
 * those zips get handed over for help, and a lost key costs only another {@code /discord}.</p>
 *
 * <p>It used to be {@code config/dungeontrain/live-report-key.json}, and config folders travel in modpack exports and
 * support zips. The first read of a session moves a key found there and deletes the old file; a failed write leaves
 * the old file for the next try. No-throw: a file fault reads as no key.</p>
 */
public final class ReportKeyStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** The pre-move name, under {@code config/dungeontrain/}. */
    static final String LEGACY_FILE = "live-report-key.json";
    static final String FILE = PlayerDataPaths.REPORT_KEY;
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern UUID = Pattern.compile("^[0-9a-f]{32}$");

    /** A player's keys: either may be null. */
    public record Keys(@Nullable String current, @Nullable String pending) {
        static final Keys NONE = new Keys(null, null);

        public boolean any() {
            return current != null || pending != null;
        }
    }

    @Nullable private static Path override;
    @Nullable private static Path legacyOverride;
    private static boolean migrated;

    private ReportKeyStore() {}

    /** Whether {@code key} has the relay's shape (base64url of 32 bytes). Pure. */
    static boolean validKey(@Nullable String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** {@code uuid}'s keys, or {@link Keys#NONE}. */
    public static synchronized Keys load(String uuid) {
        if (!validUuid(uuid)) return Keys.NONE;
        JsonObject all = read();
        JsonElement el = all.get(uuid);
        if (el == null || !el.isJsonObject()) return Keys.NONE;
        JsonObject o = el.getAsJsonObject();
        return new Keys(keyOf(o.get("current")), keyOf(o.get("pending")));
    }

    /** A new link code was minted with {@code key}: keep it as {@code pending}. */
    public static synchronized void savePending(String uuid, @Nullable String key) {
        if (!validUuid(uuid) || !validKey(key)) return;
        Keys was = load(uuid);
        write(uuid, new Keys(was.current(), key));
    }

    /** The relay accepted {@code key}: it is now {@code current} (and no longer pending). */
    public static synchronized void promote(String uuid, String key) {
        if (!validUuid(uuid) || !validKey(key)) return;
        Keys was = load(uuid);
        if (key.equals(was.current())) return;
        write(uuid, new Keys(key, key.equals(was.pending()) ? null : was.pending()));
    }

    /** Test seam: read and write {@code file}, with no old location. Null restores both. */
    static synchronized void useFile(@Nullable Path file) {
        useFiles(file, null);
    }

    /** Test seam: read and write {@code file}, migrating from {@code legacy}. Also forgets this session's migration. */
    static synchronized void useFiles(@Nullable Path file, @Nullable Path legacy) {
        override = file;
        legacyOverride = legacy;
        migrated = false;
    }

    private static boolean validUuid(@Nullable String uuid) {
        return uuid != null && UUID.matcher(uuid).matches();
    }

    @Nullable
    private static String keyOf(@Nullable JsonElement el) {
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) return null;
        String s = el.getAsString();
        return validKey(s) ? s : null;
    }

    private static Path path() {
        return override != null ? override : PlayerDataPaths.root().resolve(FILE);
    }

    @Nullable
    private static Path legacyPath() {
        if (override != null) return legacyOverride;
        return FMLPaths.CONFIGDIR.get().resolve(DungeonTrain.MOD_ID).resolve(LEGACY_FILE);
    }

    /** Once a session: a key still in {@code config/} moves to the new file, then the old one goes. */
    private static void migrateOnce() {
        if (migrated) return;
        migrated = true;
        Path old = legacyPath();
        if (old == null || !Files.isRegularFile(old)) return;
        try {
            Path p = path();
            if (!Files.isRegularFile(p)) {
                JsonObject keys = parse(old);
                if (keys != null && !keys.isEmpty() && !writeAll(keys)) return; // keep the old file for the next try
            }
            Files.deleteIfExists(old);
            Files.deleteIfExists(old.resolveSibling(LEGACY_FILE + ".tmp"));
            LOGGER.info("[DungeonTrain] Live report key: moved out of the config folder");
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Live report key: could not move the old key file ({})", e.getClass().getSimpleName());
        }
    }

    /** {@code file}'s JSON object, or null when it can't be read as one. */
    @Nullable
    private static JsonObject parse(Path file) {
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject read() {
        migrateOnce();
        try {
            Path p = path();
            if (!Files.isRegularFile(p)) return new JsonObject();
            JsonElement root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
            return root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Live report key: could not read the key file ({})", e.getClass().getSimpleName());
            return new JsonObject();
        }
    }

    private static void write(String uuid, Keys keys) {
        JsonObject all = read();
        JsonObject o = new JsonObject();
        if (keys.current() != null) o.addProperty("current", keys.current());
        if (keys.pending() != null) o.addProperty("pending", keys.pending());
        JsonObject next = all.deepCopy();
        next.add(uuid, o);
        writeAll(next);
    }

    /** Replace the file with {@code all}, owner-only where the disk allows. Whether it was written. */
    private static boolean writeAll(JsonObject all) {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(FILE + ".tmp");
            Files.deleteIfExists(tmp);
            boolean posix = Files.getFileStore(p.getParent()).supportsFileAttributeView("posix");
            if (posix) {
                FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(OWNER_ONLY);
                Files.createFile(tmp, attr);
            }
            Files.writeString(tmp, all.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (posix) Files.setPosixFilePermissions(p, OWNER_ONLY);
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Live report key: could not save the key file ({})", e.getClass().getSimpleName());
            return false;
        }
    }
}
