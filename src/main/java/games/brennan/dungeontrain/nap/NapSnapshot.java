package games.brennan.dungeontrain.nap;

import net.minecraft.nbt.CompoundTag;

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
 */
public record NapSnapshot(UUID napId, long savedAtEpochMs, String modVersion, String levelId,
                          CompoundTag player, double x, int carriage, String band, int direction,
                          int worldDepth, boolean cheated, String difficulty) {

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

    public NapSnapshot {
        direction = direction < 0 ? -1 : 1;
        band = band == null ? "" : band;
        difficulty = difficulty == null ? "" : difficulty;
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
        return tag;
    }

    /** Null when {@code tag} is not a nap (no id or no player). */
    public static NapSnapshot fromTag(CompoundTag tag) {
        if (!tag.hasUUID(TAG_ID) || !tag.contains(TAG_PLAYER)) return null;
        return new NapSnapshot(tag.getUUID(TAG_ID), tag.getLong(TAG_SAVED), tag.getString(TAG_VERSION),
            tag.getString(TAG_LEVEL), tag.getCompound(TAG_PLAYER), tag.getDouble(TAG_X), tag.getInt(TAG_CARRIAGE),
            tag.getString(TAG_BAND), tag.getInt(TAG_DIRECTION), tag.getInt(TAG_DEPTH), tag.getBoolean(TAG_CHEATED),
            tag.getString(TAG_DIFFICULTY));
    }
}
