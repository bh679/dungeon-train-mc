package games.brennan.dungeontrain.client.live;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * The report keys the relay hands this client with each {@code /discord} link code ({@code POST /community/link/start}),
 * kept so a Live chat bot report ({@link LiveBotReport}) can prove it comes from the player whose Discord is linked.
 *
 * <p>Two per player uuid: {@code current}, the key of the link that is in place, and {@code pending}, the key of the
 * newest code, which only becomes the link's key if the player runs that code with {@code /dtlink}. A report tries
 * {@code current} first, then {@code pending}; whichever the relay accepts becomes {@code current}.</p>
 *
 * <p>One small JSON file in the config folder, never logged, never sent anywhere but the relay. Not part of any config
 * integrity check (those read only named {@code .toml} files). No-throw: a file fault reads as no key.</p>
 */
public final class ReportKeyStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String FILE = "live-report-key.json";
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

    /** Test seam: read and write {@code file} instead of the config folder's. Null restores it. */
    static synchronized void useFile(@Nullable Path file) {
        override = file;
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
        return override != null ? override : FMLPaths.CONFIGDIR.get().resolve(DungeonTrain.MOD_ID).resolve(FILE);
    }

    private static JsonObject read() {
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
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(FILE + ".tmp");
            Files.writeString(tmp, next.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Live report key: could not save the key file ({})", e.getClass().getSimpleName());
        }
    }
}
