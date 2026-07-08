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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory registry of every standalone {@link RandomBookFile} bundled in
 * the mod jar at {@code data/dungeontrain/narratives/random_books/}.
 *
 * <p>Loaded once at {@link ServerStartingEvent}, re-loadable via
 * {@code /dungeontrain narrative reload}. Mirrors {@link StoryRegistry} —
 * server-thread synchronous, no datapack-reload listener (the bundled
 * content is shipped-only).</p>
 *
 * <p>The registry feeds the {@code dungeontrain:random_book} placeholder
 * intercept inside {@code ContainerContentsRoller.rollItemStack}; entries
 * appear in chests as already-stamped vanilla {@code WRITTEN_BOOK} stacks,
 * not as the placeholder itself.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class RandomBookRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE_PREFIX = "/data/" + DungeonTrain.MOD_ID + "/narratives/random_books/";
    private static final String JSON_EXT = ".json";

    private static final Map<ResourceLocation, RandomBookFile> BOOKS = new LinkedHashMap<>();

    private RandomBookRegistry() {}

    /** Reload from the bundled resources. Safe to call outside event handlers. */
    public static synchronized void reload() {
        BOOKS.clear();
        Set<String> basenames = BundledNbtScanner.scanBasenames(
            RandomBookRegistry.class, RESOURCE_PREFIX, LOGGER, JSON_EXT);
        int loaded = 0;
        int failed = 0;
        for (String basename : basenames) {
            String resourcePath = RESOURCE_PREFIX + basename + JSON_EXT;
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                DungeonTrain.MOD_ID, "narratives/random_books/" + basename);
            try (InputStream in = RandomBookRegistry.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    LOGGER.warn("[DungeonTrain] RandomBook: scanner found '{}' but resource stream is null — skipping",
                        basename);
                    failed++;
                    continue;
                }
                RandomBookFile book = RandomBookCodec.parse(in, id);
                BOOKS.put(id, book);
                loaded++;
            } catch (RandomBookCodec.RandomBookParseException e) {
                LOGGER.error("[DungeonTrain] RandomBook: failed to parse {} — {}", resourcePath, e.getMessage());
                failed++;
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] RandomBook: unexpected error reading {} — {}", resourcePath, e.toString());
                failed++;
            }
        }
        LOGGER.info("[DungeonTrain] RandomBook registry loaded — {} books from {} (failed: {})",
            loaded, RESOURCE_PREFIX, failed);
    }

    /** Snapshot of every registered book id, alphabetical. */
    public static synchronized List<ResourceLocation> ids() {
        List<ResourceLocation> out = new ArrayList<>(BOOKS.keySet());
        out.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        return out;
    }

    /** Snapshot of every registered book basename (path tail), alphabetical. */
    public static synchronized List<String> basenames() {
        List<String> out = new ArrayList<>();
        for (ResourceLocation rl : BOOKS.keySet()) {
            String path = rl.getPath();
            int slash = path.lastIndexOf('/');
            out.add(slash >= 0 ? path.substring(slash + 1) : path);
        }
        Collections.sort(out);
        return out;
    }

    /** Lookup by full ResourceLocation. */
    public static synchronized Optional<RandomBookFile> get(ResourceLocation id) {
        return Optional.ofNullable(BOOKS.get(id));
    }

    /** Lookup by short basename (e.g. {@code "musings_of_faulthurst"}). */
    public static synchronized Optional<RandomBookFile> getByBasename(String basename) {
        for (Map.Entry<ResourceLocation, RandomBookFile> e : BOOKS.entrySet()) {
            String path = e.getKey().getPath();
            int slash = path.lastIndexOf('/');
            String tail = slash >= 0 ? path.substring(slash + 1) : path;
            if (tail.equals(basename)) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    public static synchronized int count() {
        return BOOKS.size();
    }

    /**
     * Sum of every loaded book's variant count — the denominator for the shared-community-book loot
     * chance (numerator is {@link NarrativeProgressData#distinctRandomBookVariantsEverRead()}), so the
     * chance scales with individual variants read rather than whole books.
     */
    public static synchronized int totalVariantCount() {
        int total = 0;
        for (RandomBookFile b : BOOKS.values()) total += b.variants().size();
        return total;
    }

    /**
     * Sum of every loaded book's {@code weight}. Returns 0 when the pool is
     * empty or every book has weight 0 — callers should treat 0 as
     * "skip the random-book substitution and emit nothing".
     */
    public static synchronized int totalWeight() {
        int total = 0;
        for (RandomBookFile b : BOOKS.values()) total += b.weight();
        return total;
    }

    /**
     * Pick a book from the pool weighted by each book's {@code weight}, using
     * the supplied {@code seed} as the only randomness source. Returns
     * {@link Optional#empty()} when the pool is empty or every weight is 0.
     *
     * <p>Iteration order matches {@link #ids()} (alphabetical by path) for
     * determinism — a different file-system order on different machines must
     * not produce different picks.</p>
     */
    public static synchronized Optional<RandomBookFile> pickWeighted(long seed) {
        if (BOOKS.isEmpty()) return Optional.empty();
        int total = totalWeight();
        if (total <= 0) return Optional.empty();
        long unsigned = seed & 0x7FFFFFFFFFFFFFFFL;
        int target = (int) (unsigned % total);
        // Iterate in the deterministic order returned by ids().
        for (ResourceLocation id : ids()) {
            RandomBookFile book = BOOKS.get(id);
            if (book == null) continue;
            target -= book.weight();
            if (target < 0) return Optional.of(book);
        }
        // Fallback for floating-point-style edge case — return the last book.
        ResourceLocation last = ids().get(ids().size() - 1);
        return Optional.ofNullable(BOOKS.get(last));
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (RandomBookRegistry.class) {
            BOOKS.clear();
        }
    }
}
