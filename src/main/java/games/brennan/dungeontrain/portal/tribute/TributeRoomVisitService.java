package games.brennan.dungeontrain.portal.tribute;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.compat.vista.TvBlocks;
import games.brennan.dungeontrain.compat.vista.TvPowerToggle;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.BoardingProgressEvents;
import games.brennan.dungeontrain.event.PortalCarriageEvents;
import games.brennan.dungeontrain.event.RespawnDimensionEvents;
import games.brennan.dungeontrain.event.StartingBookEvents;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TributeRoomSavedPacket;
import games.brennan.dungeontrain.net.TributeRoomVisitStatePacket;
import games.brennan.dungeontrain.net.PortalRoomSkyPacket;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageLayout;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorKind;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.portal.PortalExitCopies;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.portal.PortalRoomTiling;
import games.brennan.dungeontrain.portal.PortalStructure;
import games.brennan.dungeontrain.portal.PortalTestSweep;
import games.brennan.dungeontrain.portal.PortalTwinLanes;
import games.brennan.dungeontrain.portal.PortalTwinRegion;
import games.brennan.dungeontrain.portal.PortalTwinSpace;
import games.brennan.dungeontrain.ship.sable.DeadRiderServerCarry;
import games.brennan.dungeontrain.ship.sable.DeathBodyRagdoll;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.StagePlacementScope;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.mehvahdjukaar.vista.common.tv.TVBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/**
 * The death visit, start to finish: stand the player's tribute room up in its private band, respawn them
 * into it in front of the TV, and when they are done capture the room, save it, sweep the site and
 * send them on — to the train, or (singleplayer) to the next world.
 *
 * <p>The stamp is the portal test rig's ({@code PortalTestCommand.runTestScoped}) with the room's blob
 * in place of its template, so what stands is laid by the same code a drifted room is.</p>
 */
public final class TributeRoomVisitService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TributeRoomVisitService() {}

    /**
     * A room stood ahead of the visit, while the perks menu still shows: everything the stamp needs
     * done, so {@link #begin} only has to move the player in. Discarded if they never come.
     */
    record Prepared(int slot, ResourceKey<Level> deathDim, Vec3 deathPos, PortalStructure structure, BoundingBox window,
                    BoundingBox room, List<BlockPos> screen, Direction facing, TributeRoomCamera.Pose pose,
                    TributeRoomBlob blob, List<BoundingBox> tubes, PortalCarriageLayout layout) {}

    /** What {@link #begin} did: moved them in, will move them in once the room stands, or cannot. */
    public enum BeginResult { STARTED, PENDING, REFUSED }

    /** A site to clear once its visitor has gone; its slot stays taken and its room ticket held until then. */
    private record Sweep(UUID player, int slot, BoundingBox window, long dueTick, String who) {}

    private static final Map<UUID, Prepared> PREPARED = new ConcurrentHashMap<>();
    /** Rooms still being stood up, a step a tick ({@link TributeRoomStandJob}). */
    private static final Map<UUID, TributeRoomStandJob> JOBS = new ConcurrentHashMap<>();
    private static final List<Sweep> SWEEPS = java.util.Collections.synchronizedList(new ArrayList<>());
    /**
     * Bumped when the server stops. The statics outlive a singleplayer world, so a file read that lands
     * after the world is gone must not put anything back.
     */
    private static volatile int epoch;

    /**
     * Start standing {@code dead}'s room now, in the background, so the visit that follows the perks
     * menu opens at once. Idempotent per death. False when no room can stand (then {@link #begin} fails too).
     */
    public static boolean prepare(ServerPlayer dead) {
        MinecraftServer server = dead.getServer();
        if (server == null || server.isHardcore() || !dead.isDeadOrDying()) return false;
        if (PREPARED.containsKey(dead.getUUID()) || JOBS.containsKey(dead.getUUID())) return true;
        // Died inside the room (their own lava, say): the old visit is saved and swept, and a fresh one stands.
        if (TributeRoomVisitSessions.isVisiting(dead.getUUID())) end(dead, false);
        return startJob(server, dead) != null;
    }

    /** A room stood ahead, or still standing, that nobody came to: swept, its tickets let go. */
    public static void discardPrepared(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Prepared prepared = PREPARED.remove(player.getUUID());
        if (prepared != null) {
            TributeRoomVisitTickets.releaseDeathSpot(server.getLevel(prepared.deathDim()), prepared.deathPos(), player.getUUID());
            queueSweep(server, player.getUUID(), prepared.slot(), prepared.window(), player.getName().getString());
            LOGGER.info("[DungeonTrain] tribute room: sweeping the room stood ahead for {}", player.getName().getString());
        }
        TributeRoomStandJob job = JOBS.get(player.getUUID());
        if (job != null) fail(server, job, "nobody came");
    }

    /** Where a visit in {@code slot} stands: its blob-less structure, layout, room box and sweep window. */
    record Site(PortalStructure geometry, PortalCarriageLayout layout, BlockPos roomOrigin, Vec3i roomSize, BoundingBox window) {}

    /** The site of a visit in {@code slot} in this world; null when the room does not fit its basement. No level write. */
    @Nullable
    static Site planSite(ServerLevel overworld, int slot) {
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        Vec3i size = TributeRooms.size(overworld, dims);
        PortalTwinRegion region = PortalTwinSpace.basementOf(overworld);
        Vec3i roomSize = PortalCarriageBuilder.heldInRegion(region, size);
        int structureHeight = Math.max(dims.height(), roomSize.getY());
        int laneFloor = PortalTwinLanes.floorY(region.base());
        if (!roomSize.equals(size) || !PortalTwinLanes.fitsUnderWorld(region.base(), region.ceiling(), laneFloor, structureHeight)) {
            LOGGER.warn("[DungeonTrain] tribute room: {}x{}x{} does not fit this world's basement — no visit",
                size.getX(), size.getY(), size.getZ());
            return null;
        }
        PortalRoomSettings settings = PortalRoomSettings.of(TributeRooms.TEMPLATE);
        int doorHeightOffset = PortalRoomLayout.clampDoorHeightOffset(dims, roomSize.getY(), settings.doorHeightOffset().value());
        PortalStructure geometry = structureAt(TributeRoomVisitSite.originFor(slot, laneFloor, doorHeightOffset),
            roomSize, settings, null);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, geometry.kind());
        BlockPos roomOrigin = geometry.roomOrigin(dims, layout);
        BoundingBox window = TributeRoomCorridors.windowFor(overworld, geometry, dims);
        return new Site(geometry, layout, roomOrigin, roomSize, window);
    }

    /** Whether nothing (a visit, a room standing or stood, a sweep) is using {@code slot} right now. */
    static boolean slotFree(int slot) {
        return !takenSlots().contains(slot);
    }

    /**
     * Plan {@code dead}'s room and start it: slot, window and tickets now, the file on the IO thread, the
     * stamp once the chunks are in ({@link #tick}). Nothing is written to the level here. Null when no
     * room can stand in this world.
     */
    @Nullable
    private static TributeRoomStandJob startJob(MinecraftServer server, ServerPlayer dead) {
        ServerLevel overworld = server.overworld();
        int slot = TributeRoomVisitSite.freeSlot(takenSlots());
        Site site = planSite(overworld, slot);
        if (site == null) return null;
        PortalStructure geometry = site.geometry();
        PortalCarriageLayout layout = site.layout();
        BlockPos roomOrigin = site.roomOrigin();
        Vec3i roomSize = site.roomSize();
        BoundingBox window = site.window();
        UUID id = dead.getUUID();
        String key = TributeRoomKeys.keyFor(overworld, dead);

        // The ticket first: the chunks load (or generate) on the worker threads while the file is read,
        // and the stamp waits for them rather than pulling them in itself.
        TributeRoomVisitTickets.holdRoom(overworld, window, id);
        TributeRoomVisitTickets.holdDeathSpot(dead.serverLevel(), dead.position(), id);
        int myEpoch = epoch;
        TributeRoomStandJob job = new TributeRoomStandJob(id, dead.getName().getString(), myEpoch, slot, key,
            dead.level().dimension(), dead.position(), geometry, layout, roomOrigin, roomSize, window, System.nanoTime());
        JOBS.put(id, job);
        TributeRoomStore.loadAsync(id, key).whenComplete((saved, error) -> server.execute(() -> {
            if (myEpoch != epoch || JOBS.get(id) != job) return;
            if (error != null) {
                LOGGER.warn("[DungeonTrain] tribute room: could not read {}'s room — starting from the default: {}",
                    job.playerName, error.toString());
            }
            job.loaded = saved == null ? java.util.Optional.empty() : saved;
            job.loadedNs = System.nanoTime();
        }));
        return job;
    }

    private static PortalStructure structureAt(BlockPos origin, Vec3i roomSize, PortalRoomSettings settings,
                                               @Nullable games.brennan.dungeontrain.portal.PortalRoomBlob blob) {
        return new PortalStructure(origin, TributeRooms.TEMPLATE, roomSize, settings,
            PortalRoomTiling.base(), PortalExitCopies.NONE, PortalRoomTiling.Tile.BASE, PortalCorridorKind.DEFAULT,
            PortalStructure.NO_SALT, blob);
    }

    /** Every slot a visit, a room stood ahead, a room standing or a sweep still holds. */
    private static Set<Integer> takenSlots() {
        Set<Integer> taken = new java.util.HashSet<>(TributeRoomVisitSessions.takenSlots());
        for (Prepared p : PREPARED.values()) taken.add(p.slot());
        for (TributeRoomStandJob j : JOBS.values()) taken.add(j.slot);
        synchronized (SWEEPS) {
            for (Sweep s : SWEEPS) taken.add(s.slot());
        }
        return taken;
    }

    // ---- the tick --------------------------------------------------------------

    /** Server tick: sweeps that are due, then one step of every room being stood. */
    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty() && SWEEPS.isEmpty() && PREPARED.isEmpty()) return;
        runDueSweeps(server);
        dropRevived(server);
        for (TributeRoomStandJob job : List.copyOf(JOBS.values())) {
            if (JOBS.get(job.player) != job) continue;
            try {
                step(server, job);
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] tribute room: standing {}'s room failed", job.playerName, t);
                fail(server, job, "it threw " + t);
            }
        }
    }

    /**
     * A room standing, or stood, for someone alive again without having come (a revive, a command): let
     * go, so its slot and tickets do not wait for them to log out. A visitor is alive too, but is not here.
     */
    private static void dropRevived(MinecraftServer server) {
        if (JOBS.isEmpty() && PREPARED.isEmpty()) return;
        java.util.Set<UUID> ids = new java.util.HashSet<>(JOBS.keySet());
        ids.addAll(PREPARED.keySet());
        for (UUID id : ids) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.isDeadOrDying() || TributeRoomVisitSessions.isVisiting(id)) continue;
            LOGGER.info("[DungeonTrain] tribute room: {} is alive again without visiting — letting their room go", player.getName().getString());
            discardPrepared(player);
        }
    }

    private static void step(MinecraftServer server, TributeRoomStandJob job) {
        if (job.beginOverdue(System.currentTimeMillis())) {
            fail(server, job, "not ready within " + TributeRoomStandJob.BEGIN_DEADLINE_MS + " ms of Next");
            return;
        }
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        switch (job.phase) {
            case LOADING -> {
                if (job.loaded == null) return;
                ServerPlayer player = server.getPlayerList().getPlayer(job.player);
                if (player == null) {
                    fail(server, job, "they left");
                    return;
                }
                TributeRoomBlob blob = TributeRoomStore.orDefault(overworld, player, dims, job.loaded).orElse(null);
                if (blob == null) {
                    fail(server, job, "no room and no template");
                    return;
                }
                job.blob = blob;
                job.structure = structureAt(job.geometry.origin(), job.roomSize, job.geometry.settings(), blob.toPortalRoomBlob());
                job.phase = TributeRoomStandJob.Phase.CHUNKS;
                stepChunks(overworld, job);
            }
            case CHUNKS -> stepChunks(overworld, job);
            case STAMP_ROOM -> {
                long t0 = System.nanoTime();
                job.laid = true;
                // The room as saved, then (next tick) the visit's own entrance and exit tubes against its
                // doors — not the pair corridors a room gets on the train (TributeRoomCorridors).
                StagePlacementScope.run(PortalCarriageBuilder.stageIdFor(overworld, TributeRooms.PAIR_KEY, dims),
                    () -> PortalCarriageBuilder.stampRoomFromBlob(overworld, job.roomOrigin, job.roomSize,
                        job.structure.blob(), TributeRooms.PAIR_KEY));
                job.stampRoomMs = TributeRoomStandJob.ms(System.nanoTime() - t0);
                job.phase = TributeRoomStandJob.Phase.STAMP_DRESS;
            }
            case STAMP_DRESS -> {
                long t0 = System.nanoTime();
                job.tubes = TributeRoomCorridors.stamp(overworld, job.roomOrigin, job.roomSize, dims);
                TributeRoomHeads.dress(overworld, job.roomOrigin, job.roomSize, job.blob.owner(), job.blob.ownerName());
                TributeRoomFloor.lay(overworld, job.roomOrigin, job.roomSize);
                TvBlocks.tuneTributeTvs(overworld, job.room);
                TributeTvButton.place(overworld, job.room);
                job.stampDressMs = TributeRoomStandJob.ms(System.nanoTime() - t0);
                job.phase = TributeRoomStandJob.Phase.FINISH;
            }
            case FINISH -> finish(server, overworld, job);
        }
    }

    private static void stepChunks(ServerLevel overworld, TributeRoomStandJob job) {
        if (!TributeRoomVisitTickets.ready(overworld, job.window)) {
            long waited = TributeRoomStandJob.ms(System.nanoTime() - job.loadedNs);
            if (!job.slowLogged && waited >= TributeRoomStandJob.SLOW_CHUNKS_LOG_MS) {
                job.slowLogged = true;
                LOGGER.info("[DungeonTrain] tribute room: {}'s room is still waiting on its chunks after {} ms", job.playerName, waited);
            }
            return;
        }
        job.chunksNs = System.nanoTime();
        job.phase = TributeRoomStandJob.Phase.STAMP_ROOM;
    }

    private static void finish(MinecraftServer server, ServerLevel overworld, TributeRoomStandJob job) {
        long t0 = System.nanoTime();
        BoundingBox laidWindow = TributeRoomCorridors.window(job.room, job.tubes);
        if (!laidWindow.equals(job.window)) {
            LOGGER.warn("[DungeonTrain] tribute room: {}'s room laid {} but was ticketed for {}", job.playerName, laidWindow, job.window);
        }
        List<BlockPos> screen = new ArrayList<>();
        Direction facing = screenBlocks(overworld, job.room, screen);
        if (screen.isEmpty()) {
            LOGGER.warn("[DungeonTrain] tribute room: {}'s room has no TV — sweeping it and skipping the visit", job.playerName);
            fail(server, job, "no TV");
            return;
        }
        TributeRoomCamera.Pose pose = arrivalPose(overworld, job.room, screen, facing);
        powerOn(overworld, screen);
        job.finishMs = TributeRoomStandJob.ms(System.nanoTime() - t0);
        Prepared prepared = new Prepared(job.slot, job.deathDim, job.deathPos, job.structure, job.window, job.room,
            List.copyOf(screen), facing, pose, job.blob, job.tubes, job.layout);
        JOBS.remove(job.player, job);
        PREPARED.put(job.player, prepared);
        LOGGER.info("[DungeonTrain] tribute room: stood {}'s room (rev {}) at slot {}, window {} — timings: {}",
            job.playerName, job.blob.revision(), job.slot, job.window, job.timings(System.nanoTime()));
        if (!job.beginRequested) return;
        ServerPlayer player = server.getPlayerList().getPlayer(job.player);
        if (player == null) {
            discardPreparedById(server, job.player, job.playerName);
            return;
        }
        if (begin(player) == BeginResult.REFUSED) DungeonTrainNet.sendTo(player, TributeRoomVisitStatePacket.none());
    }

    /**
     * Give up on {@code job}: tickets let go and anything laid swept (the slot stays taken until it is),
     * and a client that asked for the visit told none is coming.
     */
    private static void fail(MinecraftServer server, TributeRoomStandJob job, String why) {
        if (!JOBS.remove(job.player, job)) return;
        LOGGER.info("[DungeonTrain] tribute room: gave up standing {}'s room ({}) in phase {}, {}/{} chunk(s) in, after {} ms",
            job.playerName, why, job.phase, TributeRoomVisitTickets.loaded(server.overworld(), job.window),
            TributeRoomVisitTickets.waitedOn(job.window), TributeRoomStandJob.ms(System.nanoTime() - job.startedNs));
        TributeRoomVisitTickets.releaseDeathSpot(server.getLevel(job.deathDim), job.deathPos, job.player);
        if (job.laid) {
            queueSweep(server, job.player, job.slot, job.window, job.playerName);
        } else {
            TributeRoomVisitTickets.releaseRoom(server.overworld(), job.window, job.player);
        }
        if (!job.beginRequested) return;
        ServerPlayer player = server.getPlayerList().getPlayer(job.player);
        if (player != null) DungeonTrainNet.sendTo(player, TributeRoomVisitStatePacket.none());
    }

    private static void discardPreparedById(MinecraftServer server, UUID id, String who) {
        Prepared prepared = PREPARED.remove(id);
        if (prepared == null) return;
        TributeRoomVisitTickets.releaseDeathSpot(server.getLevel(prepared.deathDim()), prepared.deathPos(), id);
        queueSweep(server, id, prepared.slot(), prepared.window(), who);
    }

    private static void queueSweep(MinecraftServer server, UUID player, int slot, BoundingBox window, String who) {
        SWEEPS.add(new Sweep(player, slot, window, server.getTickCount() + 1L, who));
    }

    /** Clear each site whose visitor has gone, then let go of its chunks. A tick after they left, never inside it. */
    private static void runDueSweeps(MinecraftServer server) {
        List<Sweep> due = new ArrayList<>();
        synchronized (SWEEPS) {
            java.util.Iterator<Sweep> it = SWEEPS.iterator();
            while (it.hasNext()) {
                Sweep s = it.next();
                if (s.dueTick() <= server.getTickCount()) {
                    due.add(s);
                    it.remove();
                }
            }
        }
        ServerLevel overworld = server.overworld();
        for (Sweep s : due) {
            long t0 = System.nanoTime();
            int cleared = PortalClear.clearBox(overworld, s.window(), PortalCorridorMask.NONE);
            int removed = PortalTestSweep.discardMobs(overworld, s.window());
            TributeRoomVisitTickets.releaseRoom(overworld, s.window(), s.player());
            LOGGER.info("[DungeonTrain] tribute room: swept {}'s site at slot {} — {} block(s), {} mob(s), {} ms",
                s.who(), s.slot(), cleared, removed, TributeRoomStandJob.ms(System.nanoTime() - t0));
        }
    }

    // ---- the visit -------------------------------------------------------------

    /**
     * Stand {@code dead} in their tribute room: at once when it stood ahead, else as soon as it does
     * ({@link BeginResult#PENDING}; the server answers when it is in, or tells the client none is coming).
     * {@link BeginResult#REFUSED} when the visit cannot start (hardcore, no template, the room does not
     * fit this world), in which case the caller tells the client to fall back to the full-screen recap.
     */
    public static BeginResult begin(ServerPlayer dead) {
        MinecraftServer server = dead.getServer();
        if (server == null || server.isHardcore()) return BeginResult.REFUSED;
        if (!dead.isDeadOrDying()) {
            LOGGER.info("[DungeonTrain] tribute room: {} asked for a visit while alive — ignored", dead.getName().getString());
            discardPrepared(dead);
            return BeginResult.REFUSED;
        }
        Prepared p = PREPARED.remove(dead.getUUID());
        if (p != null) {
            moveIn(server, dead, p);
            return BeginResult.STARTED;
        }
        TributeRoomStandJob job = JOBS.get(dead.getUUID());
        if (job == null) {
            // Nothing stood ahead (an older client, or the prepare failed): stand it now.
            if (TributeRoomVisitSessions.isVisiting(dead.getUUID())) end(dead, false);
            job = startJob(server, dead);
            if (job == null) return BeginResult.REFUSED;
        }
        job.requestBegin(System.currentTimeMillis());
        LOGGER.info("[DungeonTrain] tribute room: {} pressed Next while their room is still standing ({}) — moving them in when it is",
            dead.getName().getString(), job.phase);
        return BeginResult.PENDING;
    }

    private static void moveIn(MinecraftServer server, ServerPlayer dead, Prepared p) {
        ServerLevel overworld = server.overworld();
        DungeonTrainWorldData worldData = DungeonTrainWorldData.get(overworld);
        CarriageDims dims = worldData.dims();
        TributeRoomCamera.Pose pose = p.pose();

        boolean keepInventory = overworld.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
        List<TributeRoomDeathKit.Entry> kit = TributeRoomDeathKit.take(dead.getUUID(), keepInventory);
        GameType previous = dead.gameMode.getGameModeForPlayer();
        UUID trainId = nearestTrain(dead.serverLevel(), dead.position());
        TributeRoomVisitSessions.put(dead.getUUID(), new TributeRoomVisitSessions.Session(p.slot(), p.deathDim(),
            p.deathPos(), p.structure(), p.window(), p.room(), p.screen(), pose, p.blob(), trainId, !kit.isEmpty(),
            previous, overworld.getGameTime()));

        // Alive again, in the room. The session is registered first so every respawn listener can see
        // this is a visit (RespawnDimensionEvents, StartingBookEvents) and the body's pins let go.
        DeathBodyRagdoll.release(dead);
        DeadRiderServerCarry.release(dead);
        ServerPlayer fresh = server.getPlayerList().respawn(dead, false, Entity.RemovalReason.KILLED);
        // Vanilla's respawn handler points the connection at the new player itself; done here or the
        // teleport below moves the dead body and every packet keeps landing on it.
        fresh.connection.player = fresh;
        if (fresh.isPassenger()) fresh.stopRiding();
        fresh.teleportTo(overworld, pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
        fresh.resetFallDistance();
        // Re-centre their chunk view on the room now, not next tick: the chunks below are sent
        // against the view, and a client whose view still sits at the death spot drops every one.
        overworld.getChunkSource().move(fresh);
        LOGGER.info("[DungeonTrain] tribute room: chunk tracking for {} — chunkPos={} lastSection={} view={}",
            fresh.getName().getString(), fresh.chunkPosition(), fresh.getLastSectionPos(), fresh.getChunkTrackingView());
        if (previous == GameType.ADVENTURE || previous == GameType.SPECTATOR) fresh.setGameMode(GameType.SURVIVAL);
        giveKit(fresh, kit);

        resendWindow(server, overworld, fresh, p.window());
        DungeonTrainNet.sendTo(fresh, TributeRoomVisitStatePacket.active(boxOf(p.screen()), p.facing(), p.tubes()));
        PortalCarriageEvents.sendRoomAmbience(dims, p.layout(), p.structure(), worldData.getTrainY(), List.of(fresh));

        LOGGER.info("[DungeonTrain] tribute room: stamped {}'s room (rev {}) at {} slot {} — standing at ({}, {}, {}) facing the TV, kit {} item(s), train {}",
            fresh.getName().getString(), p.blob().revision(), p.structure().origin(), p.slot(),
            String.format("%.1f", pose.x()), String.format("%.1f", pose.y()), String.format("%.1f", pose.z()),
            kit.size(), trainId);
    }

    /**
     * Each kit stack back in the slot it was carried in (armour worn, offhand held, hotbar as it was);
     * all slotted ones first so a loose stack cannot take an armour or offhand slot, then the rest
     * wherever they fit.
     */
    private static void giveKit(ServerPlayer player, List<TributeRoomDeathKit.Entry> kit) {
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        List<ItemStack> loose = new java.util.ArrayList<>();
        for (TributeRoomDeathKit.Entry e : kit) {
            int slot = e.slot();
            if (slot >= 0 && slot < inv.getContainerSize() && inv.getItem(slot).isEmpty()) inv.setItem(slot, e.stack());
            else loose.add(e.stack());
        }
        for (ItemStack stack : loose) inv.placeItemBackInInventory(stack);
    }

    /**
     * The client pressed Board anew. Saves, sweeps, and either places them on the train or hands the
     * run's end back. The client hears once the room is on disk: in singleplayer it leaves the world on that.
     */
    public static void endFromClient(ServerPlayer player) {
        if (!TributeRoomVisitSessions.isVisiting(player.getUUID())) return;
        MinecraftServer server = player.getServer();
        boolean newWorld = server != null && server.isSingleplayer() && server.isSingleplayerOwner(player.getGameProfile());
        java.util.concurrent.CompletableFuture<TributeRoomBlob> saving = end(player, !newWorld);
        if (server == null) return;
        int myEpoch = epoch;
        saving.whenComplete((saved, error) -> server.execute(() -> {
            if (myEpoch != epoch || player.hasDisconnected()) return;
            DungeonTrainNet.sendTo(player, new TributeRoomSavedPacket(newWorld));
        }));
    }

    /**
     * End {@code player}'s visit: capture the room and queue its save, clear the kit, and
     * ({@code placeOnTrain}) put them on the train. The site is swept a tick later, once they are out of
     * it. Safe when they are not visiting. Completes, off the server thread, with the room saved, or null.
     */
    public static java.util.concurrent.CompletableFuture<TributeRoomBlob> end(ServerPlayer player, boolean placeOnTrain) {
        TributeRoomVisitSessions.Session session = TributeRoomVisitSessions.take(player.getUUID());
        if (session == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        MinecraftServer server = player.getServer();
        if (server == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        ServerLevel overworld = server.overworld();
        DungeonTrainWorldData worldData = DungeonTrainWorldData.get(overworld);
        CarriageDims dims = worldData.dims();

        long t0 = System.nanoTime();
        java.util.concurrent.CompletableFuture<TributeRoomBlob> saving = captureAndSave(overworld, player, session);
        long captureMs = TributeRoomStandJob.ms(System.nanoTime() - t0);
        if (session.kitGiven()) player.getInventory().clearContent();
        TributeRoomVisitTickets.releaseDeathSpot(server.getLevel(session.deathDim()), session.deathPos(), player.getUUID());
        if (player.gameMode.getGameModeForPlayer() != session.previousMode()
                && session.previousMode() != GameType.ADVENTURE && session.previousMode() != GameType.SPECTATOR) {
            player.setGameMode(session.previousMode());
        }
        DungeonTrainNet.sendTo(player, TributeRoomVisitStatePacket.none());
        DungeonTrainNet.sendTo(player, PortalRoomSkyPacket.none());

        boolean placed = false;
        if (placeOnTrain) {
            placed = RespawnDimensionEvents.placeAfterTributeRoom(player);
            if (placed) {
                StartingBookEvents.strikeAfterTributeRoom(player);
                BoardingProgressEvents.stampLifeOrigin(player);
            }
        }
        // The room's ticket is let go by the sweep, after the clear, so its chunks are still in when it runs.
        queueSweep(server, player.getUUID(), session.slot(), session.window(), player.getName().getString());
        // The clear pass of the ambience: with the player outside the box it lifts the room's fog and sky.
        PortalCarriageEvents.sendRoomAmbience(dims, PortalCarriageBuilder.layoutFor(dims, session.structure().kind()),
            session.structure(), worldData.getTrainY(), List.of(player));
        LOGGER.info("[DungeonTrain] tribute room: {} left — capture {} ms, save queued from rev {}, {}",
            player.getName().getString(), captureMs, session.blob().revision(),
            placed ? "placed on the train" : placeOnTrain ? "no train to place on" : "run ends on the client");
        return saving;
    }

    /** Server stopping: nothing stood ahead, or still standing, survives it, and nothing late lands after. */
    public static void clearPrepared() {
        epoch++;
        PREPARED.clear();
        JOBS.clear();
        SWEEPS.clear();
    }

    /** Server stopping: save every standing visit's room and wait for every save to land; nothing is swept (the world is going). */
    public static void saveAll(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        for (Map.Entry<UUID, TributeRoomVisitSessions.Session> entry : TributeRoomVisitSessions.entries()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) captureAndSave(overworld, player, entry.getValue());
        }
        TributeRoomStore.flush();
    }

    /**
     * Capture the room as it stands (server thread: it reads the level) and queue its save (IO thread).
     * Completes with the room saved, or null when nothing was. The TV must still be there.
     */
    static java.util.concurrent.CompletableFuture<TributeRoomBlob> captureAndSave(ServerLevel overworld, ServerPlayer player, TributeRoomVisitSessions.Session session) {
        BoundingBox room = session.room();
        List<BlockPos> screen = new ArrayList<>();
        screenBlocks(overworld, room, screen);
        if (screen.isEmpty()) {
            LOGGER.warn("[DungeonTrain] tribute room: {}'s room has lost its TV — keeping the last save", player.getName().getString());
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        CompoundTag snapshot;
        // The TV is saved with an empty slot: whoever stands the room tunes it (TvBlocks.tuneTributeTvs),
        // and a cassette in the save is a cassette that can go stale or be the wrong one. The slots are
        // emptied for the capture only and put back, since a visit may still be running (saveAll).
        java.util.Map<BlockPos, net.minecraft.world.item.ItemStack> held = new java.util.HashMap<>();
        for (BlockPos pos : screen) {
            if (overworld.getBlockEntity(pos) instanceof net.mehvahdjukaar.vista.common.tv.TVBlockEntity tv && !tv.getItem(0).isEmpty()) {
                held.put(pos.immutable(), tv.removeItemNoUpdate(0));
            }
        }
        try {
            snapshot = CarriageBlockSnapshot.captureLevel(overworld, new BlockPos(room.minX(), room.minY(), room.minZ()),
                new Vec3i(room.getXSpan(), room.getYSpan(), room.getZSpan()), overworld.registryAccess(),
                DungeonTrainConfig.getSharedCarriageMaxEntities()).tag();
        } catch (Throwable t) {
            held.forEach((pos, stack) -> {
                if (overworld.getBlockEntity(pos) instanceof net.mehvahdjukaar.vista.common.tv.TVBlockEntity tv) tv.setItem(0, stack);
            });
            LOGGER.warn("[DungeonTrain] tribute room: could not capture {}'s room — keeping the last save: {}",
                player.getName().getString(), t.toString());
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        held.forEach((pos, stack) -> {
            if (overworld.getBlockEntity(pos) instanceof net.mehvahdjukaar.vista.common.tv.TVBlockEntity tv) tv.setItem(0, stack);
        });
        TributeRoomBlob next = session.blob().saved(snapshot, player.getGameProfile().getName(), System.currentTimeMillis(),
            TributeRoomKeys.keyFor(overworld, player));
        // The death this visit followed rides with the room, so a tribute TV on the train can replay it.
        LastDeathReports.Report report = LastDeathReports.peek(player.getUUID());
        if (report != null) next = next.withReport(report.bytes(), report.deathMessage(), report.atMs());
        TributeRoomBlob saving = next;
        return TributeRoomStore.saveAsync(saving).thenApply(ok -> ok ? saving : null);
    }

    // ---- helpers ---------------------------------------------------------------

    /**
     * Ship the window's chunks to the visitor whole, two ticks on: a stamp writes straight into the
     * chunk, and a chunk the client already holds (or receives from a copy taken before the stamp)
     * would otherwise show the room as it was. The same packet {@code WorldUpsideDownEvents} resends
     * a mirrored chunk with.
     */
    private static void resendWindow(MinecraftServer server, ServerLevel level, ServerPlayer player, BoundingBox window) {
        server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 2, () -> {
            if (!player.isAlive() || player.hasDisconnected()) return;
            int sent = 0;
            for (int cx = window.minX() >> 4; cx <= window.maxX() >> 4; cx++) {
                for (int cz = window.minZ() >> 4; cz <= window.maxZ() >> 4; cz++) {
                    net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) continue;
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                        chunk, level.getLightEngine(), null, null));
                    sent++;
                }
            }
            LOGGER.info("[DungeonTrain] tribute room: resent {} chunk(s) of the room to {}", sent, player.getName().getString());
        }));
    }

    /** Every TV block in {@code room}, collected into {@code out}; the direction the screen faces (NORTH when none). */
    public static Direction screenBlocks(ServerLevel level, BoundingBox room, List<BlockPos> out) {
        Direction facing = Direction.NORTH;
        for (BlockPos pos : BlockPos.betweenClosed(room.minX(), room.minY(), room.minZ(), room.maxX(), room.maxY(), room.maxZ())) {
            BlockState state = level.getBlockState(pos);
            if (!TvBlocks.isTelevision(state)) continue;
            out.add(pos.immutable());
            if (state.hasProperty(HorizontalDirectionalBlock.FACING)) facing = state.getValue(HorizontalDirectionalBlock.FACING);
        }
        return facing;
    }

    /**
     * Where a visitor to the tribute room in {@code room} arrives: on the red carpet before its TV,
     * looking at the screen — or null when the room has no TV. The death visit and the editor's test of
     * the room both stand people here.
     */
    @Nullable
    public static TributeRoomCamera.Pose arrivalPose(ServerLevel level, BoundingBox room) {
        List<BlockPos> screen = new ArrayList<>();
        Direction facing = screenBlocks(level, room, screen);
        return screen.isEmpty() ? null : arrivalPose(level, room, screen, facing);
    }

    private static TributeRoomCamera.Pose arrivalPose(ServerLevel level, BoundingBox room, List<BlockPos> screen, Direction facing) {
        return standable(level, TributeRoomCamera.facing(screen, facing, room.minY() + 1), room);
    }

    public static BoundingBox boxOf(List<BlockPos> blocks) {
        BoundingBox box = new BoundingBox(blocks.get(0));
        for (BlockPos p : blocks) box.encapsulate(p);
        return box;
    }

    /** Switch the screen on if the stamp left it off — the latch the TV mixin keeps takes it from there. */
    private static void powerOn(ServerLevel level, List<BlockPos> screen) {
        for (BlockPos pos : screen) {
            BlockState state = level.getBlockState(pos);
            if (!state.hasProperty(TVBlock.POWER_STATE)) continue;
            if (state.getValue(TVBlock.POWER_STATE).isOn()) return;
            TvPowerToggle.toggle(level, pos);
            return;
        }
    }

    /**
     * {@code pose} lifted or dropped to a cell the player can stand in: the lowest row in its column with
     * two air cells over something solid, searched from the room floor up. A room whose owner built a
     * table where they stand still has somewhere to stand.
     */
    private static TributeRoomCamera.Pose standable(ServerLevel level, TributeRoomCamera.Pose pose, BoundingBox room) {
        int x = (int) Math.floor(pose.x()), z = (int) Math.floor(pose.z());
        for (int y = room.minY() + 1; y < room.maxY() - 1; y++) {
            BlockPos feet = new BlockPos(x, y, z);
            if (!level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
                    && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                return new TributeRoomCamera.Pose(pose.x(), y, pose.z(), pose.yaw(), pose.pitch());
            }
        }
        return pose;
    }

    /** The train nearest where they died, or null when none is within reach — {@code DeathSceneTargets}' own rule. */
    @Nullable
    private static UUID nearestTrain(ServerLevel level, Vec3 deathPos) {
        UUID best = null;
        double bestDist = 64.0 * 64.0;
        for (Map.Entry<UUID, List<Trains.Carriage>> train : Trains.byTrainId(level).entrySet()) {
            for (Trains.Carriage c : train.getValue()) {
                AABBdc box = c.ship().worldAABB();
                double dx = Math.max(Math.max(box.minX() - deathPos.x, 0.0), deathPos.x - box.maxX());
                double dy = Math.max(Math.max(box.minY() - deathPos.y, 0.0), deathPos.y - box.maxY());
                double dz = Math.max(Math.max(box.minZ() - deathPos.z, 0.0), deathPos.z - box.maxZ());
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestDist) {
                    bestDist = d;
                    best = train.getKey();
                }
            }
        }
        return best;
    }
}
