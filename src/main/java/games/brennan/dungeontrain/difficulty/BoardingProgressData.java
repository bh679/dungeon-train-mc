package games.brennan.dungeontrain.difficulty;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import javax.annotation.Nullable;

import java.util.UUID;

/**
 * Tracks the global "travelled carriage index" — the signed net number of
 * carriages players have moved through while boarded on the train. Frozen
 * when no player is on the train. Drives {@link DifficultyApplier} tier
 * computation in place of the per-mob stamped carriage pIdx, so progression
 * scales with player travel rather than absolute train length.
 *
 * <p>Stored at {@code <world>/data/dungeontrain_boarding_progress.dat} on
 * the overworld {@link ServerLevel}.</p>
 *
 * <p>Only {@link #travelledCarriageIndex} is persisted. The leader-tracking
 * state ({@link #activeLeaderUUID} / {@link #lastLeaderCarriage}) is
 * transient and re-established by {@code BoardingProgressEvents} on the
 * first tick after server start where a player is detected on the train.</p>
 */
public final class BoardingProgressData extends SavedData {

    public static final String NAME = "dungeontrain_boarding_progress";

    private static final String TAG_TRAVELLED = "travelledCarriageIndex";

    private int travelledCarriageIndex;
    @Nullable private UUID activeLeaderUUID;
    private int lastLeaderCarriage;

    private BoardingProgressData(int travelledCarriageIndex) {
        this.travelledCarriageIndex = travelledCarriageIndex;
    }

    public static BoardingProgressData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(
                BoardingProgressData::createDefault,
                (tag, registries) -> load(tag)
            ),
            NAME
        );
    }

    private static BoardingProgressData createDefault() {
        return new BoardingProgressData(0);
    }

    private static BoardingProgressData load(CompoundTag tag) {
        int t = tag.contains(TAG_TRAVELLED) ? tag.getInt(TAG_TRAVELLED) : 0;
        return new BoardingProgressData(t);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(TAG_TRAVELLED, travelledCarriageIndex);
        return tag;
    }

    public int travelledCarriageIndex() {
        return travelledCarriageIndex;
    }

    @Nullable
    public UUID activeLeaderUUID() {
        return activeLeaderUUID;
    }

    public int lastLeaderCarriage() {
        return lastLeaderCarriage;
    }

    /**
     * Apply an in-session delta from the active leader's movement. Updates
     * {@link #lastLeaderCarriage} regardless of delta; marks the data dirty
     * only when {@code delta != 0}.
     */
    public void advance(int delta, int newLastLeaderCarriage) {
        this.lastLeaderCarriage = newLastLeaderCarriage;
        if (delta == 0) return;
        this.travelledCarriageIndex += delta;
        setDirty();
    }

    /**
     * Start a new boarding session by assigning a leader player. The handoff
     * itself contributes no delta — only subsequent ticks where this leader
     * remains boarded advance the counter.
     */
    public void setLeader(UUID leader, int leaderCarriage) {
        this.activeLeaderUUID = leader;
        this.lastLeaderCarriage = leaderCarriage;
    }

    /** Called when no player is on the train. Freezes the counter. */
    public void clearLeader() {
        this.activeLeaderUUID = null;
        this.lastLeaderCarriage = 0;
    }
}
