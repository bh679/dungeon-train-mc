package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.CallStatus;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.DeltaResult;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.SharedCarriagePool;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
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
    /** Max base64 blob we'll upload (must stay under the relay's CARRIAGES_MAX_CHARS). */
    private static final int MAX_BLOB_CHARS = 700_000;

    private SharedCarriageEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return; // one always-ticking level drives the cadence
        if (!SharedCarriageGate.canDiscover()) return;     // feature master off → nothing to do
        long t = level.getGameTime();

        if (t % PREFETCH_INTERVAL_TICKS == 0) {
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

    /** Keep the lease buffer topped up (self-caps at the pool's target); excludes ids already resident. */
    private static void prefetch(ServerLevel level) {
        String hostUuid = "";
        List<ServerPlayer> players = level.players();
        if (!players.isEmpty()) hostUuid = players.get(0).getUUID().toString().replace("-", "");
        // Share it with the pool so leases taken off the spawn thread also record a real holder.
        SharedCarriagePool.setHostUuid(hostUuid);
        List<Integer> exclude = new ArrayList<>();
        for (SharedCarriageRegistry.Instance inst : SharedCarriageRegistry.all()) {
            Integer id = inst.relayId();
            if (id != null) exclude.add(id);
        }
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        // Buffer for the stage the train is actually spawning into — a lease for another stage would sit
        // unusable here while locking that carriage against every other world.
        SharedCarriagePool.refreshAsync(dims, SharedCarriagePool.demandStage(), hostUuid, exclude);
    }

    /** One flusher pass for a carriage: re-baseline if asked, else upload a delta/first-submit, else heartbeat. */
    private static void flush(SharedCarriageRegistry.Instance inst) {
        if (inst.isCulled() || inst.isCallInFlight()) return;
        if (inst.isOnRelay() && inst.needsRebaseline()) {
            saveFull(inst);                              // relay's delta log near/at full → collapse it
            return;
        }
        if (inst.hasPending()) {
            if (inst.isOnRelay()) flushDelta(inst);      // leased/submitted → stream only the changed cells
            else submitFresh(inst);                      // never uploaded → one-time full submit
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

    /** Upload a fresh, changed carriage for the first time (full submit, gated on a consenting player). */
    private static void submitFresh(SharedCarriageRegistry.Instance inst) {
        ServerPlayer contributor = firstConsentingPlayer(inst.level);
        if (contributor == null) return; // no consenting player present → try later, edits stay queued
        SableManagedShip ship = liveShip(inst.level, inst);
        if (ship == null) return;        // sub-level not resident → try later
        // The full capture folds in every queued edit, so drain them (re-queued on failure). Same-thread
        // as the block-change hook, so nothing new arrives between this drain and the capture.
        Set<BlockPos> covered = inst.drainPending();
        CapturedBlob blob = captureFull(ship, inst);
        if (blob == null) { inst.reenqueue(covered); return; }
        if (blob.base64().length() > MAX_BLOB_CHARS) {
            LOGGER.warn("[DungeonTrain] shared carriage variant={} too large to upload ({} chars) — skipping.",
                    inst.variantId, blob.base64().length());
            return; // drop covered — nothing we can do; a smaller later edit re-queues
        }
        String ownerUuid = contributor.getUUID().toString().replace("-", "");
        inst.setCallInFlight(true);
        long now = System.currentTimeMillis();
        SharedCarriageClient.submit(ownerUuid, blob.base64(), inst.dims.length(), inst.dims.height(), inst.dims.width(), blob.text(), inst.stageId)
                .whenComplete((result, err) -> {
                    try {
                        if (err == null && result != null && result.isPresent() && result.get().token() != null) {
                            SharedCarriageClient.LeaseResult r = result.get();
                            inst.onRelayLease(r.id(), r.token()); // baseSeq=0 on the fresh row; seq stays 0 → first delta seq 1
                            inst.stampContact(now);
                            LOGGER.info("[DungeonTrain] Uploaded fresh shared carriage variant={} → relay id={} (leased).",
                                    inst.variantId, r.id());
                        } else if (err == null && result != null && result.isPresent()) {
                            // Deduped against a build leased elsewhere → no token → stays local-only (drop covered).
                            LOGGER.debug("[DungeonTrain] fresh carriage variant={} deduped to a held relay build — local only.", inst.variantId);
                        } else {
                            inst.reenqueue(covered); // transport failure → retry the submit next flush
                        }
                    } finally {
                        inst.setCallInFlight(false);
                    }
                });
    }

    /** Capture + upload only the queued (changed) cells of a leased carriage as one delta. */
    private static void flushDelta(SharedCarriageRegistry.Instance inst) {
        SableManagedShip ship = liveShip(inst.level, inst);
        if (ship == null) return; // sub-level not resident → leave queued, retry later
        Set<BlockPos> drained = inst.drainPending();
        if (drained.isEmpty()) return;
        int seq = inst.nextSeq();
        String cells, text;
        try {
            CarriageBlockSnapshot.Captured cap =
                    CarriageBlockSnapshot.captureCells(ship, inst.shipyardOrigin, inst.dims, drained, inst.level.registryAccess());
            cells = CarriageBlockSnapshot.encode(cap.tag());
            text = cap.text();
        } catch (Throwable tErr) {
            inst.reenqueue(drained); // capture failed → retry
            LOGGER.debug("[DungeonTrain] shared-carriage delta capture failed for pIdx={}: {}", inst.pIdx, tErr.toString());
            return;
        }
        if (cells.length() > MAX_BLOB_CHARS) {
            // A single coalesced delta over the cap is implausible; fall back to a full re-baseline.
            inst.markRebaseline();
            return;
        }
        inst.setCallInFlight(true);
        long now = System.currentTimeMillis();
        SharedCarriageClient.delta(inst.relayId(), inst.leaseToken(), seq, cells, text)
                .whenComplete((res, err) -> {
                    try {
                        if (err != null || res == null) {
                            inst.reenqueue(drained);                 // transport error → retry these cells
                        } else if (res.status() == CallStatus.OK) {
                            inst.stampContact(now);
                            if (res.compactNeeded()) inst.markRebaseline(); // proactive re-baseline
                            // drained stays dropped — successfully uploaded
                        } else if (res.status() == CallStatus.ERROR && res.mustCompact()) {
                            inst.markRebaseline();                   // log full → full save captures these cells
                        } else if (res.status() == CallStatus.FORBIDDEN || res.status() == CallStatus.UNKNOWN) {
                            inst.clearRelayLease();                  // lost/gone lease → stop; edits stay local
                        } else {
                            inst.reenqueue(drained);                 // other error → retry
                        }
                    } finally {
                        inst.setCallInFlight(false);
                    }
                });
    }

    /** Full save of a leased carriage — re-baselines the relay (clears its delta log, advances baseSeq). */
    private static void saveFull(SharedCarriageRegistry.Instance inst) {
        SableManagedShip ship = liveShip(inst.level, inst);
        if (ship == null) return;
        CapturedBlob blob = captureFull(ship, inst);
        if (blob == null) return;
        if (blob.base64().length() > MAX_BLOB_CHARS) {
            LOGGER.warn("[DungeonTrain] leased shared carriage id={} too large to re-baseline ({} chars).",
                    inst.relayId(), blob.base64().length());
            inst.clearRebaseline();
            return;
        }
        int baseSeq = inst.currentSeq();
        inst.setCallInFlight(true);
        long now = System.currentTimeMillis();
        SharedCarriageClient.save(inst.relayId(), inst.leaseToken(), blob.base64(), blob.text(), baseSeq)
                .whenComplete((status, err) -> {
                    try {
                        if (status == CallStatus.OK) {
                            inst.stampContact(now);
                            inst.clearRebaseline();
                        } else if (status == CallStatus.FORBIDDEN || status == CallStatus.UNKNOWN) {
                            inst.clearRelayLease();
                            inst.clearRebaseline();
                        }
                        // ERROR → keep rebaseline set, retry next flush
                    } finally {
                        inst.setCallInFlight(false);
                    }
                });
    }

    private static void heartbeatLeased(SharedCarriageRegistry.Instance inst) {
        inst.setCallInFlight(true);
        long now = System.currentTimeMillis();
        SharedCarriageClient.heartbeat(inst.relayId(), inst.leaseToken(), SharedCarriagePool.hostUuid())
                .whenComplete((status, err) -> {
                    try {
                        if (status == CallStatus.OK) inst.stampContact(now);
                        else if (status == CallStatus.FORBIDDEN || status == CallStatus.UNKNOWN) inst.clearRelayLease();
                    } finally {
                        inst.setCallInFlight(false);
                    }
                });
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
        inst.markCulled(); // stop the flusher issuing new POSTs; a stale in-flight one 403s harmlessly
        Integer id = inst.relayId();
        String token = inst.leaseToken();
        if (id == null || token == null) return false; // never uploaded → nothing leased to return
        String blocks = null, text = null;
        int baseSeq = inst.currentSeq();
        boolean captured = false;
        if (allowCapture && inst.hasPending()) { // else the streamed deltas already reflect every edit
            SableManagedShip ship = liveShip(inst.level, inst);
            if (ship != null) {
                CapturedBlob blob = captureFull(ship, inst);
                if (blob != null && blob.base64().length() <= MAX_BLOB_CHARS) {
                    blocks = blob.base64();
                    text = blob.text();
                    captured = true;
                }
            }
        }
        SharedCarriageClient.returnLease(id, token, blocks, text, baseSeq);
        return captured;
    }

    /** A captured carriage ready for the relay: the base64 blob + its scraped moderation text. */
    private record CapturedBlob(String base64, String text) {}

    /** Full-footprint capture + encode of a live carriage (with moderation text), or null on failure. */
    private static CapturedBlob captureFull(SableManagedShip ship, SharedCarriageRegistry.Instance inst) {
        try {
            CarriageBlockSnapshot.Captured cap =
                    CarriageBlockSnapshot.capture(ship, inst.shipyardOrigin, inst.dims, inst.level.registryAccess());
            return new CapturedBlob(CarriageBlockSnapshot.encode(cap.tag()), cap.text());
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

    @SubscribeEvent
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
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SharedCarriageRegistry.clear();
        SharedCarriagePool.clear();
    }
}
