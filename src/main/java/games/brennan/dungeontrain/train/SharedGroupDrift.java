package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.SharedCarriageGate;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.Supplier;

/**
 * Drifting Group carriages: the decision, made once per group, that a Group carriage (one shell
 * spanning the whole group, {@link FullCarriageSelection}) travels the relay as a single group-long
 * build — and, when it does, which copy fills it.
 *
 * <p>The group twin of the per-slot shared path in {@code TrainAssembler.tryLeaseShared}. A drifting
 * group either stamps another world's copy whole across the run, or stands as the ordinary Group
 * carriage and uploads as one build the first time somebody changes it. Whether it drifts is
 * {@link SharedCarriageRolls#groupDrifts}; which copy it gets is the same pool/own/fresh
 * {@link SharedCarriageRolls#bucket} every shared slot rolls, on the group's first carriage.</p>
 *
 * <p>Kept out of {@code TrainAssembler} so the spawn path only gains call sites.</p>
 */
public final class SharedGroupDrift {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A relay copy chosen for a drifting group, plus whether a player in this world built it. */
    public record Pick(PoolLease lease, boolean authoredHere) {}

    private SharedGroupDrift() {}

    /**
     * Whether the Group carriage anchored at {@code anchorPIdx} drifts. Needs the feature on (a fresh
     * group must be able to upload) and a stage — the relay pools a build under its stage and never
     * leases one without, so a stageless group is the plain template by definition.
     */
    public static boolean drifts(int anchorPIdx, int groupSize, long seed, String stageId) {
        if (!SharedCarriageGate.canDiscover()) return false;
        if (stageId == null || stageId.isEmpty()) return false;
        long groupIndex = Math.floorDiv((long) anchorPIdx, Math.max(1, groupSize));
        return SharedCarriageRolls.groupDrifts(seed, groupIndex, DungeonTrainConfig.getSharedGroupChance());
    }

    /**
     * The relay copy a drifting group stamps instead of its template, or null to stand as the template
     * (leasing off, the roll came up fresh, or nothing buffered). Records the demand either way, so the
     * prefetch tick has a copy ready for the next group that drifts here.
     */
    public static Pick lease(ServerLevel level, int anchorPIdx, CarriageDims box, String stageId,
                             long seed, List<String> onlineUuids) {
        SharedGroupPool.noteDemand(stageId, box);
        if (!SharedCarriageGate.canLease()) {
            log(anchorPIdx, "LEASING_OFF", null);
            return null;
        }
        SharedCarriageRolls.Bucket bucket = SharedCarriageRolls.bucket(seed, anchorPIdx,
                DungeonTrainConfig.getSharedCarriagePoolChance(),
                DungeonTrainConfig.getSharedCarriageOwnChance());
        if (bucket == SharedCarriageRolls.Bucket.FRESH) {
            log(anchorPIdx, "ROLLED_FRESH", null);
            return null;
        }
        Supplier<Pick> own = () -> pollOwn(stageId, box, onlineUuids);
        Supplier<Pick> any = () -> pollAny(stageId, box, onlineUuids);
        Pick pick = bucket == SharedCarriageRolls.Bucket.OWN ? firstNonNull(own, any) : firstNonNull(any, own);
        if (pick == null) {
            log(anchorPIdx, SharedGroupPool.isBackedOff() ? "BACKOFF_SUPPRESSED" : "BUFFER_EMPTY", null);
            return null;
        }
        // Remembered at the poll, not the stamp — the same rule the per-slot path follows.
        markUsed(level, pick.lease());
        log(anchorPIdx, pick.authoredHere() ? "POOL_HIT_OWN" : "POOL_HIT", pick.lease());
        return pick;
    }

    /** Log a fresh drifting group — the line a Gate 2 run looks for when the roll stood as the template. */
    public static void logFresh(int anchorPIdx) {
        log(anchorPIdx, "FRESH", null);
    }

    private static Pick pollAny(String stageId, CarriageDims box, List<String> onlineUuids) {
        PoolLease l = SharedGroupPool.poll(stageId, box);
        if (l == null) return null;
        boolean own = l.owner() != null && !l.owner().isEmpty() && onlineUuids.contains(l.owner());
        return new Pick(l, own);
    }

    private static Pick pollOwn(String stageId, CarriageDims box, List<String> onlineUuids) {
        PoolLease l = SharedGroupPool.pollOwn(stageId, box, onlineUuids);
        return l == null ? null : new Pick(l, true);
    }

    private static Pick firstNonNull(Supplier<Pick> a, Supplier<Pick> b) {
        Pick first = a.get();
        return first != null ? first : b.get();
    }

    private static void markUsed(ServerLevel level, PoolLease lease) {
        try {
            DungeonTrainWorldData.get(level).markCarriageUsed(lease.id());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] could not record used group id={}: {}", lease.id(), t.toString());
        }
    }

    private static void log(int anchorPIdx, String outcome, PoolLease lease) {
        // INFO like the per-slot line: a drifting group is one Group carriage in fifty, so it is rare.
        LOGGER.info("[DungeonTrain] shared group anchorPIdx={} → {}{} (buffered={})",
                anchorPIdx, outcome, lease == null ? "" : " id=" + lease.id(), SharedGroupPool.buffered());
    }
}
