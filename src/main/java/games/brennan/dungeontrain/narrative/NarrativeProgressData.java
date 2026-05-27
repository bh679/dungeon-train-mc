package games.brennan.dungeontrain.narrative;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
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
    private static final String TAG_STORY_ID = "id";
    private static final String TAG_STORY_READ = "read";
    /** Root-level list of UUIDs that have already received their first-login welcome book. */
    private static final String TAG_STARTING_BOOK_RECEIVED = "starting_book_received";
    /** Key for a per-entry UUID inside the starting-book-received list. */
    private static final String TAG_UUID = "uuid";
    /** Root-level list of recently-started narrative basenames, oldest-first. */
    private static final String TAG_RECENT_NARRATIVE_STARTS = "recent_narrative_starts";

    private final Map<String, NarrativeProgress> byStory;
    /** World-wide seen-variant tracking for the random-book pool. */
    private final Map<String, NarrativeProgress> randomBooksSeen;
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
     * Recently-started narratives (oldest first). Pushed to from
     * {@link #markRead} the moment a story's read-count transitions
     * {@code 0 → 1}. Drives the silent-cycle cooldown in
     * {@link #cycleAndPick(long)} and the (effective-no-op pre-cycle, load-
     * bearing post-cycle) recency filter in {@link #randomUncompletedStory}.
     *
     * <p>Window size is {@code StoryRegistry.count() / 2} computed at push
     * time, so reloading more or fewer stories doesn't leave the queue
     * out of date — the shrink-on-push semantics evict overflow as needed.</p>
     */
    private final RecentStartsQueue recentNarrativeStarts;

    private NarrativeProgressData() {
        this(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashSet<>(), new RecentStartsQueue());
    }

    private NarrativeProgressData(Map<String, NarrativeProgress> byStory,
                                  Map<String, NarrativeProgress> randomBooksSeen,
                                  Map<String, NarrativeProgress> startingBooksSeen,
                                  Set<UUID> startingBookReceived,
                                  RecentStartsQueue recentNarrativeStarts) {
        this.byStory = byStory;
        this.randomBooksSeen = randomBooksSeen;
        this.startingBooksSeen = startingBooksSeen;
        this.startingBookReceived = startingBookReceived;
        this.recentNarrativeStarts = recentNarrativeStarts;
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
     *
     * <p>When the read causes the story's read-count to transition
     * {@code 0 → 1} (i.e., this letter is the first of the story to be
     * read in this world), the story basename is pushed onto
     * {@link #recentNarrativeStarts} for the silent-cycle cooldown.</p>
     */
    public boolean markRead(String storyBasename, int letterIndex) {
        NarrativeProgress p = byStory.computeIfAbsent(storyBasename, k -> new NarrativeProgress());
        int countBefore = p.readCount();
        boolean changed = p.markRead(letterIndex);
        if (changed) {
            setDirty();
            if (countBefore == 0) {
                pushNarrativeStart(storyBasename);
            }
        }
        return changed;
    }

    /**
     * Push {@code storyBasename} onto the recently-started queue with a
     * window size of {@code totalStories / 2}. Marks the SavedData dirty.
     */
    private void pushNarrativeStart(String storyBasename) {
        int maxSize = StoryRegistry.count() / 2;
        recentNarrativeStarts.push(storyBasename, maxSize);
        setDirty();
    }

    /** True when {@code storyBasename} is in the recently-started cooldown queue. */
    public boolean isRecentlyStartedNarrative(String storyBasename) {
        return recentNarrativeStarts.contains(storyBasename);
    }

    /** Oldest-first snapshot of the recently-started cooldown queue. */
    public List<String> recentNarrativeStartsSnapshot() {
        return recentNarrativeStarts.snapshot();
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
        boolean changed = p.markRead(variantIndex);
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
        boolean changed = p.markRead(variantIndex);
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
     * <p>Stories in the {@link #recentNarrativeStarts} cooldown queue are
     * filtered out first; if the filter empties the candidate list, the
     * unfiltered uncompleted list is used. Pre-cycle this filter is
     * effectively a no-op (in-queue stories are normally in-progress or
     * complete and so don't appear in {@code uncompleted}), but immediately
     * after a silent cycle every story is uncompleted again — at that point
     * this filter is what keeps the just-finished titles out of the pick.</p>
     *
     * <p>Empty when every loaded story is complete. Callers should chain to
     * {@link #cycleAndPick(long)} for the silent-cycle fallback.</p>
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
        List<String> pool = filterRecent(uncompleted);
        // Deterministic mix so the lectern seed produces a stable index
        // across re-clicks until state advances.
        int idx = Math.floorMod(randomSeed, pool.size());
        return Optional.of(pool.get(idx));
    }

    /**
     * Silent-cycle fallback for when every story is complete. Clears
     * per-story read state (without touching the recently-started queue)
     * and picks from the full story set minus the cooldown queue.
     *
     * <p>The recently-started queue is intentionally preserved across the
     * cycle — that's what lets the picker avoid the just-finished titles
     * on the first post-cycle pick. If filtering empties the candidate
     * list (e.g., a degenerate case where every story is in the queue),
     * falls back to the full registry list so the lectern still resolves
     * something rather than stalling.</p>
     *
     * <p>Returns {@link Optional#empty()} only when the registry holds zero
     * stories (a packaging fault).</p>
     */
    public Optional<String> cycleAndPick(long randomSeed) {
        List<String> all = StoryRegistry.basenames();
        if (all.isEmpty()) return Optional.empty();
        resetAll(); // silent cycle — clears byStory + setDirty()
        List<String> pool = filterRecent(all);
        int idx = Math.floorMod(randomSeed, pool.size());
        return Optional.of(pool.get(idx));
    }

    /**
     * Drop entries that are in the recently-started cooldown queue. Falls
     * back to the input list when every entry is filtered out, so callers
     * never end up with an empty pool when a non-empty one was provided.
     */
    private List<String> filterRecent(List<String> input) {
        List<String> filtered = new java.util.ArrayList<>(input.size());
        for (String b : input) {
            if (!recentNarrativeStarts.contains(b)) filtered.add(b);
        }
        return filtered.isEmpty() ? input : filtered;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put(TAG_STORIES, encodeStoryProgress(byStory));
        tag.put(TAG_RANDOM_BOOKS, encodeStoryProgress(randomBooksSeen));
        tag.put(TAG_STARTING_BOOKS_SEEN, encodeStoryProgress(startingBooksSeen));

        // Per-player starting-book receipts — flat list of UUIDs.
        ListTag startedList = new ListTag();
        for (UUID uuid : startingBookReceived) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, uuid);
            startedList.add(entry);
        }
        tag.put(TAG_STARTING_BOOK_RECEIVED, startedList);

        // Recently-started cooldown queue, oldest-first.
        ListTag recentList = new ListTag();
        for (String name : recentNarrativeStarts.snapshot()) {
            recentList.add(StringTag.valueOf(name));
        }
        tag.put(TAG_RECENT_NARRATIVE_STARTS, recentList);
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
     */
    private static NarrativeProgressData load(CompoundTag tag) {
        Map<String, NarrativeProgress> stories = decodeStoryProgress(tag, TAG_STORIES);
        Map<String, NarrativeProgress> randomBooks = decodeStoryProgress(tag, TAG_RANDOM_BOOKS);
        Map<String, NarrativeProgress> startingBooksSeen = decodeStoryProgress(tag, TAG_STARTING_BOOKS_SEEN);
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
        RecentStartsQueue recentNarrativeStarts = new RecentStartsQueue();
        // Backwards-compatible: missing tag → empty queue, identical to fresh-world behaviour.
        if (tag.contains(TAG_RECENT_NARRATIVE_STARTS, Tag.TAG_LIST)) {
            ListTag recentList = tag.getList(TAG_RECENT_NARRATIVE_STARTS, Tag.TAG_STRING);
            java.util.List<String> initial = new java.util.ArrayList<>(recentList.size());
            for (int i = 0; i < recentList.size(); i++) {
                initial.add(recentList.getString(i));
            }
            recentNarrativeStarts.load(initial);
        }
        return new NarrativeProgressData(stories, randomBooks, startingBooksSeen, startingBookReceived, recentNarrativeStarts);
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
