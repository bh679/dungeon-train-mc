package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageEntitySnapshot;
import games.brennan.dungeontrain.train.SharedCarriagePool;
import games.brennan.dungeontrain.train.SharedGroupPool;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import games.brennan.dungeontrain.train.StorageContents;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drives the shared-carriage relay lifecycle on the overworld tick:
 *
 * <ul>
 *   <li><b>Prefetch</b> (~5&nbsp;s) — tops up {@link SharedCarriagePool}'s lease buffer so the spawn
 *       path can hand out pooled builds without blocking.</li>
 *   <li><b>Flush</b> (~0.5&nbsp;s) — for each registered shared carriage with queued edits, uploads
 *       ONLY the changed cells as a delta ({@code /carriages/delta}) the instant after they change,
 *       coalescing a burst into one POST. A never-uploaded (fresh) carriage does a one-time full
 *       {@code /carriages/submit} on its first flush to establish its relay row + base blob; edits after
 *       that stream as deltas. Immediate upload — rather than a slow sweep — is what stops a moving
 *       train's edits being lost when the carriage scrolls back and is culled.</li>
 *   <li><b>Heartbeat</b> — an idle leased carriage with nothing queued is kept alive; a delta implicitly
 *       heartbeats, so only truly-idle ones need this.</li>
 *   <li><b>Return</b> (server stopping) — a final flush + hands back every held lease + the buffer.</li>
 * </ul>
 *
 * <p>Uploading is gated on a consenting player ({@link SharedCarriageGate}); leasing + heartbeats need
 * only the server master. Not {@code Dist.CLIENT} — this must run on dedicated servers. All capture runs
 * on the server thread (block changes enqueue on the server thread too, so a flush never races an edit);
 * only the HTTP POST is async.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedCarriageEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PREFETCH_INTERVAL_TICKS = 100; // ~5s
    private static final int FLUSH_INTERVAL_TICKS = 10;     // ~0.5 s — coalescing delta flush cadence
    /** Re-heartbeat a leased carriage this long after the last contact (well under the relay's ~1h TTL). */
    private static final long HEARTBEAT_INTERVAL_MS = 300_000L; // 5 min
    /**
     * How often a leased carriage's live entities are re-scanned for an entity-only edit. Block edits
     * still upload sub-second off the change hook; this is the slower net that catches the edits no block
     * change accompanies, and it walks entities, so it deliberately does not run on the flusher cadence.
     */
    private static final long ENTITY_SCAN_INTERVAL_MS = 30_000L;
    /**
     * How many ids to send as the lease exclude-list. Kept under the relay's own cap (128) so the list
     * arrives whole rather than being silently truncated at the far end.
     */
    private static final int MAX_EXCLUDE_IDS = 120;

    /** Rotates through online players so each one's own builds get prefetched in turn. */
    private static int ownPrefetchCursor = 0;

    /**
     * The pool the world was in on the last prefetch tick, so a mid-session flip is noticed. Null until
     * the first tick — a world that starts in Free Play has nothing held yet, so there is nothing to
     * hand back.
     */
    private static volatile String lastMode = null;

    private SharedCarriageEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return; // one always-ticking level drives the cadence
        if (!SharedCarriageGate.canDiscover()) return;     // feature master off → nothing to do
        long t = level.getGameTime();

        if (t % PREFETCH_INTERVAL_TICKS == 0) {
            detectModeFlip(level);
            prefetch(level);
        }
        if (t % FLUSH_INTERVAL_TICKS == 0) {
            for (SharedCarriageRegistry.Instance inst : SharedCarriageRegistry.all()) {
                try {
                    flush(inst);
                } catch (Throwable th) {
                    LOGGER.debug("[DungeonTrain] shared-carriage flush error for pIdx={}: {}", inst.pIdx, th.toString());
                }
            }
        }
    }

    /**
     * Hand back everything the world is holding when it crosses between the Free Play and normal pools
     * — a player switching to creative mid-run is the usual cause.
     *
     * <p>Held leases belong to the pool the world was in when it took them. Once it has flipped, saving
     * those back would write Free Play edits into the normal pool (or the reverse), which is the exact
     * mixing the two pools exist to prevent. So the leases go back and the carriages detach from the
     * relay: they stay standing and playable, they just stop uploading. Edits made since the last save
     * are lost, which is the price of not contaminating the other pool.</p>
     */
    private static void detectModeFlip(ServerLevel level) {
        String mode = SharedCarriageMode.current(level);
        String previous = lastMode;
        lastMode = mode;
        if (previous == null || previous.equals(mode)) return;

        int detached = 0;
        for (SharedCarriageRegistry.Instance inst : SharedCarriageRegistry.all()) {
            if (!inst.isOnRelay()) continue;
            Integer id = inst.relayId();
            String token = inst.leaseToken();
            inst.clearRelayLease(); // stop the flusher touching it before the return lands
            if (id != null && token != null) {
                SharedCarriageClient.returnLease(id, token, null, null, 0);
                detached++;
            }
        }
        SharedCarriagePool.returnAllBuffered();
        SharedGroupPool.returnAllBuffered();
        int rooms = SharedRoomEvents.returnAllHeld(); // the rooms' leases belong to the old pool too
        LOGGER.info("[DungeonTrain] Shared-carriage pool switched {} → {}; returned {} held carriage(s) and {} room(s) and cleared the buffers.",
                previous, mode, detached, rooms);
    }

    /**
     * Keep both lease buffers topped up (each self-caps at the pool's target): the shared pool, plus one
     * player's own builds per tick, cycling through everyone online so a busy server doesn't only ever
     * serve the first player their work back. Excludes ids already resident or already placed here.
     */
    private static void prefetch(ServerLevel level) {
        // Nothing may be placed while leasing is off, and a buffered lease is LOCKED against every
        // other world — so prefetching here would hold community builds hostage for no one's benefit.
        // The flush half of this tick is untouched: this world's own uploads carry on.
        if (!SharedCarriageGate.canLease()) return;
        String hostUuid = "";
        String hostName = "";
        List<ServerPlayer> players = level.players();
        if (!players.isEmpty()) {
            ServerPlayer host = players.get(0);
            hostUuid = host.getUUID().toString().replace("-", "");
            // The NAME is extra personal data, so it rides the contribution consent gate rather than the
            // uuid's looser terms — no consent means this world's edits stay credited to a bare uuid.
            if (SharedCarriageGate.canContribute(host)) hostName = host.getGameProfile().getName();
        }
        // Share it with the pool so leases taken off the spawn thread also record a real holder.
        SharedCarriagePool.setHost(hostUuid, hostName);
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        List<Integer> exclude = leaseExcludeIds(level);
        CarriageDims dims = data.dims();
        String stage = SharedCarriagePool.demandStage();
        String mode = SharedCarriageMode.current(level);
        // Buffer for the stage the train is actually spawning into — a lease for another stage would sit
        // unusable here while locking that carriage against every other world.
        SharedCarriagePool.refreshAsync(dims, stage, hostUuid, hostName, exclude, mode);
        if (!players.isEmpty()) {
            int idx = Math.floorMod(ownPrefetchCursor++, players.size());
            String owner = players.get(idx).getUUID().toString().replace("-", "");
            SharedCarriagePool.refreshOwnAsync(dims, stage, owner, exclude, mode);
        }
        // Drifting Group carriages: only once one has drifted here (demand is null until then), so a
        // world that never rolls one never locks a group build away from everyone else.
        SharedGroupPool.Demand groupDemand = SharedGroupPool.demand();
        if (groupDemand != null) {
            SharedGroupPool.refreshAsync(groupDemand, hostUuid, hostName, exclude, mode);
            if (!players.isEmpty()) {
                String owner = players.get(Math.floorMod(ownPrefetchCursor, players.size()))
                        .getUUID().toString().replace("-", "");
                SharedGroupPool.refreshOwnAsync(groupDemand, owner, exclude, mode);
            }
        }
    }

    /**
     * The relay ids a lease request must not answer with: every build resident here (carriage or
     * room — relay ids are one namespace), plus what this world has placed before. A build we've
     * already shown is the one repeat that reads as the generator running dry. Newest first, since
     * the relay truncates the list.
     */
    public static List<Integer> leaseExcludeIds(ServerLevel level) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        List<Integer> exclude = new ArrayList<>();
        // Membership rides alongside the list rather than being read out of it: the list is ordered and
        // goes to the relay as-is, but MAX_EXCLUDE_IDS of them turned the dedupe below into a scan per
        // candidate.
        Set<Integer> excluded = new HashSet<>();
        for (SharedCarriageRegistry.Instance inst : SharedCarriageRegistry.all()) {
            Integer id = inst.relayId();
            if (id != null && excluded.add(id)) exclude.add(id);
        }
        for (games.brennan.dungeontrain.train.SharedRoomRegistry.Instance inst
                : games.brennan.dungeontrain.train.SharedRoomRegistry.all()) {
            Integer id = inst.relayId();
            if (id != null && excluded.add(id)) exclude.add(id);
        }
        for (Integer id : data.recentUsedCarriageIds(MAX_EXCLUDE_IDS)) {
            if (exclude.size() >= MAX_EXCLUDE_IDS) break;
            if (excluded.add(id)) exclude.add(id);
        }
        return exclude;
    }

    /** One flusher pass for a carriage: re-baseline if asked, else upload a delta/first-submit, else heartbeat. */
    private static void flush(SharedCarriageRegistry.Instance inst) {
        if (inst.isCulled() || inst.isCallInFlight()) return;
        if (inst.isOnRelay() && inst.needsRebaseline()) {
            saveFull(inst);                              // relay's delta log near/at full → collapse it
            return;
        }
        if (inst.hasPending()) {
            // A block edit is one of the two moments parked storage travels (leaving is the other), so
            // fold it into this same upload rather than a delta of its own.
            if (inst.hasParked()) inst.releaseParked(pos -> StorageContents.read(inst.level, pos));
            if (inst.isOnRelay()) flushDelta(inst, false); // leased/submitted → stream only the changed cells
            else submitFresh(inst);                       // never uploaded → one-time full submit
            return;
        }
        // Entity-only edits reach the pool here. Hanging an item frame or posing an armor stand changes no
        // block, so nothing is ever enqueued for it; the sweep instead compares a live decor fingerprint
        // against the one last uploaded. Only for carriages ALREADY on the relay: a fresh carriage still
        // needs a block edit to earn its place in the pool, or the contents mobs a blank shared slot spawns
        // with would upload it untouched.
        if (inst.isOnRelay() && inst.dueForEntityScan(System.currentTimeMillis(), ENTITY_SCAN_INTERVAL_MS)
                && entitiesChanged(inst)) {
            flushDelta(inst, true);
            return;
        }
        // One-shot attribution: a lease claimed during world-load spawn reached the relay with no uuid
        // (no player had joined the level yet), so send one heartbeat as soon as a host is known. Waiting
        // for the 5-minute idle heartbeat is far too late — most carriages are culled long before it.
        if (inst.isOnRelay() && !inst.isAttributed() && !SharedCarriagePool.hostUuid().isEmpty()) {
            inst.markAttributed();
            heartbeatLeased(inst);
            return;
        }
        if (inst.isOnRelay() && System.currentTimeMillis() - inst.lastContactMs() > HEARTBEAT_INTERVAL_MS) {
            heartbeatLeased(inst);                        // idle leased → keep the lock alive
        }
    }

    /**
     * Has a builder changed this carriage's decor entities since its last successful upload? Walks the
     * live carriage (no serialisation) and compares fingerprints. False whenever the sub-level isn't
     * resident — there is nothing to read, and "unreadable" must never be mistaken for "emptied".
     */
    private static boolean entitiesChanged(SharedCarriageRegistry.Instance inst) {
        SableManagedShip ship = liveShip(inst.level, inst);
        if (ship == null) return false;
        long live = CarriageEntitySnapshot.liveDecorFingerprint(ship, inst.level, inst.shipyardOrigin, inst.dims);
        if (!inst.hasEntitySigBaseline()) {
            // A leased carriage was placed from the relay's own copy, so what stands in it now IS the
            // uploaded state — there is nothing to report. Adopt it as the baseline; the next scan is
            // the first that can honestly say a builder changed something. Without this the
            // uninitialised 0 never matched a real fingerprint, so every leased carriage uploaded a
            // full delta describing no edit, moments after it spawned.
            inst.setEntitySig(live);
            return false;
        }
        return live != inst.entitySig();
    }

    /** Upload a fresh, changed carriage for the first time (full submit, gated on a consenting player). */
    private static void submitFresh(SharedCarriageRegistry.Instance inst) {
        ServerPlayer contributor = firstConsentingPlayer(inst.level);
        if (contributor == null) return; // no consenting player present → try later, edits stay queued
        SableManagedShip ship = liveShip(inst.level, inst);
        if (ship == null) return;        // sub-level not resident → try later
        // The full capture folds in every queued edit, so drain them (re-queued on failure). Same-thread
        // as the block-change hook, so nothing new arrives between this drain and the capture.
        Set<BlockPos> covered = inst.drainPending();
        String ownerUuid = contributor.getUUID().toString().replace("-", "");
        // `contributor` already passed the contribution gate, so their name may go up with the build —
        // it is what every other world credits this carriage to.
        String ownerName = contributor.getGameProfile().getName();
        // Read the pool live rather than from spawn time: the session may have flipped to Free Play since
        // this carriage was placed, and the build belongs to whichever pool the world is in when it lands.
        String mode = SharedCarriageMode.current(inst.level);
        SharedUploadFlow.submitFresh(inst, captureOf(ship, inst),
                new SharedUploadFlow.SubmitSpec(inst.kind, null,
                        inst.dims.length(), inst.dims.height(), inst.dims.width()),
                covered, ownerUuid, ownerName, inst.stageId, mode);
    }

    /**
     * Capture + upload only the queued (changed) cells of a leased carriage as one delta.
     *
     * @param entitiesOnly upload even with no changed cells, because the carriage's ENTITIES changed —
     *                     every delta carries the whole current entity list, so an empty-cell delta is a
     *                     perfectly good carrier for one
     */
    private static void flushDelta(SharedCarriageRegistry.Instance inst, boolean entitiesOnly) {
        SableManagedShip ship = liveShip(inst.level, inst);
        if (ship == null) return; // sub-level not resident → leave queued, retry later
        Set<BlockPos> drained = inst.drainPending();
        SharedUploadFlow.flushDelta(inst, captureOf(ship, inst), drained, entitiesOnly);
    }

    /** Full save of a leased carriage — re-baselines the relay (clears its delta log, advances baseSeq). */
    private static void saveFull(SharedCarriageRegistry.Instance inst) {
        SableManagedShip ship = liveShip(inst.level, inst);
        if (ship == null) return;
        SharedUploadFlow.saveFull(inst, captureOf(ship, inst));
    }

    private static void heartbeatLeased(SharedCarriageRegistry.Instance inst) {
        SharedUploadFlow.heartbeat(inst);
    }

    /**
     * Final flush + lease return for a carriage about to be culled or a stopping server. When {@code
     * allowCapture} and the carriage has un-flushed edits, captures the full carriage SYNCHRONOUSLY on the
     * server thread (the plot may be about to be destroyed — capturing after that would read air and
     * re-baseline the row to an EMPTY carriage), copies the lease identity into locals, and fires the async
     * return with those final blocks so a straggling in-flight delta is already folded into the new base.
     * With {@code allowCapture=false} (mass-cull overflow) it does a bare return — at most the last
     * sub-second of un-flushed edits is left to the streamed deltas already on the relay. Returns whether a
     * full capture was taken (for per-pass capping). Safe for a non-relay carriage (no-op beyond marking
     * culled). MUST be called on the server thread BEFORE the sub-level is deleted.
     */
    public static boolean finalFlushAndReturn(SharedCarriageRegistry.Instance inst, boolean allowCapture) {
        // Storage a player changed but never walked away from (culled around them, server stopping) —
        // queue it now, while the plot is still readable and before markCulled stops enqueue, so the
        // full capture below carries it.
        inst.releaseParked(pos -> StorageContents.read(inst.level, pos));
        SableManagedShip ship = liveShip(inst.level, inst);
        // No ship → nothing readable; the flow then does a bare return (allowCapture is moot).
        return SharedUploadFlow.finalFlushAndReturn(inst,
                ship == null ? NO_CAPTURE : captureOf(ship, inst), allowCapture && ship != null);
    }

    /** A capture for a carriage whose plot is gone — every read answers "not readable". */
    private static final SharedUploadFlow.Capture NO_CAPTURE = new SharedUploadFlow.Capture() {
        @Override public SharedUploadFlow.CapturedBlob full() { return null; }
        @Override public SharedUploadFlow.CapturedBlob delta(Set<BlockPos> drained) { return null; }
    };

    /** The plot-backed reads of one live carriage, as the upload flow wants them. */
    private static SharedUploadFlow.Capture captureOf(SableManagedShip ship, SharedCarriageRegistry.Instance inst) {
        return new SharedUploadFlow.Capture() {
            @Override
            public SharedUploadFlow.CapturedBlob full() {
                return captureFull(ship, inst);
            }

            @Override
            public SharedUploadFlow.CapturedBlob delta(Set<BlockPos> drained) {
                try {
                    CarriageBlockSnapshot.Captured cap =
                            CarriageBlockSnapshot.captureCells(ship, inst.level, inst.shipyardOrigin, inst.dims, drained,
                                    DungeonTrainConfig.getSharedCarriageMaxEntities());
                    // The fingerprint of what this upload actually carries — recorded only once the POST
                    // lands, so a failed upload is re-tried rather than mistaken for "the relay already
                    // has these entities".
                    long sig = CarriageEntitySnapshot.decorFingerprint(
                            cap.tag().getList("ents", net.minecraft.nbt.Tag.TAG_COMPOUND));
                    return new SharedUploadFlow.CapturedBlob(CarriageBlockSnapshot.encode(cap.tag()), cap.text(), sig);
                } catch (Throwable tErr) {
                    LOGGER.debug("[DungeonTrain] shared-carriage delta capture failed for pIdx={}: {}", inst.pIdx, tErr.toString());
                    return null;
                }
            }
        };
    }

    /** Full-footprint capture + encode of a live carriage (with moderation text), or null on failure. */
    private static SharedUploadFlow.CapturedBlob captureFull(SableManagedShip ship, SharedCarriageRegistry.Instance inst) {
        try {
            CarriageBlockSnapshot.Captured cap =
                    CarriageBlockSnapshot.capture(ship, inst.level, inst.shipyardOrigin, inst.dims,
                            DungeonTrainConfig.getSharedCarriageMaxEntities());
            long sig = CarriageEntitySnapshot.decorFingerprint(
                    cap.tag().getList("ents", net.minecraft.nbt.Tag.TAG_COMPOUND));
            return new SharedUploadFlow.CapturedBlob(CarriageBlockSnapshot.encode(cap.tag()), cap.text(), sig);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] shared-carriage full capture failed for pIdx={}: {}", inst.pIdx, t.toString());
            return null;
        }
    }

    private static ServerPlayer firstConsentingPlayer(ServerLevel level) {
        for (ServerPlayer p : level.players()) {
            if (SharedCarriageGate.canContribute(p)) return p;
        }
        return null;
    }

    private static SableManagedShip liveShip(ServerLevel level, SharedCarriageRegistry.Instance inst) {
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (ship instanceof SableManagedShip sms && inst.subLevelId.equals(sms.subLevelId())) {
                return sms;
            }
        }
        return null;
    }

    // HIGHEST: ShipShutdownEvents deletes every train sub-level on this same event, and the final capture
    // must read the plot before that — otherwise every lease goes back bare and storage looted since the
    // last block edit (parked, see Instance.releaseParked) never reaches the relay.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStopping(ServerStoppingEvent event) {
        // Final flush + hand back every held lease + the unused buffer so carriages don't stay locked to a
        // stopped world for the full TTL. Best-effort (the return POST is async) — the relay's TTL covers
        // anything that doesn't land.
        for (SharedCarriageRegistry.Instance inst : SharedCarriageRegistry.all()) {
            try {
                finalFlushAndReturn(inst, true); // shutdown is one-time → always allow the final capture
            } catch (Throwable th) {
                LOGGER.debug("[DungeonTrain] shared-carriage final return error for pIdx={}: {}", inst.pIdx, th.toString());
            }
        }
        SharedCarriagePool.returnAllBuffered();
        SharedGroupPool.returnAllBuffered();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SharedCarriageRegistry.clear();
        SharedCarriagePool.clear();
        SharedGroupPool.clear();
        lastMode = null; // the next world decides its own pool from scratch
    }
}
