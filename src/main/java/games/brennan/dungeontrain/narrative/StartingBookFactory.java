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
import java.util.List;
import java.util.Optional;

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

    private StartingBookFactory() {}

    /**
     * Pick a book from the pool, pick a variant from that book, and build an
     * unstamped vanilla {@link Items#WRITTEN_BOOK} stack ready to drop into a
     * player's inventory. Deterministic per {@code rollSeed}.
     *
     * <p>Returns {@link Optional#empty()} when the pool is empty (or every
     * book weight is 0) — caller should log a warning and skip rather than
     * substituting a placeholder.</p>
     */
    public static Optional<ItemStack> rollFromPool(long rollSeed) {
        long bookSeed = mix(rollSeed, SALT_BOOK_PICK);
        Optional<RandomBookFile> bookOpt = StartingBookRegistry.pickWeighted(bookSeed);
        if (bookOpt.isEmpty()) return Optional.empty();
        RandomBookFile book = bookOpt.get();
        long variantSeed = mix(rollSeed, SALT_VARIANT_PICK);
        int variantIndex = book.pickVariantIndex(variantSeed);
        String body = book.variants().get(variantIndex);
        return Optional.of(buildUnstampedBook(book, body, variantIndex));
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
        // Stamp the marker so the close-detection flow (client ScreenEvent.Closing
        // → server burn handler) can identify this stack. See StartingBookTag.
        StartingBookTag.stamp(stack);
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
