package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for the per-world difficulty travelled-offset — the storage half of
 * "changes to /dungeontrain difficulty and /dtp reset on a new world".
 *
 * <p>Bypasses the live {@code ServerLevel#getDataStorage()} path by round-tripping a
 * {@link CompoundTag} through {@link DungeonTrainWorldData#load(CompoundTag)} /
 * {@link DungeonTrainWorldData#save(CompoundTag, net.minecraft.core.HolderLookup.Provider)},
 * mirroring {@code StairsLocationDataTest}. The {@code HolderLookup.Provider} is unused by
 * this store, so {@code null} is safe.</p>
 */
final class DungeonTrainWorldDataOffsetTest {

    private static DungeonTrainWorldData roundTrip(DungeonTrainWorldData data) {
        return DungeonTrainWorldData.load(data.save(new CompoundTag(), null));
    }

    @Test
    @DisplayName("a fresh world starts fully automatic (offset 0)")
    void defaultsToZero() {
        assertEquals(0, DungeonTrainWorldData.createDefault().getDifficultyTravelledOffset());
    }

    @Test
    @DisplayName("a save from before the offset was per-world loads as 0, not as whatever the global config held")
    void legacyTagLoadsAsZero() {
        CompoundTag legacy = DungeonTrainWorldData.createDefault().save(new CompoundTag(), null);
        legacy.remove("difficultyTravelledOffset");

        assertEquals(0, DungeonTrainWorldData.load(legacy).getDifficultyTravelledOffset());
    }

    @Test
    @DisplayName("a set offset survives the save/load round-trip, so reloading the same world keeps it")
    void roundTripsSetValue() {
        DungeonTrainWorldData data = DungeonTrainWorldData.createDefault();
        data.setDifficultyTravelledOffset(1234);

        assertEquals(1234, roundTrip(data).getDifficultyTravelledOffset());
    }

    @Test
    @DisplayName("negative offsets round-trip too — difficulty can be pushed below real progress")
    void roundTripsNegativeValue() {
        DungeonTrainWorldData data = DungeonTrainWorldData.createDefault();
        data.setDifficultyTravelledOffset(-500);

        assertEquals(-500, roundTrip(data).getDifficultyTravelledOffset());
    }

    @Test
    @DisplayName("out-of-range values clamp to the config's range on write and on load")
    void clampsOutOfRange() {
        DungeonTrainWorldData data = DungeonTrainWorldData.createDefault();

        data.setDifficultyTravelledOffset(Integer.MAX_VALUE);
        assertEquals(DungeonTrainConfig.MAX_DIFFICULTY_TRAVELLED_OFFSET, data.getDifficultyTravelledOffset());

        data.setDifficultyTravelledOffset(Integer.MIN_VALUE);
        assertEquals(DungeonTrainConfig.MIN_DIFFICULTY_TRAVELLED_OFFSET, data.getDifficultyTravelledOffset());

        CompoundTag tampered = data.save(new CompoundTag(), null);
        tampered.putInt("difficultyTravelledOffset", Integer.MAX_VALUE);
        assertEquals(DungeonTrainConfig.MAX_DIFFICULTY_TRAVELLED_OFFSET,
            DungeonTrainWorldData.load(tampered).getDifficultyTravelledOffset());
    }
}
