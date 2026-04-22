package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world persistence for the two world-creation choices originally exposed
 * by {@link DungeonTrainConfig}: train Y and "starts with train" auto-spawn
 * toggle. Stored at {@code <world>/data/dungeontrain_world.dat} on the
 * overworld {@link ServerLevel}.
 *
 * Back-compat: when no .dat file exists yet (worlds created before this
 * feature landed), {@link #createDefault()} sources the train Y from the
 * server config TOML and defaults auto-spawn to ON — preserving any tuned
 * value an admin already set in {@code dungeontrain-server.toml}.
 */
public final class DungeonTrainWorldData extends SavedData {

    public static final String NAME = "dungeontrain_world";

    private static final String TAG_TRAIN_Y = "trainY";
    private static final String TAG_STARTS_WITH_TRAIN = "startsWithTrain";

    private int trainY;
    private boolean startsWithTrain;

    private DungeonTrainWorldData(int trainY, boolean startsWithTrain) {
        this.trainY = trainY;
        this.startsWithTrain = startsWithTrain;
    }

    public static DungeonTrainWorldData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
            DungeonTrainWorldData::load,
            DungeonTrainWorldData::createDefault,
            NAME
        );
    }

    private static DungeonTrainWorldData createDefault() {
        return new DungeonTrainWorldData(DungeonTrainConfig.getTrainY(), true);
    }

    private static DungeonTrainWorldData load(CompoundTag tag) {
        int y = tag.contains(TAG_TRAIN_Y)
            ? clampY(tag.getInt(TAG_TRAIN_Y))
            : DungeonTrainConfig.getTrainY();
        boolean s = !tag.contains(TAG_STARTS_WITH_TRAIN) || tag.getBoolean(TAG_STARTS_WITH_TRAIN);
        return new DungeonTrainWorldData(y, s);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt(TAG_TRAIN_Y, trainY);
        tag.putBoolean(TAG_STARTS_WITH_TRAIN, startsWithTrain);
        return tag;
    }

    public int getTrainY() {
        return trainY;
    }

    public boolean startsWithTrain() {
        return startsWithTrain;
    }

    public void apply(int trainY, boolean startsWithTrain) {
        this.trainY = clampY(trainY);
        this.startsWithTrain = startsWithTrain;
        setDirty();
    }

    private static int clampY(int y) {
        return Math.max(DungeonTrainConfig.MIN_TRAIN_Y, Math.min(DungeonTrainConfig.MAX_TRAIN_Y, y));
    }
}
