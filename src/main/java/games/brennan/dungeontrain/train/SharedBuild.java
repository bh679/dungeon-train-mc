package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.Set;

/**
 * The relay-side bookkeeping every shared build carries, whatever it is made of.
 *
 * <p>A drifting carriage lives in a Sable sub-level and a drifting dimensional carriage is a box of
 * world blocks under the floor, so how each is <i>captured</i> differs — but what the relay wants to
 * know about either is the same: which row it is, which lease authorises writing to it, how far its
 * delta sequence has got, which cells changed since the last upload, and whether a call is already
 * in flight. This is that state, as the upload flow ({@code SharedUploadFlow}) reads it. Both
 * registries' instances implement it; the capture stays theirs.</p>
 *
 * <p>Positions in the outbox are in whatever space the implementor captures in — shipyard space for
 * a carriage, room-local offsets for a room. The flow never interprets them; it hands them straight
 * back to the implementor's own delta capture.</p>
 */
public interface SharedBuild {

    /** Relay row id once this build exists on the relay (via submit or lease); null until then. */
    Integer relayId();

    /** Active lease token for save/heartbeat/return; null until submitted or leased. */
    String leaseToken();

    /** True once both a row id and a lease token are held — the build streams deltas from here on. */
    boolean isOnRelay();

    /** Record that this build now lives on the relay under {@code id} with lease {@code token}. */
    void onRelayLease(int id, String token);

    /** Forget the relay row and lease — the build stays standing and playable, it just stops uploading. */
    void clearRelayLease();

    /** Allocate the next strictly-increasing delta sequence (tied to the relay row). */
    int nextSeq();

    /** The max seq allocated so far — the {@code baseSeq} to stamp on a compacting save or return. */
    int currentSeq();

    /** Whether any changed cells await upload. */
    boolean hasPending();

    /** Snapshot the pending positions and remove exactly those; the caller captures each afresh. */
    Set<BlockPos> drainPending();

    /** Re-queue positions whose upload failed so the next flush captures them again. */
    void reenqueue(Collection<BlockPos> positions);

    /** True once the build is being torn down — no further enqueues or POSTs. */
    boolean isCulled();

    /** Stop further enqueues and flusher POSTs. */
    void markCulled();

    /** Whether the relay asked for a full re-baseline (its delta log is near or at full). */
    boolean needsRebaseline();

    void markRebaseline();

    void clearRebaseline();

    /** Guards against overlapping submit / save / delta calls for one build. */
    boolean isCallInFlight();

    void setCallInFlight(boolean inFlight);

    /** Last successful save / heartbeat / delta wall-clock ms, for throttling. */
    long lastContactMs();

    void stampContact(long ms);

    /** Fingerprint of the decor entities as of the last successful upload. */
    long entitySig();

    /** Whether {@link #entitySig} holds a real baseline yet — see the carriage registry for why. */
    boolean hasEntitySigBaseline();

    void setEntitySig(long sig);

    /** True at most once per {@code intervalMs}; stamps as it answers. */
    boolean dueForEntityScan(long nowMs, long intervalMs);

    /** Whether this build's lease has been reported to the relay with a real host uuid. */
    boolean isAttributed();

    void markAttributed();

    /** The worldgen stage the build was placed in, or null when none covers it. */
    String stageId();

    /** A short label for log lines, e.g. {@code pIdx=12} or {@code pair=30}. */
    String describe();
}
