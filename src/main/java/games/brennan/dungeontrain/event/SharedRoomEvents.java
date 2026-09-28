package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageLayout;
import games.brennan.dungeontrain.portal.PortalRoomBlob;
import games.brennan.dungeontrain.portal.PortalStructure;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageEntitySnapshot;
import games.brennan.dungeontrain.train.SharedRoomPool;
import games.brennan.dungeontrain.train.SharedRoomRegistry;
import net.minecraft.core.BlockPos;
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

import java.util.List;
import java.util.Set;

/**
 * Drives the drifting dimensional carriage lifecycle — the room twin of
 * {@link SharedCarriageEvents}, on the same overworld cadence and through the same
 * {@link SharedUploadFlow}:
 *
 * <ul>
 *   <li><b>Prefetch</b> — tops up {@link SharedRoomPool} for the room the planner last rolled.</li>
 *   <li><b>Flush</b> — uploads a room's changed cells as deltas, or a fresh edited room in full.</li>
 *   <li><b>Heartbeat</b> — keeps an idle leased room's lock alive.</li>
 *   <li><b>Structure hooks</b> — registers a room when its pair is stamped, captures it live before a
 *       relocation erases it, and hands its lease back when the pair is evicted or the server stops.</li>
 * </ul>
 *
 * <p>A room is a box of world blocks under the floor, so every read here is a level read
 * ({@code captureLevel} / {@code captureLevelCells}) rather than a plot read, and every queued position
 * is a room-local offset. Nothing else differs from a carriage.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedRoomEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PREFETCH_INTERVAL_TICKS = 100; // ~5 s, offset from the carriage tick below
    private static final int FLUSH_INTERVAL_TICKS = 10;
    private static final long HEARTBEAT_INTERVAL_MS = 300_000L;
    private static final long ENTITY_SCAN_INTERVAL_MS = 30_000L;
    /** Prefetch a beat after the carriage pool does, so the two never hit the relay in the same tick. */
    private static final int PREFETCH_PHASE = 50;

    private static int ownPrefetchCursor = 0;

    private SharedRoomEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        if (!SharedCarriageGate.canDiscover()) return;
        long t = level.getGameTime();
        if (t % PREFETCH_INTERVAL_TICKS == PREFETCH_PHASE) prefetch(level);
        if (t % FLUSH_INTERVAL_TICKS == 0 && !SharedRoomRegistry.isEmpty()) {
            for (SharedRoomRegistry.Instance inst : SharedRoomRegistry.all()) {
                try {
                    flush(inst);
                } catch (Throwable th) {
                    LOGGER.debug("[DungeonTrain] shared-room flush error for {}: {}", inst.describe(), th.toString());
                }
            }
        }
    }

    /** Keep the room pool topped up for the room most recently planned, plus one online player's own. */
    private static void prefetch(ServerLevel level) {
        if (!SharedCarriageGate.canLease()) return;
        SharedRoomPool.Demand demand = SharedRoomPool.demand();
        if (demand == null) return; // no pair has planned a drifting room yet
        List<Integer> exclude = SharedCarriageEvents.leaseExcludeIds(level);
        String mode = SharedCarriageMode.current(level);
        SharedRoomPool.refreshAsync(demand, games.brennan.dungeontrain.train.SharedCarriagePool.hostUuid(),
                games.brennan.dungeontrain.train.SharedCarriagePool.hostName(), exclude, mode);
        List<ServerPlayer> players = level.players();
        if (!players.isEmpty()) {
            int idx = Math.floorMod(ownPrefetchCursor++, players.size());
            String owner = players.get(idx).getUUID().toString().replace("-", "");
            SharedRoomPool.refreshOwnAsync(demand, owner, exclude, mode);
        }
    }

    /** One flusher pass for a room — the same order {@link SharedCarriageEvents} keeps. */
    private static void flush(SharedRoomRegistry.Instance inst) {
        if (inst.isCulled() || inst.isCallInFlight()) return;
        if (inst.isOnRelay() && inst.needsRebaseline()) {
            SharedUploadFlow.saveFull(inst, captureOf(inst));
            return;
        }
        if (inst.hasPending()) {
            if (inst.isOnRelay()) {
                SharedUploadFlow.flushDelta(inst, captureOf(inst), inst.drainPending(), false);
            } else {
                submitFresh(inst);
            }
            return;
        }
        if (inst.isOnRelay() && inst.dueForEntityScan(System.currentTimeMillis(), ENTITY_SCAN_INTERVAL_MS)
                && entitiesChanged(inst)) {
            SharedUploadFlow.flushDelta(inst, captureOf(inst), Set.of(), true);
            return;
        }
        if (inst.isOnRelay() && !inst.isAttributed()
                && !games.brennan.dungeontrain.train.SharedCarriagePool.hostUuid().isEmpty()) {
            inst.markAttributed();
            SharedUploadFlow.heartbeat(inst);
            return;
        }
        if (inst.isOnRelay() && System.currentTimeMillis() - inst.lastContactMs() > HEARTBEAT_INTERVAL_MS) {
            SharedUploadFlow.heartbeat(inst);
        }
    }

    /** Upload a fresh, edited room for the first time — gated on a consenting player, as a carriage is. */
    private static void submitFresh(SharedRoomRegistry.Instance inst) {
        ServerPlayer contributor = firstConsentingPlayer(inst.level);
        if (contributor == null) return; // edits stay queued until someone who has consented is present
        Set<BlockPos> covered = inst.drainPending();
        String ownerUuid = contributor.getUUID().toString().replace("-", "");
        String ownerName = contributor.getGameProfile().getName();
        String mode = SharedCarriageMode.current(inst.level);
        SharedUploadFlow.submitFresh(inst, captureOf(inst),
                new SharedUploadFlow.SubmitSpec(PoolLease.KIND_PORTAL_ROOM, inst.roomName,
                        inst.size.getX(), inst.size.getY(), inst.size.getZ()),
                covered, ownerUuid, ownerName, inst.stageId(), mode);
    }

    /**
     * Has a builder changed this room's decor entities since its last upload? A level read of the
     * box's entities; rooms are few and the scan is every 30 s, so the walk is affordable.
     */
    private static boolean entitiesChanged(SharedRoomRegistry.Instance inst) {
        CarriageEntitySnapshot.Captured live = CarriageEntitySnapshot.captureLevel(
                inst.level, inst.roomOrigin(), inst.size, DungeonTrainConfig.getSharedCarriageMaxEntities());
        long sig = CarriageEntitySnapshot.decorFingerprint(live.ents());
        if (!inst.hasEntitySigBaseline()) {
            inst.setEntitySig(sig); // what stands now IS the uploaded state — see the carriage twin
            return false;
        }
        return sig != inst.entitySig();
    }

    private static ServerPlayer firstConsentingPlayer(ServerLevel level) {
        for (ServerPlayer p : level.players()) {
            if (SharedCarriageGate.canContribute(p)) return p;
        }
        return null;
    }

    /** The level-backed reads of one room, as the upload flow wants them. */
    private static SharedUploadFlow.Capture captureOf(SharedRoomRegistry.Instance inst) {
        return new SharedUploadFlow.Capture() {
            @Override
            public SharedUploadFlow.CapturedBlob full() {
                return captureFull(inst);
            }

            @Override
            public SharedUploadFlow.CapturedBlob delta(Set<BlockPos> drained) {
                try {
                    CarriageBlockSnapshot.Captured cap = CarriageBlockSnapshot.captureLevelCells(
                            inst.level, inst.roomOrigin(), inst.size, drained,
                            DungeonTrainConfig.getSharedCarriageMaxEntities());
                    long sig = CarriageEntitySnapshot.decorFingerprint(
                            cap.tag().getList("ents", net.minecraft.nbt.Tag.TAG_COMPOUND));
                    return new SharedUploadFlow.CapturedBlob(CarriageBlockSnapshot.encode(cap.tag()), cap.text(), sig);
                } catch (Throwable t) {
                    LOGGER.debug("[DungeonTrain] shared-room delta capture failed for {}: {}", inst.describe(), t.toString());
                    return null;
                }
            }
        };
    }

    private static SharedUploadFlow.CapturedBlob captureFull(SharedRoomRegistry.Instance inst) {
        try {
            CarriageBlockSnapshot.Captured cap = captureRoom(inst.level, inst.roomOrigin(), inst.size);
            long sig = CarriageEntitySnapshot.decorFingerprint(
                    cap.tag().getList("ents", net.minecraft.nbt.Tag.TAG_COMPOUND));
            return new SharedUploadFlow.CapturedBlob(CarriageBlockSnapshot.encode(cap.tag()), cap.text(), sig);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] shared-room full capture failed for {}: {}", inst.describe(), t.toString());
            return null;
        }
    }

    private static CarriageBlockSnapshot.Captured captureRoom(ServerLevel level, BlockPos origin,
                                                              net.minecraft.core.Vec3i size) {
        return CarriageBlockSnapshot.captureLevel(level, origin, size, level.registryAccess(),
                DungeonTrainConfig.getSharedCarriageMaxEntities());
    }

    // ---- structure hooks (called from PortalCarriageEvents on the server thread) ----

    /**
     * A pair's room has just been stamped. Registers it as a drifting room when it takes part at all:
     * a leased copy always, a fresh template stamp when the room drifts and the pair has a stage the
     * pool could ever match. A pair already registered (a relocation) keeps its record and only moves
     * its origin — the lease and the queued edits belong to the room, not to the site.
     */
    public static void onStructureStamped(ServerLevel level, int pairKey, PortalStructure structure,
                                          CarriageDims dims, String stageId) {
        BlockPos roomOrigin = structure.roomOrigin(dims, PortalCarriageBuilder.layoutFor(dims, structure.kind()));
        SharedRoomRegistry.Instance existing = SharedRoomRegistry.byPair(pairKey);
        if (existing != null) {
            SharedRoomRegistry.relocate(pairKey, roomOrigin);
            return;
        }
        if (!SharedCarriageGate.canDiscover()) return;
        if (!structure.settings().drifts()) return;
        if (games.brennan.dungeontrain.portal.PortalTestSession.isTestStamp(pairKey)) return;
        PortalRoomBlob blob = structure.blob();
        if (blob != null && blob.isLeased()) {
            SharedRoomRegistry.Instance inst = SharedRoomRegistry.register(level, pairKey, structure.roomName(),
                    roomOrigin, structure.roomSize(), true, blob.authoredHere(), blob.owner(),
                    blob.relayId(), blob.token(), blob.seqSeed(), stageId, blob.credits(), blob.deaths());
            inst.stampContact(System.currentTimeMillis()); // fresh lease → no immediate heartbeat needed
            return;
        }
        // A stageless pair can never be shared — the relay refuses a build with no stage — so
        // registering it would only queue edits nobody can be served.
        if (stageId == null || stageId.isEmpty()) return;
        SharedRoomRegistry.register(level, pairKey, structure.roomName(), roomOrigin, structure.roomSize(),
                false, false, "", null, null, 0, stageId, SharedCarriageClient.Credits.EMPTY,
                SharedCarriageClient.Deaths.EMPTY);
    }

    /**
     * The room a relocating pair must re-lay, captured live before its old site is erased — or null
     * when the template will do: a room nobody has edited and nobody lent us is the template.
     *
     * <p>Read while the old blocks are still standing, on the server thread, exactly as a carriage's
     * final capture is. A leased room is always carried: its blocks are the relay's copy plus this
     * world's edits, and neither is on disk here.</p>
     */
    public static PortalRoomBlob captureForRelocation(int pairKey) {
        SharedRoomRegistry.Instance inst = SharedRoomRegistry.byPair(pairKey);
        if (inst == null) return null;
        if (!inst.leasedFromPool && !inst.isBlockEditedThisSession() && !inst.isOnRelay()) return null;
        try {
            CompoundTag snap = captureRoom(inst.level, inst.roomOrigin(), inst.size).tag();
            return PortalRoomBlob.live(snap);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] could not capture drifting room {} before relocation — the template "
                    + "is re-laid and the edits since the last upload are lost here: {}", inst.describe(), t.toString());
            return null;
        }
    }

    /** The pair's structure is gone (evicted). Final flush, hand the lease back, forget the room. */
    public static void onStructureGone(int pairKey) {
        SharedRoomRegistry.Instance inst = SharedRoomRegistry.remove(pairKey);
        if (inst == null) return;
        finalFlushAndReturn(inst, true);
    }

    /** Final flush + lease return for a room about to go away — see the carriage twin. */
    public static boolean finalFlushAndReturn(SharedRoomRegistry.Instance inst, boolean allowCapture) {
        return SharedUploadFlow.finalFlushAndReturn(inst, captureOf(inst), allowCapture);
    }

    /**
     * Hand back every held room lease and the unused buffer — the world crossed between the Free
     * Play and normal pools, or is stopping. The rooms stay standing; they just stop uploading.
     */
    public static int returnAllHeld() {
        int detached = 0;
        for (SharedRoomRegistry.Instance inst : SharedRoomRegistry.all()) {
            if (!inst.isOnRelay()) continue;
            Integer id = inst.relayId();
            String token = inst.leaseToken();
            inst.clearRelayLease();
            if (id != null && token != null) {
                SharedCarriageClient.returnLease(id, token, null, null, 0);
                detached++;
            }
        }
        SharedRoomPool.returnAllBuffered();
        return detached;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (SharedRoomRegistry.Instance inst : SharedRoomRegistry.all()) {
            try {
                finalFlushAndReturn(inst, true);
            } catch (Throwable th) {
                LOGGER.debug("[DungeonTrain] shared-room final return error for {}: {}", inst.describe(), th.toString());
            }
        }
        SharedRoomPool.returnAllBuffered();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SharedRoomRegistry.clear();
        SharedRoomPool.clear();
    }
}
