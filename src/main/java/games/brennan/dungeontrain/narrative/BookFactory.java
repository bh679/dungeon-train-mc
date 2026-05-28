package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
 *   <li>Hard page break (three or more consecutive newlines in the source —
 *       always splits, regardless of content length)</li>
 *   <li>Paragraph boundary (double-newline in the source — soft break;
 *       paragraphs whose combined length fits the page char budget are
 *       greedy-packed onto the same page)</li>
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
     *
     * <p>Package-private so {@link StartingBookFactory#paginateExplicit} can
     * use the same threshold when deciding whether to fall back to auto-flow
     * pagination for an oversize chunk.</p>
     */
    static final int MAX_CHARS_PER_PAGE = 256;

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
        int variantIndex = letter.pickVariantIndex(seed);
        String body = letter.variants().get(variantIndex);
        return buildFromBody(parent, letter, body, variantIndex);
    }

    /**
     * World-aware spawn — picks the next unread letter for the world and
     * builds a book for it. If {@code preferStory} is present and not yet
     * complete for the world, uses that story; otherwise falls forward to
     * the world's next-uncompleted story (alphabetical by basename).
     *
     * <p>Returns {@link Optional#empty()} when every loaded story is
     * complete for the world. Caller surfaces "all stories complete".</p>
     *
     * <p>This method does NOT advance progress — that's the read-event
     * handler's job. The book is just stamped with its identity NBT.</p>
     */
    public static Optional<ItemStack> buildNext(
        ServerLevel overworld, Optional<StoryFile> preferStory
    ) {
        NarrativeProgressData data = NarrativeProgressData.get(overworld);
        StoryFile story = null;
        if (preferStory.isPresent()) {
            StoryFile candidate = preferStory.get();
            String basename = basenameOf(candidate);
            if (!data.progressFor(basename).isComplete(candidate.letters().size())) {
                story = candidate;
            }
        }
        if (story == null) {
            Optional<String> nextBasename = data.nextUncompletedStory();
            if (nextBasename.isEmpty()) return Optional.empty();
            Optional<StoryFile> resolved = StoryRegistry.getByBasename(nextBasename.get());
            if (resolved.isEmpty()) return Optional.empty();
            story = resolved.get();
        }
        String basename = basenameOf(story);
        int next = data.progressFor(basename).nextUnreadLetter(story.letters().size());
        if (next < 1) return Optional.empty();
        Letter letter = story.letterByIndex(next).orElse(null);
        if (letter == null) return Optional.empty();
        // Variant seed: stable per (story, letter) so handing out the same
        // letter twice doesn't flip variant. Mixing the letter index keeps
        // distinct letters from collapsing onto the same variant.
        long seed = ((long) basename.hashCode() << 32) ^ letter.index();
        return Optional.of(buildSignedBook(story, letter, seed));
    }

    /**
     * Lazy lectern resolver — picks "what book should this lectern show
     * right now" for the world:
     * <ol>
     *   <li>If the world has any in-progress story (started, not complete) —
     *       the next-unread letter of that story.</li>
     *   <li>Otherwise — a random pick from the world's uncompleted stories,
     *       seeded by {@code lecternSeed} so the same lectern shows the same
     *       story across re-clicks until something actually advances.</li>
     *   <li>If every story is complete → {@link Optional#empty()}.</li>
     * </ol>
     *
     * <p>The returned ItemStack is stamped with the identity NBT just like
     * {@link #buildSignedBook} — so the read-event handler advances world
     * progress automatically when the book is opened.</p>
     *
     * @param overworld   The overworld ServerLevel (where progress is stored).
     * @param lecternSeed A long stable per-lectern (e.g. {@code pos.asLong()})
     *                    used in the random-pick fallback and as the variant
     *                    seed. Same lectern → same variant forever, which
     *                    is exactly what lock-on-first-read demands.
     */
    public static Optional<ItemStack> buildOrRandomForLectern(
        ServerLevel overworld, long lecternSeed
    ) {
        NarrativeProgressData data = NarrativeProgressData.get(overworld);

        Optional<String> chosen = data.currentInProgressStory();
        if (chosen.isEmpty()) {
            chosen = data.randomUncompletedStory(lecternSeed);
        }
        if (chosen.isEmpty()) return Optional.empty();

        Optional<StoryFile> storyOpt = StoryRegistry.getByBasename(chosen.get());
        if (storyOpt.isEmpty()) return Optional.empty();
        StoryFile story = storyOpt.get();

        int next = data.progressFor(chosen.get())
            .nextUnreadLetter(story.letters().size());
        if (next < 1) return Optional.empty();
        Letter letter = story.letterByIndex(next).orElse(null);
        if (letter == null) return Optional.empty();

        return Optional.of(buildSignedBook(story, letter, lecternSeed));
    }

    /**
     * Build a signed book from an explicit body string — useful for tests and
     * for callers that want a specific variant rather than a seeded pick.
     *
     * <p>Variant-unaware overload — stamps {@link NarrativeBookTag#VARIANT_UNKNOWN}
     * so the read-event handler can't credit a variant. Prefer the four-arg
     * form when the caller knows which variant index produced {@code body}.</p>
     */
    public static ItemStack buildFromBody(StoryFile parent, Letter letter, String body) {
        return buildFromBody(parent, letter, body, NarrativeBookTag.VARIANT_UNKNOWN);
    }

    /**
     * Build a signed book from an explicit body string with a known
     * {@code variantIndex} stamped on the resulting stack. The variant index
     * is what {@link NarrativeBookEvents} uses to credit per-variant progress
     * toward the {@code read_all_story_variants} achievement.
     */
    public static ItemStack buildFromBody(StoryFile parent, Letter letter, String body, int variantIndex) {
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
        NarrativeBookTag.stamp(stack, basenameOf(parent), letter.index(), variantIndex);
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
     * characters each. First splits on hard page breaks (three or more
     * consecutive newlines) — each section then runs through the greedy
     * paragraph packer ({@link #paginateGreedy}). Authors who want sparse,
     * beat-per-page layout insert {@code \n\n\n} between beats; {@code \n\n}
     * remains a soft paragraph break that may pack onto the same page.
     */
    static List<String> paginate(String body) {
        List<String> pages = new ArrayList<>();
        // Hard page break: three or more consecutive newlines. Each section
        // between hard breaks then runs through the greedy paragraph packer.
        String[] sections = body.split("\\n{3,}");
        for (String section : sections) {
            if (section.isEmpty()) continue;
            pages.addAll(paginateGreedy(section));
        }
        return pages;
    }

    /**
     * Greedy paragraph-packing — splits on soft paragraph breaks
     * ({@code \n} + optional whitespace + {@code \n}) and packs as many
     * paragraphs onto each page as the {@link #MAX_CHARS_PER_PAGE} budget
     * allows. Oversize paragraphs spill via {@link #splitOversize}.
     */
    private static List<String> paginateGreedy(String body) {
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
