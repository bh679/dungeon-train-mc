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
    private static final String TAG_STORY_ID = "id";
    private static final String TAG_STORY_READ = "read";

    private final Map<UUID, Map<String, NarrativeProgress>> byPlayer;

    private NarrativeProgressData() {
        this(new HashMap<>());
    }

    private NarrativeProgressData(Map<UUID, Map<String, NarrativeProgress>> byPlayer) {
        this.byPlayer = byPlayer;
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
        ListTag players = new ListTag();
        for (var playerEntry : byPlayer.entrySet()) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID(TAG_PLAYER_UUID, playerEntry.getKey());
            ListTag stories = new ListTag();
            for (var storyEntry : playerEntry.getValue().entrySet()) {
                CompoundTag storyTag = new CompoundTag();
                storyTag.putString(TAG_STORY_ID, storyEntry.getKey());
                Set<Integer> read = storyEntry.getValue().readLetters();
                int[] arr = new int[read.size()];
                int i = 0;
                for (Integer v : read) arr[i++] = v;
                storyTag.putIntArray(TAG_STORY_READ, arr);
                stories.add(storyTag);
            }
            playerTag.put(TAG_PLAYER_STORIES, stories);
            players.add(playerTag);
        }
        tag.put(TAG_PLAYERS, players);
        return tag;
    }

    private static NarrativeProgressData load(CompoundTag tag) {
        Map<UUID, Map<String, NarrativeProgress>> map = new HashMap<>();
        if (!tag.contains(TAG_PLAYERS, Tag.TAG_LIST)) return new NarrativeProgressData(map);
        ListTag players = tag.getList(TAG_PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag playerTag = players.getCompound(i);
            if (!playerTag.hasUUID(TAG_PLAYER_UUID)) continue;
            UUID uuid = playerTag.getUUID(TAG_PLAYER_UUID);
            Map<String, NarrativeProgress> stories = new HashMap<>();
            if (playerTag.contains(TAG_PLAYER_STORIES, Tag.TAG_LIST)) {
                ListTag storyList = playerTag.getList(TAG_PLAYER_STORIES, Tag.TAG_COMPOUND);
                for (int j = 0; j < storyList.size(); j++) {
                    CompoundTag storyTag = storyList.getCompound(j);
                    if (!storyTag.contains(TAG_STORY_ID, Tag.TAG_STRING)) continue;
                    String basename = storyTag.getString(TAG_STORY_ID);
                    int[] arr = storyTag.getIntArray(TAG_STORY_READ);
                    TreeSet<Integer> set = new TreeSet<>();
                    for (int v : arr) set.add(v);
                    stories.put(basename, new NarrativeProgress(set));
                }
            }
            map.put(uuid, stories);
        }
        return new NarrativeProgressData(map);
    }

}
