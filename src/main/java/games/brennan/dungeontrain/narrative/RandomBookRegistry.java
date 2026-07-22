package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.BookVoteScores;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory registry of every standalone {@link RandomBookFile} at
 * {@code data/dungeontrain/narratives/random_books/}.
 *
 * <p>Loaded through the vanilla {@link ResourceManager} server-data channel via
 * {@link NarrativeDataLoaders} (fires at world load and on {@code /reload}), so a
 * datapack can override any bundled book by shipping
 * {@code data/dungeontrain/narratives/random_books/<name>.json}. Re-loadable on
 * demand via {@code /dungeontrain narrative reload}. Mirrors {@link StoryRegistry}.</p>
 *
 * <p>The registry feeds the {@code dungeontrain:random_book} placeholder
 * intercept inside {@code ContainerContentsRoller.rollItemStack}; entries
 * appear in chests as already-stamped vanilla {@code WRITTEN_BOOK} stacks,
 * not as the placeholder itself.</p>
 */
public final class RandomBookRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** ResourceManager directory (namespace-relative, no leading/trailing slash). */
    private static final String DIR = "narratives/random_books";
    private static final String JSON_EXT = ".json";

    private static final Map<ResourceLocation, RandomBookFile> BOOKS = new LinkedHashMap<>();

    private RandomBookRegistry() {}

    /**
     * Reload from the given {@link ResourceManager} (bundled data + datapack
     * overrides). Called by the reload listener at world load / {@code /reload}
     * and by the {@code /dungeontrain narrative reload} command.
     */
    public static synchronized void load(ResourceManager resourceManager) {
        BOOKS.clear();
        int loaded = 0;
        int failed = 0;
        Map<ResourceLocation, Resource> resources =
            resourceManager.listResources(DIR, rl -> rl.getPath().endsWith(JSON_EXT));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation file = entry.getKey();
            ResourceLocation id = stripJson(file);
            // Overlay the host-locale variant on the English base when one is bundled/shipped
            // (see NarrativeContentLocale); the id stays identical, so pool weights and
            // variant-count denominators are unaffected.
            Resource source = NarrativeContentLocale.localized(resourceManager, id, DIR).orElse(entry.getValue());
            try (InputStream in = source.open()) {
                RandomBookFile book = RandomBookCodec.parse(in, id);
                BOOKS.put(id, book);
                loaded++;
            } catch (RandomBookCodec.RandomBookParseException e) {
                LOGGER.error("[DungeonTrain] RandomBook: failed to parse {} — {}", file, e.getMessage());
                failed++;
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] RandomBook: unexpected error reading {} — {}", file, e.toString());
                failed++;
            }
        }
        LOGGER.info("[DungeonTrain] RandomBook registry loaded — {} books from '{}' (failed: {})",
            loaded, DIR, failed);
    }

    /** Drop every loaded book (called on server stop). */
    public static synchronized void clear() {
        BOOKS.clear();
    }

    /** Strip the trailing {@code .json} from a resource location, keeping namespace + path. */
    private static ResourceLocation stripJson(ResourceLocation file) {
        String path = file.getPath();
        return ResourceLocation.fromNamespaceAndPath(
            file.getNamespace(), path.substring(0, path.length() - JSON_EXT.length()));
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
        // Community-vote factor applied AFTER the datapack weight (BookVoteScores.effectiveWeight —
        // a uniform ×100 scale that cancels between total and walk; factor 1 when relay-less/unvoted,
        // reproducing the original odds exactly). totalWeight() itself is deliberately untouched.
        long total = 0;
        for (ResourceLocation id : ids()) {
            RandomBookFile book = BOOKS.get(id);
            if (book != null) total += BookVoteScores.effectiveWeight("random", book.basename(), book.weight());
        }
        if (total <= 0) return Optional.empty();
        long unsigned = seed & 0x7FFFFFFFFFFFFFFFL;
        long target = unsigned % total;
        // Iterate in the deterministic order returned by ids().
        for (ResourceLocation id : ids()) {
            RandomBookFile book = BOOKS.get(id);
            if (book == null) continue;
            target -= BookVoteScores.effectiveWeight("random", book.basename(), book.weight());
            if (target < 0) return Optional.of(book);
        }
        // Fallback for floating-point-style edge case — return the last book.
        ResourceLocation last = ids().get(ids().size() - 1);
        return Optional.ofNullable(BOOKS.get(last));
    }
}
