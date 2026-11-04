package games.brennan.dungeontrain.portal.home;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.compat.vista.TvBlocks;
import games.brennan.dungeontrain.compat.vista.TvPowerToggle;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.BoardingProgressEvents;
import games.brennan.dungeontrain.event.PortalCarriageEvents;
import games.brennan.dungeontrain.event.RespawnDimensionEvents;
import games.brennan.dungeontrain.event.StartingBookEvents;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.HomeRoomSavedPacket;
import games.brennan.dungeontrain.net.HomeRoomVisitStatePacket;
import games.brennan.dungeontrain.net.PortalRoomSkyPacket;
import games.brennan.dungeontrain.player.DifficultyPartition;
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
import java.util.Map;
import java.util.UUID;

/**
 * The death visit, start to finish: stand the player's home room up in its private band, respawn them
 * into it in front of the TV, and when they are done capture the room, save it, sweep the site and
 * send them on — to the train, or (singleplayer) to the next world.
 *
 * <p>The stamp is the portal test rig's ({@code PortalTestCommand.runTestScoped}) with the room's blob
 * in place of its template, so what stands is laid by the same code a drifted room is.</p>
 */
public final class HomeRoomVisitService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private HomeRoomVisitService() {}

    /**
     * Stand {@code dead} in their home room. {@code false} when the visit cannot start (hardcore, no
     * template, the room does not fit this world), in which case nothing was changed and the client is
     * told to fall back to the full-screen recap.
     */
    public static boolean begin(ServerPlayer dead) {
        MinecraftServer server = dead.getServer();
        if (server == null || server.isHardcore()) return false;
        if (!dead.isDeadOrDying()) {
            LOGGER.info("[DungeonTrain] home room: {} asked for a visit while alive — ignored", dead.getName().getString());
            return false;
        }
        // Died inside the room (their own lava, say): the old visit is saved and swept, and a fresh one stands.
        if (HomeRoomVisitSessions.isVisiting(dead.getUUID())) end(dead, false);

        ServerLevel overworld = server.overworld();
        DungeonTrainWorldData worldData = DungeonTrainWorldData.get(overworld);
        CarriageDims dims = worldData.dims();

        HomeRoomBlob blob = HomeRoomStore.loadOrDefault(overworld, dead, dims).orElse(null);
        if (blob == null) return false;
        Vec3i size = HomeRooms.size(overworld, dims);
        PortalTwinRegion region = PortalTwinSpace.basementOf(overworld);
        Vec3i roomSize = PortalCarriageBuilder.heldInRegion(region, size);
        int structureHeight = Math.max(dims.height(), roomSize.getY());
        int laneFloor = PortalTwinLanes.floorY(region.base());
        if (!roomSize.equals(size) || !PortalTwinLanes.fitsUnderWorld(region.base(), region.ceiling(), laneFloor, structureHeight)) {
            LOGGER.warn("[DungeonTrain] home room: {}x{}x{} does not fit this world's basement — no visit",
                size.getX(), size.getY(), size.getZ());
            return false;
        }

        PortalRoomSettings settings = PortalRoomSettings.of(HomeRooms.TEMPLATE);
        int doorHeightOffset = PortalRoomLayout.clampDoorHeightOffset(dims, roomSize.getY(), settings.doorHeightOffset().value());
        int slot = HomeRoomVisitSite.freeSlot(HomeRoomVisitSessions.takenSlots());
        PortalStructure structure = new PortalStructure(
            HomeRoomVisitSite.originFor(slot, laneFloor, doorHeightOffset), HomeRooms.TEMPLATE, roomSize, settings,
            PortalRoomTiling.base(), PortalExitCopies.NONE, PortalRoomTiling.Tile.BASE, PortalCorridorKind.DEFAULT,
            PortalStructure.NO_SALT, blob.toPortalRoomBlob());
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        BoundingBox room = BoundingBox.fromCorners(roomOrigin, roomOrigin.offset(roomSize.getX() - 1, roomSize.getY() - 1, roomSize.getZ() - 1));

        // The room as saved, then the visit's own entrance and exit tubes against its doors — not the
        // pair corridors a room gets on the train (HomeRoomCorridors).
        StagePlacementScope.run(PortalCarriageBuilder.stageIdFor(overworld, HomeRooms.PAIR_KEY, dims),
            () -> PortalCarriageBuilder.stampRoomFromBlob(overworld, roomOrigin, roomSize, structure.blob(), HomeRooms.PAIR_KEY));
        List<BoundingBox> tubes = HomeRoomCorridors.stamp(overworld, roomOrigin, roomSize, dims);
        HomeRoomHeads.dress(overworld, roomOrigin, roomSize, blob.owner(), blob.ownerName());
        BoundingBox window = HomeRoomCorridors.window(room, tubes);

        List<BlockPos> screen = new ArrayList<>();
        Direction facing = screenBlocks(overworld, room, screen);
        if (screen.isEmpty()) {
            LOGGER.warn("[DungeonTrain] home room: {}'s room has no TV — sweeping it and skipping the visit", dead.getName().getString());
            PortalClear.clearBox(overworld, window, PortalCorridorMask.NONE);
            return false;
        }
        HomeRoomCamera.Pose pose = HomeRoomCamera.facing(screen, facing, roomOrigin.getY() + 1);
        pose = standable(overworld, pose, room);

        boolean keepInventory = overworld.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
        List<ItemStack> kit = HomeRoomDeathKit.take(dead.getUUID(), keepInventory);
        GameType previous = dead.gameMode.getGameModeForPlayer();
        UUID trainId = nearestTrain(dead.serverLevel(), dead.position());
        HomeRoomVisitSessions.put(dead.getUUID(), new HomeRoomVisitSessions.Session(slot, dead.level().dimension(),
            dead.position(), structure, window, room, List.copyOf(screen), pose, blob, trainId, !kit.isEmpty(),
            previous, overworld.getGameTime()));
        HomeRoomVisitTickets.hold(overworld, window, dead.serverLevel(), dead.position(), dead.getUUID());

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
        LOGGER.info("[DungeonTrain] home room: chunk tracking for {} — chunkPos={} lastSection={} view={}",
            fresh.getName().getString(), fresh.chunkPosition(), fresh.getLastSectionPos(), fresh.getChunkTrackingView());
        if (previous == GameType.ADVENTURE || previous == GameType.SPECTATOR) fresh.setGameMode(GameType.SURVIVAL);
        for (ItemStack stack : kit) fresh.getInventory().placeItemBackInInventory(stack);

        powerOn(overworld, screen);
        resendWindow(server, overworld, fresh, window);
        DungeonTrainNet.sendTo(fresh, HomeRoomVisitStatePacket.active(boxOf(screen), facing, tubes));
        PortalCarriageEvents.sendRoomAmbience(dims, layout, structure, worldData.getTrainY(), List.of(fresh));

        LOGGER.info("[DungeonTrain] home room: stamped {}'s room (rev {}) at {} slot {} — standing at ({}, {}, {}) facing the TV, kit {} item(s), train {}",
            fresh.getName().getString(), blob.revision(), structure.origin(), slot,
            String.format("%.1f", pose.x()), String.format("%.1f", pose.y()), String.format("%.1f", pose.z()),
            kit.size(), trainId);
        return true;
    }

    /** The client pressed Board anew. Saves, sweeps, and either places them on the train or hands the run's end back. */
    public static void endFromClient(ServerPlayer player) {
        if (!HomeRoomVisitSessions.isVisiting(player.getUUID())) return;
        MinecraftServer server = player.getServer();
        boolean newWorld = server != null && server.isSingleplayer() && server.isSingleplayerOwner(player.getGameProfile());
        end(player, !newWorld);
        DungeonTrainNet.sendTo(player, new HomeRoomSavedPacket(newWorld));
    }

    /**
     * End {@code player}'s visit: capture and save the room, clear the kit, sweep the site, and
     * ({@code placeOnTrain}) put them on the train. Safe when they are not visiting.
     */
    public static void end(ServerPlayer player, boolean placeOnTrain) {
        HomeRoomVisitSessions.Session session = HomeRoomVisitSessions.take(player.getUUID());
        if (session == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        DungeonTrainWorldData worldData = DungeonTrainWorldData.get(overworld);
        CarriageDims dims = worldData.dims();

        HomeRoomBlob saved = captureAndSave(overworld, player, session);
        if (session.kitGiven()) player.getInventory().clearContent();
        HomeRoomVisitTickets.release(overworld, session.window(), server.getLevel(session.deathDim()), session.deathPos(), player.getUUID());
        if (player.gameMode.getGameModeForPlayer() != session.previousMode()
                && session.previousMode() != GameType.ADVENTURE && session.previousMode() != GameType.SPECTATOR) {
            player.setGameMode(session.previousMode());
        }
        DungeonTrainNet.sendTo(player, HomeRoomVisitStatePacket.none());
        DungeonTrainNet.sendTo(player, PortalRoomSkyPacket.none());

        boolean placed = false;
        if (placeOnTrain) {
            placed = RespawnDimensionEvents.placeAfterHomeRoom(player);
            if (placed) {
                StartingBookEvents.strikeAfterHomeRoom(player);
                BoardingProgressEvents.stampLifeOrigin(player);
            }
        }
        int cleared = PortalClear.clearBox(overworld, session.window(), PortalCorridorMask.NONE);
        int removed = PortalTestSweep.discardMobs(overworld, session.window());
        // The clear pass of the ambience: with the player outside the box it lifts the room's fog and sky.
        PortalCarriageEvents.sendRoomAmbience(dims, PortalCarriageBuilder.layoutFor(dims, session.structure().kind()),
            session.structure(), worldData.getTrainY(), List.of(player));
        LOGGER.info("[DungeonTrain] home room: {} left — saved rev={} ({}), cleared {} block(s) and {} mob(s), {}",
            player.getName().getString(), saved == null ? session.blob().revision() : saved.revision(),
            saved == null ? "not saved" : "saved", cleared, removed,
            placed ? "placed on the train" : placeOnTrain ? "no train to place on" : "run ends on the client");
    }

    /** Server stopping: save every standing visit's room; nothing is swept (the world is going). */
    public static void saveAll(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        for (Map.Entry<UUID, HomeRoomVisitSessions.Session> entry : HomeRoomVisitSessions.entries()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) captureAndSave(overworld, player, entry.getValue());
        }
    }

    /** Capture the room as it stands and save it; null when nothing was saved. The TV must still be there. */
    @Nullable
    static HomeRoomBlob captureAndSave(ServerLevel overworld, ServerPlayer player, HomeRoomVisitSessions.Session session) {
        BoundingBox room = session.room();
        List<BlockPos> screen = new ArrayList<>();
        screenBlocks(overworld, room, screen);
        if (screen.isEmpty()) {
            LOGGER.warn("[DungeonTrain] home room: {}'s room has lost its TV — keeping the last save", player.getName().getString());
            return null;
        }
        CompoundTag snapshot;
        try {
            snapshot = CarriageBlockSnapshot.captureLevel(overworld, new BlockPos(room.minX(), room.minY(), room.minZ()),
                new Vec3i(room.getXSpan(), room.getYSpan(), room.getZSpan()), overworld.registryAccess(),
                DungeonTrainConfig.getSharedCarriageMaxEntities()).tag();
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] home room: could not capture {}'s room — keeping the last save: {}",
                player.getName().getString(), t.toString());
            return null;
        }
        HomeRoomBlob next = session.blob().saved(snapshot, player.getGameProfile().getName(), System.currentTimeMillis(),
            DifficultyPartition.keyFor(overworld));
        // The death this visit followed rides with the room, so a home TV on the train can replay it.
        LastDeathReports.Report report = LastDeathReports.peek(player.getUUID());
        if (report != null) next = next.withReport(report.bytes(), report.deathMessage(), report.atMs());
        return HomeRoomStore.save(next) ? next : null;
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
            LOGGER.info("[DungeonTrain] home room: resent {} chunk(s) of the room to {}", sent, player.getName().getString());
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
    private static HomeRoomCamera.Pose standable(ServerLevel level, HomeRoomCamera.Pose pose, BoundingBox room) {
        int x = (int) Math.floor(pose.x()), z = (int) Math.floor(pose.z());
        for (int y = room.minY() + 1; y < room.maxY() - 1; y++) {
            BlockPos feet = new BlockPos(x, y, z);
            if (!level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
                    && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                return new HomeRoomCamera.Pose(pose.x(), y, pose.z(), pose.yaw(), pose.pitch());
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
