package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.CallStatus;
import games.brennan.dungeontrain.train.SharedBuild;
import games.brennan.dungeontrain.train.SharedCarriagePool;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.Set;

/**
 * The relay round-trips a shared build makes, and what each answer does to the build's state —
 * shared by drifting carriages ({@link SharedCarriageEvents}) and drifting dimensional carriages
 * ({@link SharedRoomEvents}).
 *
 * <p>The two differ only in how they read their own blocks: a carriage from its Sable plot, a room
 * from the level under the floor. Everything after the capture — which endpoint, what a 403 means,
 * when to re-queue, when to detach — is the same protocol, and having it once is what keeps the two
 * from drifting apart in how they treat the relay. Each caller hands in a {@link Capture} bound to
 * its own reading and gets the same lifecycle back.</p>
 *
 * <p>All state changes happen on the async callback thread, exactly as they did before this was
 * split out; the build's fields are volatile or concurrent for that reason.</p>
 */
public final class SharedUploadFlow {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Max base64 blob we'll upload (must stay under the relay's CARRIAGES_MAX_CHARS). */
    public static final int MAX_BLOB_CHARS = 700_000;

    private SharedUploadFlow() {}

    /**
     * A captured build ready for the relay: the base64 blob, its scraped moderation text, and the
     * decor fingerprint of the entities it carries — recorded on the build once the upload lands, so
     * the sweep can tell a later entity-only edit from the state the relay already holds.
     */
    public record CapturedBlob(String base64, String text, long entitySig) {}

    /** How a caller reads its own blocks. Either answer may be null when the build is not readable right now. */
    public interface Capture {
        /** The whole footprint. */
        CapturedBlob full();

        /** Only {@code drained}, in the caller's own position space, as a delta blob. */
        CapturedBlob delta(Set<BlockPos> drained);
    }

    /** What a fresh build is submitted as: its shape on the relay and its box. */
    public record SubmitSpec(String kind, String subKind, int l, int h, int w) {}

    /**
     * Upload a fresh, changed build for the first time — one full submit. {@code drained} is the set
     * of pending positions the caller already took off the outbox (the full capture folds them in);
     * it is re-queued on a transport failure so nothing is lost. Deduped-without-a-token means the
     * relay already holds an identical build leased elsewhere, and this one stays local.
     */
    public static void submitFresh(SharedBuild build, Capture capture, SubmitSpec spec,
                                   Set<BlockPos> drained, String ownerUuid, String ownerName,
                                   String stage, String mode) {
        CapturedBlob blob = capture.full();
        if (blob == null) { build.reenqueue(drained); return; }
        if (blob.base64().length() > MAX_BLOB_CHARS) {
            LOGGER.warn("[DungeonTrain] shared build {} too large to upload ({} chars) — skipping.",
                    build.describe(), blob.base64().length());
            return; // drop drained — nothing we can do; a smaller later edit re-queues
        }
        build.setCallInFlight(true);
        long now = System.currentTimeMillis();
        SharedCarriageClient.submit(ownerUuid, ownerName, blob.base64(), spec.l(), spec.h(), spec.w(),
                        blob.text(), stage, mode, spec.kind(), spec.subKind())
                .whenComplete((result, err) -> {
                    try {
                        if (err == null && result != null && result.isPresent() && result.get().token() != null) {
                            SharedCarriageClient.LeaseResult r = result.get();
                            build.onRelayLease(r.id(), r.token()); // baseSeq=0 on the fresh row; first delta seq 1
                            build.stampContact(now);
                            build.setEntitySig(blob.entitySig());
                            LOGGER.info("[DungeonTrain] Uploaded fresh shared build {} → relay id={} (leased).",
                                    build.describe(), r.id());
                        } else if (err == null && result != null && result.isPresent()) {
                            LOGGER.debug("[DungeonTrain] fresh shared build {} deduped to a held relay build — local only.",
                                    build.describe());
                        } else {
                            build.reenqueue(drained); // transport failure → retry the submit next flush
                        }
                    } finally {
                        build.setCallInFlight(false);
                    }
                });
    }

    /**
     * Upload only the changed cells of a build already on the relay as one delta. {@code drained} is
     * what the caller took off the outbox; {@code entitiesOnly} sends even an empty cell set, because
     * every delta carries the whole current entity list and so is a fine carrier for one.
     */
    public static void flushDelta(SharedBuild build, Capture capture, Set<BlockPos> drained,
                                  boolean entitiesOnly) {
        if (drained.isEmpty() && !entitiesOnly) return;
        int seq = build.nextSeq();
        CapturedBlob blob = capture.delta(drained);
        if (blob == null) {
            build.reenqueue(drained); // capture failed → retry
            return;
        }
        if (blob.base64().length() > MAX_BLOB_CHARS) {
            // A single coalesced delta over the cap is implausible; fall back to a full re-baseline.
            build.markRebaseline();
            return;
        }
        build.setCallInFlight(true);
        long now = System.currentTimeMillis();
        SharedCarriageClient.delta(build.relayId(), build.leaseToken(), seq, blob.base64(), blob.text())
                .whenComplete((res, err) -> {
                    try {
                        if (err != null || res == null) {
                            build.reenqueue(drained);                 // transport error → retry these cells
                        } else if (res.status() == CallStatus.OK) {
                            build.stampContact(now);
                            build.setEntitySig(blob.entitySig());
                            if (res.compactNeeded()) build.markRebaseline(); // proactive re-baseline
                            // drained stays dropped — successfully uploaded
                        } else if (res.status() == CallStatus.ERROR && res.mustCompact()) {
                            build.markRebaseline();                   // log full → full save captures these cells
                        } else if (res.status() == CallStatus.FORBIDDEN || res.status() == CallStatus.UNKNOWN) {
                            build.clearRelayLease();                  // lost/gone lease → stop; edits stay local
                        } else {
                            build.reenqueue(drained);                 // other error → retry
                        }
                    } finally {
                        build.setCallInFlight(false);
                    }
                });
    }

    /** Full save of a leased build — re-baselines the relay (clears its delta log, advances baseSeq). */
    public static void saveFull(SharedBuild build, Capture capture) {
        CapturedBlob blob = capture.full();
        if (blob == null) return;
        if (blob.base64().length() > MAX_BLOB_CHARS) {
            LOGGER.warn("[DungeonTrain] leased shared build id={} too large to re-baseline ({} chars).",
                    build.relayId(), blob.base64().length());
            build.clearRebaseline();
            return;
        }
        int baseSeq = build.currentSeq();
        build.setCallInFlight(true);
        long now = System.currentTimeMillis();
        SharedCarriageClient.save(build.relayId(), build.leaseToken(), blob.base64(), blob.text(), baseSeq)
                .whenComplete((status, err) -> {
                    try {
                        if (status == CallStatus.OK) {
                            build.stampContact(now);
                            build.setEntitySig(blob.entitySig());
                            build.clearRebaseline();
                        } else if (status == CallStatus.FORBIDDEN || status == CallStatus.UNKNOWN) {
                            build.clearRelayLease();
                            build.clearRebaseline();
                        }
                        // ERROR → keep rebaseline set, retry next flush
                    } finally {
                        build.setCallInFlight(false);
                    }
                });
    }

    /** Keep an idle lease alive, attributed to the world's host. */
    public static void heartbeat(SharedBuild build) {
        build.setCallInFlight(true);
        long now = System.currentTimeMillis();
        SharedCarriageClient.heartbeat(build.relayId(), build.leaseToken(),
                        SharedCarriagePool.hostUuid(), SharedCarriagePool.hostName())
                .whenComplete((status, err) -> {
                    try {
                        if (status == CallStatus.OK) build.stampContact(now);
                        else if (status == CallStatus.FORBIDDEN || status == CallStatus.UNKNOWN) build.clearRelayLease();
                    } finally {
                        build.setCallInFlight(false);
                    }
                });
    }

    /**
     * Final flush + lease return for a build about to go away. Marks it culled first, so nothing new
     * is queued or POSTed; then, when {@code allowCapture} and edits are still un-flushed, captures the
     * whole build SYNCHRONOUSLY (the blocks may be about to be destroyed) and hands the lease back
     * with those final blocks. A build that was never uploaded has nothing to return. Returns
     * whether a full capture was taken, for per-pass capping.
     */
    public static boolean finalFlushAndReturn(SharedBuild build, Capture capture, boolean allowCapture) {
        build.markCulled();
        Integer id = build.relayId();
        String token = build.leaseToken();
        if (id == null || token == null) return false;
        String blocks = null, text = null;
        int baseSeq = build.currentSeq();
        boolean captured = false;
        if (allowCapture && build.hasPending()) { // else the streamed deltas already reflect every edit
            CapturedBlob blob = capture.full();
            if (blob != null && blob.base64().length() <= MAX_BLOB_CHARS) {
                blocks = blob.base64();
                text = blob.text();
                captured = true;
            }
        }
        SharedCarriageClient.returnLease(id, token, blocks, text, baseSeq);
        return captured;
    }
}
