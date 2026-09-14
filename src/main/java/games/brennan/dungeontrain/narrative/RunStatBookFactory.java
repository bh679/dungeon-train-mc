package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
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
 * one of you he is addressing. {@code open.6} is the bare salutation — {@code "Brennan,"} and
 * nothing else — weighted to be the common case ({@link #OPENER_PLAIN_WEIGHT} against one apiece
 * for the rest), so half of all books greet the reader and go straight to the number.</p>
 *
 * <h2>What is fixed and what is live</h2>
 * <p>The opener comes from the stamped roll seed, so it is decided at the container and never
 * shifts. The SUBJECT is chosen at the first refresh, from what the holder had actually done by
 * then, and is then fixed too. The follow-up is drawn by the same seed but from a pool the subject's
 * {@link RunStatSubject.Tone tone} decides — see {@link #tailPool} — so it is settled the moment the
 * subject is, and a chest book that resolves to a grim subject may swap its closing line once on the
 * way. Only the number moves after that — and only until the book is opened, at which point
 * {@link RunStatBookTag#lock} freezes the page for good.</p>
 *
 * <h2>He does not congratulate a killing</h2>
 * <p>Every follow-up carries a {@link TailTone}. Positive lines — the encouragements, the approving
 * opinions, the warm questions — never follow a {@link RunStatSubject.Tone#GRIM} reading; negative
 * lines — the disapproving ones, the pointed questions — never follow anything else. Neutral lines
 * fit anywhere. The tone is the RUN's, not the subject's ({@link RunStatSubject#read}): three
 * slimes in a hundred carriages is a plain fact and gets a plain remark; three hundred is grim. So
 * "You've killed 3 passengers." is met with "Would you do it again?" or "I counted every one.", and
 * never "Good. Go further." — while "You've put down no echoes, 40 carriages in." may well be.</p>
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
     * Openers, all of which take the reader's name as their {@code %s}. One of them
     * ({@link #OPENER_PLAIN}) is the bare salutation.
     */
    public static final int OPENER_COUNT = 15;

    /**
     * Index of the bare salutation — the name and nothing else. Sits in the middle of the list
     * because it was once the last of seven, and lang keys never move.
     */
    public static final int OPENER_PLAIN = 6;

    /**
     * Weight of the bare salutation against 1 apiece for the written openers — so half of all books
     * greet the reader and go straight to the number, and Faulthurst's asides stay a surprise.
     */
    private static final int OPENER_PLAIN_WEIGHT = OPENER_COUNT - 1;

    /**
     * Follow-ups: encouragements, questions, opinions, and a few disapprovals. One entry per key in
     * {@link #TAIL_TONES}, which is the authoritative list.
     */
    public static final int TAIL_COUNT = 61;

    /** Whether a follow-up may sit under a kind stat, a grim one, or either. */
    public enum TailTone { POSITIVE, NEUTRAL, NEGATIVE }

    /**
     * The tone of each {@code tail.N}, indexed by N. Kept in the lang file's order with the English
     * beside it, so the table reads as the list it is. Tones are per line, not per block — the
     * original ten questions are mixed.
     */
    static final TailTone[] TAIL_TONES = {
        // encouragements
        TailTone.POSITIVE,  //  0 Keep it up.
        TailTone.POSITIVE,  //  1 Good. Go further.
        TailTone.POSITIVE,  //  2 That's more than most.
        TailTone.POSITIVE,  //  3 I'd have stopped by now.
        TailTone.POSITIVE,  //  4 Onward, then.
        TailTone.POSITIVE,  //  5 You're doing better than you think.
        TailTone.POSITIVE,  //  6 Don't let me interrupt.
        TailTone.POSITIVE,  //  7 The train has noticed.
        TailTone.POSITIVE,  //  8 Worth writing down, I thought.
        TailTone.POSITIVE,  //  9 Carry on.
        // the original questions
        TailTone.NEUTRAL,   // 10 Why?
        TailTone.NEGATIVE,  // 11 Was it worth it?
        TailTone.NEUTRAL,   // 12 On purpose?
        TailTone.NEUTRAL,   // 13 And then?
        TailTone.NEUTRAL,   // 14 Tired yet?
        TailTone.NEUTRAL,   // 15 Who's counting?
        TailTone.NEUTRAL,   // 16 Sure about that?
        TailTone.NEGATIVE,  // 17 Feeling all right?
        TailTone.NEUTRAL,   // 18 Is that a lot?
        TailTone.NEGATIVE,  // 19 Still you in there?
        // more questions
        TailTone.NEUTRAL,   // 20 Do you remember when it started?
        TailTone.NEGATIVE,  // 21 Did you mean to?
        TailTone.NEGATIVE,  // 22 What were you hoping for?
        TailTone.NEUTRAL,   // 23 Would you do it again?
        TailTone.NEUTRAL,   // 24 Do you ever look back?
        TailTone.NEUTRAL,   // 25 Is that enough?
        TailTone.NEUTRAL,   // 26 How does it feel?
        TailTone.NEGATIVE,  // 27 What did it cost you?
        TailTone.NEGATIVE,  // 28 Did it help?
        TailTone.NEUTRAL,   // 29 Where do you think this ends?
        TailTone.NEUTRAL,   // 30 Do you know why you keep going?
        TailTone.POSITIVE,  // 31 Would you have believed that, a few carriages ago?
        TailTone.NEUTRAL,   // 32 Shall I keep counting?
        TailTone.NEGATIVE,  // 33 Should I be worried?
        TailTone.NEUTRAL,   // 34 Are you keeping score too?
        TailTone.NEGATIVE,  // 35 Do you remember why?
        TailTone.POSITIVE,  // 36 Do you know how rare that is?
        TailTone.POSITIVE,  // 37 Did anyone see?
        TailTone.POSITIVE,  // 38 Will you keep it up?
        TailTone.POSITIVE,  // 39 Who taught you that?
        // opinions
        TailTone.POSITIVE,  // 40 I like your style.
        TailTone.POSITIVE,  // 41 I approve, for what it's worth.
        TailTone.POSITIVE,  // 42 The train seems to like you.
        TailTone.POSITIVE,  // 43 I'd have done the same.
        TailTone.NEUTRAL,   // 44 That is interesting.
        TailTone.NEUTRAL,   // 45 I'm surprised.
        TailTone.NEUTRAL,   // 46 Makes you think.
        TailTone.NEUTRAL,   // 47 I didn't expect that.
        TailTone.NEUTRAL,   // 48 Interesting choice.
        TailTone.NEUTRAL,   // 49 I'll remember that.
        TailTone.NEUTRAL,   // 50 That says something about you.
        TailTone.NEUTRAL,   // 51 Noted.
        TailTone.NEUTRAL,   // 52 Hm.
        TailTone.NEUTRAL,   // 53 Unusual, for a passenger.
        TailTone.NEUTRAL,   // 54 I wrote it down twice, to be sure.
        // disapproval
        TailTone.NEGATIVE,  // 55 Not what I'd have done.
        TailTone.NEGATIVE,  // 56 I won't pretend I enjoyed watching.
        TailTone.NEGATIVE,  // 57 I'd have found another way.
        TailTone.NEGATIVE,  // 58 You could stop, you know.
        TailTone.NEGATIVE,  // 59 I counted every one.
        TailTone.NEGATIVE,  // 60 I'm not here to judge. I'm only here.
    };

    /** Indices a kind or plain stat may be followed by: everything but the disapproval. */
    private static final List<Integer> TAILS_AFTER_KIND = tailsOf(TailTone.POSITIVE, TailTone.NEUTRAL);

    /** Indices a grim stat may be followed by: everything but the praise. */
    private static final List<Integer> TAILS_AFTER_GRIM = tailsOf(TailTone.NEUTRAL, TailTone.NEGATIVE);

    static {
        if (TAIL_TONES.length != TAIL_COUNT) {
            throw new IllegalStateException("TAIL_TONES has " + TAIL_TONES.length + " entries for TAIL_COUNT " + TAIL_COUNT);
        }
    }

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
            TITLE, AUTHOR, pages(seed, null, null, "", null));
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

        RunStatSubject.Reading reading = subject.read(run);
        // A kill count that has just ticked from 0 to 1 renders "1" where the page said "40" (the
        // carriage count of the .none line) — different strings, so the swap is caught here too.
        String rendered = subject.rendered(reading.number());
        if (stored.isPresent() && rendered.equals(RunStatBookTag.renderedValue(stack))) return false;

        String locale = WorldInfoReporter.clientLanguage(player);
        ItemStack rebuilt = BookFactory.buildPlainBookComponents(
            TITLE, AUTHOR, pages(seed, subject, reading, locale, player.getName().getString()));
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
     * @param reading    the subject read against the holder's run; {@code null} with {@code subject}.
     * @param playerName {@code null} in the same case — every opener greets someone by name, so
     *                   with nobody to greet there is no opener either.
     */
    static List<Component> pages(long seed, RunStatSubject subject, RunStatSubject.Reading reading,
                                 String localeCode, String playerName) {
        MutableComponent page = Component.empty();
        boolean first = true;

        if (playerName != null && !playerName.isBlank()) {
            page.append(opener(seed, playerName));
            first = false;
        }
        if (subject != null && reading != null) {
            if (!first) page.append("\n\n");
            page.append(subject.line(localeCode, reading.number(), reading.none()));
            first = false;
        }
        if (!first) page.append("\n\n");
        page.append(tail(seed, reading == null ? null : reading.tone()));

        return List.of(page);
    }

    /**
     * The lead-in, which always names {@code playerName}. The written openers share one slot's
     * worth of probability apiece against {@link #OPENER_PLAIN_WEIGHT} for the bare salutation, so
     * being addressed by name is constant and being remarked upon is a coin flip.
     */
    static Component opener(long seed, String playerName) {
        int written = OPENER_COUNT - 1;
        int total = OPENER_PLAIN_WEIGHT + written;
        int roll = (int) Math.floorMod(mix(seed, SALT_OPENER), total);
        int index;
        if (roll < OPENER_PLAIN_WEIGHT) {
            index = OPENER_PLAIN;
        } else {
            int nth = roll - OPENER_PLAIN_WEIGHT;          // the nth written opener, 0-based
            index = nth < OPENER_PLAIN ? nth : nth + 1;    // step over the salutation's slot
        }
        return Component.translatable(KEY_OPENER + index, playerName);
    }

    /**
     * The closing remark, drawn by the seed from the pool {@code tone} allows. The seed is fixed at
     * the container, so the pick is stable for as long as the pool is — which, for everything but a
     * kill count crossing its budget, is from the first refresh on.
     */
    static Component tail(long seed, RunStatSubject.Tone tone) {
        List<Integer> pool = tailPool(tone);
        int index = pool.get((int) Math.floorMod(mix(seed, SALT_TAIL), pool.size()));
        return Component.translatable(KEY_TAIL + index);
    }

    /**
     * Which follow-ups may close a page read as {@code tone}. A book that has no subject yet — one
     * still in its chest — is treated as plain: nothing has been said, so nothing is off-limits but
     * the disapproval.
     */
    static List<Integer> tailPool(RunStatSubject.Tone tone) {
        return tone == RunStatSubject.Tone.GRIM ? TAILS_AFTER_GRIM : TAILS_AFTER_KIND;
    }

    private static List<Integer> tailsOf(TailTone a, TailTone b) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < TAIL_TONES.length; i++) {
            if (TAIL_TONES[i] == a || TAIL_TONES[i] == b) out.add(i);
        }
        return List.copyOf(out);
    }

    /** Splittable-mix — the same family {@link RandomBookFactory} and the roller use. */
    private static long mix(long seed, long salt) {
        long state = seed ^ salt;
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        return state ^ (state >>> 31);
    }
}
