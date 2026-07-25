package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

/**
 * Drives the shared-carriage relay lifecycle on a coarse (~30&nbsp;s) cadence — modelled on
 * {@link RelayOutboxFlushEvents}: a {@link LevelTickEvent.Post} gated to the overworld and throttled by
 * game-time, then a single sweep over every registered carriage regardless of dimension.
 *
 * <p><b>PR B</b> handles the CONTRIBUTION half only: a fresh shared carriage that a player has actually
 * changed (its {@link SharedCarriageRegistry.Instance#isDirty() dirty} flag is set) is captured and
 * uploaded via {@code /carriages/submit}, gated on a consenting player ({@link SharedCarriageGate}).
 * The lease save-back / heartbeat / return half is added in PR C.</p>
 *
 * <p>Not {@code Dist.CLIENT} — carriage uploads must work on dedicated servers too.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedCarriageEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** ~30 s at 20 tps. Uploads are not latency-sensitive. */
    private static final int PERIOD_TICKS = 600;
    /** Max base64 blob we'll upload (must stay under the relay's CARRIAGES_MAX_CHARS). */
    private static final int MAX_BLOB_CHARS = 700_000;

    private SharedCarriageEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Gate the cadence to one always-ticking level so the sweep runs once per period.
        if (level.dimension() != Level.OVERWORLD) return;
        if (level.getGameTime() % PERIOD_TICKS != 0) return;
        if (!SharedCarriageGate.canDiscover()) return; // feature master off → nothing to do

        for (SharedCarriageRegistry.Instance inst : SharedCarriageRegistry.all()) {
            try {
                sweep(inst);
            } catch (Throwable t) {
                LOGGER.debug("[DungeonTrain] shared-carriage sweep error for pIdx={}: {}", inst.pIdx, t.toString());
            }
        }
    }

    private static void sweep(SharedCarriageRegistry.Instance inst) {
        if (inst.isCallInFlight()) return;
        // PR B: only fresh (never-uploaded) carriages that have been changed. Leased carriages are
        // handled by PR C's save-back path.
        if (inst.isOnRelay()) return;
        if (!inst.isDirty()) return;

        // Contribution needs a consenting player present (mirrors shared-book upload consent).
        ServerPlayer contributor = firstConsentingPlayer(inst.level);
        if (contributor == null) return;

        // Resolve the live sub-level to read the carriage's current blocks. Gone (culled) → try later.
        SableManagedShip ship = liveShip(inst.level, inst);
        if (ship == null) return;

        String blocks;
        try {
            CompoundTag snap = CarriageBlockSnapshot.capture(ship, inst.shipyardOrigin, inst.dims, inst.level.registryAccess());
            blocks = CarriageBlockSnapshot.encode(snap);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] shared-carriage capture failed for pIdx={}: {}", inst.pIdx, t.toString());
            return;
        }
        if (blocks.length() > MAX_BLOB_CHARS) {
            LOGGER.warn("[DungeonTrain] shared carriage variant={} too large to upload ({} chars) — skipping.",
                    inst.variantId, blocks.length());
            inst.clearDirty(); // don't retry an over-cap carriage every period
            return;
        }

        String ownerUuid = contributor.getUUID().toString().replace("-", "");
        inst.setCallInFlight(true);
        SharedCarriageClient.submit(ownerUuid, blocks, inst.dims.length(), inst.dims.height(), inst.dims.width(), null)
                .whenComplete((result, err) -> {
                    try {
                        if (err == null && result != null && result.isPresent()) {
                            SharedCarriageClient.LeaseResult r = result.get();
                            if (r.token() != null) {
                                inst.onRelayLease(r.id(), r.token());
                                inst.stampContact(System.currentTimeMillis());
                                LOGGER.info("[DungeonTrain] Uploaded fresh shared carriage variant={} → relay id={} (leased).",
                                        inst.variantId, r.id());
                            } else {
                                // Deduped against an existing (leased-elsewhere) build — nothing more to do.
                                inst.clearDirty();
                            }
                        }
                        // On any failure, leave dirty set so the next sweep retries.
                    } finally {
                        inst.setCallInFlight(false);
                    }
                });
    }

    /** First online player in {@code level} whose client has granted upload consent, or null. */
    private static ServerPlayer firstConsentingPlayer(ServerLevel level) {
        for (ServerPlayer p : level.players()) {
            if (SharedCarriageGate.canContribute(p)) return p;
        }
        return null;
    }

    /** The live Sable ship for this instance's sub-level, or null if it isn't currently resident. */
    private static SableManagedShip liveShip(ServerLevel level, SharedCarriageRegistry.Instance inst) {
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (ship instanceof SableManagedShip sms && inst.subLevelId.equals(sms.subLevelId())) {
                return sms;
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SharedCarriageRegistry.clear();
    }
}
