package games.brennan.dungeontrain.client.live.life;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Keeps {@link LiveLifeStats} per life, so the death screen's Live Eye pages can tell the whole life's
 * broadcast however many stints it took. Keyed by the server's life id ({@code LiveLife}), which holds
 * across relogs and changes on respawn, so a new life starts with empty counters on its own.
 *
 * <p>Events come from the render thread, the uploader thread and the chat poll; every update swaps an
 * immutable record under one lock. The latest {@link #KEEP_LIVES} lives are saved to
 * {@code dungeontrain/live-life-stats.json} (throttled, plus at every stint end), so a relog or a game
 * restart within a life keeps its counters. A stint a crash left running is closed at its last
 * heartbeat on load.</p>
 */
public final class LiveLifeTracker {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type LIST_TYPE = new TypeToken<List<LiveLifeStats>>() {}.getType();

    static final int KEEP_LIVES = 4;
    private static final long SAVE_EVERY_MS = 30_000L;

    private static final Object LOCK = new Object();
    private static final Map<String, LiveLifeStats> LIVES = new LinkedHashMap<>();
    @Nullable private static Path file;
    private static boolean fileSet;
    private static boolean loaded;
    private static long savedAtMs;

    private LiveLifeTracker() {}

    /** Where the counters persist, under the game directory. Only the first call counts. */
    public static void initOnce(@Nullable Path gameDir) {
        synchronized (LOCK) {
            if (fileSet) return;
            fileSet = true;
            file = gameDir == null ? null : gameDir.resolve("dungeontrain").resolve("live-life-stats.json");
            loaded = false;
            LIVES.clear();
        }
    }

    /** The counters of {@code lifeId}, empty when this life never streamed. */
    public static LiveLifeStats get(String lifeId) {
        String id = lifeId == null ? "" : lifeId;
        synchronized (LOCK) {
            ensureLoaded();
            LiveLifeStats s = LIVES.get(id);
            return s != null ? s : LiveLifeStats.empty(id);
        }
    }

    /** Apply {@code event} to {@code lifeId}'s counters. {@code saveNow} writes the file at once. */
    public static LiveLifeStats update(String lifeId, UnaryOperator<LiveLifeStats> event, boolean saveNow, long now) {
        String id = lifeId == null ? "" : lifeId;
        synchronized (LOCK) {
            ensureLoaded();
            LiveLifeStats before = LIVES.get(id);
            LiveLifeStats after = event.apply(before != null ? before : LiveLifeStats.empty(id));
            if (after == before) return after;
            LIVES.remove(id);
            LIVES.put(id, after); // most recent last
            while (LIVES.size() > KEEP_LIVES) LIVES.remove(LIVES.keySet().iterator().next());
            if (saveNow || now - savedAtMs >= SAVE_EVERY_MS) save(now);
            return after;
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            List<LiveLifeStats> saved = parse(Files.readString(file, StandardCharsets.UTF_8));
            for (LiveLifeStats s : saved) LIVES.put(s.lifeId(), s.closeStale());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] live life stats unreadable, starting fresh: {}", e.toString());
        }
    }

    /** The saved file's lives, or empty when it is malformed. Package-private for tests. */
    static List<LiveLifeStats> parse(String json) {
        try {
            List<LiveLifeStats> out = GSON.fromJson(json, LIST_TYPE);
            if (out == null) return List.of();
            List<LiveLifeStats> clean = new ArrayList<>();
            for (LiveLifeStats s : out) if (s != null) clean.add(s);
            return clean;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    static String serialize(List<LiveLifeStats> lives) {
        return GSON.toJson(lives, LIST_TYPE);
    }

    private static void save(long now) {
        savedAtMs = now;
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, serialize(new ArrayList<>(LIVES.values())), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] could not save live life stats: {}", e.toString());
        }
    }

    /** Tests only: forget everything. */
    static void clearForTest() {
        synchronized (LOCK) {
            LIVES.clear();
            loaded = true;
            fileSet = true;
            file = null;
            savedAtMs = 0;
        }
    }
}
