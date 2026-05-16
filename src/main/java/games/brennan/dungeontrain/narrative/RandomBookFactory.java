package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 * <p>Every built stack carries a {@link RandomBookTag} identity component
 * ({@code (bookBasename, variantIndex)}) so the equipment-change handler
 * in {@link NarrativeBookEvents} can recognise the stack and, when the
 * holder has already seen the tuple, swap content via
 * {@link #replaceStackContent} before the book screen opens.</p>
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
     * A picked {@code (book, variantIndex)} tuple. Returned by
     * {@link #pickUnseenForWorld} and consumed by {@link #replaceStackContent}.
     */
    public record PickedBook(RandomBookFile book, int variantIndex) {}

    /**
     * Pick a book from the pool, pick a variant from that book, and build a
     * stamped vanilla {@link Items#WRITTEN_BOOK} stack ready to drop into a
     * chest slot. Deterministic per {@code rollSeed}.
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
        int variantIndex = book.pickVariantIndex(variantSeed);
        String body = book.variants().get(variantIndex);
        return Optional.of(buildVanillaBook(book, body, variantIndex));
    }

    /**
     * Pick an unseen {@code (book, variantIndex)} tuple for the world.
     * Books with at least one unseen variant are weighted by their pool
     * weight; within the picked book, an unseen variant is chosen uniformly
     * from the unseen set.
     *
     * <p>If every variant of every loaded book has been seen, the world's
     * random-book tracking is <b>reset</b> (silent cycle) and the pick
     * retries against the now-empty seen set — so the caller always gets
     * a fresh pick when the pool is non-empty.</p>
     *
     * <p>Returns {@link Optional#empty()} only when the registry holds no
     * books at all (or every book has weight 0 — same outcome).</p>
     */
    public static Optional<PickedBook> pickUnseenForWorld(NarrativeProgressData data, long rollSeed) {
        if (RandomBookRegistry.count() == 0) return Optional.empty();
        long bookSeed = mix(rollSeed, SALT_BOOK_PICK);
        long variantSeed = mix(rollSeed, SALT_VARIANT_PICK);
        Optional<PickedBook> first = tryPickUnseen(data, bookSeed, variantSeed);
        if (first.isPresent()) return first;
        // World has seen every variant of every loaded book — silent cycle.
        data.resetRandomBookProgress();
        return tryPickUnseen(data, bookSeed, variantSeed);
    }

    private static Optional<PickedBook> tryPickUnseen(NarrativeProgressData data,
                                                      long bookSeed, long variantSeed) {
        List<RandomBookFile> candidates = new ArrayList<>();
        int totalWeight = 0;
        for (ResourceLocation id : RandomBookRegistry.ids()) {
            Optional<RandomBookFile> bookOpt = RandomBookRegistry.get(id);
            if (bookOpt.isEmpty()) continue;
            RandomBookFile book = bookOpt.get();
            if (book.weight() <= 0) continue;
            if (hasAnyUnseenVariant(data, book)) {
                candidates.add(book);
                totalWeight += book.weight();
            }
        }
        if (candidates.isEmpty() || totalWeight <= 0) return Optional.empty();

        // Weighted pick across candidates.
        long unsignedBook = bookSeed & 0x7FFFFFFFFFFFFFFFL;
        int target = (int) (unsignedBook % totalWeight);
        RandomBookFile picked = candidates.get(candidates.size() - 1);
        for (RandomBookFile c : candidates) {
            target -= c.weight();
            if (target < 0) { picked = c; break; }
        }

        // Uniform pick across the picked book's unseen variants.
        List<Integer> unseen = collectUnseenVariantIndices(data, picked);
        if (unseen.isEmpty()) return Optional.empty(); // defensive: hasAnyUnseenVariant said yes
        long unsignedVariant = variantSeed & 0x7FFFFFFFFFFFFFFFL;
        int variantIndex = unseen.get((int) (unsignedVariant % unseen.size()));
        return Optional.of(new PickedBook(picked, variantIndex));
    }

    private static boolean hasAnyUnseenVariant(NarrativeProgressData data, RandomBookFile book) {
        int total = book.variants().size();
        for (int i = 0; i < total; i++) {
            if (!data.hasSeenRandomBook(book.basename(), i)) return true;
        }
        return false;
    }

    private static List<Integer> collectUnseenVariantIndices(NarrativeProgressData data, RandomBookFile book) {
        List<Integer> out = new ArrayList<>();
        int total = book.variants().size();
        for (int i = 0; i < total; i++) {
            if (!data.hasSeenRandomBook(book.basename(), i)) out.add(i);
        }
        return out;
    }

    /**
     * Mutate {@code stack}'s {@link DataComponents#WRITTEN_BOOK_CONTENT} and
     * {@link RandomBookTag} components to point at {@code picked}. Used by
     * the equipment-change handler to swap a stale random-book stack to
     * unseen content before the player right-clicks to open it.
     *
     * <p>Other components on the stack (e.g. unrelated custom data) are
     * preserved — only the book content and the random-book identity tag
     * are overwritten.</p>
     */
    public static void replaceStackContent(ItemStack stack, PickedBook picked) {
        String body = picked.book().variants().get(picked.variantIndex());
        ItemStack temp = buildVanillaBook(picked.book(), body, picked.variantIndex());
        WrittenBookContent newContent = temp.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (newContent != null) {
            stack.set(DataComponents.WRITTEN_BOOK_CONTENT, newContent);
        }
        // Re-stamp the identity tag with the new (basename, variantIndex)
        // — uses CustomData.update internally so other CUSTOM_DATA keys
        // (if any) are preserved.
        RandomBookTag.stamp(stack, picked.book().basename(), picked.variantIndex());
    }

    /**
     * Build a vanilla written book from an explicit body and variant index.
     * Stamps the {@link RandomBookTag} identity component so the
     * equipment-change handler can detect and re-roll stale stacks.
     */
    public static ItemStack buildVanillaBook(RandomBookFile book, String body, int variantIndex) {
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
        // Identity stamp so per-player tracking and on-equip re-roll can
        // recognise this stack later.
        RandomBookTag.stamp(stack, book.basename(), variantIndex);
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
