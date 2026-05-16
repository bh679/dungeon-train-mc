package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Produces a Minecraft {@code Items.WRITTEN_BOOK} {@link ItemStack} from a
 * {@link Letter} of a {@link StoryFile}. One Letter → one signed book; the
 * Letter's variants are picked deterministically from the spawn seed so the
 * same lectern + same world seed always shows the same alt.
 *
 * <p>Page break strategy (in priority order):
 * <ol>
 *   <li>Paragraph boundary (double-newline in the source)</li>
 *   <li>Sentence boundary inside an oversize paragraph</li>
 *   <li>Word boundary inside an oversize sentence</li>
 *   <li>Hard char split (never reached for normal English text — only kicks
 *       in for runes / cipher text without spaces)</li>
 * </ol>
 *
 * <p>Vanilla limits enforced: title clamped to {@link #MAX_TITLE_CHARS},
 * pages capped at {@link #MAX_PAGES} (overflow truncated with a warn log).</p>
 */
public final class BookFactory {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Target chars per page. Vanilla's hard storage limit is
     * {@link WrittenBookContent#PAGE_LENGTH} = 32767, but the in-game book
     * UI fits ~14 lines × ~19 chars = ~266 chars before content runs off
     * the page. 256 leaves a small visual buffer.
     */
    private static final int MAX_CHARS_PER_PAGE = 256;

    /** Vanilla cap on pages per signed book — anything beyond is truncated. */
    public static final int MAX_PAGES = 100;

    /** Vanilla cap on title length (see {@link WrittenBookContent#TITLE_MAX_LENGTH}). */
    public static final int MAX_TITLE_CHARS = 32;

    private BookFactory() {}

    /**
     * Build a signed-book ItemStack for {@code letter} from {@code parent}.
     * The {@code seed} drives variant selection (mod the variant count) so
     * carriage placements are reproducible per world seed + carriage index.
     */
    public static ItemStack buildSignedBook(StoryFile parent, Letter letter, long seed) {
        String body = letter.pickVariant(seed);
        return buildFromBody(parent, letter, body);
    }

    /**
     * Player-aware spawn — picks the next unread letter for the player and
     * builds a book for it. If {@code preferStory} is present and not
     * complete for the player, uses that story; otherwise falls forward to
     * the player's next-uncompleted story (alphabetical by basename).
     *
     * <p>Returns {@link Optional#empty()} when every loaded story is
     * complete for the player. Caller surfaces "all stories complete" to
     * the player.</p>
     *
     * <p>This method does NOT advance progress — that's the read-event
     * handler's job. The book is just stamped with its identity NBT.</p>
     */
    public static Optional<ItemStack> buildNextForPlayer(
        ServerLevel overworld, ServerPlayer player, Optional<StoryFile> preferStory
    ) {
        NarrativeProgressData data = NarrativeProgressData.get(overworld);
        StoryFile story = null;
        if (preferStory.isPresent()) {
            StoryFile candidate = preferStory.get();
            String basename = basenameOf(candidate);
            if (!data.progressFor(player.getUUID(), basename).isComplete(candidate.letters().size())) {
                story = candidate;
            }
        }
        if (story == null) {
            Optional<String> nextBasename = data.nextUncompletedStory(player.getUUID());
            if (nextBasename.isEmpty()) return Optional.empty();
            Optional<StoryFile> resolved = StoryRegistry.getByBasename(nextBasename.get());
            if (resolved.isEmpty()) return Optional.empty();
            story = resolved.get();
        }
        String basename = basenameOf(story);
        int next = data.progressFor(player.getUUID(), basename).nextUnreadLetter(story.letters().size());
        if (next < 1) return Optional.empty();
        Letter letter = story.letterByIndex(next).orElse(null);
        if (letter == null) return Optional.empty();
        long seed = overworld.getGameTime() ^ player.getUUID().getLeastSignificantBits();
        return Optional.of(buildSignedBook(story, letter, seed));
    }

    /**
     * Lazy lectern resolver — picks "what book should this lectern show this
     * player right now":
     * <ol>
     *   <li>If the player has any in-progress story (started, not complete) —
     *       the next-unread letter of that story.</li>
     *   <li>Otherwise — a random pick from the player's uncompleted stories,
     *       seeded by {@code lecternSeed} so the same lectern shows the same
     *       story across re-clicks until the player actually advances.</li>
     *   <li>If every story is complete → {@link Optional#empty()}.</li>
     * </ol>
     *
     * <p>The returned ItemStack is stamped with the identity NBT just like
     * {@link #buildSignedBook} — so the read-event handler advances progress
     * automatically when the player opens it.</p>
     *
     * @param overworld   The overworld ServerLevel (where progress is stored).
     * @param player      The player asking — drives all per-player progression.
     * @param lecternSeed A long stable per-lectern (e.g. {@code pos.asLong()})
     *                    used in the random-pick fallback. Mixing in player
     *                    UUID happens internally.
     */
    public static Optional<ItemStack> buildOrRandomForPlayer(
        ServerLevel overworld, ServerPlayer player, long lecternSeed
    ) {
        NarrativeProgressData data = NarrativeProgressData.get(overworld);

        Optional<String> inProgress = data.currentInProgressStory(player.getUUID());
        Optional<String> chosen = inProgress;
        if (chosen.isEmpty()) {
            long mixed = lecternSeed ^ player.getUUID().getLeastSignificantBits()
                                     ^ (player.getUUID().getMostSignificantBits() << 1);
            chosen = data.randomUncompletedStory(player.getUUID(), mixed);
        }
        if (chosen.isEmpty()) return Optional.empty();

        Optional<StoryFile> storyOpt = StoryRegistry.getByBasename(chosen.get());
        if (storyOpt.isEmpty()) return Optional.empty();
        StoryFile story = storyOpt.get();

        int next = data.progressFor(player.getUUID(), chosen.get())
            .nextUnreadLetter(story.letters().size());
        if (next < 1) return Optional.empty();
        Letter letter = story.letterByIndex(next).orElse(null);
        if (letter == null) return Optional.empty();

        long variantSeed = overworld.getGameTime() ^ player.getUUID().getLeastSignificantBits();
        return Optional.of(buildSignedBook(story, letter, variantSeed));
    }

    /**
     * Build a signed book from an explicit body string — useful for tests and
     * for callers that want a specific variant rather than a seeded pick.
     */
    public static ItemStack buildFromBody(StoryFile parent, Letter letter, String body) {
        String title = preferredTitle(parent, letter);
        String author = parent.character() == null || parent.character().isEmpty()
            ? "Anonymous"
            : parent.character();

        List<String> pageStrings = paginate(body);
        if (pageStrings.size() > MAX_PAGES) {
            LOGGER.warn("[DungeonTrain] Narrative: {} '{}' produced {} pages — truncating to {} (vanilla cap)",
                parent.id(), letter.label(), pageStrings.size(), MAX_PAGES);
            pageStrings = pageStrings.subList(0, MAX_PAGES);
        }

        List<Filterable<Component>> pages = new ArrayList<>(pageStrings.size());
        for (String page : pageStrings) {
            pages.add(Filterable.passThrough(Component.literal(page)));
        }
        if (pages.isEmpty()) {
            // Defensive: an empty body shouldn't happen (Letter constructor
            // rejects empty variants), but if it does, ship a single blank
            // page so the book is still openable rather than a hard failure.
            pages.add(Filterable.passThrough(Component.literal("")));
        }

        WrittenBookContent content = new WrittenBookContent(
            Filterable.passThrough(clampTitle(title)),
            clampAuthor(author),
            /*generation*/ 0,
            pages,
            /*resolved*/ true
        );

        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        NarrativeBookTag.stamp(stack, basenameOf(parent), letter.index());
        return stack;
    }

    /**
     * Story basename = path tail of the registry id. Used for stamping the
     * identity NBT and for the progression data lookup.
     */
    private static String basenameOf(StoryFile story) {
        String path = story.id().getPath();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * Title preference: the story's {@code Story:} header if it's not the
     * "Untitled" sentinel, else the letter label. Visible in the inventory
     * tooltip and at the top of the lectern's open-book screen.
     */
    private static String preferredTitle(StoryFile parent, Letter letter) {
        String story = parent.story();
        if (story != null && !story.isEmpty() && !"Untitled".equals(story)) {
            return story;
        }
        return letter.label();
    }

    private static String clampTitle(String s) {
        return s.length() <= MAX_TITLE_CHARS ? s : s.substring(0, MAX_TITLE_CHARS);
    }

    private static String clampAuthor(String s) {
        // Author has no codec-enforced cap, but keep it short for the
        // tooltip line. 32 mirrors the title cap.
        return s.length() <= MAX_TITLE_CHARS ? s : s.substring(0, MAX_TITLE_CHARS);
    }

    /**
     * Split {@code body} into pages of at most {@link #MAX_CHARS_PER_PAGE}
     * characters each. Uses the priority described in the class javadoc.
     */
    static List<String> paginate(String body) {
        List<String> pages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        // Split on one-or-more blank lines (paragraph break).
        String[] paragraphs = body.split("\\n\\s*\\n");
        for (String paragraph : paragraphs) {
            String para = paragraph.strip();
            if (para.isEmpty()) continue;

            if (para.length() > MAX_CHARS_PER_PAGE) {
                // Doesn't fit on one page even alone. Flush whatever's pending,
                // then split this paragraph using sentence / word fallbacks.
                flush(current, pages);
                for (String chunk : splitOversize(para)) {
                    pages.add(chunk);
                }
                continue;
            }

            int needed = current.length() == 0 ? para.length() : current.length() + 2 + para.length();
            if (needed > MAX_CHARS_PER_PAGE) {
                flush(current, pages);
                current.append(para);
            } else {
                if (current.length() > 0) current.append("\n\n");
                current.append(para);
            }
        }
        flush(current, pages);
        return pages;
    }

    private static void flush(StringBuilder current, List<String> pages) {
        if (current.length() == 0) return;
        pages.add(current.toString());
        current.setLength(0);
    }

    /**
     * Paragraph too big for one page — split on sentence boundaries first,
     * then word boundaries, packing each page as full as possible.
     */
    private static List<String> splitOversize(String paragraph) {
        List<String> out = new ArrayList<>();
        // Sentence split: keep the punctuation by using a regex with lookbehind.
        String[] sentences = paragraph.split("(?<=[.!?])\\s+");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence.length() > MAX_CHARS_PER_PAGE) {
                if (current.length() > 0) { out.add(current.toString()); current.setLength(0); }
                for (String wordChunk : splitByWord(sentence)) {
                    out.add(wordChunk);
                }
                continue;
            }
            int needed = current.length() == 0 ? sentence.length() : current.length() + 1 + sentence.length();
            if (needed > MAX_CHARS_PER_PAGE) {
                out.add(current.toString());
                current.setLength(0);
                current.append(sentence);
            } else {
                if (current.length() > 0) current.append(' ');
                current.append(sentence);
            }
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    /**
     * Last-resort splitter — sentence longer than a page (rare). Pack words
     * one at a time, hard-break only if a single word exceeds page size.
     */
    private static List<String> splitByWord(String sentence) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : sentence.split("\\s+")) {
            if (word.length() > MAX_CHARS_PER_PAGE) {
                if (current.length() > 0) { out.add(current.toString()); current.setLength(0); }
                int idx = 0;
                while (idx < word.length()) {
                    int end = Math.min(word.length(), idx + MAX_CHARS_PER_PAGE);
                    out.add(word.substring(idx, end));
                    idx = end;
                }
                continue;
            }
            int needed = current.length() == 0 ? word.length() : current.length() + 1 + word.length();
            if (needed > MAX_CHARS_PER_PAGE) {
                out.add(current.toString());
                current.setLength(0);
                current.append(word);
            } else {
                if (current.length() > 0) current.append(' ');
                current.append(word);
            }
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }
}
