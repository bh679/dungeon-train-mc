package games.brennan.dungeontrain.narrative;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
 * <p>Shape: {@code storyBasename -> readLetterIndices}, plus a parallel
 * map for random-book variant tracking. Narrative state is shared by every
 * player in the world — one cursor, one source of truth.</p>
 *
 * <p>Legacy {@code players} list from the prior per-player schema is silently
 * dropped on load — fresh start on first launch of the new code, per the
 * approved plan.</p>
 */
public final class NarrativeProgressData extends SavedData {

    public static final String NAME = "dungeontrain_narratives";

    private static final String TAG_STORIES = "stories";
    private static final String TAG_RANDOM_BOOKS = "random_books";
    private static final String TAG_STORY_ID = "id";
    private static final String TAG_STORY_READ = "read";

    private final Map<String, NarrativeProgress> byStory;
    /** World-wide seen-variant tracking for the random-book pool. */
    private final Map<String, NarrativeProgress> randomBooksSeen;

    private NarrativeProgressData() {
        this(new HashMap<>(), new HashMap<>());
    }

    private NarrativeProgressData(Map<String, NarrativeProgress> byStory,
                                  Map<String, NarrativeProgress> randomBooksSeen) {
        this.byStory = byStory;
        this.randomBooksSeen = randomBooksSeen;
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

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put(TAG_STORIES, encodeStoryProgress(byStory));
        tag.put(TAG_RANDOM_BOOKS, encodeStoryProgress(randomBooksSeen));
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
     * Load the world-scoped schema. Legacy per-player {@code players} list
     * from the old schema is silently dropped (returns empty store) — by
     * design, "each new world starts fresh" per the approved plan.
     */
    private static NarrativeProgressData load(CompoundTag tag) {
        Map<String, NarrativeProgress> stories = decodeStoryProgress(tag, TAG_STORIES);
        Map<String, NarrativeProgress> randomBooks = decodeStoryProgress(tag, TAG_RANDOM_BOOKS);
        return new NarrativeProgressData(stories, randomBooks);
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
