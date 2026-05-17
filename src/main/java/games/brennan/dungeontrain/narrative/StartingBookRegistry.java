package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.util.BundledNbtScanner;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * In-memory registry of every welcome / starting book bundled in the mod jar
 * under {@code data/dungeontrain/narratives/starting_books/}. Sibling to
 * {@link RandomBookRegistry} — same {@link RandomBookFile} data shape, same
 * {@link RandomBookCodec} parser, but a separate, multi-pool storage so
 * welcome books never appear in chest loot and chest random books never
 * appear at first spawn.
 *
 * <p><b>Multi-pool (per-context) layout:</b> books are partitioned by
 * {@link StartingBookContext}. Each context maps to a sub-folder under
 * {@code starting_books/}; {@link StartingBookContext#DEFAULT} is the
 * top-level. A context-specific pool falls back to the DEFAULT pool when
 * empty (or when its total weight is 0) — see {@link #pickWeighted}.</p>
 *
 * <p>Loaded once at {@link ServerStartingEvent}. Server-thread synchronous,
 * no datapack-reload listener (bundled content only).</p>
 *
 * <p>Consumed by {@link StartingBookFactory#rollFromPool} and the per-player
 * login / respawn hook in {@code StartingBookEvents}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class StartingBookRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE_PREFIX = "/data/" + DungeonTrain.MOD_ID + "/narratives/starting_books/";
    private static final String JSON_EXT = ".json";

    /**
     * Per-context book maps. Each value is a LinkedHashMap to preserve
     * insertion order — but lookups inside {@link #pickWeighted} iterate
     * {@link #idsFor} which sorts, so iteration determinism doesn't rely on
     * this map's order.
     */
    private static final EnumMap<StartingBookContext, Map<ResourceLocation, RandomBookFile>> POOLS =
        new EnumMap<>(StartingBookContext.class);

    static {
        for (StartingBookContext ctx : StartingBookContext.values()) {
            POOLS.put(ctx, new LinkedHashMap<>());
        }
    }

    private StartingBookRegistry() {}

    /** Reload every per-context pool from the bundled resources. */
    public static synchronized void reload() {
        for (Map<ResourceLocation, RandomBookFile> pool : POOLS.values()) pool.clear();

        int totalLoaded = 0;
        int totalFailed = 0;
        for (StartingBookContext ctx : StartingBookContext.values()) {
            String subdir = ctx.folderName().isEmpty() ? "" : ctx.folderName() + "/";
            String prefix = RESOURCE_PREFIX + subdir;
            Set<String> basenames = BundledNbtScanner.scanBasenames(
                StartingBookRegistry.class, prefix, LOGGER, JSON_EXT);

            int loaded = 0;
            int failed = 0;
            Map<ResourceLocation, RandomBookFile> pool = POOLS.get(ctx);
            for (String basename : basenames) {
                String resourcePath = prefix + basename + JSON_EXT;
                String idPath = "narratives/starting_books/" + subdir + basename;
                ResourceLocation id;
                try {
                    id = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, idPath);
                } catch (RuntimeException e) {
                    // Filename has chars Minecraft's ResourceLocation rejects
                    // (e.g. a space, uppercase letter, punctuation outside
                    // [a-z0-9._-]). Skip rather than crash the whole server —
                    // common author paper-cut, e.g. "lighting copy.json".
                    LOGGER.warn("[DungeonTrain] StartingBook: filename '{}' in context '{}' is not a valid ResourceLocation path — rename to use only [a-z0-9._-]. Skipping. ({})",
                        basename, ctx.name(), e.getMessage());
                    failed++;
                    continue;
                }
                try (InputStream in = StartingBookRegistry.class.getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        LOGGER.warn("[DungeonTrain] StartingBook: scanner found '{}' but resource stream is null — skipping",
                            basename);
                        failed++;
                        continue;
                    }
                    RandomBookFile book = RandomBookCodec.parse(in, id);
                    pool.put(id, book);
                    loaded++;
                } catch (RandomBookCodec.RandomBookParseException e) {
                    LOGGER.error("[DungeonTrain] StartingBook: failed to parse {} — {}", resourcePath, e.getMessage());
                    failed++;
                } catch (Exception e) {
                    LOGGER.error("[DungeonTrain] StartingBook: unexpected error reading {} — {}", resourcePath, e.toString());
                    failed++;
                }
            }
            LOGGER.info("[DungeonTrain] StartingBook context '{}' loaded — {} books from {} (failed: {})",
                ctx.name(), loaded, prefix, failed);
            totalLoaded += loaded;
            totalFailed += failed;
        }
        LOGGER.info("[DungeonTrain] StartingBook registry loaded — {} books across {} contexts (failed: {})",
            totalLoaded, StartingBookContext.values().length, totalFailed);
    }

    /** Snapshot of every registered book id across every pool, alphabetical. */
    public static synchronized List<ResourceLocation> ids() {
        List<ResourceLocation> out = new ArrayList<>();
        for (Map<ResourceLocation, RandomBookFile> pool : POOLS.values()) {
            out.addAll(pool.keySet());
        }
        out.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        return out;
    }

    /** Snapshot of every registered id within {@code context}, alphabetical. */
    public static synchronized List<ResourceLocation> idsFor(StartingBookContext context) {
        List<ResourceLocation> out = new ArrayList<>(POOLS.get(context).keySet());
        out.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        return out;
    }

    /**
     * Snapshot of every loaded book in {@code context}, in deterministic
     * alphabetical-by-path order. Used by
     * {@link StartingBookFactory#rollForRespawn} to enumerate
     * (book, variantIndex) tuples across the RESPAWN and DEFAULT pools.
     */
    public static synchronized List<RandomBookFile> booksIn(StartingBookContext context) {
        Map<ResourceLocation, RandomBookFile> pool = POOLS.get(context);
        List<RandomBookFile> out = new ArrayList<>(pool.size());
        for (ResourceLocation id : idsFor(context)) {
            RandomBookFile book = pool.get(id);
            if (book != null) out.add(book);
        }
        return out;
    }

    /** Snapshot of every registered book basename across every pool, alphabetical (unique). */
    public static synchronized List<String> basenames() {
        TreeSet<String> out = new TreeSet<>();
        for (Map<ResourceLocation, RandomBookFile> pool : POOLS.values()) {
            for (ResourceLocation rl : pool.keySet()) {
                out.add(tailOf(rl));
            }
        }
        return new ArrayList<>(out);
    }

    /** Lookup by full ResourceLocation across every pool. */
    public static synchronized Optional<RandomBookFile> get(ResourceLocation id) {
        for (Map<ResourceLocation, RandomBookFile> pool : POOLS.values()) {
            RandomBookFile book = pool.get(id);
            if (book != null) return Optional.of(book);
        }
        return Optional.empty();
    }

    /**
     * Lookup by short basename (e.g. {@code "meme_start"}) across every pool.
     * Returns the first match in {@link StartingBookContext} enum order; if
     * the author has the same basename in two contexts (unusual but allowed),
     * DEFAULT wins. Test commands can pass an explicit context if needed.
     */
    public static synchronized Optional<RandomBookFile> getByBasename(String basename) {
        for (StartingBookContext ctx : StartingBookContext.values()) {
            Optional<RandomBookFile> hit = getByBasename(ctx, basename);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    /** Lookup by basename scoped to a single context. */
    public static synchronized Optional<RandomBookFile> getByBasename(StartingBookContext context, String basename) {
        Map<ResourceLocation, RandomBookFile> pool = POOLS.get(context);
        for (Map.Entry<ResourceLocation, RandomBookFile> e : pool.entrySet()) {
            if (tailOf(e.getKey()).equals(basename)) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    /** Total book count across every pool. */
    public static synchronized int count() {
        int total = 0;
        for (Map<ResourceLocation, RandomBookFile> pool : POOLS.values()) total += pool.size();
        return total;
    }

    /** Book count for one context. */
    public static synchronized int countFor(StartingBookContext context) {
        return POOLS.get(context).size();
    }

    /**
     * Sum of every loaded book's {@code weight} across every pool. Used by
     * {@code /narrative startingbook list}. For weighted-pick total, prefer
     * {@link #totalWeightFor}.
     */
    public static synchronized int totalWeight() {
        int total = 0;
        for (Map<ResourceLocation, RandomBookFile> pool : POOLS.values()) {
            for (RandomBookFile b : pool.values()) total += b.weight();
        }
        return total;
    }

    /** Total weight inside a single context's pool. 0 → pool is empty / dead. */
    public static synchronized int totalWeightFor(StartingBookContext context) {
        int total = 0;
        for (RandomBookFile b : POOLS.get(context).values()) total += b.weight();
        return total;
    }

    /**
     * Pick a book from the DEFAULT pool. Convenience for legacy callers and
     * test commands. Equivalent to {@code pickWeighted(seed, DEFAULT)} but
     * without the fallback path (DEFAULT pool IS the fallback target).
     */
    public static synchronized Optional<RandomBookFile> pickWeighted(long seed) {
        return pickFrom(StartingBookContext.DEFAULT, seed);
    }

    /**
     * Pick a book from {@code context}'s pool, falling back to the DEFAULT
     * pool when {@code context}'s pool is empty or its total weight is 0.
     * Returns {@link Optional#empty()} when even the DEFAULT pool is empty.
     *
     * <p>Iteration order matches {@link #idsFor(StartingBookContext)} —
     * alphabetical by path — for determinism across machines.</p>
     */
    public static synchronized Optional<RandomBookFile> pickWeighted(long seed, StartingBookContext context) {
        if (context == StartingBookContext.DEFAULT) {
            return pickFrom(StartingBookContext.DEFAULT, seed);
        }
        Optional<RandomBookFile> hit = pickFrom(context, seed);
        if (hit.isPresent()) return hit;
        // Context pool empty / dead — fall back to default.
        return pickFrom(StartingBookContext.DEFAULT, seed);
    }

    /**
     * Weighted pick scoped to a single pool. No fallback — callers that
     * want fallback use {@link #pickWeighted(long, StartingBookContext)}.
     * Returns empty when the pool is empty or every weight is 0.
     */
    private static Optional<RandomBookFile> pickFrom(StartingBookContext context, long seed) {
        Map<ResourceLocation, RandomBookFile> pool = POOLS.get(context);
        if (pool.isEmpty()) return Optional.empty();
        int total = totalWeightFor(context);
        if (total <= 0) return Optional.empty();
        long unsigned = seed & 0x7FFFFFFFFFFFFFFFL;
        int target = (int) (unsigned % total);
        List<ResourceLocation> ordered = idsFor(context);
        for (ResourceLocation id : ordered) {
            RandomBookFile book = pool.get(id);
            if (book == null) continue;
            target -= book.weight();
            if (target < 0) return Optional.of(book);
        }
        // Rounding edge — return the last book.
        return Optional.ofNullable(pool.get(ordered.get(ordered.size() - 1)));
    }

    private static String tailOf(ResourceLocation rl) {
        String path = rl.getPath();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (StartingBookRegistry.class) {
            for (Map<ResourceLocation, RandomBookFile> pool : POOLS.values()) pool.clear();
        }
    }
}
