package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Assembles a train as N independent Sable sub-levels — each holding a
 * {@code groupSize}-carriage <i>group</i> — all sharing a
 * {@link TrainTransformProvider#getTrainId() trainId}.
 *
 * <p>Each group is its own kinematic body. Because each sub-level's blocks
 * are placed once at assembly and never modified after, the per-sub-level
 * MassTracker / rotationPoint stays constant — eliminating the COM-drift
 * jitter we hit with the previous "one big sub-level for the whole train"
 * model. Trains are reassembled into logical groups via {@link Trains}.</p>
 *
 * <p>{@code groupSize} comes from the per-world
 * {@link CarriageGenerationConfig#groupSize() generation config} and is
 * captured per-train at spawn time. Runtime config changes only affect
 * future {@code spawnTrain} calls — existing groups keep their groupSize.</p>
 *
 * <p>All ship-physics interaction goes through the {@link Shipyard} port,
 * so a future swap to a different physics mod only changes the
 * {@code ship.sable.*} adapter package.</p>
 */
public final class TrainAssembler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A relay build chosen for a shared slot, plus whether it was authored by someone in this world
     * (which the on-enter chat line reads). Null in the caller means "place a fresh template".
     */
    private record SharedPick(SharedCarriageClient.PoolLease lease, boolean authoredHere) {}

    /**
     * Decide what fills a shared-carriage slot: a relay build by anyone, a relay build by a player in
     * this world, or nothing (→ fresh local template). The bucket roll is deterministic per carriage
     * index (so a walk-back re-decides identically) — see {@link SharedCarriageRolls} — but whether a
     * bucket can actually be served depends on the async prefetch buffers.
     *
     * <p>A bucket that comes up empty falls through to the other lease bucket before giving up on a
     * fresh template: the alternative is leaving a community build sitting locked in a buffer while the
     * slot places an empty room. Only {@link SharedCarriageRolls.Bucket#FRESH} never falls through —
     * that share is what keeps blank canvases entering the pool at all.</p>
     */
    private static SharedPick tryLeaseShared(ServerLevel level, CarriageVariant variant, int carriagePIdx,
                                             CarriageDims dims, CarriageGenerationConfig genCfg,
                                             String stageId, List<String> onlineUuids) {
        if (!games.brennan.dungeontrain.event.SharedCarriageGate.canDiscover()) return null;
        if (!SharedCarriageFlags.isSharedVariant(variant.id())) return null;
        // A slot no stage covers can't be matched against the pool at all — the relay only serves
        // carriages pooled under a stage, so this is a template slot by definition.
        if (stageId == null || stageId.isEmpty()) {
            logLeaseOutcome(carriagePIdx, "NO_STAGE", null);
            return null;
        }
        SharedCarriagePool.noteStageDemand(stageId); // steer the prefetch tick at the stage we're spawning into
        SharedCarriageRolls.Bucket bucket = SharedCarriageRolls.bucket(
                genCfg.seed(), carriagePIdx,
                games.brennan.dungeontrain.config.DungeonTrainConfig.getSharedCarriagePoolChance(),
                games.brennan.dungeontrain.config.DungeonTrainConfig.getSharedCarriageOwnChance());
        if (bucket == SharedCarriageRolls.Bucket.FRESH) {
            logLeaseOutcome(carriagePIdx, "ROLLED_FRESH", null);
            return null;
        }
        boolean ownFirst = bucket == SharedCarriageRolls.Bucket.OWN;
        SharedPick pick = ownFirst
                ? firstNonNull(() -> pollOwn(level, dims, stageId, onlineUuids, carriagePIdx),
                               () -> pollAny(level, dims, stageId, onlineUuids, carriagePIdx))
                : firstNonNull(() -> pollAny(level, dims, stageId, onlineUuids, carriagePIdx),
                               () -> pollOwn(level, dims, stageId, onlineUuids, carriagePIdx));
        if (pick != null) return pick;
        // Nothing buffered either way → fresh template. The spawn path never blocks on HTTP: a lease
        // that isn't already buffered isn't worth hitching the server thread for.
        logLeaseOutcome(carriagePIdx, SharedCarriagePool.isBackedOff() ? "BACKOFF_SUPPRESSED" : "BUFFER_EMPTY", null);
        return null;
    }

    /** Take a buffered build by anyone; flagged as the player's own if the relay says they authored it. */
    private static SharedPick pollAny(ServerLevel level, CarriageDims dims, String stageId,
                                      List<String> onlineUuids, int carriagePIdx) {
        SharedCarriageClient.PoolLease l = SharedCarriagePool.poll(dims, stageId);
        if (l == null) return null;
        boolean own = l.owner() != null && !l.owner().isEmpty() && onlineUuids.contains(l.owner());
        markUsed(level, l);
        logLeaseOutcome(carriagePIdx, own ? "POOL_HIT_OWN" : "POOL_HIT", l);
        return new SharedPick(l, own);
    }

    /** Take a buffered build authored by one of the players currently in this world. */
    private static SharedPick pollOwn(ServerLevel level, CarriageDims dims, String stageId,
                                      List<String> onlineUuids, int carriagePIdx) {
        SharedCarriageClient.PoolLease l = SharedCarriagePool.pollOwn(dims, stageId, onlineUuids);
        if (l == null) return null;
        markUsed(level, l);
        logLeaseOutcome(carriagePIdx, "OWN_HIT", l);
        return new SharedPick(l, true);
    }

    /**
     * Remember that this world has placed this build, so the relay is told not to offer it again. Done
     * at the poll, not at placement: a lease we fail to stamp is handed back to the pool for someone
     * else, but showing it here twice would still read as a repeat.
     */
    private static void markUsed(ServerLevel level, SharedCarriageClient.PoolLease lease) {
        try {
            DungeonTrainWorldData.get(level).markCarriageUsed(lease.id());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] could not record used carriage id={}: {}", lease.id(), t.toString());
        }
    }

    private static SharedPick firstNonNull(java.util.function.Supplier<SharedPick> a,
                                           java.util.function.Supplier<SharedPick> b) {
        SharedPick first = a.get();
        return first != null ? first : b.get();
    }

    /** Uuids (dashless, as the relay stores them) of the players currently in this level. */
    private static List<String> onlinePlayerUuids(ServerLevel level) {
        List<String> out = new java.util.ArrayList<>();
        for (net.minecraft.server.level.ServerPlayer p : level.players()) {
            out.add(p.getUUID().toString().replace("-", ""));
        }
        return out;
    }

    /**
     * Record why a shared slot did or didn't get a relay carriage. Without this the distinct reasons
     * {@link #tryLeaseShared} returns null are indistinguishable in a log, and "the train has no
     * community carriages" can't be told apart from "the relay was never asked".
     */
    private static void logLeaseOutcome(int carriagePIdx, String outcome, SharedCarriageClient.PoolLease lease) {
        LOGGER.debug("[DungeonTrain] shared slot pIdx={} → {}{} (buffered={})",
                carriagePIdx, outcome, lease == null ? "" : " id=" + lease.id(), SharedCarriagePool.buffered());
    }

    /**
     * Stamp a leased carriage's relay snapshot at {@code carriageOrigin} (world coords, pre-assembly).
     * The relay hands back a base blob PLUS an opaque delta log; we fold the deltas (those with
     * {@code seq > baseSeq}, in seq order) onto the base before placing. Returns the placed block
     * positions, or null on decode/dims/place failure (caller falls back to NEW).
     */
    private static Set<BlockPos> placeRelayLease(ServerLevel level, BlockPos carriageOrigin,
                                                 SharedCarriageClient.PoolLease lease, CarriageDims dims) {
        try {
            net.minecraft.nbt.CompoundTag snap = foldLeaseDeltas(CarriageBlockSnapshot.decode(lease.blocks()), lease);
            if (snap.getInt("l") != dims.length() || snap.getInt("h") != dims.height() || snap.getInt("w") != dims.width()) {
                LOGGER.warn("[DungeonTrain] leased carriage id={} dims mismatch — falling back to fresh.", lease.id());
                return null;
            }
            int cellCount = snap.getList("cells", net.minecraft.nbt.Tag.TAG_COMPOUND).size();
            Set<BlockPos> placed = CarriageBlockSnapshot.place(level, carriageOrigin, snap);
            LOGGER.debug("[DungeonTrain] relay carriage id={} at {}: {} cells → {} blocks in world (baseSeq={} deltas={})",
                    lease.id(), carriageOrigin, cellCount, placed == null ? "FAILED" : placed.size(),
                    lease.baseSeq(), lease.deltas() == null ? 0 : lease.deltas().size());
            return placed;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Failed to place leased carriage id={}: {}", lease.id(), e.toString());
            return null;
        }
    }

    /** Fold a lease's opaque delta log (seq &gt; baseSeq, ascending seq) onto its decoded base snapshot. */
    private static net.minecraft.nbt.CompoundTag foldLeaseDeltas(net.minecraft.nbt.CompoundTag base,
                                                                 SharedCarriageClient.PoolLease lease) {
        List<SharedCarriageClient.DeltaRec> deltas = lease.deltas();
        if (deltas == null || deltas.isEmpty()) return base;
        List<SharedCarriageClient.DeltaRec> sorted = new java.util.ArrayList<>(deltas);
        sorted.sort(java.util.Comparator.comparingInt(SharedCarriageClient.DeltaRec::seq));
        net.minecraft.nbt.CompoundTag folded = base;
        for (SharedCarriageClient.DeltaRec d : sorted) {
            if (d.seq() <= lease.baseSeq()) continue; // already folded into the base blob
            try {
                folded = CarriageBlockSnapshot.applyDeltaCells(folded, CarriageBlockSnapshot.decode(d.cells()));
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] leased carriage id={} delta seq={} decode failed: {}", lease.id(), d.seq(), e.toString());
            }
        }
        return folded;
    }

    /** The delta-sequence floor to seed a leased carriage's Instance with (max of baseSeq + any delta seq). */
    private static int leaseSeqSeed(SharedCarriageClient.PoolLease lease) {
        int seed = lease.baseSeq();
        if (lease.deltas() != null) {
            for (SharedCarriageClient.DeltaRec d : lease.deltas()) if (d.seq() > seed) seed = d.seq();
        }
        return seed;
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private TrainAssembler() {}

    /**
     * Spawn a fresh train consisting of enough groups to cover the
     * requested {@code count} carriages, snapped outward to group
     * boundaries. Deletes any previously-loaded Dungeon Train sub-levels
     * first so the world starts with exactly this one new train.
     *
     * @return the lead group's {@link ManagedShip} — useful for callers
     *     that want a representative handle (the bootstrap log,
     *     {@code /dt spawn} output, etc.). The full train is reachable
     *     via {@link Trains#findById(ServerLevel, UUID)}.
     */
    public static ManagedShip spawnTrain(ServerLevel level, BlockPos origin, Vector3dc velocity, int count, Vector3dc spawnerWorldPos, CarriageDims dims) {
        int deleted = deleteExistingTrains(level);
        if (deleted > 0) {
            LOGGER.info("[DungeonTrain] Deleted {} existing carriage(s) before spawn", deleted);
        }
        return spawnCore(level, origin, velocity, count, spawnerWorldPos, dims);
    }

    /**
     * Spawn an additional train without deleting any existing trains in
     * {@code level}. Each invocation creates a new {@link UUID} trainId;
     * carriages from this call do NOT join any pre-existing train.
     *
     * <p>Used by {@code /dt debug pair} to place two independent trains
     * side by side. The legacy {@code globalPIdxBase} parameter is
     * preserved for ABI compatibility but ignored.</p>
     */
    @SuppressWarnings("unused")
    public static ManagedShip spawnSuccessor(ServerLevel level, BlockPos origin, Vector3dc velocity, int count, Vector3dc spawnerWorldPos, CarriageDims dims, int legacyGlobalPIdxBase) {
        return spawnCore(level, origin, velocity, count, spawnerWorldPos, dims);
    }

    private static ManagedShip spawnCore(ServerLevel level, BlockPos origin, Vector3dc velocity, int count, Vector3dc spawnerWorldPos, CarriageDims dims) {
        if (count <= 0) {
            throw new IllegalArgumentException("Cannot spawn a train with count <= 0 (got " + count + ")");
        }

        // At spawn time, worldToShip is undefined for sub-levels that don't
        // exist yet, so initialPIdx is computed in plain world space. The
        // pIdx-to-world convention: origin.x is the world X of pIdx 0's
        // ENCLOSED CARRIAGE start (NOT the sub-level's back pad — the
        // back pad sits at origin.x − halfPadLen).
        int initialPIdx = (int) Math.floor((spawnerWorldPos.x() - origin.getX()) / (double) dims.length());

        // Each Sable sub-level packs `groupSize` enclosed carriages PLUS
        // a half-flatbed pad on each side, sitting OUTSIDE the integer
        // carriage-slot grid:
        //
        //   [BACK pad | groupSize × enclosed | FRONT pad]
        //   ←halfPadLen→ ← groupSize × length → ←halfPadLen→
        //
        // Both pads use a SINGLE half-sized NBT derived once from
        // FLATBED — BACK stamps it as-is, FRONT stamps it mirrored
        // (Mirror.FRONT_BACK). Adjacent groups' BACK + FRONT pads at
        // every seam combine to 2×halfPadLen blocks of contiguous floor.
        CarriageGenerationConfig genCfg = DungeonTrainWorldData.get(level).getGenerationConfig();
        int groupSize = genCfg.groupSize();
        int length = dims.length();
        int halfPadLen = CarriagePlacer.halfPadLen(dims);
        int subLevelStride = groupSize * length + 2 * halfPadLen;

        // Bootstrap spawns ONLY the seed group — the one containing the
        // player's initial pIdx. {@link TrainCarriageAppender} extends
        // the train backward and forward over the next several ticks
        // (capped at one new group per tick) until it covers the
        // player's window of `count` carriages.
        //
        // Why not bulk-spawn all `count` carriages here:
        // {@code Shipyards.assemble} returns a {@link ManagedShip} whose
        // backing Sable {@code ServerSubLevel} is queued for asynchronous
        // plot-loading. A burst spawn of N sub-levels in one tick races
        // against Sable's lazy load: the appender on subsequent ticks
        // sees only a subset of just-created sub-levels in
        // {@code SubLevelContainer.getAllSubLevels()} and was previously
        // tricked into stacking duplicates at the same anchor. Spawning
        // one per tick gives Sable's plot-loader a full tick to register
        // each new sub-level before the next is requested.
        int seedAnchor = Math.floorDiv(initialPIdx, groupSize) * groupSize;
        int seedGroupIdx = Math.floorDiv(seedAnchor, groupSize);
        int seedGroupOriginX = origin.getX() + seedGroupIdx * subLevelStride - halfPadLen;
        BlockPos seedGroupOrigin = new BlockPos(seedGroupOriginX, origin.getY(), origin.getZ());

        UUID trainId = UUID.randomUUID();

        TrackGeometry geometry = new TrackGeometry(
            origin.getY() - 2,
            origin.getY() - 1,
            origin.getZ(),
            origin.getZ() + dims.width() - 1);

        LOGGER.info("[DungeonTrain] Spawning train trainId={} seedAnchor={} (initialPIdx={}, targetCount={}) groupSize={} halfPadLen={} subLevelStride={} dims={}x{}x{} velocity={} origin={} — appender will extend",
            trainId, seedAnchor, initialPIdx, count, groupSize, halfPadLen, subLevelStride,
            length, dims.height(), dims.width(),
            velocity, origin);

        ManagedShip seedShip = spawnGroup(level, seedGroupOrigin, velocity, seedAnchor, groupSize, dims, trainId);
        if (seedShip.getKinematicDriver() instanceof TrainTransformProvider seedProvider) {
            seedProvider.setTrackGeometry(geometry);
            TrackGenerator.bootstrapPendingChunks(level, seedShip, seedProvider);
        }

        return seedShip;
    }

    /**
     * Assemble a single GROUP of {@code groupSize} enclosed carriages,
     * wrapped on each end by a half-flatbed pad (when groupSize > 1),
     * into one Sable sub-level. Used both by {@link #spawnTrain}'s
     * initial loop and by {@link TrainCarriageAppender} when a player
     * advances past the train's current pIdx range.
     *
     * <p>Sub-level world layout (groupSize > 1):
     * <pre>
     *   [BACK pad | enclosed₀ | enclosed₁ | … | enclosed_{n-1} | FRONT pad]
     *   ←halfPadLen→ ← groupSize × dims.length() blocks → ←halfPadLen→
     * </pre>
     * Sub-level total span: {@code groupSize × length + 2 × halfPadLen}
     * blocks (37 for length=9, groupSize=3). Both pads use the same
     * half-sized NBT extracted from FLATBED — BACK stamps as-is, FRONT
     * stamps with {@link Mirror#FRONT_BACK}.</p>
     *
     * <p>Variant selection for enclosed slots is per-carriage via
     * {@link CarriagePlacer#enclosedVariantForIndex(int, CarriageGenerationConfig)},
     * deterministic on absolute pIdx — never produces a flatbed-like
     * variant (the pads already provide bed continuity at every seam).</p>
     *
     * <p>Edge case: when {@code groupSize == 1} (e.g. {@code /dt debug pair}
     * probe), the sub-level holds a single enclosed carriage at
     * {@code origin} with NO pads — pads only make sense when there
     * are enclosed carriages to flank.</p>
     *
     * @param origin       sub-level's world-space minimum corner. For
     *                     groupSize > 1: the BACK pad's lowest-X corner
     *                     (= anchor enclosed carriage's lowest-X minus
     *                     halfPadLen). For groupSize == 1: the lone
     *                     enclosed carriage's lowest-X corner.
     * @param anchorPIdx   absolute pIdx of the LOWEST enclosed carriage
     *                     in this group
     * @param groupSize    number of enclosed carriages (≥ 1)
     * @param trainId      UUID shared by every group in the same train
     */
    public static ManagedShip spawnGroup(ServerLevel level, BlockPos origin, Vector3dc velocity, int anchorPIdx, int groupSize, CarriageDims dims, UUID trainId) {
        if (groupSize < 1) {
            throw new IllegalArgumentException("groupSize must be ≥ 1, got " + groupSize);
        }

        long tStart = System.nanoTime();
        int length = dims.length();
        int halfPadLen = CarriagePlacer.halfPadLen(dims);
        boolean wrapWithPads = groupSize > 1;
        int subLevelLength = wrapWithPads ? (groupSize * length + 2 * halfPadLen) : length;
        int enclosedStartOffset = wrapWithPads ? halfPadLen : 0;

        // The group's first enclosed carriage's ACTUAL world-X. `origin` is the real placement
        // corner (the seed uses the formula; the appender uses the live-relative placed position),
        // so this is the same world-X frame the band terrain / track gate on — feeding it to the
        // shell / contents / parts dimension gate makes the whole group's stage flip in lockstep
        // with the band beneath it, instead of trailing it by the appender's accumulated MIN_GAP /
        // collision drift (worst at the End and on leaving the Nether). See GateContext.forCarriageAtWorldX.
        int groupAnchorWorldX = origin.getX() + enclosedStartOffset;

        int cleared = clearSubLevelVolume(level, origin, subLevelLength, dims);
        long tAfterClear = System.nanoTime();
        if (cleared > 0) {
            LOGGER.debug("[DungeonTrain] Cleared {} world blocks in sub-level footprint anchorPIdx={} groupSize={} subLevelLength={} at {}",
                cleared, anchorPIdx, groupSize, subLevelLength, origin);
        }

        CarriageGenerationConfig genCfg = DungeonTrainWorldData.get(level).getGenerationConfig();

        // Attribute any lease this group takes to the host player. SharedCarriageEvents' prefetch tick
        // also caches this, but the world-load spawn runs before that tick has fired — leases taken here
        // would otherwise reach the relay with an empty holder, so the admin view can't say who has a
        // carriage locked.
        if (!level.players().isEmpty()) {
            net.minecraft.server.level.ServerPlayer host = level.players().get(0);
            // The display name is extra personal data, so it needs the contribution consent gate; the uuid
            // keeps its existing looser terms. No consent → uuid only, and our edits stay uncredited.
            String hostName = games.brennan.dungeontrain.event.SharedCarriageGate.canContribute(host)
                    ? host.getGameProfile().getName() : "";
            SharedCarriagePool.setHost(host.getUUID().toString().replace("-", ""), hostName);
        }

        // Place every enclosed carriage in the group at world coords.
        // For groupSize > 1, the enclosed run starts at
        // origin + halfPadLen; pad placement runs separately afterwards.
        // For groupSize == 1, the lone enclosed carriage sits at origin
        // and there are no pads.
        Set<BlockPos> blocks = new HashSet<>();
        CarriageVariant[] enclosedBySlot = new CarriageVariant[groupSize];
        // Per-slot worldgen stage: gates which pooled carriages this slot may draw, and stamps a fresh
        // build's origin so it is only ever placed back into the same stage.
        String[] stageBySlot = new String[groupSize];
        // Non-null for slots filled from the relay pool (leased build placed verbatim); null = fresh/local.
        SharedPick[] pickBySlot = new SharedPick[groupSize];
        // Whose builds count as "own" for this group — resolved once, since it can't change mid-spawn.
        List<String> onlineUuids = onlinePlayerUuids(level);

        for (int slot = 0; slot < groupSize; slot++) {
            int carriagePIdx = anchorPIdx + slot;
            BlockPos carriageOrigin = origin.offset(enclosedStartOffset + slot * length, 0, 0);
            // Gate the shell pick by this carriage's Diff-Level + worldgen phase. Phase comes from
            // the group's ACTUAL placed world-X so it matches the contents / parts / track gates and
            // the band terrain (Diff-Level still derives from pIdx — see GateContext.forCarriageAtWorldX).
            GateContext gateCtx = GateContext.forCarriageAtWorldX(level, groupAnchorWorldX, carriagePIdx, length);
            CarriageVariant variant = CarriagePlacer.enclosedVariantForIndex(carriagePIdx, genCfg, gateCtx);
            enclosedBySlot[slot] = variant;
            String stageId = games.brennan.dungeontrain.template.StageResolver.stageIdFor(gateCtx);
            stageBySlot[slot] = stageId;

            // Within a spawnGroup call, the enclosed run is wrapped by
            // half-flatbed pads when groupSize > 1. Slot 0's BACK door
            // and slot N-1's FRONT door therefore face flatbed-like
            // geometry; inner slots face other enclosed carriages.
            // For groupSize == 1, the lone carriage has no pads (per
            // wrapWithPads above) — its neighbours come from whatever
            // sits adjacent in the world, which the placer doesn't
            // currently know about, so both flags stay false.
            boolean flatbedAtBack  = wrapWithPads && slot == 0;
            boolean flatbedAtFront = wrapWithPads && slot == groupSize - 1;

            // Shared-carriage RELAY path: if this shared slot draws a build (the community pool, or one
            // authored by a player here) AND a lease is buffered, stamp it VERBATIM (no parts/variants/
            // contents/loot overlays — blocks come from the relay). Otherwise fall through to normal
            // placement (the FRESH path).
            Set<BlockPos> carriageBlocks = null;
            SharedPick pick = tryLeaseShared(level, variant, carriagePIdx, dims, genCfg, stageId, onlineUuids);
            if (pick != null) {
                SharedCarriageClient.PoolLease lease = pick.lease();
                carriageBlocks = placeRelayLease(level, carriageOrigin, lease, dims);
                if (carriageBlocks == null) {          // decode/dims/place failure → hand the lease back
                    SharedCarriagePool.returnLease(lease);
                } else {
                    pickBySlot[slot] = pick;
                }
            }
            if (carriageBlocks == null) {
                // applyContents=false: defer until after assembly so entities
                // land in shipyard space, not world space.
                carriageBlocks = CarriagePlacer.placeAt(
                    level, carriageOrigin, variant, dims, genCfg, carriagePIdx,
                    false, flatbedAtBack, flatbedAtFront, groupAnchorWorldX);
            }
            if (carriageBlocks.isEmpty()) {
                LOGGER.warn("[DungeonTrain] spawnGroup produced empty enclosed block set anchorPIdx={} slot={} carriagePIdx={} variant={} at {}",
                    anchorPIdx, slot, carriagePIdx, variant.id(), carriageOrigin);
            }
            blocks.addAll(carriageBlocks);
        }

        // Place the half-flatbed pads at sub-level boundaries.
        if (wrapWithPads) {
            BlockPos backPadOrigin = origin;
            BlockPos frontPadOrigin = origin.offset(halfPadLen + groupSize * length, 0, 0);
            blocks.addAll(CarriagePlacer.placeHalfFlatbedPad(
                level, backPadOrigin, CarriagePlacer.HalfPadSide.BACK, dims));
            blocks.addAll(CarriagePlacer.placeHalfFlatbedPad(
                level, frontPadOrigin, CarriagePlacer.HalfPadSide.FRONT, dims));
        }
        long tAfterPlace = System.nanoTime();

        ManagedShip ship = Shipyards.of(level).assemble(blocks, 1.0);
        long tAfterAssemble = System.nanoTime();

        // Resolve the sub-level's shipyard origin = the back pad's
        // lowest-X corner in shipyard coords (or, for groupSize == 1,
        // the anchor carriage's lowest-X corner — same thing since
        // origin == anchor in that case). Contents placement and the
        // appender's pIdx-from-player calc both shift by enclosedStartOffset.
        Vector3d shipyardOriginVec = new Vector3d(origin.getX(), origin.getY(), origin.getZ());
        ship.worldToShip(shipyardOriginVec);
        BlockPos shipyardOrigin = new BlockPos(
            (int) Math.round(shipyardOriginVec.x),
            (int) Math.round(shipyardOriginVec.y),
            (int) Math.round(shipyardOriginVec.z));

        // Contents pass at shipyard coords for each enclosed carriage.
        // We split the contents into two phases to dodge the entity-displacement
        // bug: blocks now, entities deferred until placement settles.
        //   • Blocks travel with the ship via shipyard chunks regardless of
        //     any {@code shiftSpawnPosition} the placement tracker applies
        //     during collision resolution, so it's safe to stamp them now.
        //   • Entities are added with {@code level.addFreshEntity} at the
        //     shipyard world position; VS's shipyard-entity mixin binds them
        //     to the ship lazily, and that lazy bind interacting with the
        //     per-tick shifts is the suspected cause of entities going
        //     missing in newly-spawned carriages. Deferred to
        //     {@code TrainCarriageAppender.runPlacementCollisionTracker} after
        //     {@code CLEAN_TICKS_FOR_SUCCESS} consecutive collision-free ticks.
        PendingContentsEntitySpawn[] pendingEntities = new PendingContentsEntitySpawn[groupSize];
        for (int slot = 0; slot < groupSize; slot++) {
            int carriagePIdx = anchorPIdx + slot;
            BlockPos carriageShipyardOrigin = shipyardOrigin.offset(enclosedStartOffset + slot * length, 0, 0);
            CarriageVariant variant = enclosedBySlot[slot];
            SharedPick pick = pickBySlot[slot];
            if (pick != null) {
                // RELAY carriage: its blocks were stamped verbatim from the relay snapshot. Do NOT run the
                // contents/loot pass (contents come from the relay) and spawn no contents entities.
                // Register it as leased so later edits save back to the relay.
                SharedCarriageClient.PoolLease lease = pick.lease();
                pendingEntities[slot] = null;
                SharedCarriageRegistry.Instance inst = SharedCarriageRegistry.register(
                    level, ship.subLevelId(), trainId, carriagePIdx,
                    carriageShipyardOrigin, dims, variant.id(), true, pick.authoredHere(),
                    lease.id(), lease.token(),
                    leaseSeqSeed(lease), // seq floor = max(baseSeq, delta seqs) so our edits clear the relay watermark
                    stageBySlot[slot], lease.credits());
                inst.stampContact(System.currentTimeMillis()); // fresh lease → no immediate heartbeat needed
                continue;
            }
            CarriagePlacer.applyContentsBlocksAt(level, carriageShipyardOrigin, variant, dims, genCfg, carriagePIdx, groupAnchorWorldX);
            // Stash for the appender's tick handler to fire once the
            // placement-collision tracker confirms this group has settled.
            // FLATBEDs (variant.type == FLATBED) have null entries — the
            // appender skips null slots. Per-slot stashing (rather than one
            // record for the group) keeps the model trivially correct if
            // groupSize ever moves to mixed-variant groups.
            pendingEntities[slot] = new PendingContentsEntitySpawn(
                carriageShipyardOrigin, variant, dims, genCfg, carriagePIdx, groupAnchorWorldX);
            // Fresh shared carriage (NEW path): register it so a real edit later queues a delta and
            // uploads the build to the pool for the first time. seqSeed=0 (a brand-new relay row is baseSeq=0).
            if (games.brennan.dungeontrain.event.SharedCarriageGate.canDiscover()
                    && SharedCarriageFlags.isSharedVariant(variant.id())) {
                // No credits: nobody has contributed to a brand-new local build yet.
                SharedCarriageRegistry.register(level, ship.subLevelId(), trainId, carriagePIdx,
                        carriageShipyardOrigin, dims, variant.id(), false, false, null, null, 0,
                        stageBySlot[slot], SharedCarriageClient.Credits.EMPTY);
            }
        }
        long tAfterContents = System.nanoTime();

        TrainTransformProvider provider = new TrainTransformProvider(
            velocity, shipyardOrigin, level.dimension(), anchorPIdx, groupSize, dims, trainId);
        provider.setPendingContentsEntitySpawns(pendingEntities);
        ship.setKinematicDriver(provider);
        ship.setStatic(true);

        // Pre-seed spawnGameTick at the ACTUAL spawn moment, not when Sable
        // first calls nextTransform (which can lag many ticks for far-from-
        // player carriages and leaves them permanently behind by
        // velocity * lateTicks blocks — see
        // {@link TrainTransformProvider#preSeedSpawnTick} for the full
        // explanation). NOTE: we deliberately do NOT pre-seed spawnWorldPos
        // — Sable's pose translation uses the block-AABB centre (see
        // {@code SableShipyard.computeAnchor}), not the back-pad corner —
        // so spawnWorldPos must come from {@code input.currentPosition()}
        // on first kinematic tick to stay in Sable's coordinate frame.
        provider.preSeedSpawnTick(level.getGameTime());

        // Register in the spawn-time truth source. Sable's SubLevelContainer
        // is lazy after assembly (a freshly-assembled sub-level can take
        // several ticks to appear in getAllSubLevels()), so the appender
        // can't trust Trains.byTrainId for "what anchors does this train
        // already own." The registry is the authoritative answer.
        Trains.registerSpawned(trainId, anchorPIdx, ship);

        long tEnd = System.nanoTime();
        LOGGER.info("[DungeonTrain] Spawned group anchorPIdx={} groupSize={} enclosed=[{}] pads={} trainId={} ship id={} shipyardOrigin={} blocks={} timing(ms): clear={} place={} assemble={} contents={} total={}",
            anchorPIdx, groupSize, summariseVariants(enclosedBySlot),
            wrapWithPads ? "back+front" : "none",
            trainId, ship.id(), shipyardOrigin, blocks.size(),
            (tAfterClear - tStart) / 1_000_000,
            (tAfterPlace - tAfterClear) / 1_000_000,
            (tAfterAssemble - tAfterPlace) / 1_000_000,
            (tAfterContents - tAfterAssemble) / 1_000_000,
            (tEnd - tStart) / 1_000_000);

        return ship;
    }

    private static String summariseVariants(CarriageVariant[] variants) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < variants.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(variants[i].id());
        }
        return sb.toString();
    }

    /**
     * Return every loaded {@link TrainTransformProvider} group in
     * {@code level}, ungrouped. Drop-in usage for legacy callers
     * ({@code /dt speed}, settings screen) that iterate providers and
     * mutate them. Per-train operations should prefer
     * {@link Trains#byTrainId(ServerLevel)}.
     */
    public static List<TrainTransformProvider> getActiveTrainProviders(ServerLevel level) {
        List<TrainTransformProvider> providers = new ArrayList<>();
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (ship.getKinematicDriver() instanceof TrainTransformProvider p) {
                providers.add(p);
            }
        }
        return providers;
    }

    /**
     * Public deletion helper for callers that need a clean slate before
     * spawning their own carriages outside the {@link #spawnTrain} path —
     * e.g. {@code /dt debug pair}, which spawns single-carriage groups
     * directly via {@link #spawnGroup}. Returns the count of carriages
     * deleted.
     */
    public static int deleteAllTrains(ServerLevel level) {
        return deleteExistingTrains(level);
    }

    private static int deleteExistingTrains(ServerLevel level) {
        // Wipe the spawn-time registry first so any trainIds being torn
        // down here don't linger as ghost entries that would block a
        // future appender from re-spawning at those anchors. Also clear
        // the appender's wait-for-Sable-settle tracker so the first
        // post-wipe spawn doesn't get gated on a now-deleted ship's AABB.
        Trains.clearRegistry();
        TrainCarriageAppender.clearSettleTracker();
        games.brennan.dungeontrain.event.CarriageGroupGapTicker.resetWarnings();
        Shipyard shipyard = Shipyards.of(level);
        // Sweep any trailing-segment force-load tickets before the train
        // re-fills. Guarantees no DT force-load survives a session boundary —
        // Sable persists tickets to disk and resurrects the ticketed
        // sub-levels on world load, so a ticket left live at save would
        // otherwise reappear as an orphan carriage. See SableShipyard.
        shipyard.releaseAllForceLoads();
        List<ManagedShip> trains = new ArrayList<>();
        for (ManagedShip ship : shipyard.findAll()) {
            if (ship.getKinematicDriver() instanceof TrainTransformProvider) {
                trains.add(ship);
            }
        }
        for (ManagedShip ship : trains) {
            shipyard.delete(ship);
        }
        CarriagePersistenceStore.clear(level);
        return trains.size();
    }

    /**
     * Clear every world block inside the sub-level's axis-aligned
     * footprint — the union of {@code subLevelLength × height × width}
     * blocks at {@code origin}, covering the BACK pad, all enclosed
     * carriage slots, and the FRONT pad in one pass.
     */
    private static int clearSubLevelVolume(ServerLevel level, BlockPos origin, int subLevelLength, CarriageDims dims) {
        int cleared = 0;
        for (int dx = 0; dx < subLevelLength; dx++) {
            for (int dy = 0; dy < dims.height(); dy++) {
                for (int dz = 0; dz < dims.width(); dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).isAir()) {
                        // Section-local air write — no light engine, no neighbour
                        // cascade. The whole footprint is lifted into a Sable
                        // sub-level this same tick (which then re-airs the source
                        // cells), so any world-side relight here is discarded work.
                        SilentBlockOps.setBlockSectionLocal(level, pos, AIR);
                        cleared++;
                    }
                }
            }
        }
        return cleared;
    }
}
