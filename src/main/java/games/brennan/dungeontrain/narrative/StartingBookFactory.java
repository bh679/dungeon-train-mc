package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Pool-aware builder for {@code Items.WRITTEN_BOOK} stacks rolled from
 * {@link StartingBookRegistry}. Mirrors {@link RandomBookFactory#rollFromPool}
 * — same two-stage splittable-mix on the caller's seed for independent
 * book-pick and variant-pick — but pulls from the welcome-book pool instead
 * of the chest pool.
 *
 * <p>Unlike {@link RandomBookFactory#buildVanillaBook}, this factory does
 * <b>not</b> stamp the {@link RandomBookTag} identity component. Starting
 * books are plain written books; without the tag, the equipment-change
 * handler in {@link NarrativeBookEvents} won't try to swap the content out
 * from under the player the second time they hold it.</p>
 *
 * <p>Reuses {@link BookFactory#paginate} (package-private) for page splitting
 * and the {@code MAX_*} caps so layout matches narrative + random books.</p>
 */
public final class StartingBookFactory {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Splittable-mix salt for the book-pick stage. */
    private static final long SALT_BOOK_PICK    = 0x57A47E8B00C0FFEEL;
    /** Splittable-mix salt for the variant-pick stage. */
    private static final long SALT_VARIANT_PICK = 0x57A47E8B0AF1ED01L;
    /** Splittable-mix salt for the respawn-cycling tuple pick. */
    private static final long SALT_RESPAWN_PICK = 0x57A47E8B0CFCEDA7L;
    /** Splittable-mix salt for the dimension-cycling tuple pick. */
    private static final long SALT_DIMENSION_PICK = 0x57A47E8B0D13E5A1L;

    private StartingBookFactory() {}

    /**
     * One leaf option in the respawn-cycling pool: a specific
     * {@code (book, variantIndex)} pair. {@code fromRespawnPool} is true when
     * the source pool was RESPAWN, false when it came from DEFAULT — the
     * respawn picker uses this to decide whether to mark the seen-set.
     */
    private record Tuple(RandomBookFile book, int variantIndex, boolean fromRespawnPool) {}

    /**
     * Pick a book from the DEFAULT context pool, pick a variant from that
     * book, and build an unstamped vanilla {@link Items#WRITTEN_BOOK} stack
     * ready to drop into a player's inventory. Deterministic per
     * {@code rollSeed}.
     *
     * <p>Returns {@link Optional#empty()} when the pool is empty (or every
     * book weight is 0) — caller should log a warning and skip rather than
     * substituting a placeholder.</p>
     */
    public static Optional<ItemStack> rollFromPool(long rollSeed) {
        return rollFromPool(rollSeed, StartingBookContext.DEFAULT);
    }

    /**
     * Context-aware variant of {@link #rollFromPool(long)}. Rolls from the
     * pool for {@code context}, falling back to the DEFAULT pool when the
     * context pool is empty or has zero total weight (see
     * {@link StartingBookRegistry#pickWeighted(long, StartingBookContext)}).
     *
     * <p>Two-stage splittable-mix on {@code rollSeed} — same family used by
     * {@link RandomBookFactory#rollFromPool} — so the book-pick and the
     * variant-pick are independent: re-rolling with the same seed for a
     * different context still picks the variant deterministically given the
     * resolved book.</p>
     */
    public static Optional<ItemStack> rollFromPool(long rollSeed, StartingBookContext context) {
        long bookSeed = mix(rollSeed, SALT_BOOK_PICK);
        Optional<RandomBookFile> bookOpt = StartingBookRegistry.pickWeighted(bookSeed, context);
        if (bookOpt.isEmpty()) return Optional.empty();
        RandomBookFile book = bookOpt.get();
        long variantSeed = mix(rollSeed, SALT_VARIANT_PICK);
        int variantIndex = book.pickVariantIndex(variantSeed);
        String body = book.variants().get(variantIndex);
        return Optional.of(buildUnstampedBook(book, body, variantIndex));
    }

    /**
     * Respawn-specific roll with cycling semantics.
     *
     * <p>While any {@code (book, variantIndex)} tuple in the RESPAWN pool
     * is unseen by this world, the roll is restricted to those unseen
     * tuples — every respawn delivers a fresh respawn variant. Once every
     * RESPAWN tuple has been seen at least once, the picker permanently
     * widens to the union of {RESPAWN, DEFAULT} tuples; the seen-set is
     * not reset. A DEFAULT-side pick in this combined phase does NOT
     * advance the seen-set (only respawn picks do — but the seen-set is
     * already full at that point so it's a no-op).</p>
     *
     * <p>Side-effect: marks the picked tuple via
     * {@link NarrativeProgressData#markStartingBookVariantSeen} when the
     * pick came from RESPAWN.</p>
     *
     * <p>Empty-pool fallback: if the RESPAWN pool is empty (no books
     * authored yet), defer to {@link #rollFromPool(long, StartingBookContext)}
     * with RESPAWN — which falls back to DEFAULT — exactly matching the
     * pre-cycling shipping behaviour for unconfigured installs.</p>
     */
    public static Optional<ItemStack> rollForRespawn(long rollSeed, NarrativeProgressData data) {
        List<Tuple> respawnTuples = enumerateTuples(StartingBookContext.RESPAWN, true);
        if (respawnTuples.isEmpty()) {
            // No respawn books authored — fall back to default pool. No
            // cycling state to maintain.
            return rollFromPool(rollSeed, StartingBookContext.RESPAWN);
        }
        long pickSeed = mix(rollSeed, SALT_RESPAWN_PICK);

        List<Tuple> unseen = new ArrayList<>(respawnTuples.size());
        for (Tuple t : respawnTuples) {
            if (!data.hasSeenStartingBookVariant(t.book().basename(), t.variantIndex())) {
                unseen.add(t);
            }
        }
        Tuple pick;
        if (!unseen.isEmpty()) {
            pick = pickTupleWeighted(unseen, pickSeed);
        } else {
            // Every respawn tuple seen — widen permanently to RESPAWN +
            // DEFAULT. Default-side tuples carry fromRespawnPool=false so
            // we know not to mark them seen.
            List<Tuple> combined = new ArrayList<>(respawnTuples.size() + 16);
            combined.addAll(respawnTuples);
            combined.addAll(enumerateTuples(StartingBookContext.DEFAULT, false));
            if (combined.isEmpty()) return Optional.empty();
            pick = pickTupleWeighted(combined, pickSeed);
        }
        if (pick.fromRespawnPool()) {
            data.markStartingBookVariantSeen(pick.book().basename(), pick.variantIndex());
        }
        String body = pick.book().variants().get(pick.variantIndex());
        return Optional.of(buildUnstampedBook(pick.book(), body, pick.variantIndex()));
    }

    /**
     * Enumerate every {@code (book, variantIndex)} tuple in the given
     * context pool. The tuples retain their source-pool tag via
     * {@link Tuple#fromRespawnPool} for the cycling mark logic.
     */
    private static List<Tuple> enumerateTuples(StartingBookContext context, boolean fromRespawnPool) {
        List<RandomBookFile> books = StartingBookRegistry.booksIn(context);
        List<Tuple> out = new ArrayList<>();
        for (RandomBookFile book : books) {
            int n = book.variants().size();
            for (int v = 0; v < n; v++) {
                out.add(new Tuple(book, v, fromRespawnPool));
            }
        }
        return out;
    }

    /**
     * Weighted-by-book pick across a tuple list. Each tuple inherits its
     * parent book's {@code weight} — so a higher-weight book gets more
     * total share AND that share is split evenly across its variants
     * (matches the original two-stage roll's weighting semantics).
     *
     * <p>Books with {@code weight==0} are still enumerable but contribute
     * zero share; they only get picked when every other book is also
     * zero-weight (in which case we degrade to first-in-list).</p>
     */
    private static Tuple pickTupleWeighted(List<Tuple> tuples, long seed) {
        long total = 0L;
        for (Tuple t : tuples) total += Math.max(0, t.book().weight());
        if (total <= 0) {
            // All tuples are zero-weight — degrade to first.
            return tuples.get(0);
        }
        long unsigned = seed & 0x7FFFFFFFFFFFFFFFL;
        long target = unsigned % total;
        for (Tuple t : tuples) {
            target -= Math.max(0, t.book().weight());
            if (target < 0) return t;
        }
        // Numerical edge — return last.
        return tuples.get(tuples.size() - 1);
    }

    // ---------------- Dimension-welcome cycling (NETHER / END) ----------------

    /**
     * Stable per-installation key for one dimension-welcome
     * {@code (book, variant)} tuple — e.g. {@code "nether/why_the_nether#0"}.
     * Namespaced by the context folder so the Nether and End "playlists"
     * never collide. Pure; this is the exact form persisted by
     * {@link PlayerPlayedMarker#markDimensionVariantSeen}.
     */
    public static String dimKey(String folderName, String basename, int variantIndex) {
        return folderName + "/" + basename + "#" + variantIndex;
    }

    /**
     * True when every key in {@code allKeys} is already present in
     * {@code seen}. An empty {@code allKeys} (no books authored) counts as
     * exhausted — callers then fall through to the lifecycle welcome.
     */
    public static boolean isExhausted(Collection<String> allKeys, Set<String> seen) {
        return seen.containsAll(allKeys);
    }

    /**
     * True when {@code context}'s pool still has at least one
     * {@code (book, variant)} tuple unseen by {@code seen}. Drives the route
     * decision in {@code StartingBookEvents.resolveLoginContext}: route to the
     * dimension pool while unseen tuples remain, otherwise fall through to the
     * lifecycle welcome. An empty pool returns {@code false}.
     */
    public static boolean hasUnseenDimensionTuples(StartingBookContext context, Set<String> seen) {
        return !isExhausted(dimensionKeys(context), seen);
    }

    /** Every dimension key currently loaded for {@code context}, in pool order. */
    private static List<String> dimensionKeys(StartingBookContext context) {
        List<Tuple> tuples = enumerateTuples(context, false);
        List<String> keys = new ArrayList<>(tuples.size());
        for (Tuple t : tuples) {
            keys.add(dimKey(context.folderName(), t.book().basename(), t.variantIndex()));
        }
        return keys;
    }

    /**
     * Dimension-welcome roll with per-installation cycling.
     *
     * <p>While {@code context}'s pool has tuples unseen by {@code seen}, the
     * roll is restricted to those unseen tuples — so every new run in that
     * dimension delivers a fresh welcome — and the picked tuple's key is
     * reported to {@code markSeen}. When every tuple has been seen (or the
     * pool is empty), defers to {@link #rollFromPool(long, StartingBookContext)}
     * (weighted, non-marking) so test / preview callers still receive a book.
     * The real login path is guarded by {@link #hasUnseenDimensionTuples}, so
     * it never reaches that fall-through branch.</p>
     */
    public static Optional<ItemStack> rollForDimension(long rollSeed, StartingBookContext context,
                                                       Set<String> seen, Consumer<String> markSeen) {
        List<Tuple> tuples = enumerateTuples(context, false);
        List<Tuple> unseen = new ArrayList<>(tuples.size());
        for (Tuple t : tuples) {
            String key = dimKey(context.folderName(), t.book().basename(), t.variantIndex());
            if (!seen.contains(key)) unseen.add(t);
        }
        if (unseen.isEmpty()) {
            // Exhausted (or empty pool) — weighted fall-through, no marking.
            return rollFromPool(rollSeed, context);
        }
        long pickSeed = mix(rollSeed, SALT_DIMENSION_PICK);
        Tuple pick = pickTupleWeighted(unseen, pickSeed);
        markSeen.accept(dimKey(context.folderName(), pick.book().basename(), pick.variantIndex()));
        String body = pick.book().variants().get(pick.variantIndex());
        return Optional.of(buildUnstampedBook(pick.book(), body, pick.variantIndex()));
    }

    /**
     * Build a vanilla written book from an explicit body and variant index.
     * Used by the {@code /narrative startingbook give <basename>} command for
     * deterministic test gives.
     *
     * <p>No identity stamp — see class javadoc.</p>
     *
     * <p>Pagination is explicit: every blank line ({@code \n\n}) in the source
     * starts a new page. Single newlines are preserved as line breaks within
     * a page. See {@link #paginateExplicit} for the overflow fallback.</p>
     */
    public static ItemStack buildUnstampedBook(RandomBookFile book, String body, int variantIndex) {
        String title = book.title() == null || book.title().isEmpty() || "Untitled".equals(book.title())
            ? book.basename()
            : book.title();
        String author = book.author() == null || book.author().isEmpty()
            ? "Anonymous"
            : book.author();

        List<String> pageStrings = paginateExplicit(body);
        if (pageStrings.size() > BookFactory.MAX_PAGES) {
            LOGGER.warn("[DungeonTrain] StartingBook: '{}' produced {} pages — truncating to {} (vanilla cap)",
                book.id(), pageStrings.size(), BookFactory.MAX_PAGES);
            pageStrings = pageStrings.subList(0, BookFactory.MAX_PAGES);
        }

        List<Filterable<Component>> pages = new ArrayList<>(pageStrings.size());
        for (String page : pageStrings) {
            pages.add(Filterable.passThrough(Component.literal(page)));
        }
        if (pages.isEmpty()) {
            pages.add(Filterable.passThrough(Component.literal("")));
        }

        WrittenBookContent content = new WrittenBookContent(
            Filterable.passThrough(clampTitle(title)),
            clampAuthor(author),
            book.generation(),
            pages,
            /*resolved*/ true
        );

        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        // Stamp the marker + identity so (a) the close-detection flow (client
        // ScreenEvent.Closing → server burn handler) can identify this stack and
        // (b) the read handler can credit this exact (book, variant) toward the
        // all_starting_books advancement. See StartingBookTag.
        StartingBookTag.stamp(stack, book.basename(), variantIndex);
        return stack;
    }

    /**
     * Pagination strategy specific to starting books: <b>every {@code \n\n}
     * in the source is exactly one page break</b>. This gives authors precise
     * per-page control:
     * <ul>
     *   <li>{@code "page A\n\npage B"} → 2 pages.</li>
     *   <li>{@code "page A\n\n\n\npage B"} → 3 pages (A, blank, B).</li>
     *   <li>{@code "page A\n\n \n\npage B"} → 3 pages (A, blank, B) —
     *       a whitespace-only line between two {@code \n\n}s also acts as
     *       a blank-page slot.</li>
     *   <li>Single {@code \n} stays as a line break within a page.</li>
     * </ul>
     *
     * <p>Trimming:</p>
     * <ul>
     *   <li>Each page's leading/trailing whitespace is stripped.</li>
     *   <li>Leading + trailing blank pages are removed — opening a book on
     *       a blank page (or having dead pages at the end) is never useful.
     *       Internal blank pages are preserved as visual padding.</li>
     * </ul>
     *
     * <p>Overflow: if a single chunk between page breaks exceeds
     * {@link BookFactory#MAX_CHARS_PER_PAGE}, it spills into additional pages
     * via {@link BookFactory#paginate} (sentence / word fallback). Without
     * this an unbroken long paragraph would visually clip the in-game page.</p>
     */
    static List<String> paginateExplicit(String body) {
        List<String> pages = new ArrayList<>();
        // Split on EXACTLY two consecutive newlines. The -1 limit keeps
        // trailing empty chunks so they can become blank pages (or be
        // trimmed off at the end below).
        String[] chunks = body.split("\\n\\n", -1);
        for (String chunk : chunks) {
            String page = chunk.strip();
            if (page.length() <= BookFactory.MAX_CHARS_PER_PAGE) {
                pages.add(page);  // empty pages are intentional blank slots
            } else {
                pages.addAll(BookFactory.paginate(page));
            }
        }
        // Drop leading + trailing blanks. A book that opens on a blank page
        // looks broken, and trailing blanks are dead pages.
        while (!pages.isEmpty() && pages.get(0).isEmpty()) {
            pages.remove(0);
        }
        while (!pages.isEmpty() && pages.get(pages.size() - 1).isEmpty()) {
            pages.remove(pages.size() - 1);
        }
        return pages;
    }

    private static String clampTitle(String s) {
        return s.length() <= BookFactory.MAX_TITLE_CHARS ? s : s.substring(0, BookFactory.MAX_TITLE_CHARS);
    }

    private static String clampAuthor(String s) {
        return s.length() <= BookFactory.MAX_TITLE_CHARS ? s : s.substring(0, BookFactory.MAX_TITLE_CHARS);
    }

    /** Splittable-mix — same family used by {@link RandomBookFactory}. */
    private static long mix(long seed, long salt) {
        long state = seed ^ salt;
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        return state;
    }
}
