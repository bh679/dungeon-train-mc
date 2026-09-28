package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.SharedCarriageGate;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import games.brennan.dungeontrain.train.SharedCarriageRolls;
import games.brennan.dungeontrain.train.SharedRoomPool;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides, as a pair is planned, whether its room comes from the drifting pool rather than the
 * template — the room half of {@code TrainAssembler.tryLeaseShared}.
 *
 * <p>Same shape as the carriage decision, on purpose: the same gates, the same deterministic
 * bucket roll ({@link SharedCarriageRolls}, keyed by pair rather than carriage index, so a
 * re-plan decides identically), the same "own build first when the roll says so, the other bucket
 * as a fallback, fresh only when nothing is buffered". The spawn path never blocks on HTTP; a
 * copy that is not already buffered is not worth hitching the portal tick for.</p>
 *
 * <p>What is asked of the pool is exact: this room's name at this size, in this stage. A room's
 * doors and corridors line up only with a copy of the same template at the same box, which is why
 * the relay matches on {@code subKind} and dims and why {@link SharedRoomPool#noteDemand} is told
 * the name — the pool cannot guess which room the next pair rolls.</p>
 */
public final class PortalRoomDriftPlanner {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PortalRoomDriftPlanner() {}

    /**
     * The drifted copy this pair stamps instead of its template, or null for the template.
     *
     * <p>Null whenever the room does not drift (mode, author's veto), leasing is off, the pair has
     * no stage the pool could match, the test rig is stamping, the roll came up fresh, or nothing
     * of this room is buffered. Records the room as the pool's demand either way, so a later pair
     * rolling the same room can be served. {@code stageId} is the pair's recorded stage — the one
     * the stamp and the registry use — so a copy is asked for, filed and resolved under one stage.</p>
     */
    public static PortalRoomBlob leaseFor(ServerLevel level, int pairKey, String roomName,
                                          PortalRoomSettings settings, Vec3i size, String stageId) {
        if (!driftsHere(level, pairKey, settings)) return null;
        if (!SharedCarriageGate.canLease()) return null;
        if (stageId == null || stageId.isEmpty()) {
            log(pairKey, roomName, "NO_STAGE", null);
            return null;
        }
        SharedRoomPool.noteDemand(stageId, roomName, size);
        SharedCarriageRolls.Bucket bucket = SharedCarriageRolls.bucket(
            level.getSeed(), pairKey,
            DungeonTrainConfig.getSharedCarriagePoolChance(),
            DungeonTrainConfig.getSharedCarriageOwnChance());
        if (bucket == SharedCarriageRolls.Bucket.FRESH) {
            log(pairKey, roomName, "ROLLED_FRESH", null);
            return null;
        }
        List<String> online = onlinePlayerUuids(level);
        boolean ownFirst = bucket == SharedCarriageRolls.Bucket.OWN;
        PoolLease lease = ownFirst
            ? firstNonNull(SharedRoomPool.pollOwn(stageId, roomName, size, online),
                           () -> SharedRoomPool.poll(stageId, roomName, size))
            : firstNonNull(SharedRoomPool.poll(stageId, roomName, size),
                           () -> SharedRoomPool.pollOwn(stageId, roomName, size, online));
        if (lease == null) {
            log(pairKey, roomName, SharedRoomPool.isBackedOff() ? "BACKOFF_SUPPRESSED" : "BUFFER_EMPTY", null);
            return null;
        }
        // Remembered at the poll, not the stamp — a copy this world has been shown once is the one
        // repeat that reads as the pool running dry, even if the stamp then fails and hands it back.
        markUsed(level, lease);
        boolean own = !lease.owner().isEmpty() && online.contains(lease.owner());
        try {
            PortalRoomBlob blob = PortalRoomBlob.fromLease(lease, own);
            if (!blob.matches(size.getX(), size.getY(), size.getZ())) {
                LOGGER.warn("[DungeonTrain] drifted room id={} for '{}' is {}x{}x{}, pair {} planned {}x{}x{} — "
                        + "returning it; the template stamps instead.", lease.id(), roomName,
                    blob.snapshot().getInt("l"), blob.snapshot().getInt("h"), blob.snapshot().getInt("w"),
                    pairKey, size.getX(), size.getY(), size.getZ());
                SharedRoomPool.returnLease(lease);
                return null;
            }
            log(pairKey, roomName, own ? "POOL_HIT_OWN" : "POOL_HIT", lease);
            return blob;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] drifted room id={} for '{}' failed to decode: {} — returning it.",
                lease.id(), roomName, e.toString());
            SharedRoomPool.returnLease(lease);
            return null;
        }
    }

    /**
     * Whether this pair's room takes part in drifting at all: the room's mode and the author's
     * setting say it may, it is not the editor's test rig, and the pair won its
     * {@code sharedRoomChance} roll — one in fifteen by default. The same answer registers a fresh
     * room for upload ({@code SharedRoomEvents.onStructureStamped}) and lets the planner draw a
     * copy, so a pair that lost the roll is a plain template room in every respect.
     */
    public static boolean driftsHere(ServerLevel level, int pairKey, PortalRoomSettings settings) {
        if (settings == null || !settings.drifts()) {
            LOGGER.info("[DungeonTrain] drifting room pair={} → NOT_ELIGIBLE (mode {}, drift {})", pairKey,
                settings == null ? "?" : settings.mode().id(), settings == null ? "?" : settings.drift().id());
            return false;
        }
        if (PortalTestSession.isTestStamp(pairKey)) return false;
        double chance = DungeonTrainConfig.getSharedRoomChance();
        boolean drifts = SharedCarriageRolls.roomDrifts(level.getSeed(), pairKey, chance);
        // Logged either way, at the same rate as pairs stamp: a session in which every pair lost the
        // roll is otherwise silent, and "no room ever drifted" reads exactly like "the feature is
        // off". One line per decision, so a tester can see the roll rather than infer it.
        if (!drifts) {
            LOGGER.info("[DungeonTrain] drifting room pair={} → LOST_ROLL (chance {}) — plain template room",
                pairKey, chance);
        }
        return drifts;
    }

    private static PoolLease firstNonNull(PoolLease first, java.util.function.Supplier<PoolLease> second) {
        return first != null ? first : second.get();
    }

    private static void markUsed(ServerLevel level, PoolLease lease) {
        try {
            DungeonTrainWorldData.get(level).markCarriageUsed(lease.id());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] could not record used room id={}: {}", lease.id(), t.toString());
        }
    }

    /** Uuids (dashless, as the relay stores them) of the players currently in this level. */
    static List<String> onlinePlayerUuids(ServerLevel level) {
        List<String> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) out.add(p.getUUID().toString().replace("-", ""));
        return out;
    }

    /** One line per drifting room planned — the same audit the carriage slots leave. */
    private static void log(int pairKey, String roomName, String outcome, PoolLease lease) {
        LOGGER.info("[DungeonTrain] drifting room pair={} '{}' → {}{} (buffered={})",
            pairKey, roomName, outcome, lease == null ? "" : " id=" + lease.id(), SharedRoomPool.buffered());
    }
}
