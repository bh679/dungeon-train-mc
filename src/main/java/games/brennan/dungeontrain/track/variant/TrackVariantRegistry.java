package games.brennan.dungeontrain.track.variant;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.util.BundledNbtScanner;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Per-{@link TrackKind} sorted set of available variant names. Mirrors
 * {@link games.brennan.dungeontrain.train.CarriageVariantRegistry} but indexed
 * by kind — each kind has its own namespace so a "default" tunnel section and
 * a "default" pillar top don't collide.
 *
 * <p>Discovery on {@link ServerStartingEvent}: scans
 * {@code config/dungeontrain/<kind.subdir>/*.nbt} and adds each basename to
 * the kind's set. {@link TrackKind#DEFAULT_NAME} is always present as a
 * synthetic entry — even on a fresh install with no NBTs, the registry
 * answers a single "default" name per kind so the per-tile picker has at
 * least one variant to pick (the legacy hardcoded fallback covers the actual
 * placement until the user authors one).</p>
 *
 * <p>Names follow the same {@code [a-z0-9_]{{1,32}}} pattern as carriage
 * variants — keeps shell command tokens unambiguous and filesystem-safe.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TrackVariantRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Same pattern as {@link games.brennan.dungeontrain.train.CarriageVariant#NAME_PATTERN}. */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    private static final Map<TrackKind, TreeSet<String>> NAMES = new EnumMap<>(TrackKind.class);
    static {
        for (TrackKind k : TrackKind.values()) NAMES.put(k, new TreeSet<>());
    }

    private TrackVariantRegistry() {}

    /**
     * All names registered for {@code kind}, alphabetical, with
     * {@link TrackKind#DEFAULT_NAME} guaranteed first. Returns a snapshot —
     * safe to iterate without locking.
     */
    public static synchronized List<String> namesFor(TrackKind kind) {
        TreeSet<String> set = NAMES.get(kind);
        java.util.ArrayList<String> out = new java.util.ArrayList<>(set.size() + 1);
        out.add(TrackKind.DEFAULT_NAME);
        for (String n : set) {
            if (!TrackKind.DEFAULT_NAME.equals(n)) out.add(n);
        }
        return Collections.unmodifiableList(out);
    }

    /** True if {@code name} is registered for {@code kind} (or is the synthetic default). */
    public static synchronized boolean contains(TrackKind kind, String name) {
        if (name == null) return false;
        String key = name.toLowerCase(Locale.ROOT);
        if (TrackKind.DEFAULT_NAME.equals(key)) return true;
        return NAMES.get(kind).contains(key);
    }

    /**
     * Add a custom name for {@code kind}. Returns false if the name is
     * invalid, equals {@link TrackKind#DEFAULT_NAME} (reserved), or is
     * already registered. Caller writes the backing NBT file.
     */
    public static synchronized boolean register(TrackKind kind, String name) {
        if (name == null) return false;
        String key = name.toLowerCase(Locale.ROOT);
        if (!NAME_PATTERN.matcher(key).matches()) return false;
        if (TrackKind.DEFAULT_NAME.equals(key)) return false;
        return NAMES.get(kind).add(key);
    }

    /**
     * Remove a custom name. Returns false for unknown names or for
     * {@link TrackKind#DEFAULT_NAME} (the synthetic default cannot be
     * unregistered). Caller deletes the backing files.
     */
    public static synchronized boolean unregister(TrackKind kind, String name) {
        if (name == null) return false;
        String key = name.toLowerCase(Locale.ROOT);
        if (TrackKind.DEFAULT_NAME.equals(key)) return false;
        return NAMES.get(kind).remove(key);
    }

    public static synchronized Optional<String> find(TrackKind kind, String name) {
        if (name == null) return Optional.empty();
        String key = name.toLowerCase(Locale.ROOT);
        if (TrackKind.DEFAULT_NAME.equals(key)) return Optional.of(key);
        if (NAMES.get(kind).contains(key)) return Optional.of(key);
        return Optional.empty();
    }

    /** Reload every kind's name set from disk. Safe to call outside event handlers. */
    public static synchronized void reload() {
        // Migrate legacy single-file paths first so the directory scan below
        // picks up the renamed default.nbt as the synthetic "default" entry
        // instead of registering "track" as a custom name. Idempotent.
        TrackVariantStore.migrateLegacyPaths();
        int bundled = 0;
        int custom = 0;
        for (TrackKind kind : TrackKind.values()) {
            TreeSet<String> set = NAMES.get(kind);
            set.clear();
            // Bundled scan first so the per-install config dir's customs land
            // alphabetically *after* the shipped variants in the same TreeSet
            // (TreeSet sorts globally, so order is alphabetical regardless —
            // the count split is what we care about for the log line).
            bundled += scanBundled(kind, set);
            custom += scanDir(kind, set);
        }
        LOGGER.info("[DungeonTrain] Track variant registry loaded — {} bundled + {} custom names across {} kinds",
            bundled, custom, TrackKind.values().length);
    }

    /**
     * Add every {@code .nbt} basename shipped in the mod jar at
     * {@code /data/dungeontrain/<kind.subdir>/} to the kind's name set,
     * skipping {@link TrackKind#DEFAULT_NAME} (synthetic, always-present
     * entry) and any name that fails {@link #NAME_PATTERN}.
     *
     * <p>This closes the discovery gap that left {@code TILE} and
     * {@code ADJUNCT_STAIRS} with only the synthetic default — bundled
     * variants like {@code sleeperless.nbt} and {@code ladder.nbt} now show
     * up in the in-world block-variant editor menu without a hand-maintained
     * manifest file.</p>
     */
    private static int scanBundled(TrackKind kind, TreeSet<String> into) {
        Set<String> scanned = BundledNbtScanner.scanBasenames(
            TrackVariantRegistry.class, kind.bundledResourcePrefix(), LOGGER);
        int added = 0;
        for (String basename : scanned) {
            if (TrackKind.DEFAULT_NAME.equals(basename)) continue;
            if (!NAME_PATTERN.matcher(basename).matches()) {
                LOGGER.warn("[DungeonTrain] Ignoring bundled track variant '{}' for kind {} — invalid name",
                    basename, kind.id());
                continue;
            }
            if (into.add(basename)) added++;
        }
        return added;
    }

    private static int scanDir(TrackKind kind, TreeSet<String> into) {
        Path dir = FMLPaths.CONFIGDIR.get().resolve(kind.configSubdir());
        if (!Files.isDirectory(dir)) return 0;
        int added = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + TrackKind.NBT_EXT)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!name.endsWith(TrackKind.NBT_EXT)) continue;
                String basename = name.substring(0, name.length() - TrackKind.NBT_EXT.length())
                    .toLowerCase(Locale.ROOT);
                if (TrackKind.DEFAULT_NAME.equals(basename)) continue;
                if (!NAME_PATTERN.matcher(basename).matches()) {
                    LOGGER.warn("[DungeonTrain] Ignoring track variant '{}' from {} — invalid name",
                        basename, file);
                    continue;
                }
                if (into.add(basename)) added++;
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to scan track variant dir {}: {}", dir, e.toString());
        }
        return added;
    }

    public static synchronized void clear() {
        for (TreeSet<String> set : NAMES.values()) set.clear();
        ZERO_WARNED.clear();
    }

    /**
     * Deterministic weighted pick of a name for the tile at {@code tileIndex}.
     * Same {@code (worldSeed, tileIndex, kind)} input always yields the same
     * name across server restarts and rolling-window re-renders. If every name
     * in the pool has weight 0 the function falls back to a uniform pick.
     *
     * <p>Mirrors {@code CarriageTemplate.weightedSeededPick} but with kind-id
     * mixed into the seed so the same tile index in two adjacent kinds
     * (e.g. tunnel section vs portal at the same X) doesn't pick correlated
     * names.</p>
     */
    public static String pickName(TrackKind kind, long worldSeed, long tileIndex) {
        List<String> pool = namesFor(kind);
        int n = pool.size();
        if (n == 0) return TrackKind.DEFAULT_NAME;
        int[] cumulative = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            total += TrackVariantWeights.weightFor(kind, pool.get(i));
            cumulative[i] = total;
        }
        long mixed = worldSeed
            ^ (tileIndex * 0x9E3779B97F4A7C15L)
            ^ ((long) kind.id().hashCode() * 0xBF58476D1CE4E5B9L);
        Random rng = new Random(mixed);
        if (total <= 0) {
            warnAllZeroOnce(kind);
            return pool.get(rng.nextInt(n));
        }
        int r = rng.nextInt(total);
        for (int i = 0; i < n; i++) {
            if (r < cumulative[i]) return pool.get(i);
        }
        return pool.get(n - 1);
    }

    private static final java.util.EnumSet<TrackKind> ZERO_WARNED = java.util.EnumSet.noneOf(TrackKind.class);

    private static synchronized void warnAllZeroOnce(TrackKind kind) {
        if (!ZERO_WARNED.add(kind)) return;
        LOGGER.warn("[DungeonTrain] Every track variant in pool {} has weight 0 — falling back to uniform pick. Check weights.json.",
            kind.id());
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
