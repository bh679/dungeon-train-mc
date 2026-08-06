package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Builder for the <b>cursed</b> welcome book — the story of one of the player's Death Note curses
 * landing in someone else's world.
 *
 * <p>Sibling to {@link StartingBookFactory}, with three differences that matter:</p>
 * <ol>
 *   <li><b>Outcome-routed pool.</b> How the curse ended picks the folder
 *       ({@link #contextFor}); an outcome folder with no books authored falls back to the plain
 *       {@link StartingBookContext#CURSED} pool. It never falls back to DEFAULT — a cursed strike
 *       handing out an ordinary welcome book would be a worse bug than no strike at all.</li>
 *   <li><b>Token substitution.</b> The authored variant is a template: {@code %TARGET%},
 *       {@code %CARRIAGE%} and {@code %DAYS%} are filled from the curse before the book is built,
 *       so the same authored prose tells each player's own story.</li>
 *   <li><b>Unseen-first cycling.</b> The next unread curse gets a variant this installation has not
 *       been shown before, so a player who curses repeatedly gets new prose each time, falling back
 *       to a weighted re-use once the pool is exhausted. Shares the per-installation seen-store the
 *       Nether/End welcome playlists use ({@link PlayerPlayedMarker#seenDimensionVariants}), keyed
 *       by {@link StartingBookFactory#dimKey}.</li>
 * </ol>
 *
 * <p>Pagination, title/author clamping and the {@link StartingBookTag} stamp are reused wholesale
 * from {@link StartingBookFactory#buildUnstampedBook}; the extra {@link CursedStoryTag} stamp is what
 * lets the read handler retire this exact story.</p>
 */
public final class CursedBookFactory {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Splittable-mix salt for the cursed (book, variant) pick. */
    private static final long SALT_CURSED_PICK = 0x0DEAD0C0FFEE57A1L;

    /** The name the story falls back to when the relay has no target name for the curse. */
    static final String UNKNOWN_TARGET = "someone";

    private CursedBookFactory() {}

    /** One candidate leaf: a specific {@code (book, variant)} of a cursed pool. */
    private record Leaf(RandomBookFile book, int variantIndex, StartingBookContext context) {}

    /**
     * The pool an ending routes to: the echo won, the target won, or nobody ever found out.
     * An unknown / absent outcome is the plain {@link StartingBookContext#CURSED} pool — those books
     * tell the story without an ending, which is exactly the situation.
     */
    public static StartingBookContext contextFor(String outcome) {
        if (outcome == null) return StartingBookContext.CURSED;
        return switch (outcome) {
            case "echo_killed_target" -> StartingBookContext.CURSED_FULFILLED;
            case "target_killed_echo" -> StartingBookContext.CURSED_DEFIED;
            default -> StartingBookContext.CURSED;
        };
    }

    /**
     * Build the book for {@code story}: pick an outcome-appropriate variant, fill its tokens, and
     * stamp it as both a starting book and this curse's story.
     *
     * <p>Returns empty when no cursed book is authored at all (neither the outcome folder nor the
     * CURSED fallback has content) — the caller then skips the strike rather than substituting an
     * unrelated welcome book.</p>
     *
     * @param seed     roll seed (same shape as the other welcome rolls)
     * @param story    the landed curse being told
     * @param seen     this installation's already-shown cursed variants
     * @param markSeen sink for the picked variant's key; pass a no-op for a non-consuming preview
     */
    public static Optional<ItemStack> roll(long seed, CursedStoryPool.Story story,
                                           Set<String> seen, Consumer<String> markSeen) {
        StartingBookContext context = contextFor(story.outcome());
        List<Leaf> leaves = leavesIn(context);
        if (leaves.isEmpty() && context != StartingBookContext.CURSED) {
            leaves = leavesIn(StartingBookContext.CURSED);   // outcome folder unauthored — tell it plainly
        }
        if (leaves.isEmpty()) {
            LOGGER.warn("[DungeonTrain] CursedBook: no cursed books authored (context {}) — skipping story {}",
                context, story.id());
            return Optional.empty();
        }

        List<Leaf> unseen = new ArrayList<>(leaves.size());
        for (Leaf leaf : leaves) {
            if (!seen.contains(keyOf(leaf))) unseen.add(leaf);
        }
        List<Leaf> candidates = unseen.isEmpty() ? leaves : unseen;   // exhausted → re-use rather than go silent
        Leaf pick = pickWeighted(candidates, StartingBookFactory.mix(seed, SALT_CURSED_PICK));
        markSeen.accept(keyOf(pick));

        RandomBookFile book = pick.book();
        String body = fill(book.variants().get(pick.variantIndex()), story);
        RandomBookFile filled = withTitle(book, fill(book.title(), story));
        ItemStack stack = StartingBookFactory.buildUnstampedBook(filled, body, pick.variantIndex());
        CursedStoryTag.stamp(stack, story.id());
        return Optional.of(stack);
    }

    /**
     * Substitute the story tokens in {@code text}:
     * <ul>
     *   <li>{@code %TARGET%} — the name the author wrote on page one</li>
     *   <li>{@code %CARRIAGE%} — the carriage the author died at, where the echo lay in wait</li>
     *   <li>{@code %DAYS%} — whole days the echo waited between the author's death and the moment it
     *       found its target ({@code 0} when either timestamp is unknown, i.e. "the same day")</li>
     * </ul>
     * Pure and null-safe — a null/empty template returns an empty string.
     */
    static String fill(String text, CursedStoryPool.Story story) {
        if (text == null || text.isEmpty()) return "";
        String target = story.targetName() == null || story.targetName().isBlank()
            ? UNKNOWN_TARGET : story.targetName();
        return text
            .replace("%TARGET%", target)
            .replace("%CARRIAGE%", Integer.toString(story.deathCarriage()))
            .replace("%DAYS%", Long.toString(waitedDays(story)));
    }

    /**
     * Whole days between the curse being uploaded (the author's death) and its echo spawning in the
     * target's world. {@code 0} when either timestamp is missing or the clocks disagree — never
     * negative, because "your echo waited -3 days" is not a story.
     */
    static long waitedDays(CursedStoryPool.Story story) {
        if (story.signedTs() <= 0 || story.landedTs() <= 0) return 0;
        long millis = story.landedTs() - story.signedTs();
        return millis <= 0 ? 0 : TimeUnit.MILLISECONDS.toDays(millis);
    }

    /** Every {@code (book, variant)} of {@code context}'s pool, in the registry's deterministic order. */
    private static List<Leaf> leavesIn(StartingBookContext context) {
        List<Leaf> out = new ArrayList<>();
        for (RandomBookFile book : StartingBookRegistry.booksIn(context)) {
            for (int v = 0; v < book.variants().size(); v++) {
                out.add(new Leaf(book, v, context));
            }
        }
        return out;
    }

    /** This installation's seen-store key for a leaf — namespaced by folder, like the dimension pools. */
    private static String keyOf(Leaf leaf) {
        return StartingBookFactory.dimKey(leaf.context().folderName(), leaf.book().basename(), leaf.variantIndex());
    }

    /**
     * Weighted-by-book pick across leaves: a higher-weight book takes a bigger share, split evenly
     * across its variants (same semantics as the respawn/dimension pickers). All-zero weights degrade
     * to the first leaf rather than returning nothing — a story must still get told.
     */
    private static Leaf pickWeighted(List<Leaf> leaves, long seed) {
        long total = 0L;
        for (Leaf leaf : leaves) total += Math.max(0, leaf.book().weight());
        if (total <= 0) return leaves.get(0);
        long target = (seed & 0x7FFFFFFFFFFFFFFFL) % total;
        for (Leaf leaf : leaves) {
            target -= Math.max(0, leaf.book().weight());
            if (target < 0) return leaf;
        }
        return leaves.get(leaves.size() - 1);   // numerical edge
    }

    /**
     * A copy of {@code book} with its title token-substituted. {@link RandomBookFile} is an immutable
     * record, so the title is swapped by rebuilding rather than mutating — the pool's own copy keeps
     * its template for the next player's story.
     */
    private static RandomBookFile withTitle(RandomBookFile book, String title) {
        return new RandomBookFile(book.id(), title, book.author(), book.generation(),
            book.weight(), book.variants());
    }
}
