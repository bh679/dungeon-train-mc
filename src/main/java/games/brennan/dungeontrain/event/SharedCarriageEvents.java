package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.CallStatus;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.SharedCarriagePool;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.nbt.CompoundTag;
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

/**
 * Drives the shared-carriage relay lifecycle on the overworld tick (throttled by game-time):
 *
 * <ul>
 *   <li><b>Prefetch</b> (~5&nbsp;s) — tops up {@link SharedCarriagePool}'s lease buffer so the spawn
 *       path can hand out pooled builds without blocking.</li>
 *   <li><b>Sweep</b> (~30&nbsp;s) — for each registered shared carriage: a fresh one that was changed is
 *       uploaded ({@code /carriages/submit}); a leased one that was changed is saved back
 *       ({@code /carriages/save}); an idle leased one is kept alive ({@code /carriages/heartbeat}).</li>
 *   <li><b>Return</b> (server stopping) — hands back every held lease + the unused buffer; the relay's
 *       ~1&nbsp;h TTL is the backstop for anything not returned.</li>
 * </ul>
 *
 * <p>Uploading (submit/save) is gated on a consenting player ({@link SharedCarriageGate}); leasing +
 * heartbeats need only the server master. Not {@code Dist.CLIENT} — this must run on dedicated servers.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedCarriageEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PREFETCH_INTERVAL_TICKS = 100;  // ~5 s
    private static final int SWEEP_INTERVAL_TICKS = 600;     // ~30 s
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
        if (t % SWEEP_INTERVAL_TICKS == 0) {
            for (SharedCarriageRegistry.Instance inst : SharedCarriageRegistry.all()) {
                try {
                    sweep(inst);
                } catch (Throwable th) {
                    LOGGER.debug("[DungeonTrain] shared-carriage sweep error for pIdx={}: {}", inst.pIdx, th.toString());
                }
            }
        }
    }

    /** Keep the lease buffer topped up (self-caps at the pool's target); excludes ids already resident. */
    private static void prefetch(ServerLevel level) {
        String hostUuid = "";
        List<ServerPlayer> players = level.players();
        if (!players.isEmpty()) hostUuid = players.get(0).getUUID().toString().replace("-", "");
        List<Integer> exclude = new ArrayList<>();
        for (SharedCarriageRegistry.Instance inst : SharedCarriageRegistry.all()) {
            Integer id = inst.relayId();
            if (id != null) exclude.add(id);
        }
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        SharedCarriagePool.refreshAsync(dims, hostUuid, exclude);
    }

    private static void sweep(SharedCarriageRegistry.Instance inst) {
        if (inst.isCallInFlight()) return;
        if (!inst.isOnRelay()) {
            if (inst.isDirty()) submitFresh(inst);       // never-uploaded → first upload on a real change
            return;
        }
        if (inst.isDirty()) {
            saveLeased(inst);                            // leased + changed → save back (also heartbeat)
        } else if (System.currentTimeMillis() - inst.lastContactMs() > HEARTBEAT_INTERVAL_MS) {
            heartbeatLeased(inst);                        // leased + idle → keep the lock alive
        }
    }

    /** Upload a fresh, changed carriage for the first time (gated on a consenting player). */
    private static void submitFresh(SharedCarriageRegistry.Instance inst) {
        ServerPlayer contributor = firstConsentingPlayer(inst.level);
        if (contributor == null) return; // no consenting player present → try later, stay dirty
        String blocks = captureBlob(inst);
        if (blocks == null) return;
        if (blocks.length() > MAX_BLOB_CHARS) {
            LOGGER.warn("[DungeonTrain] shared carriage variant={} too large to upload ({} chars) — skipping.",
                    inst.variantId, blocks.length());
            inst.clearDirty();
            return;
        }
        String ownerUuid = contributor.getUUID().toString().replace("-", "");
        inst.setCallInFlight(true);
        long now = System.currentTimeMillis();
        SharedCarriageClient.submit(ownerUuid, blocks, inst.dims.length(), inst.dims.height(), inst.dims.width(), null)
                .whenComplete((result, err) -> {
                    try {
                        if (err == null && result != null && result.isPresent()) {
                            SharedCarriageClient.LeaseResult r = result.get();
                            if (r.token() != null) {
                                inst.onRelayLease(r.id(), r.token());
                                inst.clearDirty();
                                inst.stampContact(now);
                                LOGGER.info("[DungeonTrain] Uploaded fresh shared carriage variant={} → relay id={} (leased).",
                                        inst.variantId, r.id());
                            } else {
                                inst.clearDirty(); // deduped against a build leased elsewhere → nothing to hold
                            }
                        }
                    } finally {
                        inst.setCallInFlight(false);
                    }
                });
    }

    /** Save a leased carriage's current blocks back to the relay (doubles as a heartbeat). */
    private static void saveLeased(SharedCarriageRegistry.Instance inst) {
        String blocks = captureBlob(inst);
        if (blocks == null) return;
        if (blocks.length() > MAX_BLOB_CHARS) {
            LOGGER.warn("[DungeonTrain] leased shared carriage id={} too large to save ({} chars) — skipping.",
                    inst.relayId(), blocks.length());
            inst.clearDirty();
            return;
        }
        inst.setCallInFlight(true);
        long now = System.currentTimeMillis();
        SharedCarriageClient.save(inst.relayId(), inst.leaseToken(), blocks, null)
                .whenComplete((status, err) -> {
                    try {
                        if (status == CallStatus.OK) {
                            inst.clearDirty();
                            inst.stampContact(now);
                        } else if (status == CallStatus.FORBIDDEN || status == CallStatus.UNKNOWN) {
                            inst.clearRelayLease(); // lost the lease (or gone) → stop trying to save
                            inst.clearDirty();
                        }
                        // ERROR → keep dirty, retry next sweep
                    } finally {
                        inst.setCallInFlight(false);
                    }
                });
    }

    private static void heartbeatLeased(SharedCarriageRegistry.Instance inst) {
        inst.setCallInFlight(true);
        long now = System.currentTimeMillis();
        SharedCarriageClient.heartbeat(inst.relayId(), inst.leaseToken())
                .whenComplete((status, err) -> {
                    try {
                        if (status == CallStatus.OK) inst.stampContact(now);
                        else if (status == CallStatus.FORBIDDEN || status == CallStatus.UNKNOWN) inst.clearRelayLease();
                    } finally {
                        inst.setCallInFlight(false);
                    }
                });
    }

    /** Capture + encode this carriage's live blocks, or null if the sub-level isn't resident / capture fails. */
    private static String captureBlob(SharedCarriageRegistry.Instance inst) {
        SableManagedShip ship = liveShip(inst.level, inst);
        if (ship == null) return null;
        try {
            CompoundTag snap = CarriageBlockSnapshot.capture(ship, inst.shipyardOrigin, inst.dims, inst.level.registryAccess());
            return CarriageBlockSnapshot.encode(snap);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] shared-carriage capture failed for pIdx={}: {}", inst.pIdx, t.toString());
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
        // Hand back every held lease + the unused buffer so carriages don't stay locked to a stopped
        // world for the full TTL. Best-effort (async) — the relay's TTL covers anything that doesn't land.
        for (SharedCarriageRegistry.Instance inst : SharedCarriageRegistry.all()) {
            if (inst.isOnRelay()) {
                SharedCarriageClient.returnLease(inst.relayId(), inst.leaseToken(), null, null);
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
