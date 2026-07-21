package games.brennan.dungeontrain.narrative;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Per-overworld persistence for world narrative read-progress. Stored at
 * {@code <world>/data/dungeontrain_narratives.dat}.
 *
 * <p>Mirrors the
 * {@link games.brennan.dungeontrain.world.DungeonTrainWorldData} pattern:
 * registered via {@link ServerLevel#getDataStorage()} with a
 * {@link SavedData.Factory}, keyed on the overworld so all dimensions read
 * the same store.</p>
 *
 * <p>Two scopes coexist in one .dat file:</p>
 * <ul>
 *   <li><b>World-scoped</b> — story progress (which letters were read) and
 *       random-book seen-variants. One cursor per world, shared by every
 *       player. Introduced when the system pivoted away from per-player
 *       progression.</li>
 *   <li><b>Per-player</b> — starting-book first-login receipt. Whether a
 *       given player has been handed their welcome book on first join.
 *       Stays per-player because each player gets their own welcome on first
 *       join; the world-scoped flip would surprise the second player.</li>
 * </ul>
 *
 * <p>Legacy {@code players} list from the prior per-player schema is silently
 * dropped on load — fresh start on first launch of the new code, per the
 * approved plan.</p>
 */
public final class NarrativeProgressData extends SavedData {

    public static final String NAME = "dungeontrain_narratives";

    private static final String TAG_STORIES = "stories";
    private static final String TAG_RANDOM_BOOKS = "random_books";
    /** Per-world (basename → seen-variant indices) for the starting-book pool — RESPAWN cycling. */
    private static final String TAG_STARTING_BOOKS_SEEN = "starting_books_seen";
    /**
     * Per-world (storyBasename#letterIndex → seen-variant indices) for
     * narrative-story letters. Drives the {@code read_all_story_variants}
     * achievement. Flat-key shape so the existing {@link #encodeStoryProgress}
     * / {@link #decodeStoryProgress} helpers serialize it unchanged.
     */
    private static final String TAG_STORY_VARIANTS = "story_variants";
    private static final String TAG_STORY_ID = "id";
    private static final String TAG_STORY_READ = "read";
    /** Root-level list of UUIDs that have already received their first-login welcome book. */
    private static final String TAG_STARTING_BOOK_RECEIVED = "starting_book_received";
    /** Key for a per-entry UUID inside the starting-book-received list. */
    private static final String TAG_UUID = "uuid";
    /**
     * Per-world (basename → ever-read variant indices), monotonic — unlike {@link #randomBooksSeen},
     * which the picker resets on the silent full-cycle. Drives the shared-community-book loot chance,
     * which scales with how much of the hardcoded random-book pool has been read AT VARIANT
     * granularity, so the fraction must never fall back after a variant is earned. Same flat-key shape
     * as {@link #randomBooksSeen} / {@link #storyVariantsSeen}, so {@link #encodeStoryProgress} /
     * {@link #decodeStoryProgress} serialize it unchanged.
     */
    private static final String TAG_RANDOM_BOOK_VARIANTS_EVER_READ = "random_book_variants_ever_read";
    /**
     * Root-level int-array of relay pool ids for community (player-written) books this world has EVER
     * read. Monotonic, like {@link #TAG_RANDOM_BOOK_VARIANTS_EVER_READ}: it feeds the "community books
     * read / total" fraction that tapers the shared-book loot max, so it must never regress. Stored as
     * a flat int-array rather than the {@code basename → Set<Integer>} shape because the identity is a
     * single numeric relay id, not a (book, variant) pair.
     */
    private static final String TAG_SHARED_BOOKS_EVER_READ = "shared_books_ever_read";
    /**
     * Root-level list of per-player letter-series state for the player-written lectern-letters
     * feature. Each entry: {@code {uuid, deaths, series, index}} — the lifetime death count that
     * anchors the current series ({@code deaths}, from {@code GlobalPlayerStats.totalDeaths}), the
     * opaque per-life series id ({@code series}, regenerated when {@code deaths} changes) and the
     * highest letter index signed in that series ({@code index}). Per-player, monotonic within a
     * life; a new life (deaths changed) re-bases to a fresh series at index 1.
     */
    private static final String TAG_LETTER_SERIES = "letter_series";
    private static final String TAG_LETTER_SERIES_DEATHS = "deaths";
    private static final String TAG_LETTER_SERIES_ID = "series";
    private static final String TAG_LETTER_SERIES_INDEX = "index";
    /**
     * Root-level string-list of {@code "seriesId#letterIndex"} keys for approved player-written narrative
     * letters this world has EVER read from a narrative lectern (the DISCOVERY half). Monotonic, insertion
     * -ordered: it doubles as the world's player-series read-set (continuation via
     * {@link #hasReadPlayerLetter}) and the in-progress pin source ({@link #startedPlayerSeriesIds}, most
     * recently started last). World-local and never fed into {@code GlobalNarrativeProgress}/advancements.
     */
    private static final String TAG_PLAYER_LETTERS_EVER_READ = "player_letters_ever_read";

    private final Map<String, NarrativeProgress> byStory;
    /** World-wide seen-variant tracking for the random-book pool. */
    private final Map<String, NarrativeProgress> randomBooksSeen;
    /**
     * World-wide seen-variant tracking for narrative-story letters. Keyed
     * {@code storyBasename + "#" + letterIndex}; values are the set of
     * variant indices the world has shown for that letter. Drives the
     * {@code read_all_story_variants} achievement.
     */
    private final Map<String, NarrativeProgress> storyVariantsSeen;
    /**
     * World-wide seen-variant tracking for the <em>starting-book</em> pool.
     * Drives the RESPAWN cycling rule: while any RESPAWN-pool variant is
     * unseen, rolls come only from that subset; once every RESPAWN variant
     * has been marked here, the picker switches to the union of
     * RESPAWN + DEFAULT pools and stays there.
     */
    private final Map<String, NarrativeProgress> startingBooksSeen;
    /**
     * Players who have already been given their first-login welcome book.
     * Per-player rather than world-scoped: each player gets their own welcome
     * strike on their own first join, regardless of whether someone else
     * already spawned in this world.
     */
    private final Set<UUID> startingBookReceived;
    /**
     * (Basename → ever-read variant indices) this world has EVER seen, at variant granularity
     * (monotonic; only grows). Distinct from {@link #randomBooksSeen}, which the unseen-picker clears
     * on the silent full-cycle. Feeds the shared-book loot chance so it never regresses once earned.
     */
    private final Map<String, NarrativeProgress> randomBookVariantsEverRead;
    /**
     * Relay pool ids of community (player-written) books this world has EVER read (monotonic; only
     * grows). Numerator of the "community books read / total" fraction that tapers the shared-book loot
     * max toward the fair share once a world has seen most community content.
     */
    private final Set<Integer> sharedBooksEverRead;
    /**
     * Per-player current-life letter-series cursor for the lectern-letters feature. Keyed by
     * player UUID; value is the death-count-anchored series id + highest signed letter index. See
     * {@link #nextLetter(UUID, long)}.
     */
    private final Map<UUID, LetterSeriesState> letterSeries;
    /**
     * {@code "seriesId#letterIndex"} keys for approved player narrative letters this world has ever read on
     * a lectern (monotonic, insertion-ordered). Backs continuation ({@link #hasReadPlayerLetter}), the
     * in-progress pin set ({@link #startedPlayerSeriesIds}), and is world-local — never touches canon
     * progress or advancements. See {@link #TAG_PLAYER_LETTERS_EVER_READ}.
     */
    private final Set<String> playerLettersEverRead;

    /** Stored letter-series cursor for one player: which life ({@code seriesDeaths}), the series id, and the highest index signed. */
    private record LetterSeriesState(long seriesDeaths, String seriesId, int letterIndex) {}

    private NarrativeProgressData() {
        this(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashSet<>(),
                new HashMap<>(), new HashSet<>(), new HashMap<>(), new LinkedHashSet<>());
    }

    private NarrativeProgressData(Map<String, NarrativeProgress> byStory,
                                  Map<String, NarrativeProgress> randomBooksSeen,
                                  Map<String, NarrativeProgress> startingBooksSeen,
                                  Map<String, NarrativeProgress> storyVariantsSeen,
                                  Set<UUID> startingBookReceived,
                                  Map<String, NarrativeProgress> randomBookVariantsEverRead,
                                  Set<Integer> sharedBooksEverRead,
                                  Map<UUID, LetterSeriesState> letterSeries,
                                  Set<String> playerLettersEverRead) {
        this.byStory = byStory;
        this.randomBooksSeen = randomBooksSeen;
        this.startingBooksSeen = startingBooksSeen;
        this.storyVariantsSeen = storyVariantsSeen;
        this.startingBookReceived = startingBookReceived;
        this.randomBookVariantsEverRead = randomBookVariantsEverRead;
        this.sharedBooksEverRead = sharedBooksEverRead;
        this.letterSeries = letterSeries;
        this.playerLettersEverRead = playerLettersEverRead;
    }

    public static NarrativeProgressData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(
                NarrativeProgressData::new,
                (tag, registries) -> load(tag)
            ),
            NAME
        );
    }

    /** Read-only progress for {@code storyBasename}; never null. */
    public NarrativeProgress progressFor(String storyBasename) {
        NarrativeProgress p = byStory.get(storyBasename);
        return p != null ? p : new NarrativeProgress();
    }

    /**
     * Mark {@code letterIndex} read for the story. Returns {@code true} when
     * state changed (caller can decide whether to log). Always calls
     * {@link #setDirty()} so the .dat file persists.
     */
    public boolean markRead(String storyBasename, int letterIndex) {
        NarrativeProgress p = byStory.computeIfAbsent(storyBasename, k -> new NarrativeProgress());
        boolean changed = p.markRead(letterIndex);
        if (changed) setDirty();
        return changed;
    }

    /**
     * Snapshot of every story the world has touched (read at least one
     * letter of). Used by `/narrative progress` to print a table.
     */
    public Map<String, NarrativeProgress> snapshotStories() {
        Map<String, NarrativeProgress> out = new HashMap<>(byStory.size());
        for (var e : byStory.entrySet()) {
            out.put(e.getKey(), new NarrativeProgress(e.getValue().readLetters()));
        }
        return out;
    }

    /** Reset every story for the world. */
    public void resetAll() {
        if (!byStory.isEmpty()) {
            byStory.clear();
            setDirty();
        }
    }

    // ---------------- Random-book tracking ----------------

    /**
     * True when the world has already seen
     * {@code (bookBasename, variantIndex)}. Used by the equipment-change
     * handler to decide whether to swap the held stack to an unseen pick.
     */
    public boolean hasSeenRandomBook(String bookBasename, int variantIndex) {
        NarrativeProgress p = randomBooksSeen.get(bookBasename);
        return p != null && p.readLetters().contains(variantIndex);
    }

    /**
     * Mark {@code variantIndex} of {@code bookBasename} seen for the world.
     * Returns {@code true} when state changed. Calls {@link #setDirty()} so
     * the .dat file persists.
     */
    public boolean markRandomBookSeen(String bookBasename, int variantIndex) {
        NarrativeProgress p = randomBooksSeen.computeIfAbsent(bookBasename, k -> new NarrativeProgress());
        boolean changed = p.markSeen(variantIndex);
        if (changed) setDirty();
        return changed;
    }

    /** Clear random-book tracking for the world. */
    public void resetRandomBookProgress() {
        if (!randomBooksSeen.isEmpty()) {
            randomBooksSeen.clear();
            setDirty();
        }
    }

    /**
     * Record that the world has read {@code (bookBasename, variantIndex)} at least once. Monotonic —
     * never cleared by {@link #resetRandomBookProgress()} — so the shared-book loot fraction it feeds
     * can't fall back after the player has read a variant. Returns {@code true} on first read.
     */
    public boolean markRandomBookVariantEverRead(String bookBasename, int variantIndex) {
        NarrativeProgress p = randomBookVariantsEverRead.computeIfAbsent(bookBasename, k -> new NarrativeProgress());
        boolean changed = p.markSeen(variantIndex);
        if (changed) setDirty();
        return changed;
    }

    /**
     * How many DISTINCT {@code (book, variant)} pairs the world has ever read — the numerator for the
     * shared-community-book loot chance (denominator is {@link RandomBookRegistry#totalVariantCount()}).
     */
    public int distinctRandomBookVariantsEverRead() {
        int total = 0;
        for (NarrativeProgress p : randomBookVariantsEverRead.values()) total += p.readCount();
        return total;
    }

    /**
     * Record that the world has read the community (player-written) book with relay pool {@code id} at
     * least once. Monotonic — never cleared — so the shared-book loot taper's "community books read"
     * numerator can't fall back. Returns {@code true} on first read of this id.
     */
    public boolean markSharedBookEverRead(int id) {
        boolean added = sharedBooksEverRead.add(id);
        if (added) setDirty();
        return added;
    }

    /**
     * How many DISTINCT community books (by relay pool id) the world has ever read — the numerator for
     * the shared-book loot taper (denominator is {@link SharedBookPool#approvedTotal()}).
     */
    public int distinctSharedBooksEverRead() {
        return sharedBooksEverRead.size();
    }

    // ---------------- Player-narrative discovery tracking ----------------

    /**
     * Record that the world has read letter {@code letterIndex} of player narrative series {@code seriesId}
     * on a lectern. Monotonic — the world's player-series read-set only grows. Returns {@code true} on the
     * first read of this letter. World-local; never touches canon progress / advancements.
     */
    public boolean markPlayerLetterRead(String seriesId, int letterIndex) {
        if (seriesId == null || seriesId.isEmpty()) return false;
        boolean added = playerLettersEverRead.add(playerLetterKey(seriesId, letterIndex));
        if (added) setDirty();
        return added;
    }

    /** Whether the world has already read letter {@code letterIndex} of {@code seriesId} — drives next-unread. */
    public boolean hasReadPlayerLetter(String seriesId, int letterIndex) {
        if (seriesId == null || seriesId.isEmpty()) return false;
        return playerLettersEverRead.contains(playerLetterKey(seriesId, letterIndex));
    }

    /**
     * The distinct player narrative seriesIds the world has started (read ≥1 letter of), in the order they
     * were first read (oldest first, so the tail is the most recently started). Used both to continue an
     * in-progress series and to pin in-progress series into the relay pool fetch so they stay resolvable.
     */
    public List<String> startedPlayerSeriesIds() {
        Set<String> seen = new LinkedHashSet<>();
        for (String key : playerLettersEverRead) {
            int hash = key.lastIndexOf('#');
            seen.add(hash >= 0 ? key.substring(0, hash) : key);
        }
        return new ArrayList<>(seen);
    }

    /**
     * How many mod-story letters the world has read — the warm-up-ramp numerator {@code Lr} for the
     * narrative-lectern discovery chance (denominator {@link StoryRegistry#totalLetterCount()}). Reads the
     * canon story-progress map, so it rises as the world reads the hand-authored lectern content.
     */
    public int distinctModStoryLettersRead() {
        int total = 0;
        for (NarrativeProgress p : byStory.values()) total += p.readCount();
        return total;
    }

    private static String playerLetterKey(String seriesId, int letterIndex) {
        return seriesId + "#" + letterIndex;
    }

    // ---------------- Story-letter variant tracking ----------------

    /**
     * Flat-key encoding for the {@link #storyVariantsSeen} map. Mirrors the
     * {@code basename → Set<variantIndex>} shape used by {@link #randomBooksSeen}
     * so {@link #encodeStoryProgress} / {@link #decodeStoryProgress} can
     * serialize both with no schema change.
     */
    private static String storyVariantKey(String storyBasename, int letterIndex) {
        return storyBasename + "#" + letterIndex;
    }

    /**
     * True when the world has already shown
     * {@code (storyBasename, letterIndex, variantIndex)} to any player.
     * Used by the {@code read_all_story_variants} completion scan.
     */
    public boolean hasSeenStoryVariant(String storyBasename, int letterIndex, int variantIndex) {
        NarrativeProgress p = storyVariantsSeen.get(storyVariantKey(storyBasename, letterIndex));
        return p != null && p.readLetters().contains(variantIndex);
    }

    /**
     * Mark {@code (storyBasename, letterIndex, variantIndex)} seen for the
     * world. Returns {@code true} when state changed. Calls {@link #setDirty()}
     * so the .dat file persists.
     */
    public boolean markStoryVariantSeen(String storyBasename, int letterIndex, int variantIndex) {
        NarrativeProgress p = storyVariantsSeen.computeIfAbsent(
            storyVariantKey(storyBasename, letterIndex),
            k -> new NarrativeProgress()
        );
        boolean changed = p.markSeen(variantIndex);
        if (changed) setDirty();
        return changed;
    }

    /**
     * Snapshot of every {@code (story, letter)} pair the world has shown at
     * least one variant of. Key shape is {@link #storyVariantKey(String, int)}
     * ({@code basename#letterIndex}). Used by the achievement-completion
     * scan in {@code AchievementEvents}.
     */
    public Map<String, NarrativeProgress> storyVariantsSnapshot() {
        Map<String, NarrativeProgress> out = new HashMap<>(storyVariantsSeen.size());
        for (var e : storyVariantsSeen.entrySet()) {
            out.put(e.getKey(), new NarrativeProgress(e.getValue().readLetters()));
        }
        return out;
    }

    /** Clear story-letter variant tracking for the world. */
    public void resetStoryVariantsSeen() {
        if (!storyVariantsSeen.isEmpty()) {
            storyVariantsSeen.clear();
            setDirty();
        }
    }

    // ---------------- Starting-book variant tracking (respawn cycling) ----------------

    /**
     * True when the world has already delivered
     * {@code (bookBasename, variantIndex)} via the RESPAWN cycling path.
     * Used by {@link StartingBookFactory#rollForRespawn} to bias toward
     * unseen variants while any remain in the RESPAWN pool.
     */
    public boolean hasSeenStartingBookVariant(String bookBasename, int variantIndex) {
        NarrativeProgress p = startingBooksSeen.get(bookBasename);
        return p != null && p.readLetters().contains(variantIndex);
    }

    /**
     * Mark {@code variantIndex} of {@code bookBasename} seen for the world.
     * Returns {@code true} when state changed.
     */
    public boolean markStartingBookVariantSeen(String bookBasename, int variantIndex) {
        NarrativeProgress p = startingBooksSeen.computeIfAbsent(bookBasename, k -> new NarrativeProgress());
        boolean changed = p.markSeen(variantIndex);
        if (changed) setDirty();
        return changed;
    }

    /** Clear the starting-book variant-seen-set for the world. */
    public void resetStartingBookVariantsSeen() {
        if (!startingBooksSeen.isEmpty()) {
            startingBooksSeen.clear();
            setDirty();
        }
    }

    /**
     * Snapshot of every starting-book the world has delivered (any variant
     * seen). Used for diagnostics / a future `progress` chat-print.
     */
    public Map<String, NarrativeProgress> startingBookSeenSnapshot() {
        Map<String, NarrativeProgress> out = new HashMap<>(startingBooksSeen.size());
        for (var e : startingBooksSeen.entrySet()) {
            out.put(e.getKey(), new NarrativeProgress(e.getValue().readLetters()));
        }
        return out;
    }

    // ---------------- Starting-book first-login tracking ----------------

    /**
     * True when the player has already been handed their first-login welcome
     * book. Used by the login hook in {@code StartingBookEvents} to suppress
     * a second give on plain logins. Does NOT suppress respawn gives — those
     * always fire regardless of this flag.
     */
    public boolean hasReceivedStartingBook(UUID playerUuid) {
        return startingBookReceived.contains(playerUuid);
    }

    /**
     * Mark the player as having received their first-login welcome book.
     * Returns {@code true} when state changed.
     */
    public boolean markStartingBookReceived(UUID playerUuid) {
        boolean added = startingBookReceived.add(playerUuid);
        if (added) setDirty();
        return added;
    }

    /**
     * Clear the first-login flag for {@code playerUuid} — next plain login
     * will give a fresh welcome book again. Used by the
     * {@code /narrative startingbook reset} test command.
     */
    public void resetStartingBookReceived(UUID playerUuid) {
        if (startingBookReceived.remove(playerUuid)) setDirty();
    }

    /**
     * True when any player <em>other than</em> {@code excluding} has already
     * been welcomed in this world. Used by the starting-book context
     * resolver to detect the "joined someone else's world" case at
     * strike-fire time.
     */
    public boolean anyOtherPlayerReceivedStartingBook(UUID excluding) {
        for (UUID uuid : startingBookReceived) {
            if (!uuid.equals(excluding)) return true;
        }
        return false;
    }

    /**
     * Snapshot of every random book the world has touched (any variant
     * seen). Used by {@code /narrative randombook progress} to print a
     * table. Returns an immutable-copy-style map; mutating the result
     * doesn't affect the store.
     */
    public Map<String, NarrativeProgress> randomBookSnapshot() {
        Map<String, NarrativeProgress> out = new HashMap<>(randomBooksSeen.size());
        for (var e : randomBooksSeen.entrySet()) {
            out.put(e.getKey(), new NarrativeProgress(e.getValue().readLetters()));
        }
        return out;
    }

    /**
     * Pick the next uncompleted story for the world, alphabetical by
     * basename. Iterates the live registry so newly-loaded stories show up
     * automatically. Empty when every story is complete.
     */
    public Optional<String> nextUncompletedStory() {
        List<String> all = StoryRegistry.basenames();
        for (String basename : all) {
            Optional<StoryFile> story = StoryRegistry.getByBasename(basename);
            if (story.isEmpty()) continue;
            NarrativeProgress p = progressFor(basename);
            if (!p.isComplete(story.get().letters().size())) return Optional.of(basename);
        }
        return Optional.empty();
    }

    /**
     * First story (alphabetical) that the world has *started* but not
     * finished. "In-progress" = at least one letter read AND not complete.
     *
     * <p>Used by the narrative_lectern's lazy resolver: if the world is in
     * the middle of any story, that lectern continues that story rather
     * than picking a fresh random one. Empty when there is no in-progress
     * narrative.</p>
     */
    public Optional<String> currentInProgressStory() {
        List<String> all = StoryRegistry.basenames();
        for (String basename : all) {
            Optional<StoryFile> story = StoryRegistry.getByBasename(basename);
            if (story.isEmpty()) continue;
            NarrativeProgress p = progressFor(basename);
            int total = story.get().letters().size();
            if (p.readCount() > 0 && !p.isComplete(total)) {
                return Optional.of(basename);
            }
        }
        return Optional.empty();
    }

    /**
     * Random pick from uncompleted stories, deterministic per
     * {@code randomSeed}. Used by the narrative_lectern when the world has
     * no in-progress story — same lectern shows the same first-read story
     * on re-clicks (until something actually advances).
     *
     * <p>Empty when every loaded story is complete.</p>
     */
    public Optional<String> randomUncompletedStory(long randomSeed) {
        List<String> uncompleted = new java.util.ArrayList<>();
        for (String basename : StoryRegistry.basenames()) {
            Optional<StoryFile> story = StoryRegistry.getByBasename(basename);
            if (story.isEmpty()) continue;
            NarrativeProgress p = progressFor(basename);
            if (!p.isComplete(story.get().letters().size())) {
                uncompleted.add(basename);
            }
        }
        if (uncompleted.isEmpty()) return Optional.empty();
        // Deterministic mix so the lectern seed produces a stable index
        // across re-clicks until state advances.
        int idx = Math.floorMod(randomSeed, uncompleted.size());
        return Optional.of(uncompleted.get(idx));
    }

    // ---------------- Letter-series tracking (player-written lectern letters) ----------------

    /**
     * Assign the next letter in {@code playerUuid}'s current-life series and advance the cursor.
     * The life is identified by {@code currentDeaths} (a monotonic lifetime death count, e.g.
     * {@code GlobalPlayerStats.totalDeaths}): while it is unchanged the same {@code seriesId} is
     * reused and {@code letterIndex} climbs; when it changes (the player died and started a new
     * life) a fresh {@code seriesId} is minted and numbering restarts at 1. The series is tied to
     * the life, not to any lectern.
     *
     * <p>Calls {@link #setDirty()} so the cursor survives a mid-life relog. Returns the freshly
     * assigned {@link LetterSeries} ({@code seriesId} + 1-based {@code letterIndex}).</p>
     */
    public LetterSeries nextLetter(UUID playerUuid, long currentDeaths) {
        LetterSeriesState state = letterSeries.get(playerUuid);
        String seriesId;
        int nextIndex;
        if (state == null || state.seriesDeaths() != currentDeaths) {
            // New life (or first ever letter) — mint a fresh series and start at 1.
            seriesId = UUID.randomUUID().toString().replace("-", "");
            nextIndex = 1;
        } else {
            seriesId = state.seriesId();
            nextIndex = state.letterIndex() + 1;
        }
        letterSeries.put(playerUuid, new LetterSeriesState(currentDeaths, seriesId, nextIndex));
        setDirty();
        return new LetterSeries(seriesId, nextIndex);
    }

    /**
     * The index the NEXT signed letter would receive for {@code playerUuid} at {@code currentDeaths},
     * WITHOUT advancing the cursor — used to label the unsigned "Letter X" draft left on a lectern.
     * 1 when the player has no series yet this life.
     */
    public int peekNextIndex(UUID playerUuid, long currentDeaths) {
        LetterSeriesState state = letterSeries.get(playerUuid);
        if (state == null || state.seriesDeaths() != currentDeaths) return 1;
        return state.letterIndex() + 1;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put(TAG_STORIES, encodeStoryProgress(byStory));
        tag.put(TAG_RANDOM_BOOKS, encodeStoryProgress(randomBooksSeen));
        tag.put(TAG_STARTING_BOOKS_SEEN, encodeStoryProgress(startingBooksSeen));
        tag.put(TAG_STORY_VARIANTS, encodeStoryProgress(storyVariantsSeen));

        // Per-player starting-book receipts — flat list of UUIDs.
        ListTag startedList = new ListTag();
        for (UUID uuid : startingBookReceived) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, uuid);
            startedList.add(entry);
        }
        tag.put(TAG_STARTING_BOOK_RECEIVED, startedList);

        // Monotonic ever-read random-book variants — same (basename -> Set<Integer>) shape as
        // randomBooksSeen, so the shared encoder applies unchanged.
        tag.put(TAG_RANDOM_BOOK_VARIANTS_EVER_READ, encodeStoryProgress(randomBookVariantsEverRead));

        // Monotonic ever-read community-book relay ids — a flat int-array (identity is one numeric id).
        int[] sharedArr = new int[sharedBooksEverRead.size()];
        int si = 0;
        for (Integer id : sharedBooksEverRead) sharedArr[si++] = id;
        tag.putIntArray(TAG_SHARED_BOOKS_EVER_READ, sharedArr);

        // Per-player letter-series cursors — flat list of {uuid, deaths, series, index}.
        ListTag letterList = new ListTag();
        for (var entry : letterSeries.entrySet()) {
            LetterSeriesState state = entry.getValue();
            CompoundTag e = new CompoundTag();
            e.putUUID(TAG_UUID, entry.getKey());
            e.putLong(TAG_LETTER_SERIES_DEATHS, state.seriesDeaths());
            e.putString(TAG_LETTER_SERIES_ID, state.seriesId());
            e.putInt(TAG_LETTER_SERIES_INDEX, state.letterIndex());
            letterList.add(e);
        }
        tag.put(TAG_LETTER_SERIES, letterList);

        // Monotonic ever-read player-narrative letters — a flat string-list of "seriesId#letterIndex".
        ListTag playerLetters = new ListTag();
        for (String key : playerLettersEverRead) playerLetters.add(StringTag.valueOf(key));
        tag.put(TAG_PLAYER_LETTERS_EVER_READ, playerLetters);
        return tag;
    }

    /**
     * Shared encoder for both the story-progress map and the random-book
     * map — same shape ({@code basename -> Set<Integer>}), so the same
     * ListTag layout works for both. Returns an empty ListTag when the
     * input is null or empty.
     */
    private static ListTag encodeStoryProgress(Map<String, NarrativeProgress> progress) {
        ListTag list = new ListTag();
        if (progress == null) return list;
        for (var entry : progress.entrySet()) {
            CompoundTag storyTag = new CompoundTag();
            storyTag.putString(TAG_STORY_ID, entry.getKey());
            Set<Integer> read = entry.getValue().readLetters();
            int[] arr = new int[read.size()];
            int i = 0;
            for (Integer v : read) arr[i++] = v;
            storyTag.putIntArray(TAG_STORY_READ, arr);
            list.add(storyTag);
        }
        return list;
    }

    /**
     * Load the world-scoped schema + per-player starting-book set. Legacy
     * per-player {@code players} list from the old story schema is silently
     * dropped — by design, "each new world starts fresh" per the approved
     * plan.
     *
     * <p>Package-private so {@code NarrativeProgressDataTest} can round-trip
     * {@link #save} output without a {@link ServerLevel} mock.</p>
     */
    static NarrativeProgressData load(CompoundTag tag) {
        Map<String, NarrativeProgress> stories = decodeStoryProgress(tag, TAG_STORIES);
        Map<String, NarrativeProgress> randomBooks = decodeStoryProgress(tag, TAG_RANDOM_BOOKS);
        Map<String, NarrativeProgress> startingBooksSeen = decodeStoryProgress(tag, TAG_STARTING_BOOKS_SEEN);
        // Missing tag → empty map (back-compat with worlds saved before the
        // per-variant tracking landed). The achievement won't fire on those
        // worlds until variants are encountered post-update.
        Map<String, NarrativeProgress> storyVariants = decodeStoryProgress(tag, TAG_STORY_VARIANTS);
        Set<UUID> startingBookReceived = new HashSet<>();
        // Backwards-compatible: missing `starting_book_received` tag means no
        // players have been marked yet. On first post-update login each
        // existing player gets the welcome strike they would've gotten if
        // the feature had shipped earlier. Intended behaviour.
        if (tag.contains(TAG_STARTING_BOOK_RECEIVED, Tag.TAG_LIST)) {
            ListTag startedList = tag.getList(TAG_STARTING_BOOK_RECEIVED, Tag.TAG_COMPOUND);
            for (int i = 0; i < startedList.size(); i++) {
                CompoundTag entry = startedList.getCompound(i);
                if (!entry.hasUUID(TAG_UUID)) continue;
                startingBookReceived.add(entry.getUUID(TAG_UUID));
            }
        }
        // Missing tag → empty map (worlds saved before shared-book loot scaling landed simply
        // start the fraction at whatever they've since re-read).
        Map<String, NarrativeProgress> randomBookVariantsEverRead = decodeStoryProgress(tag, TAG_RANDOM_BOOK_VARIANTS_EVER_READ);
        // Missing tag → empty set. getIntArray returns an empty array for an absent/mistyped key, so
        // worlds saved before the community-book taper landed simply start with nothing read.
        Set<Integer> sharedBooksEverRead = new HashSet<>();
        for (int id : tag.getIntArray(TAG_SHARED_BOOKS_EVER_READ)) sharedBooksEverRead.add(id);
        // Missing tag → empty map (worlds saved before lectern letters landed start with no series).
        Map<UUID, LetterSeriesState> letterSeries = new HashMap<>();
        if (tag.contains(TAG_LETTER_SERIES, Tag.TAG_LIST)) {
            ListTag letterList = tag.getList(TAG_LETTER_SERIES, Tag.TAG_COMPOUND);
            for (int i = 0; i < letterList.size(); i++) {
                CompoundTag e = letterList.getCompound(i);
                if (!e.hasUUID(TAG_UUID) || !e.contains(TAG_LETTER_SERIES_ID, Tag.TAG_STRING)) continue;
                letterSeries.put(e.getUUID(TAG_UUID), new LetterSeriesState(
                        e.getLong(TAG_LETTER_SERIES_DEATHS),
                        e.getString(TAG_LETTER_SERIES_ID),
                        e.getInt(TAG_LETTER_SERIES_INDEX)));
            }
        }
        // Missing tag → empty set (worlds saved before narrative discovery landed start with nothing read).
        // Insertion order is preserved so startedPlayerSeriesIds() keeps its oldest-first ordering.
        Set<String> playerLettersEverRead = new LinkedHashSet<>();
        if (tag.contains(TAG_PLAYER_LETTERS_EVER_READ, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_PLAYER_LETTERS_EVER_READ, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                String s = list.getString(i);
                if (!s.isEmpty()) playerLettersEverRead.add(s);
            }
        }
        // Legacy per-player community-book read-set (TAG "shared_books_read_by_player") is intentionally
        // NOT loaded: read history is now GLOBAL + client-side (dungeontrain-client.toml, mirrored via
        // SharedBookReadSyncPacket), so a per-world set would defeat the point. An old world carrying the
        // tag simply drops it on load — no migration, no crash.
        return new NarrativeProgressData(stories, randomBooks, startingBooksSeen, storyVariants,
                startingBookReceived, randomBookVariantsEverRead, sharedBooksEverRead, letterSeries,
                playerLettersEverRead);
    }

    /** Inverse of {@link #encodeStoryProgress}; tolerates missing tag (returns empty). */
    private static Map<String, NarrativeProgress> decodeStoryProgress(CompoundTag root, String key) {
        Map<String, NarrativeProgress> out = new HashMap<>();
        if (!root.contains(key, Tag.TAG_LIST)) return out;
        ListTag list = root.getList(key, Tag.TAG_COMPOUND);
        for (int j = 0; j < list.size(); j++) {
            CompoundTag storyTag = list.getCompound(j);
            if (!storyTag.contains(TAG_STORY_ID, Tag.TAG_STRING)) continue;
            String basename = storyTag.getString(TAG_STORY_ID);
            int[] arr = storyTag.getIntArray(TAG_STORY_READ);
            TreeSet<Integer> set = new TreeSet<>();
            for (int v : arr) set.add(v);
            out.put(basename, new NarrativeProgress(set));
        }
        return out;
    }

}
