package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.nbt.CompoundTag;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Everything a nap keeps: the player as vanilla saves them (inventory, XP, effects, every mod's data
 * attachments including DT's run state), where they were on the train, and how the run stood.
 *
 * <p>{@code levelId} is the save folder the nap came from. It is deleted when the nap is taken, and
 * again by the title-screen sweep if it survived (a crash between saving and deleting), so a nap and
 * its world never both exist. {@code napId} is written to the applied list when the nap wakes, so a
 * copied file can't wake twice ({@link NapStore}).</p>
 *
 * @param x          world X the player napped at
 * @param carriage   carriage number there ({@code x} over the calibrated carriage length)
 * @param band       band label at {@code x} ({@code BandLabel.bandAt}), for the log and the wake check
 * @param direction  +1 when the player faced +X along the train, -1 for -X
 * @param worldDepth the world's carriage counter ({@code BoardingProgressData}), raised again on waking
 * @param cheated    the run was Free Play, from any source; the woken run stays Free Play
 * @param advancementsJson the player's advancements file from the napped world, verbatim; empty when none
 * @param statsJson  the player's stats file from the napped world, verbatim; empty when none
 * @param narrative  the world's narrative progress for the player ({@code NarrativeProgressData#exportFor});
 *                   empty when none. All three are absent from naps saved before they existed.
 * @param runExtras  the run-state fields the attachment codec has no room for
 *                   ({@code PlayerRunState#napExtras}); empty when none (naps saved before it existed)
 * @param difficultyOffset the napped world's admin difficulty travelled-offset
 *                   ({@code DungeonTrainWorldData#getDifficultyTravelledOffset}); 0 = automatic, and
 *                   for naps saved before it existed
 * @param wakeLevelId the save folder this nap is waking in, written before the train spawns there
 *                   ({@link NapBinding}); empty while unbound, and for naps saved before it existed
 * @param place      where the train stood in the band layout, and the napped world's reverse-slide state;
 *                   null for naps saved before it existed (and in worlds with no layout), which wake by X alone
 */
public record NapSnapshot(UUID napId, long savedAtEpochMs, String modVersion, String levelId,
                          CompoundTag player, double x, int carriage, String band, int direction,
                          int worldDepth, boolean cheated, String difficulty,
                          String advancementsJson, String statsJson, CompoundTag narrative,
                          CompoundTag runExtras, int difficultyOffset, String wakeLevelId,
                          Place place) {

    /**
     * The train's place in the band layout when the nap was taken ({@link WorldGenCycle#bandPosition}),
     * with the napped world's reverse-slide state ({@code worldgen.ReverseSlide}): behind spawn the band
     * at an X depends on that slide, so the woken world takes it too.
     */
    public record Place(WorldGenCycle.BandPosition position, long originX, long reachX, long earned, long slide) {}

    private static final String TAG_ID = "napId";
    private static final String TAG_SAVED = "savedAt";
    private static final String TAG_VERSION = "modVersion";
    private static final String TAG_LEVEL = "levelId";
    private static final String TAG_PLAYER = "player";
    private static final String TAG_X = "x";
    private static final String TAG_CARRIAGE = "carriage";
    private static final String TAG_BAND = "band";
    private static final String TAG_DIRECTION = "direction";
    private static final String TAG_DEPTH = "worldDepth";
    private static final String TAG_CHEATED = "cheated";
    private static final String TAG_DIFFICULTY = "difficulty";
    private static final String TAG_ADVANCEMENTS = "advancements";
    private static final String TAG_STATS = "stats";
    private static final String TAG_NARRATIVE = "narrative";
    private static final String TAG_RUN_EXTRAS = "runExtras";
    private static final String TAG_DIFFICULTY_OFFSET = "difficultyOffset";
    private static final String TAG_WAKE_LEVEL = "wakeLevelId";
    private static final String TAG_PLACE = "place";

    public NapSnapshot {
        direction = direction < 0 ? -1 : 1;
        band = band == null ? "" : band;
        difficulty = difficulty == null ? "" : difficulty;
        advancementsJson = advancementsJson == null ? "" : advancementsJson;
        statsJson = statsJson == null ? "" : statsJson;
        narrative = narrative == null ? new CompoundTag() : narrative;
        runExtras = runExtras == null ? new CompoundTag() : runExtras;
        wakeLevelId = wakeLevelId == null ? "" : wakeLevelId;
    }

    /** A nap with no band place (the form naps had before {@link Place}). */
    public NapSnapshot(UUID napId, long savedAtEpochMs, String modVersion, String levelId,
                       CompoundTag player, double x, int carriage, String band, int direction,
                       int worldDepth, boolean cheated, String difficulty,
                       String advancementsJson, String statsJson, CompoundTag narrative,
                       CompoundTag runExtras, int difficultyOffset, String wakeLevelId) {
        this(napId, savedAtEpochMs, modVersion, levelId, player, x, carriage, band, direction, worldDepth,
            cheated, difficulty, advancementsJson, statsJson, narrative, runExtras, difficultyOffset, wakeLevelId, null);
    }

    /** An unbound nap (not yet tied to the world it wakes in). */
    public NapSnapshot(UUID napId, long savedAtEpochMs, String modVersion, String levelId,
                       CompoundTag player, double x, int carriage, String band, int direction,
                       int worldDepth, boolean cheated, String difficulty,
                       String advancementsJson, String statsJson, CompoundTag narrative,
                       CompoundTag runExtras, int difficultyOffset) {
        this(napId, savedAtEpochMs, modVersion, levelId, player, x, carriage, band, direction, worldDepth,
            cheated, difficulty, advancementsJson, statsJson, narrative, runExtras, difficultyOffset, "");
    }

    /** True once the nap has started waking in a world ({@link NapBinding}). */
    public boolean bound() {
        return !wakeLevelId.isEmpty();
    }

    /** This nap tied to the save folder {@code levelId}: from now on it wakes only there. */
    public NapSnapshot boundTo(String levelId) {
        return new NapSnapshot(napId, savedAtEpochMs, modVersion, this.levelId, player, x, carriage, band, direction,
            worldDepth, cheated, difficulty, advancementsJson, statsJson, narrative, runExtras, difficultyOffset,
            levelId, place);
    }

    /** This nap with its X replaced (the X it wakes at, once resolved from {@link #place}). */
    public NapSnapshot withX(double newX) {
        return new NapSnapshot(napId, savedAtEpochMs, modVersion, levelId, player, newX, carriage, band, direction,
            worldDepth, cheated, difficulty, advancementsJson, statsJson, narrative, runExtras, difficultyOffset,
            wakeLevelId, place);
    }

    /** This nap as a Free Play run (its file couldn't be vouched for: {@link NapStore.Loaded#tampered}). */
    public NapSnapshot asFreePlay() {
        return new NapSnapshot(napId, savedAtEpochMs, modVersion, levelId, player, x, carriage, band, direction,
            worldDepth, true, difficulty, advancementsJson, statsJson, narrative, runExtras, difficultyOffset,
            wakeLevelId, place);
    }

    /** This nap with the train's band place {@code place}. */
    public NapSnapshot withPlace(Place place) {
        return new NapSnapshot(napId, savedAtEpochMs, modVersion, levelId, player, x, carriage, band, direction,
            worldDepth, cheated, difficulty, advancementsJson, statsJson, narrative, runExtras, difficultyOffset,
            wakeLevelId, place);
    }

    /** A nap without the run-state extras or a difficulty offset. */
    public NapSnapshot(UUID napId, long savedAtEpochMs, String modVersion, String levelId,
                       CompoundTag player, double x, int carriage, String band, int direction,
                       int worldDepth, boolean cheated, String difficulty,
                       String advancementsJson, String statsJson, CompoundTag narrative) {
        this(napId, savedAtEpochMs, modVersion, levelId, player, x, carriage, band, direction, worldDepth,
            cheated, difficulty, advancementsJson, statsJson, narrative, new CompoundTag(), 0);
    }

    /** A nap without the previous life's advancements, stats or narrative progress. */
    public NapSnapshot(UUID napId, long savedAtEpochMs, String modVersion, String levelId,
                       CompoundTag player, double x, int carriage, String band, int direction,
                       int worldDepth, boolean cheated, String difficulty) {
        this(napId, savedAtEpochMs, modVersion, levelId, player, x, carriage, band, direction, worldDepth,
            cheated, difficulty, "", "", new CompoundTag());
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, napId);
        tag.putLong(TAG_SAVED, savedAtEpochMs);
        tag.putString(TAG_VERSION, modVersion);
        tag.putString(TAG_LEVEL, levelId);
        tag.put(TAG_PLAYER, player.copy());
        tag.putDouble(TAG_X, x);
        tag.putInt(TAG_CARRIAGE, carriage);
        tag.putString(TAG_BAND, band);
        tag.putInt(TAG_DIRECTION, direction);
        tag.putInt(TAG_DEPTH, worldDepth);
        tag.putBoolean(TAG_CHEATED, cheated);
        tag.putString(TAG_DIFFICULTY, difficulty);
        // Bytes, not a string tag: NBT strings stop at 64 KB and a long run's advancements can pass that.
        if (!advancementsJson.isEmpty()) tag.putByteArray(TAG_ADVANCEMENTS, advancementsJson.getBytes(StandardCharsets.UTF_8));
        if (!statsJson.isEmpty()) tag.putByteArray(TAG_STATS, statsJson.getBytes(StandardCharsets.UTF_8));
        if (!narrative.isEmpty()) tag.put(TAG_NARRATIVE, narrative.copy());
        if (!runExtras.isEmpty()) tag.put(TAG_RUN_EXTRAS, runExtras.copy());
        if (difficultyOffset != 0) tag.putInt(TAG_DIFFICULTY_OFFSET, difficultyOffset);
        if (!wakeLevelId.isEmpty()) tag.putString(TAG_WAKE_LEVEL, wakeLevelId);
        if (place != null && place.position() != null) tag.put(TAG_PLACE, placeTag(place));
        return tag;
    }

    /** Null when {@code tag} is not a nap (no id or no player). */
    public static NapSnapshot fromTag(CompoundTag tag) {
        if (!tag.hasUUID(TAG_ID) || !tag.contains(TAG_PLAYER)) return null;
        return new NapSnapshot(tag.getUUID(TAG_ID), tag.getLong(TAG_SAVED), tag.getString(TAG_VERSION),
            tag.getString(TAG_LEVEL), tag.getCompound(TAG_PLAYER), tag.getDouble(TAG_X), tag.getInt(TAG_CARRIAGE),
            tag.getString(TAG_BAND), tag.getInt(TAG_DIRECTION), tag.getInt(TAG_DEPTH), tag.getBoolean(TAG_CHEATED),
            tag.getString(TAG_DIFFICULTY), text(tag, TAG_ADVANCEMENTS), text(tag, TAG_STATS),
            tag.getCompound(TAG_NARRATIVE), tag.getCompound(TAG_RUN_EXTRAS), tag.getInt(TAG_DIFFICULTY_OFFSET),
            tag.getString(TAG_WAKE_LEVEL), tag.contains(TAG_PLACE) ? placeOf(tag.getCompound(TAG_PLACE)) : null);
    }

    private static CompoundTag placeTag(Place p) {
        CompoundTag t = new CompoundTag();
        t.putBoolean("reversed", p.position().reversed());
        t.putInt("run", p.position().run());
        t.putInt("slot", p.position().slot());
        t.putLong("local", p.position().local());
        t.putLong("originX", p.originX());
        t.putLong("reachX", p.reachX());
        t.putLong("earned", p.earned());
        t.putLong("slide", p.slide());
        return t;
    }

    /** Null when the tag is not a whole place (a damaged file): the nap then wakes by X alone. */
    private static Place placeOf(CompoundTag t) {
        for (String k : new String[] {"reversed", "run", "slot", "local", "originX", "reachX", "earned", "slide"}) {
            if (!t.contains(k)) return null;
        }
        return new Place(new WorldGenCycle.BandPosition(t.getBoolean("reversed"), t.getInt("run"), t.getInt("slot"),
            t.getLong("local")), t.getLong("originX"), t.getLong("reachX"), t.getLong("earned"), t.getLong("slide"));
    }

    private static String text(CompoundTag tag, String key) {
        return new String(tag.getByteArray(key), StandardCharsets.UTF_8);
    }
}
