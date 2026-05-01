package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGenerationConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world persistence for world-creation choices originally exposed by
 * {@link DungeonTrainConfig}: train Y, "starts with train" auto-spawn toggle,
 * carriage length/width/height, and (since v0.25) a generation seed that
 * keeps random-mode carriages deterministic across restarts. Stored at
 * {@code <world>/data/dungeontrain_world.dat} on the overworld
 * {@link ServerLevel}.
 *
 * Back-compat: when no .dat file exists yet (worlds created before this
 * feature landed), {@link #createDefault()} sources the train Y from the
 * server config TOML, defaults auto-spawn to ON, uses
 * {@link CarriageDims#DEFAULT} (9×7×7) for dims, and leaves the generation
 * seed at 0 — the first save after world load re-seeds it from the level's
 * random so legacy worlds get a real per-world seed without a migration step.
 *
 * Individual NBT tags are checked independently so a world saved with only
 * {@code trainY}/{@code startsWithTrain} (pre-dims) loads cleanly with
 * defaults filled in. Clamping happens on read so legacy values outside
 * current floors/ceilings self-heal.
 */
public final class DungeonTrainWorldData extends SavedData {

    public static final String NAME = "dungeontrain_world";

    private static final String TAG_TRAIN_Y = "trainY";
    private static final String TAG_STARTS_WITH_TRAIN = "startsWithTrain";
    private static final String TAG_CARRIAGE_LENGTH = "carriageLength";
    private static final String TAG_CARRIAGE_WIDTH = "carriageWidth";
    private static final String TAG_CARRIAGE_HEIGHT = "carriageHeight";
    private static final String TAG_GENERATION_SEED = "generationSeed";
    private static final String TAG_STARTING_DIMENSION = "startingDimension";

    private int trainY;
    private boolean startsWithTrain;
    private CarriageDims dims;
    private long generationSeed;
    private StartingDimension startingDimension;

    private DungeonTrainWorldData(int trainY, boolean startsWithTrain, CarriageDims dims, long generationSeed, StartingDimension startingDimension) {
        this.trainY = trainY;
        this.startsWithTrain = startsWithTrain;
        this.dims = dims;
        this.generationSeed = generationSeed;
        this.startingDimension = startingDimension;
    }

    public static DungeonTrainWorldData get(ServerLevel overworld) {
        DungeonTrainWorldData data = overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(
                DungeonTrainWorldData::createDefault,
                (tag, registries) -> load(tag)
            ),
            NAME
        );
        // Legacy upgrade path: missing NBT tag loaded as seed=0. Replace with
        // a real per-world seed drawn from the overworld's random so the
        // Random/RandomGrouped modes produce the same layout on future
        // loads. Mark dirty so the fresh seed persists.
        if (data.generationSeed == 0L) {
            data.generationSeed = overworld.random.nextLong();
            data.setDirty();
        }
        return data;
    }

    private static DungeonTrainWorldData createDefault() {
        return new DungeonTrainWorldData(
                DungeonTrainConfig.getTrainY(),
                true,
                CarriageDims.DEFAULT,
                0L,
                StartingDimension.OVERWORLD
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
        long seed = tag.contains(TAG_GENERATION_SEED) ? tag.getLong(TAG_GENERATION_SEED) : 0L;
        StartingDimension sd = tag.contains(TAG_STARTING_DIMENSION)
                ? StartingDimension.fromNbt(tag.getString(TAG_STARTING_DIMENSION))
                : StartingDimension.OVERWORLD;
        return new DungeonTrainWorldData(y, s, d, seed, sd);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(TAG_TRAIN_Y, trainY);
        tag.putBoolean(TAG_STARTS_WITH_TRAIN, startsWithTrain);
        tag.putInt(TAG_CARRIAGE_LENGTH, dims.length());
        tag.putInt(TAG_CARRIAGE_WIDTH, dims.width());
        tag.putInt(TAG_CARRIAGE_HEIGHT, dims.height());
        tag.putLong(TAG_GENERATION_SEED, generationSeed);
        tag.putString(TAG_STARTING_DIMENSION, startingDimension.nbtId());
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

    public long getGenerationSeed() {
        return generationSeed;
    }

    public StartingDimension startingDimension() {
        return startingDimension;
    }

    public void setStartingDimension(StartingDimension d) {
        if (d == null || this.startingDimension == d) return;
        this.startingDimension = d;
        setDirty();
    }

    /**
     * Build a complete {@link CarriageGenerationConfig} by pairing the
     * per-world seed stored here with the mode + groupSize read live from
     * {@link DungeonTrainConfig}. Called from {@code TrainAssembler} (once at
     * spawn) and {@code TrainWindowManager} (once per tick).
     */
    public CarriageGenerationConfig getGenerationConfig() {
        return new CarriageGenerationConfig(
                DungeonTrainConfig.getGenerationMode(),
                DungeonTrainConfig.getGroupSize(),
                generationSeed);
    }

    public void apply(int trainY, boolean startsWithTrain, CarriageDims dims) {
        this.trainY = clampY(trainY);
        this.startsWithTrain = startsWithTrain;
        this.dims = dims;
        setDirty();
    }

    /**
     * Explicitly set the per-world generation seed — used by
     * {@code WorldLifecycleEvents} at integrated-server start to lock in a
     * value derived from the overworld's random.
     */
    public void setGenerationSeed(long seed) {
        this.generationSeed = seed;
        setDirty();
    }

    private static int clampY(int y) {
        return Math.max(DungeonTrainConfig.MIN_TRAIN_Y, Math.min(DungeonTrainConfig.MAX_TRAIN_Y, y));
    }
}
