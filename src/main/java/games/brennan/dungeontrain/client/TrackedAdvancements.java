package games.brennan.dungeontrain.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The advancements the local player has chosen to <em>track</em> — the ones they want to be told
 * about the moment this life rules them out (see {@link LifeDisqualificationClient}).
 *
 * <p>A client-only, per-install choice, so it lives beside the other player data at
 * {@code <gameDir>/dungeontrain/user/tracked-advancements.json} ({@link PlayerDataPaths} —
 * never under {@code config/}, which pack updates replace). A flat JSON array of advancement ids;
 * loaded once, written on every toggle. Persists across lives and worlds — tracking is a
 * preference, not run state.</p>
 *
 * <p>Only ids the server can disqualify are trackable
 * ({@link games.brennan.dungeontrain.compat.AdvancementHintText#isTrackable}); toggling anything
 * else is a no-op so a stale file can't grow junk.</p>
 *
 * <p>All access is on the client thread (screen clicks, packet {@code enqueueWork}).</p>
 */
public final class TrackedAdvancements {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final String FILE_NAME = "tracked-advancements.json";

    private static Set<ResourceLocation> tracked;
    /** Bumped on every change so per-widget tooltip caches know to re-split. */
    private static int revision;

    private TrackedAdvancements() {}

    public static boolean isTracked(ResourceLocation id) {
        return id != null && load().contains(id);
    }

    /** Flip {@code id}'s tracked state and persist. Returns the new state; untrackable ids stay false. */
    public static boolean toggle(ResourceLocation id) {
        if (!games.brennan.dungeontrain.compat.AdvancementHintText.isTrackable(id)) return false;
        Set<ResourceLocation> next = new LinkedHashSet<>(load());
        boolean nowTracked = !next.remove(id);
        if (nowTracked) next.add(id);
        tracked = Set.copyOf(next);
        revision++;
        save(path(), tracked);
        return nowTracked;
    }

    public static int revision() {
        return revision;
    }

    private static Set<ResourceLocation> load() {
        if (tracked == null) {
            tracked = read(path());
        }
        return tracked;
    }

    private static Path path() {
        return PlayerDataPaths.dir(PlayerDataPaths.USER).resolve(FILE_NAME);
    }

    /** Parse the store at {@code file}; a missing or malformed file reads as "nothing tracked". */
    static Set<ResourceLocation> read(Path file) {
        if (!Files.isRegularFile(file)) return Set.of();
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) return Set.of();
            Set<ResourceLocation> out = new LinkedHashSet<>();
            for (JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) continue;
                ResourceLocation id = ResourceLocation.tryParse(el.getAsString());
                if (id != null) out.add(id);
            }
            return Set.copyOf(out);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not read {} — treating as empty: {}", file, e.toString());
            return Set.of();
        }
    }

    static void save(Path file, Set<ResourceLocation> ids) {
        JsonArray arr = new JsonArray();
        ids.forEach(id -> arr.add(id.toString()));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(arr), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not write {}: {}", file, e.toString());
        }
    }
}
