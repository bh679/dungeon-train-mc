package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.SharedBookGate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
     * Lazy lectern resolver — picks "what book should this lectern show right now" for the world. The
     * world reads one narrative thread at a time (mirroring the mod-story cursor); continuity is resolved
     * first, and a weighted coin only decides the TYPE of a NEW thread:
     * <ol>
     *   <li><b>(a)</b> Continue an in-progress MOD story (next-unread letter).</li>
     *   <li><b>(b)</b> Continue an in-progress PLAYER narrative series (most-recently-started first) —
     *       its next-unread letter, resolved from {@link NarrativePool}.</li>
     *   <li><b>(c)</b> Coin flip ({@link #narrativeLecternChanceForWorld}, tapered): with that chance
     *       START a new approved player series instead of a new mod story.</li>
     *   <li><b>(d)</b> Otherwise serve a MOD story — a random uncompleted one, or (once all are read) a
     *       seeded RE-READ of an already-read one, so built-in stories keep their fair share and never
     *       vanish.</li>
     *   <li><b>(e)</b> Only when there is no content at all (no mod stories AND empty player pool) →
     *       {@link Optional#empty()} (the lectern then shows "All narratives complete").</li>
     * </ol>
     *
     * <p>A mod-story book is stamped with {@link NarrativeBookTag} (advances canon progress on read); a
     * player-series book is stamped with {@link PlayerNarrativeBookTag} (advances only the world-local
     * player-series read-set). The pick is deterministic per {@code lecternSeed}+world-seed, so a lectern
     * is stable across re-clicks until world state advances — exactly what lock-on-first-read demands.</p>
     *
     * @param overworld   The overworld ServerLevel (where progress is stored).
     * @param lecternSeed A long stable per-lectern (e.g. {@code pos.asLong()}) used for the coin flip,
     *                    the random/re-read pick, and the variant seed.
     */
    public static Optional<ItemStack> buildOrRandomForLectern(
        ServerLevel overworld, long lecternSeed
    ) {
        NarrativeProgressData data = NarrativeProgressData.get(overworld);

        // Mix the world seed into the lectern-position seed. The train spawns at a deterministic origin,
        // so the first lectern a player reaches sits at a near-constant coordinate — without this,
        // floorMod(pos, n) lands on the same story every world (the "always pip" bug). Stable across
        // reloads, different per world. Drives the coin flip, the random/re-read pick, and the variant pick.
        long seed = lecternSeed + overworld.getSeed();

        // (a) Continue an in-progress MOD story.
        Optional<String> inProgress = data.currentInProgressStory();
        if (inProgress.isPresent()) {
            Optional<ItemStack> book = buildModStoryLetter(data, inProgress.get(), seed);
            if (book.isPresent()) return book;
        }

        // (b) Continue an in-progress PLAYER series (most recently started first).
        Optional<ItemStack> continued = continueInProgressPlayerSeries(data, seed);
        if (continued.isPresent()) return continued;

        // (c) Coin flip: with the tapered chance, START a new player series instead of a new mod story.
        if (SharedBookGate.canDiscoverNarratives() && !NarrativePool.isEmpty()
                && rollNarrativeChance(narrativeLecternChanceForWorld(overworld, data), seed)) {
            Optional<ItemStack> started = startNewPlayerSeries(data, seed);
            if (started.isPresent()) return started;
            // no unstarted series available right now → fall through to a mod story
        }

        // (d) Serve a MOD story: a random uncompleted one, else a seeded RE-READ of an already-read one.
        Optional<String> fresh = data.randomUncompletedStory(seed);
        if (fresh.isPresent()) {
            Optional<ItemStack> book = buildModStoryLetter(data, fresh.get(), seed);
            if (book.isPresent()) return book;
        }
        Optional<ItemStack> reread = buildModStoryReread(seed);
        if (reread.isPresent()) return reread;

        // (e) No content at all → let the lectern show "All narratives complete".
        return Optional.empty();
    }

    /** Build the next-unread letter of mod story {@code basename}, or empty if the story is unknown/complete. */
    private static Optional<ItemStack> buildModStoryLetter(NarrativeProgressData data, String basename, long seed) {
        Optional<StoryFile> storyOpt = StoryRegistry.getByBasename(basename);
        if (storyOpt.isEmpty()) return Optional.empty();
        StoryFile story = storyOpt.get();
        int next = data.progressFor(basename).nextUnreadLetter(story.letters().size());
        if (next < 1) return Optional.empty();
        Letter letter = story.letterByIndex(next).orElse(null);
        if (letter == null) return Optional.empty();
        return Optional.of(buildSignedBook(story, letter, seed));
    }

    /**
     * A seeded RE-READ of an already-read mod story — served once every story is complete so built-in
     * content keeps its fair share rather than vanishing. Picks a story + letter deterministically from
     * {@code seed}; the book is a normal mod-story book ({@link NarrativeBookTag}), and its idempotent
     * read-credit re-marks already-read progress harmlessly.
     */
    private static Optional<ItemStack> buildModStoryReread(long seed) {
        List<String> names = StoryRegistry.basenames();
        if (names.isEmpty()) return Optional.empty();
        String basename = names.get((int) Math.floorMod(mixNarrative(seed, SALT_REREAD_STORY), names.size()));
        Optional<StoryFile> storyOpt = StoryRegistry.getByBasename(basename);
        if (storyOpt.isEmpty()) return Optional.empty();
        StoryFile story = storyOpt.get();
        int letterCount = story.letters().size();
        if (letterCount < 1) return Optional.empty();
        int index = (int) Math.floorMod(mixNarrative(seed, SALT_REREAD_LETTER), letterCount) + 1; // 1-based
        Letter letter = story.letterByIndex(index).orElse(null);
        if (letter == null) return Optional.empty();
        return Optional.of(buildSignedBook(story, letter, seed));
    }

    /**
     * Continue the world's most-recently-started, not-yet-complete player narrative series (resolving it
     * from {@link NarrativePool}). A series that has rotated out of the pool window while the relay is cold
     * simply resolves empty and is skipped; a series with all present letters read is complete and skipped.
     */
    private static Optional<ItemStack> continueInProgressPlayerSeries(NarrativeProgressData data, long seed) {
        List<String> started = data.startedPlayerSeriesIds();
        for (int i = started.size() - 1; i >= 0; i--) { // tail = most recently started
            Optional<NarrativePool.Series> resolved = NarrativePool.resolve(started.get(i));
            if (resolved.isEmpty()) continue;
            NarrativePool.Series series = resolved.get();
            Optional<NarrativePool.SeriesLetter> next = nextUnreadPlayerLetter(data, series);
            if (next.isPresent()) return Optional.of(buildPlayerSeriesLetter(series, next.get()));
        }
        return Optional.empty();
    }

    /** Start a fresh (unstarted) player series from the pool, serving its lowest letter. Empty if none available. */
    private static Optional<ItemStack> startNewPlayerSeries(NarrativeProgressData data, long seed) {
        Set<String> started = new HashSet<>(data.startedPlayerSeriesIds());
        Optional<NarrativePool.Series> pick = NarrativePool.pickUnstarted(seed, started);
        if (pick.isEmpty()) return Optional.empty();
        NarrativePool.Series series = pick.get();
        Optional<NarrativePool.SeriesLetter> first = nextUnreadPlayerLetter(data, series);
        if (first.isEmpty()) return Optional.empty();
        return Optional.of(buildPlayerSeriesLetter(series, first.get()));
    }

    /**
     * The lowest present {@code letterIndex} of {@code series} the world has not read yet (edge-case-aware:
     * iterates the series' CURRENTLY present letters, so a post-approval-rejected letter never leaves a
     * lectern hunting a deleted index). Empty when every present letter is read → the series is complete.
     */
    private static Optional<NarrativePool.SeriesLetter> nextUnreadPlayerLetter(NarrativeProgressData data,
                                                                              NarrativePool.Series series) {
        NarrativePool.SeriesLetter best = null;
        for (NarrativePool.SeriesLetter l : series.letters()) {
            if (data.hasReadPlayerLetter(series.seriesId(), l.letterIndex())) continue;
            if (best == null || l.letterIndex() < best.letterIndex()) best = l;
        }
        return Optional.ofNullable(best);
    }

    /**
     * Build one served player-narrative letter as a plain written book (same pagination as shared books)
     * stamped with {@link PlayerNarrativeBookTag} so the lectern read-credit advances the world's
     * player-series read-set. Blank titles fall back to {@code "Letter N"}.
     */
    public static ItemStack buildPlayerSeriesLetter(NarrativePool.Series series, NarrativePool.SeriesLetter letter) {
        String title = (letter.title() == null || letter.title().isBlank())
                ? "Letter " + letter.letterIndex() : letter.title();
        ItemStack stack = buildPlainBook(title, series.author(), letter.pages());
        PlayerNarrativeBookTag.stamp(stack, series.seriesId(), letter.letterIndex());
        return stack;
    }

    /**
     * The chance a lectern serves a NEW player narrative instead of a new mod story, LETTER-granular:
     * {@code fairShare × ramp} where {@code fairShare = P/(P+V)} (approved player letters P vs total mod
     * letters V) and {@code ramp} holds the chance at 0 until a configured fraction {@code T} of the mod
     * letters is read, then rises to 1. Settles permanently at the pool-size fair share; never capped,
     * never 100%. Returns 0 when discovery is off or the pool is empty/unknown. Package-private for tests.
     */
    static double narrativeLecternChanceForWorld(ServerLevel overworld, NarrativeProgressData data) {
        if (!SharedBookGate.canDiscoverNarratives()) return 0.0;
        return discoveryChance(
                NarrativePool.approvedTotal(),
                StoryRegistry.totalLetterCount(),
                data.distinctModStoryLettersRead(),
                DungeonTrainConfig.getNarrativeDiscoveryRampThreshold());
    }

    /**
     * Pure math for the discovery chance — {@code fairShare × ramp}, LETTER-granular. {@code p} = approved
     * player letters, {@code v} = total mod letters, {@code lettersRead} = mod letters this world has read,
     * {@code threshold} = the warm-up ramp threshold {@code T}. Returns 0 when there is nothing to discover
     * ({@code p <= 0}); below {@code T} of the mod letters read the chance is 0; at full read it settles at
     * the fair share {@code p/(p+v)}. Package-private + server-free so it can be unit-tested directly.
     */
    static double discoveryChance(int p, int v, int lettersRead, double threshold) {
        if (p <= 0) return 0.0;
        double fairShare = (double) p / (double) (p + v);
        double readFraction = (v <= 0) ? 1.0 : Math.min(1.0, (double) lettersRead / (double) v);
        double ramp;
        if (threshold >= 1.0) {
            ramp = readFraction >= 1.0 ? 1.0 : 0.0;
        } else {
            double t = Math.max(0.0, threshold);
            ramp = Math.max(0.0, Math.min(1.0, (readFraction - t) / (1.0 - t)));
        }
        return fairShare * ramp;
    }

    /** Deterministic {@code [0,1)} coin flip against {@code chance}, stable per {@code seed}. */
    private static boolean rollNarrativeChance(double chance, long seed) {
        if (chance <= 0.0) return false;
        if (chance >= 1.0) return true;
        long state = mixNarrative(seed, SALT_CHANCE) & 0x7FFFFFFFFFFFFFFFL;
        double roll = state / (double) (1L << 63);
        return roll < chance;
    }

    private static final long SALT_CHANCE = 0x4E4C43_43L;         // narrative-lectern chance flip
    private static final long SALT_REREAD_STORY = 0x4E4C52_53L;   // re-read story pick
    private static final long SALT_REREAD_LETTER = 0x4E4C52_4CL;  // re-read letter pick

    /** Splittable-mix so a raw lectern seed spreads uniformly for a given decision salt. */
    private static long mixNarrative(long seed, long salt) {
        long state = seed ^ salt;
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        return state;
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
            pages.add(Filterable.passThrough(BookText.toPage(page)));
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
     * Build a plain, un-stamped {@code Items.WRITTEN_BOOK} from explicit
     * {@code title}/{@code author}/{@code pages} — no {@link NarrativeBookTag}
     * / {@link RandomBookTag} identity component. Used by the community
     * "shared books" feature: the sign-interception path builds the burn book
     * (which the caller then stamps with {@link SharedBookTag}), and the
     * discovery pool builds the found book (which stays unstamped so reading
     * it counts as an ordinary written book, never as a story read).
     *
     * <p>Reuses the same pagination, page-cap, title/author-clamp and
     * {@link BookText} keybind expansion as the narrative books so layout is
     * identical. Each source page is first re-flowed through {@link #paginate}
     * (so an overlong contributed page is split rather than truncated by the
     * client), then all sections are concatenated in order. A {@code null}
     * or empty page list yields a single blank page so the book is still
     * openable.</p>
     *
     * @param title  book title (clamped to {@link #MAX_TITLE_CHARS}); blank → "Untitled"
     * @param author author credited on the book (clamped); blank → "Anonymous"
     * @param pages  source page strings in order; {@code null}/empty → one blank page
     */
    public static ItemStack buildPlainBook(String title, String author, List<String> pages) {
        String safeTitle = title == null || title.isBlank() ? "Untitled" : title;
        String safeAuthor = author == null || author.isBlank() ? "Anonymous" : author;

        List<String> pageStrings = new ArrayList<>();
        if (pages != null) {
            for (String page : pages) {
                if (page == null) continue;
                // Re-flow each contributed page through the shared paginator so an
                // overlong page is split across multiple book pages rather than
                // silently clipped by the client's page renderer.
                pageStrings.addAll(paginate(page));
            }
        }
        if (pageStrings.size() > MAX_PAGES) {
            LOGGER.warn("[DungeonTrain] SharedBook: '{}' by '{}' produced {} pages — truncating to {} (vanilla cap)",
                safeTitle, safeAuthor, pageStrings.size(), MAX_PAGES);
            pageStrings = pageStrings.subList(0, MAX_PAGES);
        }

        List<Filterable<Component>> bookPages = new ArrayList<>(pageStrings.size());
        for (String page : pageStrings) {
            bookPages.add(Filterable.passThrough(BookText.toPage(page)));
        }
        if (bookPages.isEmpty()) {
            bookPages.add(Filterable.passThrough(Component.literal("")));
        }

        WrittenBookContent content = new WrittenBookContent(
            Filterable.passThrough(clampTitle(safeTitle)),
            clampAuthor(safeAuthor),
            /*generation*/ 0,
            bookPages,
            /*resolved*/ true
        );

        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
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
