package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.builder.BuilderMirrorFlags;
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
    private static final String TAG_USED_CARRIAGE_IDS = "usedSharedCarriageIds";
    private static final String TAG_BUILDER_MODE = "builderMode";
    private static final String TAG_BUILDER_VARIANT = "builderVariant";
    private static final String TAG_BUILDER_STAGE = "builderStage";
    private static final String TAG_BUILDER_NAME = "builderName";
    private static final String TAG_BUILDER_MIRROR = "builderMirror";
    private static final String TAG_BUILDER_SUB_TYPE = "builderSubType";
    private static final String TAG_BUILDER_PART_KIND = "builderPartKind";
    private static final String TAG_BUILDER_TRACK_KIND = "builderTrackKind";
    private static final String TAG_BUILDER_CARRIAGES = "builderCarriages";
    private static final String TAG_BUILDER_STRUCTURE_MODE = "builderStructureMode";
    private static final String TAG_BUILDER_STRUCTURE_REFRESH = "builderStructureRefresh";
    private static final String TAG_BUILDER_RELAY_BUILDS = "builderRelayBuilds";
    private static final String TAG_DIFFICULTY_TRAVELLED_OFFSET = "difficultyTravelledOffset";
    private static final String TAG_CUSTOM_CONTENT_CHOICE = "customContentChoice";
    private static final String TAG_PORTAL_RATE_TUNED = "portalRateTuned";
    private static final String TAG_KEEP_INVENTORY_USED = "keepInventoryUsed";
    private static final String TAG_HELP_PANEL_DISMISSED = "editorHelpPanelDismissed";

    private int trainY;
    private boolean startsWithTrain;
    private CarriageDims dims;
    private long generationSeed;
    private StartingDimension startingDimension;
    /** Per-world override of the PlayerMob 1-in-N spawn rate; null = use the global COMMON default. */
    private Integer playerMobSpawnOneInOverride;
    /** Per-world override of the behind-the-player PlayerMob spawn percent chance; null = global COMMON default. */
    private Integer playerMobBehindSpawnPercentOverride;
    /**
     * Which Train Builder mode this world was created for, or null in any ordinary world.
     *
     * <p>The mode is picked on the title screen and exists nowhere else, so without persisting it
     * a reopened builder world has no way to know how much train it holds — which the client
     * needs to draw the build bounds.</p>
     */
    private String builderMode;
    /**
     * The registered carriage variant the builder world was <em>stamped from</em> — the source of
     * the blocks standing on the track. Recorded so a mode switch can re-stamp the same carriage
     * even if the registry order changes (a package reload reorders it).
     *
     * <p>Deliberately <b>not</b> the name the build will be saved as: see {@link #builderName}.
     * Conflating the two meant a named build resolved to no registered variant and saved over
     * whichever template happened to be first.</p>
     */
    private String builderVariant;
    /**
     * What the current build will be <em>saved as</em>. Empty or null means an unnamed draft — it
     * exists to build in, and nothing is written to disk until the builder names it.
     */
    private String builderName;
    /** Packed {@code BuilderMirrorFlags} for this build; 0 = no mirroring, which is the default. */
    private int builderMirror;
    /**
     * What kind of thing this build is — {@code whole_carriage}, {@code carriage_room} or
     * {@code parts}. Save needs it: a room and a part are captured from different regions and
     * written to different stores, so without it every Save could only ever write a carriage.
     */
    private String builderSubType;
    /** Which part kind, when {@link #builderSubType} is {@code parts}. */
    private String builderPartKind;
    /**
     * Which {@code TrackKind} this build is, when it is a track-side template rather than part of a
     * carriage — {@code tile}, {@code pillar_middle}, {@code tunnel_portal} and so on. Null or empty
     * for a carriage build.
     *
     * <p>The track modes have no {@link #builderSubType}, because the sub types name the parts of a
     * carriage and a rail is not one. This is the track equivalent, and it is what Save reads to
     * decide which of the eight directories the template belongs in — {@code default} exists in all
     * of them, so the name alone can't say.</p>
     */
    private String builderTrackKind;

    /**
     * How many carriages are actually parked on the track, or {@code -1} when nothing has recorded
     * it yet.
     *
     * <p>A recorded fact, not a re-derived decision, and that distinction is the point. The count
     * was briefly computed from {@link #builderMode} and {@link #builderSubType} at each of the six
     * places that needed it — but those two answer "what is this build for" and "what does it save
     * as", and neither is the same question as "how many carriages did we stamp". They diverge
     * exactly where the Open screen's carriage list does: browsing rooms from outside the train
     * opens a <em>carriage</em> template, which has to save as a whole carriage while standing
     * alone on the track.</p>
     *
     * <p>Written by every path that stamps or clears the train, so the build volumes, the dirty
     * check, the save cut, the cinematic framing and the spawn standoff all read the same number
     * the stamp used.</p>
     */
    private int builderCarriages = -1;
    /**
     * What the builder does with the structures around the build — {@code ghost}, {@code solid} or
     * {@code none}. Null/empty on a world saved before the control existed, which reads as the
     * default.
     *
     * <p>World state rather than a client preference, unlike the two-state ghost toggle it replaces:
     * {@code solid} is the difference between a wall existing and not existing, which everyone in
     * the world has to agree on. See {@code BuilderStructureMode}.</p>
     */
    private String builderStructureMode;
    /**
     * When the structures around the build re-read the template they are made of, or null when this
     * world has never been told. See {@code BuilderStructureRefresh}.
     *
     * <p>World state for the same reason the mode is: while the structures are solid, refreshing
     * them is the server rewriting blocks rather than a client redrawing a ghost.</p>
     */
    private String builderStructureRefresh;
    /**
     * The Stage the builder picked when starting this build, or null/empty when they didn't pick
     * one. Held until the build is saved, at which point the written template is linked to it —
     * without this the stage choice would only decide which blocks got copied and then evaporate.
     */
    private String builderStage;
    /** Per-world override of train-on-contact block breaking; null = use the global COMMON default. */
    private Boolean breakBlocksOnContactOverride;
    /** Per-world one-shot: true once the join-info report (DT version + train seed + mods) has been posted to Discord. */
    private boolean joinReportPosted;
    /**
     * Per-world admin difficulty travelled-offset (carriages) — the authoritative copy of
     * {@code DungeonTrainConfig.DIFFICULTY_TRAVELLED_OFFSET}, which is a GLOBAL server-config
     * value and would otherwise leak an offset set in one world into every other world.
     * Written through {@code DifficultyOffset.set}; mirrored back into the config on world load
     * by {@code DifficultyOffsetLifecycle}. 0 = fully automatic.
     */
    private int difficultyTravelledOffset;

    /**
     * This world's answer to the custom-Train-Editor-content prompt. Absent on every world saved
     * before the feature landed → {@link CustomContentChoice#UNSET} → the prompt fires on the next
     * join. Written through {@code EditorContentIntegrity.setWorldChoice}, which also mirrors it
     * into the static the Free Play gate reads.
     */
    private CustomContentChoice customContentChoice = CustomContentChoice.UNSET;

    /**
     * True once someone has retuned how often portals arrive in this world
     * ({@code /dungeontrain portal carriage …}).
     *
     * <p>A property of the <b>world</b>, not of whoever typed the command: the track everyone rides
     * was laid at a rate DT did not balance, so the whole world is Free Play. Read it through
     * {@code PortalTuningIntegrity} rather than here — that class caches it so the Free Play gate
     * doesn't touch SavedData on hot paths.</p>
     *
     * <p><b>One-way.</b> Setting the rate back changes nothing already generated, so there is no
     * un-tuning a world.</p>
     */
    private boolean portalRateTuned = false;

    /**
     * True once this world has been seen running with the vanilla {@code keepInventory} game rule
     * turned on.
     *
     * <p>A property of the <b>world</b> for the same reason {@link #portalRateTuned} is: the rule
     * applies to everyone on it, including players who never touched a setting. Read it through
     * {@code KeepInventoryIntegrity} rather than here — that class caches it so the Free Play gate
     * doesn't touch SavedData on hot paths.</p>
     *
     * <p><b>One-way.</b> Gear already carried through a death is in the save, so turning the rule
     * back off does not give the world its stats back.</p>
     */
    private boolean keepInventoryUsed = false;

    /**
     * Players who have closed the editor's world-space Welcome panel in this world, by UUID.
     *
     * <p>Per player rather than per world: the panel teaches the editor keybinds, and one builder
     * having read them says nothing about the next person to join. Per <em>world</em> rather than in
     * the client config because that is what the dismissal was asked to be — closing it in one world
     * leaves it up in the next.</p>
     *
     * <p>Absent from every world saved before the close button existed, which reads as "nobody has
     * dismissed it" — so the panel keeps showing in old worlds, which is the pre-existing behaviour.</p>
     */
    private final java.util.Set<java.util.UUID> helpPanelDismissed = new java.util.LinkedHashSet<>();

    /**
     * Transient scheduling set of chunk keys ({@link net.minecraft.world.level.ChunkPos#toLong}) whose
     * upside-down mirror is deferred and still pending. NOT serialized — the durable truth is the
     * {@code NEEDS_UPSIDE_DOWN_MIRROR} chunk attachment; this set is only a fast-path work list, rebuilt
     * from {@code ChunkEvent.Load} enqueues (+ the reconciling scan) after a reload. A {@link LinkedHashSet}
     * so it dedups (a chunk enqueued at gen, on reload, and by the scan is only processed once) while
     * keeping insertion order as a stable tiebreak. Main-thread only (Load + level tick both run there).
     */
    private final java.util.Set<Long> pendingMirrorChunks = new java.util.LinkedHashSet<>();

    /**
     * Transient READY subset of {@link #pendingMirrorChunks}: chunks whose full 3×3 neighbourhood is
     * loaded, so their deferred mirror can actually be applied this tick. The per-tick drain iterates
     * ONLY this set, so it never re-scans the un-appliable frontier ring (chunks whose outer neighbours
     * are beyond sim-distance) that otherwise pegs the pending backlog and wastes ~1–3 ms/tick. A chunk
     * is promoted here from a {@code ChunkEvent.Load} that completes its neighbourhood (see
     * {@code WorldUpsideDownEvents.promoteNeighbourhood}) or by the low-frequency reconcile in the drain;
     * it is demoted back if the final apply-time {@code neighboursFull} guard fails (a neighbour unloaded
     * since promotion). NOT serialized — derived from {@link #pendingMirrorChunks} + the durable marker.
     * {@link LinkedHashSet} for dedup + stable insertion-order tiebreak. Main-thread only.
     */
    private final java.util.Set<Long> readyMirrorChunks = new java.util.LinkedHashSet<>();

    /**
     * Shared carriages this world has already placed. Sent to the relay as the lease exclude-list so a
     * world doesn't meet the same community build twice — the point of the feature is breadth, and a
     * repeat reads as the generator running out of ideas. Serialized as a flat int array (the identity
     * is one number).
     */
    private final games.brennan.dungeontrain.train.UsedCarriageIds usedCarriageIds =
            new games.brennan.dungeontrain.train.UsedCarriageIds();

    /**
     * What a Train Builder world has uploaded to the relay — one record per saved template. Empty in
     * every ordinary world. Its credentials cannot be re-derived, which is why they are saved rather
     * than held in memory; see {@link games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds}.
     */
    private final games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds builderRelayBuilds =
            new games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds();

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

    /** Enqueue a chunk key for the deferred upside-down mirror drain (dedup; main-thread only). New keys
     *  start in WAITING (pending only) — they are promoted to READY once their neighbourhood is loaded. */
    public void enqueueMirrorChunk(long chunkKey) {
        pendingMirrorChunks.add(chunkKey);
    }

    /**
     * The live pending-mirror work set (chunk keys) — the full backlog (WAITING ∪ READY), mirroring the
     * durable marker. Drives the {@code [ud-drain] backlog=} count and the reconcile scan. Mutable +
     * main-thread only; not persisted (the chunk attachment is the durable marker).
     */
    public java.util.Set<Long> pendingMirrorChunks() {
        return pendingMirrorChunks;
    }

    /**
     * The READY subset the drain iterates (chunks whose 3×3 neighbourhood is loaded). Mutable +
     * main-thread only. See {@link #readyMirrorChunks} field doc.
     */
    public java.util.Set<Long> readyMirrorChunks() {
        return readyMirrorChunks;
    }

    /** Mark a pending chunk READY (its neighbourhood is now loaded). Idempotent; no-op if not pending. */
    public void promoteMirrorChunk(long chunkKey) {
        if (pendingMirrorChunks.contains(chunkKey)) {
            readyMirrorChunks.add(chunkKey);
        }
    }

    /** Return a pending chunk to WAITING (a neighbour unloaded before it could apply). Keeps it pending. */
    public void demoteMirrorChunk(long chunkKey) {
        readyMirrorChunks.remove(chunkKey);
    }

    /** Remove a chunk from the mirror work list entirely (applied, unloaded, or marker already cleared). */
    public void removeMirrorChunk(long chunkKey) {
        pendingMirrorChunks.remove(chunkKey);
        readyMirrorChunks.remove(chunkKey);
    }

    static DungeonTrainWorldData createDefault() {
        return new DungeonTrainWorldData(
                DungeonTrainConfig.getTrainY(),
                true,
                CarriageDims.DEFAULT,
                0L,
                StartingDimension.OVERWORLD
        );
    }

    static DungeonTrainWorldData load(CompoundTag tag) {
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
        // Absent on worlds saved before the offset became per-world → 0 → fully automatic,
        // which is exactly the reset those worlds should get.
        if (tag.contains(TAG_DIFFICULTY_TRAVELLED_OFFSET)) {
            data.difficultyTravelledOffset = clampDifficultyOffset(tag.getInt(TAG_DIFFICULTY_TRAVELLED_OFFSET));
        }
        // Absent on legacy worlds → UNSET → the custom-content prompt fires on the next join.
        if (tag.contains(TAG_CUSTOM_CONTENT_CHOICE)) {
            data.customContentChoice = CustomContentChoice.fromNbt(tag.getString(TAG_CUSTOM_CONTENT_CHOICE));
        }
        // Absent on every world saved before the rate was settable → false, which is correct: those
        // worlds ran at the rate DT balanced.
        data.portalRateTuned = tag.getBoolean(TAG_PORTAL_RATE_TUNED);
        // Absent on every world saved before this was tracked → false. Those worlds latch on the
        // next tick anyway if the rule is still on, so nothing is missed.
        data.keepInventoryUsed = tag.getBoolean(TAG_KEEP_INVENTORY_USED);
        // getIntArray returns an empty array for an absent key, so worlds saved before shared carriages
        // simply start having placed nothing.
        data.usedCarriageIds.loadFrom(tag.getIntArray(TAG_USED_CARRIAGE_IDS));
        // Absent in every world that has never uploaded a build, which is every non-builder world.
        data.builderRelayBuilds.loadFrom(
                tag.getList(TAG_BUILDER_RELAY_BUILDS, net.minecraft.nbt.Tag.TAG_COMPOUND));
        // Absent in every non-builder world (and in builder worlds saved before the stamp ran).
        if (tag.contains(TAG_BUILDER_MODE)) {
            data.builderMode = tag.getString(TAG_BUILDER_MODE);
        }
        if (tag.contains(TAG_BUILDER_VARIANT)) {
            data.builderVariant = tag.getString(TAG_BUILDER_VARIANT);
        }
        if (tag.contains(TAG_BUILDER_STAGE)) {
            data.builderStage = tag.getString(TAG_BUILDER_STAGE);
        }
        if (tag.contains(TAG_BUILDER_NAME)) {
            data.builderName = tag.getString(TAG_BUILDER_NAME);
        }
        // getInt returns 0 for an absent key, which is exactly "no mirroring".
        data.builderMirror = tag.getInt(TAG_BUILDER_MIRROR);
        if (tag.contains(TAG_BUILDER_SUB_TYPE)) {
            data.builderSubType = tag.getString(TAG_BUILDER_SUB_TYPE);
        }
        if (tag.contains(TAG_BUILDER_TRACK_KIND)) {
            data.builderTrackKind = tag.getString(TAG_BUILDER_TRACK_KIND);
        }
        if (tag.contains(TAG_BUILDER_PART_KIND)) {
            data.builderPartKind = tag.getString(TAG_BUILDER_PART_KIND);
        }
        // Absent in every builder world saved before the count was recorded. Left at -1 rather than
        // read as 0, so callers can tell "no train" from "nobody wrote it down" and fall back to
        // the mode's own count — which is what those worlds were in fact stamped with.
        if (tag.contains(TAG_BUILDER_CARRIAGES)) {
            data.builderCarriages = tag.getInt(TAG_BUILDER_CARRIAGES);
        }
        // Absent in every builder world saved before the structure control existed. Left null, which
        // BuilderStructureMode.orDefault reads as ghosts — those worlds must not come up with
        // scenery stood inside a build somebody left half-finished.
        if (tag.contains(TAG_BUILDER_STRUCTURE_MODE)) {
            data.builderStructureMode = tag.getString(TAG_BUILDER_STRUCTURE_MODE);
        }
        // Absent in every world saved before the refresh control existed. Left null, which
        // BuilderStructureRefresh.orDefault reads as instant — the room copies were live before
        // there was a control, and loading an old world must not quietly stop them being.
        if (tag.contains(TAG_BUILDER_STRUCTURE_REFRESH)) {
            data.builderStructureRefresh = tag.getString(TAG_BUILDER_STRUCTURE_REFRESH);
        }
        // Absent until somebody closes the editor Welcome panel, which is most worlds. Unparseable
        // entries are skipped rather than failing the whole load — a malformed uuid only costs that
        // one player their dismissal.
        if (tag.contains(TAG_HELP_PANEL_DISMISSED)) {
            net.minecraft.nbt.ListTag dismissed =
                    tag.getList(TAG_HELP_PANEL_DISMISSED, net.minecraft.nbt.Tag.TAG_STRING);
            for (int i = 0; i < dismissed.size(); i++) {
                try {
                    data.helpPanelDismissed.add(java.util.UUID.fromString(dismissed.getString(i)));
                } catch (IllegalArgumentException ignored) {
                    // not a uuid — drop it
                }
            }
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
        tag.putInt(TAG_DIFFICULTY_TRAVELLED_OFFSET, difficultyTravelledOffset);
        tag.putString(TAG_CUSTOM_CONTENT_CHOICE, customContentChoice.nbtId());
        tag.putBoolean(TAG_PORTAL_RATE_TUNED, portalRateTuned);
        tag.putBoolean(TAG_KEEP_INVENTORY_USED, keepInventoryUsed);
        tag.putIntArray(TAG_USED_CARRIAGE_IDS, usedCarriageIds.toIntArray());
        if (!builderRelayBuilds.isEmpty()) {
            tag.put(TAG_BUILDER_RELAY_BUILDS, builderRelayBuilds.toTag());
        }
        if (builderMode != null) {
            tag.putString(TAG_BUILDER_MODE, builderMode);
        }
        if (builderVariant != null) {
            tag.putString(TAG_BUILDER_VARIANT, builderVariant);
        }
        if (builderStage != null) {
            tag.putString(TAG_BUILDER_STAGE, builderStage);
        }
        if (builderName != null) {
            tag.putString(TAG_BUILDER_NAME, builderName);
        }
        if (builderMirror != 0) {
            tag.putInt(TAG_BUILDER_MIRROR, builderMirror);
        }
        if (builderSubType != null) {
            tag.putString(TAG_BUILDER_SUB_TYPE, builderSubType);
        }
        if (builderTrackKind != null) {
            tag.putString(TAG_BUILDER_TRACK_KIND, builderTrackKind);
        }
        if (builderPartKind != null) {
            tag.putString(TAG_BUILDER_PART_KIND, builderPartKind);
        }
        if (builderCarriages >= 0) {
            tag.putInt(TAG_BUILDER_CARRIAGES, builderCarriages);
        }
        if (builderStructureMode != null) {
            tag.putString(TAG_BUILDER_STRUCTURE_MODE, builderStructureMode);
        }
        if (builderStructureRefresh != null) {
            tag.putString(TAG_BUILDER_STRUCTURE_REFRESH, builderStructureRefresh);
        }
        if (!helpPanelDismissed.isEmpty()) {
            net.minecraft.nbt.ListTag dismissed = new net.minecraft.nbt.ListTag();
            for (java.util.UUID id : helpPanelDismissed) {
                dismissed.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
            }
            tag.put(TAG_HELP_PANEL_DISMISSED, dismissed);
        }
        return tag;
    }

    /** True when {@code playerId} has closed the editor's world-space Welcome panel in this world. */
    public boolean isHelpPanelDismissed(java.util.UUID playerId) {
        return helpPanelDismissed.contains(playerId);
    }

    /** Record (or clear) {@code playerId}'s dismissal of the editor Welcome panel. */
    public void setHelpPanelDismissed(java.util.UUID playerId, boolean dismissed) {
        boolean changed = dismissed
                ? helpPanelDismissed.add(playerId)
                : helpPanelDismissed.remove(playerId);
        if (changed) setDirty();
    }

    /** Train Builder mode id, or null in an ordinary world. See {@code BuilderMode#fromId}. */
    public String builderMode() {
        return builderMode;
    }

    public void setBuilderMode(String modeId) {
        this.builderMode = modeId;
        setDirty();
    }

    /** Registered variant id the builder world was stamped from, or null outside a builder world. */
    public String builderVariant() {
        return builderVariant;
    }

    public void setBuilderVariant(String variantId) {
        this.builderVariant = variantId;
        setDirty();
    }

    /** What the current build saves as; null/empty for an unnamed draft. */
    public String builderName() {
        return builderName;
    }

    public void setBuilderName(String name) {
        this.builderName = name;
        setDirty();
    }

    /** Mirror setting for the current build. See {@code BuilderMirrorFlags} for why it lives here. */
    public BuilderMirrorFlags builderMirror() {
        return BuilderMirrorFlags.unpack(builderMirror);
    }

    public void setBuilderMirror(BuilderMirrorFlags flags) {
        this.builderMirror = flags == null ? 0 : flags.pack();
        setDirty();
    }

    /** What kind of template this build becomes on Save; null outside a builder world. */
    public String builderSubType() {
        return builderSubType;
    }

    /** Part kind for a parts build, or null/empty when this build isn't a part. */
    public String builderPartKind() {
        return builderPartKind;
    }

    /** Record what kind of carriage template this build is. Clears any track kind — see the twin below. */
    public void setBuilderSubType(String subTypeId, String partKindId) {
        this.builderSubType = subTypeId;
        this.builderPartKind = partKindId;
        if (subTypeId != null && !subTypeId.isEmpty()) {
            this.builderTrackKind = "";
        }
        setDirty();
    }

    /** Track kind for a track build, or null/empty when this build is part of a carriage. */
    public String builderTrackKind() {
        return builderTrackKind;
    }

    /**
     * Record which track kind is on the plot.
     *
     * <p>Clears the carriage sub type at the same time, because the two are alternatives and a build
     * that is both would be read as whichever the caller asked about first — the exact ambiguity
     * that would have Save write a tunnel into the carriage store.</p>
     */
    public void setBuilderTrackKind(String trackKindId) {
        this.builderTrackKind = trackKindId;
        if (trackKindId != null && !trackKindId.isEmpty()) {
            this.builderSubType = "";
            this.builderPartKind = "";
        }
        setDirty();
    }

    /** Carriages parked on the track, or {@code -1} when no stamp has recorded a count yet. */
    public int builderCarriages() {
        return builderCarriages;
    }

    /** Record what was just stamped. Callers pass the count they actually laid down, including 0. */
    public void setBuilderCarriages(int carriages) {
        this.builderCarriages = Math.max(0, carriages);
        setDirty();
    }

    /**
     * What this world does with the structures around the build, or null/empty when it has never
     * been told. See {@code BuilderStructureMode#orDefault}, which is what every reader goes through.
     */
    public String builderStructureMode() {
        return builderStructureMode;
    }

    public void setBuilderStructureMode(String modeId) {
        this.builderStructureMode = modeId;
        setDirty();
    }

    /**
     * When the structures re-read their template, or null/empty when never told. See
     * {@code BuilderStructureRefresh#orDefault}, which is what every reader goes through.
     */
    public String builderStructureRefresh() {
        return builderStructureRefresh;
    }

    public void setBuilderStructureRefresh(String refreshId) {
        this.builderStructureRefresh = refreshId;
        setDirty();
    }

    /** Stage the current build was started for, or null/empty when none was picked. */
    public String builderStage() {
        return builderStage;
    }

    public void setBuilderStage(String stageId) {
        this.builderStage = stageId;
        setDirty();
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

    /**
     * This world's answer to the custom-content prompt. Read it through
     * {@code EditorContentIntegrity} rather than here — that class caches it so the Free Play
     * gate doesn't touch SavedData on hot paths.
     */
    public CustomContentChoice customContentChoice() {
        return customContentChoice;
    }

    /** True once this world's portal rate has been retuned — see {@link #portalRateTuned}. */
    public boolean isPortalRateTuned() {
        return portalRateTuned;
    }

    /** Record the retuning. One-way: there is no path back to false. */
    public void markPortalRateTuned() {
        if (portalRateTuned) return;
        portalRateTuned = true;
        setDirty();
    }

    /** True once this world has run with {@code keepInventory} on — see {@link #keepInventoryUsed}. */
    public boolean isKeepInventoryUsed() {
        return keepInventoryUsed;
    }

    /** Record that the rule was seen on. One-way: there is no path back to false. */
    public void markKeepInventoryUsed() {
        if (keepInventoryUsed) return;
        keepInventoryUsed = true;
        setDirty();
    }

    /** Record the player's answer. Called from {@code EditorContentIntegrity.setWorldChoice}. */
    public void setCustomContentChoice(CustomContentChoice choice) {
        if (choice == null || this.customContentChoice == choice) return;
        this.customContentChoice = choice;
        setDirty();
    }

    public void setStartingDimension(StartingDimension d) {
        if (d == null || this.startingDimension == d) return;
        this.startingDimension = d;
        setDirty();
    }

    /**
     * This world's admin difficulty travelled-offset in carriages; 0 = fully automatic.
     * Mirrored into the global server config on world load — read it through
     * {@code DungeonTrainConfig.getDifficultyTravelledOffset()} everywhere else.
     */
    public int getDifficultyTravelledOffset() {
        return difficultyTravelledOffset;
    }

    /** Store the world's difficulty travelled-offset (clamped to the config's range). */
    public void setDifficultyTravelledOffset(int value) {
        int clamped = clampDifficultyOffset(value);
        if (this.difficultyTravelledOffset == clamped) return;
        this.difficultyTravelledOffset = clamped;
        setDirty();
    }

    private static int clampDifficultyOffset(int value) {
        return Math.max(DungeonTrainConfig.MIN_DIFFICULTY_TRAVELLED_OFFSET,
                Math.min(DungeonTrainConfig.MAX_DIFFICULTY_TRAVELLED_OFFSET, value));
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

    /**
     * Record that this world has placed the shared carriage with relay id {@code id}, so it is never
     * placed here again.
     *
     * @return true if this was a new id (a repeat is a no-op and leaves the save clean)
     */
    public boolean markCarriageUsed(int id) {
        if (!usedCarriageIds.add(id)) return false;
        setDirty();
        return true;
    }

    /** Relay ids this world has already placed, newest first and capped at {@code limit}. */
    public java.util.List<Integer> recentUsedCarriageIds(int limit) {
        return usedCarriageIds.recent(limit);
    }

    /**
     * This builder world's relay uploads. Handed out live rather than copied, because every caller
     * that reads it is about to change it — callers must {@link #markBuilderRelayBuildsDirty()} after
     * a write, which is the same contract {@code readyMirrorChunks} has.
     */
    public games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds builderRelayBuilds() {
        return builderRelayBuilds;
    }

    /** Persist a change made through {@link #builderRelayBuilds()}. */
    public void markBuilderRelayBuildsDirty() {
        setDirty();
    }

    /**
     * Forget every already-placed shared carriage, so the whole relay pool is eligible again. Called
     * when an operator resets the train with {@code /dt spawn}.
     *
     * @return how many ids were forgotten (a no-op on an empty list leaves the save clean)
     */
    public int clearUsedCarriageIds() {
        int cleared = usedCarriageIds.clear();
        if (cleared > 0) setDirty();
        return cleared;
    }

    private static int clampY(int y) {
        return Math.max(DungeonTrainConfig.MIN_TRAIN_Y, Math.min(DungeonTrainConfig.MAX_TRAIN_Y, y));
    }
}
