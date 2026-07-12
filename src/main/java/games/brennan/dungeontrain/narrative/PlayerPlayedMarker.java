package games.brennan.dungeontrain.narrative;
import games.brennan.dungeontrain.DtCore;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Per-installation, per-player record of:
 * <ol>
 *   <li>"has this player been welcomed by the mod before, anywhere?"
 *       (file existence)</li>
 *   <li>How many NEW_WORLD scenarios this player has been through
 *       (counter — drives the every-second-world alternation).</li>
 *   <li>How many JOINED_WORLD scenarios this player has been through
 *       (counter — drives the alternating-from-first joined cadence).</li>
 *   <li>Which Nether/End welcome-book {@code (book, variant)} tuples this
 *       player has already been shown — a one-each "playlist" set that drives
 *       the dimension-welcome cycle in {@code StartingBookEvents}; once a
 *       dimension's whole pool is in this set, new runs in that dimension
 *       fall through to the lifecycle welcome.</li>
 * </ol>
 *
 * <p>Backed by a tiny {@link Properties} file at
 * {@code <FMLPaths.GAMEDIR>/dungeontrain/players_seen/<uuid>.properties}.
 * Location is per-Minecraft-installation rather than per-world: a player
 * hopping between singleplayer worlds is marked once, but a player who
 * moves to a different machine looks "new" again — an acceptable trade-off
 * for narrative flavour state.</p>
 *
 * <p>Backwards-compat: an earlier dev iteration of this class wrote a
 * zero-byte {@code <uuid>.flag} file as the "has played" signal. We treat
 * such a flag as {@link #hasPlayed} = true; the next increment will write
 * the new {@code .properties} form alongside it.</p>
 */
public final class PlayerPlayedMarker {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String MOD_DIR = DtCore.MOD_ID;
    private static final String PLAYERS_DIR = "players_seen";
    private static final String PROPS_EXT = ".properties";
    /** Legacy flag-file extension from earlier dev iterations of this class. */
    private static final String LEGACY_FLAG_EXT = ".flag";

    private static final String KEY_NEW_WORLD = "new_world_count";
    private static final String KEY_JOINED_WORLD = "joined_world_count";
    /** Comma-joined set of seen dimension-welcome keys (see {@code StartingBookFactory.dimKey}). */
    private static final String KEY_SEEN_DIMENSION = "seen_dimension_variants";

    private PlayerPlayedMarker() {}

    /**
     * True when the player has been welcomed before on this installation
     * — i.e. the marker file (or the legacy flag) exists. Returns
     * {@code false} on any IO error.
     */
    public static boolean hasPlayed(UUID uuid) {
        try {
            return Files.exists(propsPath(uuid)) || Files.exists(legacyFlagPath(uuid));
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] PlayerPlayedMarker: exists() failed for {} — treating as new", uuid, e);
            return false;
        }
    }

    /**
     * Idempotently ensure the marker file exists. Safe to call every
     * welcome strike. Logs (does not throw) on IO failure.
     */
    public static void markPlayed(UUID uuid) {
        State state = read(uuid);
        write(uuid, state);
    }

    /** Current new-world counter for the player (0 when never marked). */
    public static int newWorldCount(UUID uuid) {
        return read(uuid).newWorldCount;
    }

    /** Current joined-world counter (0 when never marked). */
    public static int joinedWorldCount(UUID uuid) {
        return read(uuid).joinedWorldCount;
    }

    /**
     * Increment the new-world counter (+1). Creates the marker file if
     * missing so {@link #hasPlayed} flips to true on the next read.
     */
    public static void incrementNewWorldCount(UUID uuid) {
        State state = read(uuid);
        state.newWorldCount++;
        write(uuid, state);
    }

    /** Increment the joined-world counter (+1). Same write semantics as new. */
    public static void incrementJoinedWorldCount(UUID uuid) {
        State state = read(uuid);
        state.joinedWorldCount++;
        write(uuid, state);
    }

    /**
     * Snapshot of the dimension-welcome {@code (book, variant)} keys this
     * player has already been shown (empty when never marked). Keys are
     * produced by {@code StartingBookFactory.dimKey}. Returns a defensive
     * copy callers may use as a fast {@code contains} set.
     */
    public static Set<String> seenDimensionVariants(UUID uuid) {
        return new LinkedHashSet<>(read(uuid).seenDimensionVariants);
    }

    /**
     * Record that {@code key} (a {@code StartingBookFactory.dimKey}) has been
     * shown to this player. Idempotent — only rewrites the file when the key
     * was not already present; creates the marker file if missing.
     */
    public static void markDimensionVariantSeen(UUID uuid, String key) {
        if (key == null || key.isEmpty()) return;
        State state = read(uuid);
        if (state.seenDimensionVariants.add(key)) {
            write(uuid, state);
        }
    }

    /**
     * Clear this player's seen dimension-welcome set so the Nether/End cycle
     * starts over (used by {@code /narrative startingbook reset}). No write
     * when the set is already empty.
     */
    public static void clearDimensionVariantsSeen(UUID uuid) {
        State state = read(uuid);
        if (!state.seenDimensionVariants.isEmpty()) {
            state.seenDimensionVariants.clear();
            write(uuid, state);
        }
    }

    /** Visible for tests. */
    public static Path propsPath(UUID uuid) {
        return FMLPaths.GAMEDIR.get()
            .resolve(MOD_DIR)
            .resolve(PLAYERS_DIR)
            .resolve(uuid.toString() + PROPS_EXT);
    }

    private static Path legacyFlagPath(UUID uuid) {
        return FMLPaths.GAMEDIR.get()
            .resolve(MOD_DIR)
            .resolve(PLAYERS_DIR)
            .resolve(uuid.toString() + LEGACY_FLAG_EXT);
    }

    /** Plain mutable holder for read-modify-write. */
    private static final class State {
        int newWorldCount;
        int joinedWorldCount;
        Set<String> seenDimensionVariants = new LinkedHashSet<>();
    }

    private static State read(UUID uuid) {
        State state = new State();
        Path path = propsPath(uuid);
        if (!Files.exists(path)) return state;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] PlayerPlayedMarker: read failed for {} ({}) — treating as zero", uuid, path, e);
            return state;
        }
        state.newWorldCount = parseIntOrZero(props.getProperty(KEY_NEW_WORLD));
        state.joinedWorldCount = parseIntOrZero(props.getProperty(KEY_JOINED_WORLD));
        state.seenDimensionVariants = parseKeySet(props.getProperty(KEY_SEEN_DIMENSION));
        return state;
    }

    private static void write(UUID uuid, State state) {
        Path path = propsPath(uuid);
        Properties props = new Properties();
        props.setProperty(KEY_NEW_WORLD, Integer.toString(state.newWorldCount));
        props.setProperty(KEY_JOINED_WORLD, Integer.toString(state.joinedWorldCount));
        props.setProperty(KEY_SEEN_DIMENSION, String.join(",", state.seenDimensionVariants));
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "DungeonTrain — per-player welcome-strike state");
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] PlayerPlayedMarker: write failed for {} at {} — {}",
                uuid, path, e.toString());
        }
    }

    private static int parseIntOrZero(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parse a comma-joined key list into an insertion-ordered set (empties skipped). */
    private static Set<String> parseKeySet(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            String k = part.trim();
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }
}
