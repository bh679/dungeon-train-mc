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
 * {@link RandomBookRegistry}. The two-stage pick uses splittable-mix on the
 * caller's seed so the book selection and the variant selection don't
 * correlate — same seed → same book → same variant, but a small change to
 * the seed shuffles both independently.
 *
 * <p>Unlike {@link BookFactory#buildFromBody}, this builder does <b>not</b>
 * stamp {@link NarrativeBookTag} on the returned stack. Random books are
 * flavour drops with no per-player progression; the read-event handler
 * silently no-ops on untagged books, which is the behaviour we want.</p>
 *
 * <p>Reuses {@link BookFactory#paginate} (package-private) for page splitting
 * and the {@code MAX_*} caps so layout matches narrative books.</p>
 */
public final class RandomBookFactory {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Splittable-mix salt for the book-pick stage. */
    private static final long SALT_BOOK_PICK    = 0xB00C0DEC0FFEEEEFL;
    /** Splittable-mix salt for the variant-pick stage. */
    private static final long SALT_VARIANT_PICK = 0xDEC0DEC0DEFACED1L;

    private RandomBookFactory() {}

    /**
     * Pick a book from the pool, pick a variant from that book, and build a
     * stamped vanilla {@link Items#WRITTEN_BOOK} stack ready to drop into a
     * chest slot.
     *
     * <p>Returns {@link Optional#empty()} when the pool is empty (or every
     * book weight is 0) — caller should leave the slot empty rather than
     * substituting the placeholder item itself.</p>
     */
    public static Optional<ItemStack> rollFromPool(long rollSeed) {
        long bookSeed = mix(rollSeed, SALT_BOOK_PICK);
        Optional<RandomBookFile> bookOpt = RandomBookRegistry.pickWeighted(bookSeed);
        if (bookOpt.isEmpty()) {
            // Pool empty — caller will leave slot empty. Logged once at
            // registry-load WARN level; don't spam per-roll.
            return Optional.empty();
        }
        RandomBookFile book = bookOpt.get();
        long variantSeed = mix(rollSeed, SALT_VARIANT_PICK);
        String body = book.pickVariant(variantSeed);
        return Optional.of(buildVanillaBook(book, body));
    }

    /**
     * Build a vanilla written book from an explicit body — exposed for the
     * {@code /dungeontrain narrative randombook give <basename>} test
     * command and for direct callers that already know the book.
     */
    public static ItemStack buildVanillaBook(RandomBookFile book, String body) {
        String title = book.title() == null || book.title().isEmpty() || "Untitled".equals(book.title())
            ? book.basename()
            : book.title();
        String author = book.author() == null || book.author().isEmpty()
            ? "Anonymous"
            : book.author();

        List<String> pageStrings = BookFactory.paginate(body);
        if (pageStrings.size() > BookFactory.MAX_PAGES) {
            LOGGER.warn("[DungeonTrain] RandomBook: '{}' produced {} pages — truncating to {} (vanilla cap)",
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
        // Deliberately NOT stamping NarrativeBookTag — random books are not
        // progression-tracked. The read handler treats untagged books as
        // no-op, which is the behaviour we want.
        return stack;
    }

    private static String clampTitle(String s) {
        return s.length() <= BookFactory.MAX_TITLE_CHARS ? s : s.substring(0, BookFactory.MAX_TITLE_CHARS);
    }

    private static String clampAuthor(String s) {
        return s.length() <= BookFactory.MAX_TITLE_CHARS ? s : s.substring(0, BookFactory.MAX_TITLE_CHARS);
    }

    /** Splittable-mix — same family used by {@code ContainerContentsRoller}. */
    private static long mix(long seed, long salt) {
        long state = seed ^ salt;
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        return state;
    }
}
