package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Builds the Faulthurst stat book — a one-page note, signed by the mod's watching narrator, that
 * names one real number from the reader's current run.
 *
 * <h2>Three sentences, three keys</h2>
 * <p>A page is an OPENER, the STAT line, and a FOLLOW-UP, each a sentence of its own with its own
 * translation key and no grammatical dependency on the others:</p>
 *
 * <pre>
 *   Brennan, I see you.
 *
 *   You've made it to carriage 14.
 *
 *   Keep it up.
 * </pre>
 *
 * <p>Written that way so all twenty locales can reorder and re-punctuate freely — a fragment glued
 * mid-sentence to another fragment is a sentence only English has agreed to.</p>
 *
 * <p><b>Every opener says the reader's name.</b> It is the difference between a leaflet and a note
 * left for someone: Faulthurst is not narrating the train, he is addressing you, and he knows which
 * one of you he is addressing. The seventh opener is the bare salutation — {@code "Brennan,"} and
 * nothing else — weighted to be the common case ({@link #OPENER_PLAIN_WEIGHT} against one apiece
 * for the rest), so most books greet the reader and go straight to the number.</p>
 *
 * <h2>What is fixed and what is live</h2>
 * <p>The opener and follow-up come from the stamped roll seed, so a given book's WORDING is decided
 * at the container and never shifts. The SUBJECT is chosen at the first refresh, from what the
 * holder had actually done by then, and is then fixed too. Only the number moves — and only until
 * the book is opened, at which point {@link RunStatBookTag#lock} freezes the page for good.</p>
 *
 * <p>A book baked at the container has no subject yet: there is no reader there to have done
 * anything. Until the first refresh it is opener + follow-up alone — a terse but honest scrap,
 * rather than a sentence with a number in it that nobody earned.</p>
 */
public final class RunStatBookFactory {

    /** Signed by the narrator who has been watching the whole time. */
    public static final String AUTHOR = "Faulthurst";

    /**
     * English cover title. {@code WrittenBookContent} takes a plain string, so — as with
     * {@link LeaderboardBookFactory} — there is nowhere for a translation to happen.
     */
    public static final String TITLE = "A Note From Faulthurst";

    /**
     * Openers, all of which take the reader's name as their {@code %s}. The last
     * ({@link #OPENER_PLAIN}) is the bare salutation.
     */
    public static final int OPENER_COUNT = 7;

    /** Index of the bare salutation — the name and nothing else. */
    public static final int OPENER_PLAIN = OPENER_COUNT - 1;

    /**
     * Weight of the bare salutation against 1 apiece for the six written openers — so most books
     * greet the reader and go straight to the number, and Faulthurst's asides stay a surprise.
     */
    private static final int OPENER_PLAIN_WEIGHT = 6;

    /** Follow-ups: ten encouragements and ten very short questions. */
    public static final int TAIL_COUNT = 20;

    private static final String KEY_OPENER = RunStatSubject.KEY_ROOT + "open.";
    private static final String KEY_TAIL = RunStatSubject.KEY_ROOT + "tail.";

    /** Splittable-mix salts, so the three picks do not correlate with each other or the slot. */
    private static final long SALT_OPENER  = 0xFA0175B00C0DEAD1L;
    private static final long SALT_TAIL    = 0x7A11B00C0FFEE511L;
    private static final long SALT_SUBJECT = 0x57A7B00C1DEA5001L;

    private RunStatBookFactory() {}

    /**
     * Bake the stack a container drops: a signed note carrying the roll seed, and nothing that
     * needs a reader. {@code RunStatBookEvents} writes the greeting and the number the moment it
     * reaches a hand.
     *
     * <p>A container knows neither WHO will find this nor what they have done, and both the opener
     * and the stat line now need the first of those. So the baked page is the closing remark alone.
     * It is a placeholder with a very short life — a written book cannot be opened without being
     * held, and being held is what resolves it — but it is a real, readable, signed book for as long
     * as it sits in the chest.</p>
     */
    public static ItemStack create(long seed) {
        ItemStack stack = BookFactory.buildPlainBookComponents(
            TITLE, AUTHOR, pages(seed, null, "", 0L, null));
        RunStatBookTag.stamp(stack, seed);
        return stack;
    }

    /**
     * Re-bake {@code stack}'s page from {@code player}'s live run, if anything it says would change.
     *
     * <p>Returns {@code false} — having touched nothing — when the stack is not a stat book, when it
     * has already been opened and locked, or when the number renders identically to what the page
     * already carries. That last case is the common one: this runs once a second against every book
     * in a player's bag, and a count that has not moved must not churn the stack or re-sync it to
     * the client.</p>
     */
    public static boolean refresh(ItemStack stack, ServerPlayer player) {
        if (player == null || !RunStatBookTag.is(stack)) return false;
        if (RunStatBookTag.isLocked(stack)) return false; // opened — the page is final

        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        long seed = RunStatBookTag.seed(stack, 0L);
        // Read the stored subject ONCE — this runs every sweep against every book in every player's
        // bag, and each read copies the stack's whole NBT compound.
        Optional<RunStatSubject> stored = RunStatBookTag.subject(stack);
        RunStatSubject subject = stored.orElseGet(() -> chooseSubject(seed, run));

        long value = subject.value(run);
        String rendered = subject.rendered(value);
        if (stored.isPresent() && rendered.equals(RunStatBookTag.renderedValue(stack))) return false;

        String locale = WorldInfoReporter.clientLanguage(player);
        ItemStack rebuilt = BookFactory.buildPlainBookComponents(
            TITLE, AUTHOR, pages(seed, subject, locale, value, player.getName().getString()));
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, rebuilt.get(DataComponents.WRITTEN_BOOK_CONTENT));
        RunStatBookTag.recordBaked(stack, subject, rendered);
        return true;
    }

    /**
     * Which counter this book settles on: a seeded pick across everything {@code run} has done
     * enough of to be worth remarking on. {@link RunStatSubject#eligible} guarantees a non-empty
     * list, so this always answers.
     */
    static RunStatSubject chooseSubject(long seed, PlayerRunState run) {
        List<RunStatSubject> eligible = RunStatSubject.eligible(run);
        return eligible.get((int) Math.floorMod(mix(seed, SALT_SUBJECT), eligible.size()));
    }

    /**
     * The book's single page. Package-private and free of item/NBT concerns so the composition can
     * be tested without building a stack.
     *
     * @param subject    {@code null} while the book has not met a reader yet — the stat line is
     *                   then omitted rather than invented.
     * @param playerName {@code null} in the same case — every opener greets someone by name, so
     *                   with nobody to greet there is no opener either.
     */
    static List<Component> pages(long seed, RunStatSubject subject, String localeCode, long value,
                                 String playerName) {
        MutableComponent page = Component.empty();
        boolean first = true;

        if (playerName != null && !playerName.isBlank()) {
            page.append(opener(seed, playerName));
            first = false;
        }
        if (subject != null) {
            if (!first) page.append("\n\n");
            page.append(subject.line(localeCode, value));
            first = false;
        }
        if (!first) page.append("\n\n");
        page.append(tail(seed));

        return List.of(page);
    }

    /**
     * The lead-in, which always names {@code playerName}. The six written openers share one slot's
     * worth of probability between them against {@link #OPENER_PLAIN_WEIGHT} for the bare
     * salutation, so being addressed by name is constant and being remarked upon is not.
     */
    static Component opener(long seed, String playerName) {
        int written = OPENER_COUNT - 1;
        int total = OPENER_PLAIN_WEIGHT + written;
        int roll = (int) Math.floorMod(mix(seed, SALT_OPENER), total);
        int index = roll < OPENER_PLAIN_WEIGHT ? OPENER_PLAIN : roll - OPENER_PLAIN_WEIGHT;
        return Component.translatable(KEY_OPENER + index, playerName);
    }

    /** The closing remark — an encouragement or a very short question. */
    static Component tail(long seed) {
        return Component.translatable(KEY_TAIL + Math.floorMod(mix(seed, SALT_TAIL), TAIL_COUNT));
    }

    /** Splittable-mix — the same family {@link RandomBookFactory} and the roller use. */
    private static long mix(long seed, long salt) {
        long state = seed ^ salt;
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        return state ^ (state >>> 31);
    }
}
