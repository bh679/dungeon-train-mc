package games.brennan.dungeontrain.narrative;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
 * Per-overworld persistence for player narrative read-progress. Stored at
 * {@code <world>/data/dungeontrain_narratives.dat}.
 *
 * <p>Mirrors the
 * {@link games.brennan.dungeontrain.world.DungeonTrainWorldData} pattern:
 * registered via {@link ServerLevel#getDataStorage()} with a
 * {@link SavedData.Factory}, keyed on the overworld so all dimensions read
 * the same store.</p>
 *
 * <p>Shape: {@code playerUuid -> storyBasename -> readLetterIndices}.
 * Each mutation calls {@link #setDirty()} so the dat-file picks it up on
 * save.</p>
 */
public final class NarrativeProgressData extends SavedData {

    public static final String NAME = "dungeontrain_narratives";

    private static final String TAG_PLAYERS = "players";
    private static final String TAG_PLAYER_UUID = "uuid";
    private static final String TAG_PLAYER_STORIES = "stories";
    private static final String TAG_PLAYER_RANDOM_BOOKS = "random_books";
    private static final String TAG_STORY_ID = "id";
    private static final String TAG_STORY_READ = "read";

    private final Map<UUID, Map<String, NarrativeProgress>> byPlayer;
    /** Per-player seen-variant tracking for the random-book pool. Shape mirrors {@link #byPlayer}. */
    private final Map<UUID, Map<String, NarrativeProgress>> byPlayerRandomBooks;

    private NarrativeProgressData() {
        this(new HashMap<>(), new HashMap<>());
    }

    private NarrativeProgressData(Map<UUID, Map<String, NarrativeProgress>> byPlayer,
                                  Map<UUID, Map<String, NarrativeProgress>> byPlayerRandomBooks) {
        this.byPlayer = byPlayer;
        this.byPlayerRandomBooks = byPlayerRandomBooks;
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

    /** Read-only progress for {@code (player, storyBasename)}; never null. */
    public NarrativeProgress progressFor(UUID playerUuid, String storyBasename) {
        Map<String, NarrativeProgress> stories = byPlayer.get(playerUuid);
        if (stories == null) return new NarrativeProgress();
        NarrativeProgress p = stories.get(storyBasename);
        return p != null ? p : new NarrativeProgress();
    }

    /**
     * Mark {@code letterIndex} read for the player + story. Returns
     * {@code true} when state changed (caller can decide whether to log).
     * Always calls {@link #setDirty()} so the .dat file persists.
     */
    public boolean markRead(UUID playerUuid, String storyBasename, int letterIndex) {
        NarrativeProgress p = byPlayer
            .computeIfAbsent(playerUuid, k -> new HashMap<>())
            .computeIfAbsent(storyBasename, k -> new NarrativeProgress());
        boolean changed = p.markRead(letterIndex);
        if (changed) setDirty();
        return changed;
    }

    /**
     * Snapshot of every story this player has touched (read at least one
     * letter of). Used by `/narrative progress` to print a table.
     */
    public Map<String, NarrativeProgress> snapshotForPlayer(UUID playerUuid) {
        Map<String, NarrativeProgress> stories = byPlayer.get(playerUuid);
        if (stories == null) return Map.of();
        Map<String, NarrativeProgress> out = new HashMap<>(stories.size());
        for (var e : stories.entrySet()) {
            out.put(e.getKey(), new NarrativeProgress(e.getValue().readLetters()));
        }
        return out;
    }

    /** Reset every story for {@code playerUuid}. */
    public void resetPlayer(UUID playerUuid) {
        Map<String, NarrativeProgress> removed = byPlayer.remove(playerUuid);
        if (removed != null) setDirty();
    }

    // ---------------- Random-book tracking ----------------

    /**
     * True when the player has already seen
     * {@code (bookBasename, variantIndex)}. Used by the equipment-change
     * handler to decide whether to swap the held stack to an unseen pick.
     */
    public boolean hasSeenRandomBook(UUID playerUuid, String bookBasename, int variantIndex) {
        Map<String, NarrativeProgress> books = byPlayerRandomBooks.get(playerUuid);
        if (books == null) return false;
        NarrativeProgress p = books.get(bookBasename);
        return p != null && p.readLetters().contains(variantIndex);
    }

    /**
     * Mark {@code variantIndex} of {@code bookBasename} seen for the player.
     * Returns {@code true} when state changed. Calls {@link #setDirty()} so
     * the .dat file persists.
     */
    public boolean markRandomBookSeen(UUID playerUuid, String bookBasename, int variantIndex) {
        NarrativeProgress p = byPlayerRandomBooks
            .computeIfAbsent(playerUuid, k -> new HashMap<>())
            .computeIfAbsent(bookBasename, k -> new NarrativeProgress());
        boolean changed = p.markRead(variantIndex);
        if (changed) setDirty();
        return changed;
    }

    /** Clear random-book tracking for {@code playerUuid}. */
    public void resetRandomBookProgress(UUID playerUuid) {
        Map<String, NarrativeProgress> removed = byPlayerRandomBooks.remove(playerUuid);
        if (removed != null) setDirty();
    }

    /**
     * Snapshot of every random book this player has touched (any variant
     * seen). Used by {@code /narrative randombook progress} to print a
     * table. Returns an immutable-copy-style map; mutating the result
     * doesn't affect the store.
     */
    public Map<String, NarrativeProgress> randomBookSnapshot(UUID playerUuid) {
        Map<String, NarrativeProgress> books = byPlayerRandomBooks.get(playerUuid);
        if (books == null) return Map.of();
        Map<String, NarrativeProgress> out = new HashMap<>(books.size());
        for (var e : books.entrySet()) {
            out.put(e.getKey(), new NarrativeProgress(e.getValue().readLetters()));
        }
        return out;
    }

    /**
     * Pick the next uncompleted story for {@code playerUuid}, alphabetical
     * by basename. Iterates the live registry so newly-loaded stories show
     * up automatically. Empty when every story is complete.
     */
    public Optional<String> nextUncompletedStory(UUID playerUuid) {
        List<String> all = StoryRegistry.basenames();
        for (String basename : all) {
            Optional<StoryFile> story = StoryRegistry.getByBasename(basename);
            if (story.isEmpty()) continue;
            NarrativeProgress p = progressFor(playerUuid, basename);
            if (!p.isComplete(story.get().letters().size())) return Optional.of(basename);
        }
        return Optional.empty();
    }

    /**
     * First story (alphabetical) that the player has *started* but not
     * finished. "In-progress" = at least one letter read AND not complete.
     *
     * <p>Used by the narrative_lectern's lazy resolver: if the player is in
     * the middle of any story, that lectern continues that story rather
     * than picking a fresh random one. Empty when the player has no
     * in-progress narrative.</p>
     */
    public Optional<String> currentInProgressStory(UUID playerUuid) {
        List<String> all = StoryRegistry.basenames();
        for (String basename : all) {
            Optional<StoryFile> story = StoryRegistry.getByBasename(basename);
            if (story.isEmpty()) continue;
            NarrativeProgress p = progressFor(playerUuid, basename);
            int total = story.get().letters().size();
            if (p.readCount() > 0 && !p.isComplete(total)) {
                return Optional.of(basename);
            }
        }
        return Optional.empty();
    }

    /**
     * Random pick from uncompleted stories, deterministic per
     * {@code randomSeed}. Used by the narrative_lectern when the player has
     * no in-progress story — same lectern shows the same first-read story
     * to the same player on re-clicks (until they actually read something).
     *
     * <p>Empty when every loaded story is complete for the player.</p>
     */
    public Optional<String> randomUncompletedStory(UUID playerUuid, long randomSeed) {
        List<String> uncompleted = new java.util.ArrayList<>();
        for (String basename : StoryRegistry.basenames()) {
            Optional<StoryFile> story = StoryRegistry.getByBasename(basename);
            if (story.isEmpty()) continue;
            NarrativeProgress p = progressFor(playerUuid, basename);
            if (!p.isComplete(story.get().letters().size())) {
                uncompleted.add(basename);
            }
        }
        if (uncompleted.isEmpty()) return Optional.empty();
        // Deterministic mix so the lectern + player UUID + seed produce a
        // stable index across re-clicks until the player advances state.
        int idx = Math.floorMod(randomSeed, uncompleted.size());
        return Optional.of(uncompleted.get(idx));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        // Union of player UUIDs across stories and random-book tracking.
        Set<UUID> uuids = new HashSet<>();
        uuids.addAll(byPlayer.keySet());
        uuids.addAll(byPlayerRandomBooks.keySet());

        ListTag players = new ListTag();
        for (UUID uuid : uuids) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID(TAG_PLAYER_UUID, uuid);
            playerTag.put(TAG_PLAYER_STORIES, encodeStoryProgress(byPlayer.get(uuid)));
            playerTag.put(TAG_PLAYER_RANDOM_BOOKS, encodeStoryProgress(byPlayerRandomBooks.get(uuid)));
            players.add(playerTag);
        }
        tag.put(TAG_PLAYERS, players);
        return tag;
    }

    /**
     * Shared encoder for both the story-progress map and the random-book
     * map — same shape ({@code basename -> Set<Integer>}), so the same
     * ListTag layout works for both. Returns an empty ListTag when the
     * input is null or empty (so the saved player tag stays well-formed).
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

    private static NarrativeProgressData load(CompoundTag tag) {
        Map<UUID, Map<String, NarrativeProgress>> stories = new HashMap<>();
        Map<UUID, Map<String, NarrativeProgress>> randomBooks = new HashMap<>();
        if (!tag.contains(TAG_PLAYERS, Tag.TAG_LIST)) {
            return new NarrativeProgressData(stories, randomBooks);
        }
        ListTag players = tag.getList(TAG_PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag playerTag = players.getCompound(i);
            if (!playerTag.hasUUID(TAG_PLAYER_UUID)) continue;
            UUID uuid = playerTag.getUUID(TAG_PLAYER_UUID);
            Map<String, NarrativeProgress> playerStories = decodeStoryProgress(playerTag, TAG_PLAYER_STORIES);
            if (!playerStories.isEmpty()) stories.put(uuid, playerStories);
            Map<String, NarrativeProgress> playerRandomBooks = decodeStoryProgress(playerTag, TAG_PLAYER_RANDOM_BOOKS);
            if (!playerRandomBooks.isEmpty()) randomBooks.put(uuid, playerRandomBooks);
        }
        return new NarrativeProgressData(stories, randomBooks);
    }

    /** Inverse of {@link #encodeStoryProgress}; tolerates missing tag (returns empty). */
    private static Map<String, NarrativeProgress> decodeStoryProgress(CompoundTag playerTag, String key) {
        Map<String, NarrativeProgress> out = new HashMap<>();
        if (!playerTag.contains(key, Tag.TAG_LIST)) return out;
        ListTag list = playerTag.getList(key, Tag.TAG_COMPOUND);
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
