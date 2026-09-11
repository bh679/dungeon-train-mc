package games.brennan.dungeontrain.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The crash detector: one small file that says "a run is in progress in this world".
 *
 * <p>Written when the player joins a Dungeon Train play world and deleted on every clean exit —
 * quit to title, a fresh world, a graceful window close, or the run ending in death. If the game
 * starts and the file is still there, the last session did not end cleanly: that is the whole
 * detection, and it needs no crash log parsing. See {@code CrashRunTracker} for the hooks and
 * {@code CrashRecoveryPromptHandler} for what the title screen does about it.</p>
 *
 * <p>Lives at {@code <gameDir>/dungeontrain/session/run-state.json} — the instance root, so a
 * launcher replacing {@code config/} on a pack update can't take it (see {@link PlayerDataPaths}).</p>
 *
 * <p>Deliberately thin and Minecraft-free: every operation takes the data root explicitly so it is
 * drivable from JUnit with {@code @TempDir}, the same shape as {@link PlayerDataRecovery}. And every
 * operation is best-effort: an I/O or parse failure is logged and treated as "no state". This file
 * must never be the thing that stops the game reaching the menu, and a corrupt one must read as
 * "nothing to recover" rather than throw.</p>
 */
public final class CrashRunState {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Folder under the data root, and the file inside it. */
    public static final String SESSION_DIR = "session";
    public static final String FILE_NAME = "run-state.json";

    private static final String KEY_LEVEL_ID = "levelId";
    private static final String KEY_WORLD_NAME = "worldName";
    private static final String KEY_STARTED_AT = "startedAtEpochMs";
    private static final String KEY_STATUS = "status";

    /** Where the run stands: in progress, or already reopened once for salvage. */
    public enum Status {
        /** A run was live when the file was written. Surviving a restart means it crashed. */
        ACTIVE,
        /** The player accepted the offer and is (or was) back in the world to empty it out. */
        SALVAGING;

        static Status parse(String raw) {
            for (Status s : values()) {
                if (s.name().equalsIgnoreCase(raw)) return s;
            }
            return ACTIVE;
        }
    }

    /**
     * One run.
     *
     * @param levelId          the save folder name under {@code saves/} — what reopens the world
     * @param worldName        the display name, for the offer card
     * @param startedAtEpochMs when the world was joined
     * @param status           see {@link Status}
     */
    public record RunState(String levelId, String worldName, long startedAtEpochMs, Status status) {

        public RunState {
            if (levelId == null || levelId.isBlank()) throw new IllegalArgumentException("levelId is required");
            if (worldName == null) worldName = levelId;
            if (status == null) status = Status.ACTIVE;
        }

        /** The same run, flagged as reopened for salvage. */
        public RunState salvaging() {
            return new RunState(levelId, worldName, startedAtEpochMs, Status.SALVAGING);
        }
    }

    private CrashRunState() {}

    public static Path file(Path dataRoot) {
        return dataRoot.resolve(SESSION_DIR).resolve(FILE_NAME);
    }

    /** Record {@code state}. Best-effort: a failure is logged and the previous file (if any) stays. */
    public static boolean write(Path dataRoot, RunState state) {
        Path file = file(dataRoot);
        try {
            Files.createDirectories(file.getParent());
            JsonObject json = new JsonObject();
            json.addProperty(KEY_LEVEL_ID, state.levelId());
            json.addProperty(KEY_WORLD_NAME, state.worldName());
            json.addProperty(KEY_STARTED_AT, state.startedAtEpochMs());
            json.addProperty(KEY_STATUS, state.status().name());
            Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Crash recovery: couldn't record the run state at {}: {}", file, e.toString());
            return false;
        }
    }

    /** The recorded run, or empty when there is none — or the file can't be read or parsed. */
    public static Optional<RunState> read(Path dataRoot) {
        Path file = file(dataRoot);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            String levelId = json.has(KEY_LEVEL_ID) ? json.get(KEY_LEVEL_ID).getAsString() : null;
            if (levelId == null || levelId.isBlank()) {
                LOGGER.warn("[DungeonTrain] Crash recovery: run state at {} names no world; ignoring it.", file);
                return Optional.empty();
            }
            String worldName = json.has(KEY_WORLD_NAME) ? json.get(KEY_WORLD_NAME).getAsString() : levelId;
            long startedAt = json.has(KEY_STARTED_AT) ? json.get(KEY_STARTED_AT).getAsLong() : 0L;
            Status status = json.has(KEY_STATUS) ? Status.parse(json.get(KEY_STATUS).getAsString()) : Status.ACTIVE;
            return Optional.of(new RunState(levelId, worldName, startedAt, status));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Crash recovery: couldn't read the run state at {}; ignoring it: {}", file, e.toString());
            return Optional.empty();
        }
    }

    /** Forget the run. Idempotent; a failure is logged and the next clean exit tries again. */
    public static boolean clear(Path dataRoot) {
        Path file = file(dataRoot);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Crash recovery: couldn't clear the run state at {}: {}", file, e.toString());
            return false;
        }
    }
}
