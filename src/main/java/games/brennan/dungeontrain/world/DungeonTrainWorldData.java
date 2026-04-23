package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world persistence for world-creation choices originally exposed by
 * {@link DungeonTrainConfig}: train Y, "starts with train" auto-spawn toggle,
 * and (since v0.21) carriage length/width/height. Stored at
 * {@code <world>/data/dungeontrain_world.dat} on the overworld
 * {@link ServerLevel}.
 *
 * Back-compat: when no .dat file exists yet (worlds created before this
 * feature landed), {@link #createDefault()} sources the train Y from the
 * server config TOML, defaults auto-spawn to ON, and uses
 * {@link CarriageDims#DEFAULT} (9×7×7) for dims — preserving any tuned
 * value an admin already set in {@code dungeontrain-server.toml}.
 *
 * Individual NBT tags are checked independently so a world saved with
 * only {@code trainY}/{@code startsWithTrain} (pre-dims) loads cleanly
 * with default dims filled in. Clamping happens on read so legacy values
 * outside current floors/ceilings self-heal.
 */
public final class DungeonTrainWorldData extends SavedData {

    public static final String NAME = "dungeontrain_world";

    private static final String TAG_TRAIN_Y = "trainY";
    private static final String TAG_STARTS_WITH_TRAIN = "startsWithTrain";
    private static final String TAG_CARRIAGE_LENGTH = "carriageLength";
    private static final String TAG_CARRIAGE_WIDTH = "carriageWidth";
    private static final String TAG_CARRIAGE_HEIGHT = "carriageHeight";

    private int trainY;
    private boolean startsWithTrain;
    private CarriageDims dims;

    private DungeonTrainWorldData(int trainY, boolean startsWithTrain, CarriageDims dims) {
        this.trainY = trainY;
        this.startsWithTrain = startsWithTrain;
        this.dims = dims;
    }

    public static DungeonTrainWorldData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
            DungeonTrainWorldData::load,
            DungeonTrainWorldData::createDefault,
            NAME
        );
    }

    private static DungeonTrainWorldData createDefault() {
        return new DungeonTrainWorldData(
                DungeonTrainConfig.getTrainY(),
                true,
                CarriageDims.DEFAULT
        );
    }

    private static DungeonTrainWorldData load(CompoundTag tag) {
        int y = tag.contains(TAG_TRAIN_Y)
            ? clampY(tag.getInt(TAG_TRAIN_Y))
            : DungeonTrainConfig.getTrainY();
        boolean s = !tag.contains(TAG_STARTS_WITH_TRAIN) || tag.getBoolean(TAG_STARTS_WITH_TRAIN);
        // If any dims tag is missing, fall back to DEFAULT rather than mixing
        // partial legacy values with current defaults — keeps the footprint
        // coherent for pre-0.21 world saves.
        boolean hasAllDims = tag.contains(TAG_CARRIAGE_LENGTH)
                && tag.contains(TAG_CARRIAGE_WIDTH)
                && tag.contains(TAG_CARRIAGE_HEIGHT);
        CarriageDims d = hasAllDims
                ? CarriageDims.clamp(
                        tag.getInt(TAG_CARRIAGE_LENGTH),
                        tag.getInt(TAG_CARRIAGE_WIDTH),
                        tag.getInt(TAG_CARRIAGE_HEIGHT))
                : CarriageDims.DEFAULT;
        return new DungeonTrainWorldData(y, s, d);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt(TAG_TRAIN_Y, trainY);
        tag.putBoolean(TAG_STARTS_WITH_TRAIN, startsWithTrain);
        tag.putInt(TAG_CARRIAGE_LENGTH, dims.length());
        tag.putInt(TAG_CARRIAGE_WIDTH, dims.width());
        tag.putInt(TAG_CARRIAGE_HEIGHT, dims.height());
        return tag;
    }

    public int getTrainY() {
        return trainY;
    }

    public boolean startsWithTrain() {
        return startsWithTrain;
    }

    public CarriageDims dims() {
        return dims;
    }

    public void apply(int trainY, boolean startsWithTrain, CarriageDims dims) {
        this.trainY = clampY(trainY);
        this.startsWithTrain = startsWithTrain;
        this.dims = dims;
        setDirty();
    }

    private static int clampY(int y) {
        return Math.max(DungeonTrainConfig.MIN_TRAIN_Y, Math.min(DungeonTrainConfig.MAX_TRAIN_Y, y));
    }
}
