package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGenerationConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Objects;

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

    /**
     * Reusable {@link SavedData.Factory} for {@link #get}. Both references are static, so the
     * factory holds no per-world state and is safe to share. Hoisted to a constant because
     * {@link #get} is called on the worldgen hot path (e.g. {@code DisintegrationBand.startX} /
     * {@code NetherBand.startX} per chunk); allocating a fresh factory + capturing lambda per call
     * showed up as a per-chunk allocation storm while a train crosses the Nether band.
     */
    private static final SavedData.Factory<DungeonTrainWorldData> FACTORY = new SavedData.Factory<>(
            DungeonTrainWorldData::createDefault,
            (tag, registries) -> load(tag));

    private static final String TAG_TRAIN_Y = "trainY";
    private static final String TAG_STARTS_WITH_TRAIN = "startsWithTrain";
    private static final String TAG_CARRIAGE_LENGTH = "carriageLength";
    private static final String TAG_CARRIAGE_WIDTH = "carriageWidth";
    private static final String TAG_CARRIAGE_HEIGHT = "carriageHeight";
    private static final String TAG_GENERATION_SEED = "generationSeed";
    private static final String TAG_STARTING_DIMENSION = "startingDimension";
    private static final String TAG_PLAYER_MOB_SPAWN_OVERRIDE = "playerMobSpawnOneInOverride";
    private static final String TAG_PLAYER_MOB_BEHIND_SPAWN_OVERRIDE = "playerMobBehindSpawnPercentOverride";
    private static final String TAG_JOIN_REPORT_POSTED = "joinReportPosted";
    private static final String TAG_BREAK_BLOCKS_ON_CONTACT_OVERRIDE = "breakBlocksOnContactOverride";

    private int trainY;
    private boolean startsWithTrain;
    private CarriageDims dims;
    private long generationSeed;
    private StartingDimension startingDimension;
    /** Per-world override of the PlayerMob 1-in-N spawn rate; null = use the global COMMON default. */
    private Integer playerMobSpawnOneInOverride;
    /** Per-world override of the behind-the-player PlayerMob spawn percent chance; null = global COMMON default. */
    private Integer playerMobBehindSpawnPercentOverride;
    /** Per-world override of train-on-contact block breaking; null = use the global COMMON default. */
    private Boolean breakBlocksOnContactOverride;
    /** Per-world one-shot: true once the join-info report (DT version + train seed + mods) has been posted to Discord. */
    private boolean joinReportPosted;

    /**
     * Transient scheduling set of chunk keys ({@link net.minecraft.world.level.ChunkPos#toLong}) whose
     * upside-down mirror is deferred and still pending. NOT serialized — the durable truth is the
     * {@code NEEDS_UPSIDE_DOWN_MIRROR} chunk attachment; this set is only a fast-path work list, rebuilt
     * from {@code ChunkEvent.Load} enqueues (+ the reconciling scan) after a reload. A {@link LinkedHashSet}
     * so it dedups (a chunk enqueued at gen, on reload, and by the scan is only processed once) while
     * keeping insertion order as a stable tiebreak. Main-thread only (Load + level tick both run there).
     */
    private final java.util.Set<Long> pendingMirrorChunks = new java.util.LinkedHashSet<>();

    private DungeonTrainWorldData(int trainY, boolean startsWithTrain, CarriageDims dims, long generationSeed, StartingDimension startingDimension) {
        this.trainY = trainY;
        this.startsWithTrain = startsWithTrain;
        this.dims = dims;
        this.generationSeed = generationSeed;
        this.startingDimension = startingDimension;
    }

    public static DungeonTrainWorldData get(ServerLevel overworld) {
        DungeonTrainWorldData data = overworld.getDataStorage().computeIfAbsent(FACTORY, NAME);
        // Fresh world (or legacy save whose NBT tag is missing → loaded as seed=0):
        // derive the per-world seed deterministically from the level seed so two
        // worlds created with the same seed bake identical band terrain/biomes.
        // Existing saves keep their persisted (nonzero) seed. Mark dirty so the
        // derived seed persists.
        if (data.generationSeed == 0L) {
            data.generationSeed = deriveGenerationSeed(overworld.getSeed());
            data.setDirty();
        }
        return data;
    }

    /**
     * Deterministic per-world generation seed: splitmix64 finalizer (same constants as
     * {@code MountainNoise.hash01}) over the level seed mixed with a mod-specific salt.
     * Pure function of the world seed — same seed, same band layout — while staying
     * decorrelated from vanilla's own seed-derived streams. Never returns {@code 0}
     * (the "unseeded" NBT sentinel).
     */
    private static long deriveGenerationSeed(long worldSeed) {
        long h = worldSeed ^ GENERATION_SEED_SALT;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return h != 0L ? h : GENERATION_SEED_SALT;
    }

    /** "DTrGenS1" — salt decorrelating the generation seed from the raw level seed. */
    private static final long GENERATION_SEED_SALT = 0x44547247656E5331L;

    /** Enqueue a chunk key for the deferred upside-down mirror drain (dedup; main-thread only). */
    public void enqueueMirrorChunk(long chunkKey) {
        pendingMirrorChunks.add(chunkKey);
    }

    /**
     * The live pending-mirror work set (chunk keys). The drain in {@code TrainTickEvents} reads it,
     * orders nearest-first by train position, applies under a per-tick budget, and removes the keys it
     * processes. Mutable + main-thread only; not persisted (the chunk attachment is the durable marker).
     */
    public java.util.Set<Long> pendingMirrorChunks() {
        return pendingMirrorChunks;
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
        DungeonTrainWorldData data = new DungeonTrainWorldData(y, s, d, seed, sd);
        // Optional per-world override; absent on legacy / un-overridden worlds → null → global default.
        if (tag.contains(TAG_PLAYER_MOB_SPAWN_OVERRIDE)) {
            data.playerMobSpawnOneInOverride = tag.getInt(TAG_PLAYER_MOB_SPAWN_OVERRIDE);
        }
        if (tag.contains(TAG_PLAYER_MOB_BEHIND_SPAWN_OVERRIDE)) {
            data.playerMobBehindSpawnPercentOverride = tag.getInt(TAG_PLAYER_MOB_BEHIND_SPAWN_OVERRIDE);
        }
        if (tag.contains(TAG_BREAK_BLOCKS_ON_CONTACT_OVERRIDE)) {
            data.breakBlocksOnContactOverride = tag.getBoolean(TAG_BREAK_BLOCKS_ON_CONTACT_OVERRIDE);
        }
        // Absent on legacy worlds → false → the join-info report fires once on the next join.
        if (tag.contains(TAG_JOIN_REPORT_POSTED)) {
            data.joinReportPosted = tag.getBoolean(TAG_JOIN_REPORT_POSTED);
        }
        return data;
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
        // Only persist the override when set, so "unset" stays distinguishable from "0 (disabled)".
        if (playerMobSpawnOneInOverride != null) {
            tag.putInt(TAG_PLAYER_MOB_SPAWN_OVERRIDE, playerMobSpawnOneInOverride);
        }
        if (playerMobBehindSpawnPercentOverride != null) {
            tag.putInt(TAG_PLAYER_MOB_BEHIND_SPAWN_OVERRIDE, playerMobBehindSpawnPercentOverride);
        }
        if (breakBlocksOnContactOverride != null) {
            tag.putBoolean(TAG_BREAK_BLOCKS_ON_CONTACT_OVERRIDE, breakBlocksOnContactOverride);
        }
        tag.putBoolean(TAG_JOIN_REPORT_POSTED, joinReportPosted);
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

    /** This world's PlayerMob 1-in-N override, or null when the world follows the global default. */
    public Integer getPlayerMobSpawnOneInOverride() {
        return playerMobSpawnOneInOverride;
    }

    /**
     * Effective 1-in-N PlayerMob spawn rate for this world: the per-world
     * override if one has been set in-game, otherwise the global default from
     * {@link DungeonTrainCommonConfig}. Read live by
     * {@link games.brennan.dungeontrain.train.PlayerMobGroupSpawner}.
     */
    public int getEffectivePlayerMobSpawnOneIn() {
        return playerMobSpawnOneInOverride != null
                ? playerMobSpawnOneInOverride
                : DungeonTrainCommonConfig.getDefaultPlayerMobSpawnOneIn();
    }

    /**
     * Set (non-null) or clear (null) this world's PlayerMob spawn-rate override.
     * A non-null value is clamped to the COMMON config's legal range.
     */
    public void setPlayerMobSpawnOneInOverride(Integer value) {
        Integer next = value == null ? null : Math.max(
                DungeonTrainCommonConfig.MIN_PLAYER_MOB_SPAWN_ONE_IN,
                Math.min(DungeonTrainCommonConfig.MAX_PLAYER_MOB_SPAWN_ONE_IN, value));
        if (Objects.equals(next, playerMobSpawnOneInOverride)) return;
        playerMobSpawnOneInOverride = next;
        setDirty();
    }

    /** This world's block-breaking override, or null when the world follows the global default. */
    public Boolean getBreakBlocksOnContactOverride() {
        return breakBlocksOnContactOverride;
    }

    /**
     * Whether a moving carriage breaks the world blocks it passes through in this world: the per-world
     * override if one has been set in-game, otherwise the global default from
     * {@link DungeonTrainCommonConfig}. Read live (once per sweep, never per cell) by
     * {@link games.brennan.dungeontrain.event.TrainTickEvents}.
     */
    public boolean getEffectiveBreakBlocksOnContact() {
        return breakBlocksOnContactOverride != null
                ? breakBlocksOnContactOverride
                : DungeonTrainCommonConfig.getDefaultBreakBlocksOnContact();
    }

    /** Set (non-null) or clear (null) this world's block-breaking override. */
    public void setBreakBlocksOnContactOverride(Boolean value) {
        if (Objects.equals(value, breakBlocksOnContactOverride)) return;
        breakBlocksOnContactOverride = value;
        setDirty();
    }

    /** This world's behind-spawn percent override, or null when the world follows the global default. */
    public Integer getPlayerMobBehindSpawnPercentOverride() {
        return playerMobBehindSpawnPercentOverride;
    }

    /**
     * Effective behind-spawn percent chance for this world: the per-world override if one has been
     * set in-game, otherwise the global default from {@link DungeonTrainCommonConfig}. Read live by
     * {@link games.brennan.dungeontrain.train.PlayerMobBehindSpawner}.
     */
    public int getEffectivePlayerMobBehindSpawnPercent() {
        return playerMobBehindSpawnPercentOverride != null
                ? playerMobBehindSpawnPercentOverride
                : DungeonTrainCommonConfig.getDefaultPlayerMobBehindSpawnPercent();
    }

    /**
     * Set (non-null) or clear (null) this world's behind-spawn percent override.
     * A non-null value is clamped to the COMMON config's legal percent range.
     */
    public void setPlayerMobBehindSpawnPercentOverride(Integer value) {
        Integer next = value == null ? null : Math.max(
                DungeonTrainCommonConfig.MIN_PLAYER_MOB_BEHIND_SPAWN_PERCENT,
                Math.min(DungeonTrainCommonConfig.MAX_PLAYER_MOB_BEHIND_SPAWN_PERCENT, value));
        if (Objects.equals(next, playerMobBehindSpawnPercentOverride)) return;
        playerMobBehindSpawnPercentOverride = next;
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
     * True once the per-world join-info report (Dungeon Train version + train regeneration data +
     * installed-mods list) has been appended to a player-join Discord message for this world.
     * One-shot per world so the data — which never changes for a world's lifetime — posts only once.
     */
    public boolean joinReportPosted() {
        return joinReportPosted;
    }

    /** Mark the per-world join-info report as posted so it never fires again for this world. */
    public void markJoinReportPosted() {
        if (joinReportPosted) return;
        joinReportPosted = true;
        setDirty();
    }

    private static int clampY(int y) {
        return Math.max(DungeonTrainConfig.MIN_TRAIN_Y, Math.min(DungeonTrainConfig.MAX_TRAIN_Y, y));
    }
}
