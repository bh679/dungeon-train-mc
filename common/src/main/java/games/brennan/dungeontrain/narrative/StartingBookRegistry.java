package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>Loaded through the vanilla {@link ResourceManager} server-data channel via
 * {@link NarrativeDataLoaders} (fires at world load and on {@code /reload}), so a
 * datapack can override any bundled welcome book by shipping
 * {@code data/dungeontrain/narratives/starting_books/[<context>/]<name>.json} at
 * the matching path — the context sub-folder is preserved in the id.</p>
 *
 * <p>Consumed by {@link StartingBookFactory#rollFromPool} and the per-player
 * login / respawn hook in {@code StartingBookEvents}.</p>
 */
public final class StartingBookRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** ResourceManager directory (namespace-relative, no leading/trailing slash). */
    private static final String DIR = "narratives/starting_books";
    /** {@link #DIR} + '/', stripped from a book id to read its context sub-folder. */
    private static final String BASE_PATH = DIR + "/";
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

    /**
     * Reload every per-context pool from the given {@link ResourceManager}
     * (bundled data + datapack overrides). A single recursive
     * {@code listResources} call returns every context sub-folder; the context
     * is re-derived from each book's path segment (see {@link #contextFor}).
     * Called by the reload listener at world load / {@code /reload} and by the
     * {@code /dungeontrain narrative reload} command.
     */
    public static synchronized void load(ResourceManager resourceManager) {
        for (Map<ResourceLocation, RandomBookFile> pool : POOLS.values()) pool.clear();

        EnumMap<StartingBookContext, Integer> perContext = new EnumMap<>(StartingBookContext.class);
        for (StartingBookContext ctx : StartingBookContext.values()) perContext.put(ctx, 0);

        int totalLoaded = 0;
        int totalFailed = 0;
        Map<ResourceLocation, Resource> resources =
            resourceManager.listResources(DIR, rl -> rl.getPath().endsWith(JSON_EXT));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation file = entry.getKey();
            // Preserve the historical id shape (dungeontrain:narratives/starting_books/[<ctx>/]<name>).
            ResourceLocation id = stripJson(file);
            StartingBookContext ctx = contextFor(id);
            try (InputStream in = entry.getValue().open()) {
                RandomBookFile book = RandomBookCodec.parse(in, id);
                POOLS.get(ctx).put(id, book);
                perContext.merge(ctx, 1, Integer::sum);
                totalLoaded++;
            } catch (RandomBookCodec.RandomBookParseException e) {
                LOGGER.error("[DungeonTrain] StartingBook: failed to parse {} — {}", file, e.getMessage());
                totalFailed++;
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] StartingBook: unexpected error reading {} — {}", file, e.toString());
                totalFailed++;
            }
        }
        for (StartingBookContext ctx : StartingBookContext.values()) {
            LOGGER.info("[DungeonTrain] StartingBook context '{}' loaded — {} books",
                ctx.name(), perContext.get(ctx));
        }
        LOGGER.info("[DungeonTrain] StartingBook registry loaded — {} books across {} contexts (failed: {})",
            totalLoaded, StartingBookContext.values().length, totalFailed);
    }

    /** Drop every per-context pool (called on server stop). */
    public static synchronized void clear() {
        for (Map<ResourceLocation, RandomBookFile> pool : POOLS.values()) pool.clear();
    }

    /** Strip the trailing {@code .json} from a resource location, keeping namespace + path. */
    private static ResourceLocation stripJson(ResourceLocation file) {
        String path = file.getPath();
        return ResourceLocation.fromNamespaceAndPath(
            file.getNamespace(), path.substring(0, path.length() - JSON_EXT.length()));
    }

    /**
     * Derive the {@link StartingBookContext} for a book id from its path: the
     * folder segment right after {@code narratives/starting_books/}. A top-level
     * file (no sub-folder) is {@link StartingBookContext#DEFAULT}; an unrecognised
     * sub-folder (e.g. a datapack's own bucket) also falls back to DEFAULT so the
     * book still loads and is usable.
     */
    private static StartingBookContext contextFor(ResourceLocation id) {
        String path = id.getPath();
        if (!path.startsWith(BASE_PATH)) return StartingBookContext.DEFAULT;
        String rel = path.substring(BASE_PATH.length());
        int slash = rel.indexOf('/');
        if (slash < 0) return StartingBookContext.DEFAULT;
        String folder = rel.substring(0, slash);
        return StartingBookContext.fromString(folder).orElse(StartingBookContext.DEFAULT);
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
}
