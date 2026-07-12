package games.brennan.dungeontrain.event;

import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.SubLevelPlayerChunkSender;

import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;

import java.util.function.Consumer;
public final class TrainCinematographerEvents {

    private static int tickCounter = 0;
    private static final int SCAN_PERIOD_TICKS = 2;

    private TrainCinematographerEvents() {}

        public static void onLevelTick(net.minecraft.world.level.Level tickedLevel) {
        if (!(tickedLevel instanceof ServerLevel level)) return;
        if (Math.floorMod(tickCounter++, SCAN_PERIOD_TICKS) != 0) return;

        for (ServerPlayer player : level.players()) {
            if (!CinematographerService.isActive(player.getUUID())) continue;
            int r = (int) CinematographerService.getDistance(player.getUUID());
            openNearbyDoors(level, player.blockPosition(), r);
            openNearbyDoorsOnShips(level, player, r);
            if (CinematographerService.isClearView(player.getUUID())) {
                CinematographerClearView.clearViewAhead(
                    level, player, CinematographerService.getClearViewDistance(player.getUUID()));
            }
        }
    }

        public static void onPlayerLoggedOut(net.minecraft.world.entity.player.Player leftPlayer) {
        if (leftPlayer instanceof ServerPlayer player) {
            CinematographerClearView.restoreAll(player);
            CinematographerService.cleanup(player.getUUID());
        }
    }

    /**
     * Opens doors on Sable sub-level ships near the player.
     *
     * EmbeddedPlotLevelAccessor.getBlockState/setBlock both delegate to the HOST
     * level (adding a fixed center offset), so they always return AIR for DT's
     * trains (loaded from NBT, never placed in the world). Instead we go directly
     * through the plot's LevelChunk storage — the same approach SoulCampfireHealEvents
     * uses to read sub-level block data. After writing, we resend the affected chunk
     * via SubLevelPlayerChunkSender so the client sub-level stays in sync.
     */
    private static void openNearbyDoorsOnShips(ServerLevel level, ServerPlayer player, int r) {
        double px = player.getX(), py = player.getY(), pz = player.getZ();

        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            AABBdc bb = ship.worldAABB();
            if (px < bb.minX() - r || px > bb.maxX() + r) continue;
            if (py < bb.minY() - r || py > bb.maxY() + r) continue;
            if (pz < bb.minZ() - r || pz > bb.maxZ() + r) continue;
            if (!(ship instanceof SableManagedShip sableShip)) continue;

            Vector3d local = new Vector3d(px, py, pz);
            ship.worldToShip(local);
            BlockPos shipCenter = BlockPos.containing(local.x, local.y, local.z);

            LevelPlot plot = sableShip.subLevel().getPlot();
            Consumer<net.minecraft.network.protocol.Packet<? super ClientGamePacketListener>> sender =
                    packet -> player.connection.send(packet);

            for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                if (chunk == null) continue;

                ChunkPos cp = chunk.getPos();
                // Skip chunks whose XZ extent doesn't overlap the scan radius
                if (cp.getMinBlockX() + 16 < shipCenter.getX() - r || cp.getMinBlockX() > shipCenter.getX() + r) continue;
                if (cp.getMinBlockZ() + 16 < shipCenter.getZ() - r || cp.getMinBlockZ() > shipCenter.getZ() + r) continue;

                int startX = Math.max(shipCenter.getX() - r, cp.getMinBlockX());
                int endX   = Math.min(shipCenter.getX() + r, cp.getMaxBlockX());
                int startZ = Math.max(shipCenter.getZ() - r, cp.getMinBlockZ());
                int endZ   = Math.min(shipCenter.getZ() + r, cp.getMaxBlockZ());
                int startY = shipCenter.getY() - r;
                int endY   = shipCenter.getY() + r;

                boolean chunkDirty = false;
                for (BlockPos pos : BlockPos.betweenClosed(startX, startY, startZ, endX, endY, endZ)) {
                    BlockState state = chunk.getBlockState(pos);
                    // Only real doors — trapdoors, fence gates and barrels also carry OPEN.
                    if (!(state.getBlock() instanceof DoorBlock)) continue;
                    if (state.getValue(BlockStateProperties.OPEN)) continue;

                    if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                        if (state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) continue;
                        BlockPos lowerPos = pos.immutable();
                        chunk.setBlockState(lowerPos, state.setValue(BlockStateProperties.OPEN, true), false);
                        // Upper half is always in the same LevelChunk (same X,Z column)
                        BlockPos upperPos = lowerPos.above();
                        BlockState upper = chunk.getBlockState(upperPos);
                        if (upper.is(state.getBlock())
                                && upper.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                                && upper.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                                && upper.hasProperty(BlockStateProperties.OPEN)
                                && !upper.getValue(BlockStateProperties.OPEN)) {
                            chunk.setBlockState(upperPos, upper.setValue(BlockStateProperties.OPEN, true), false);
                        }
                    } else {
                        chunk.setBlockState(pos.immutable(), state.setValue(BlockStateProperties.OPEN, true), false);
                    }
                    chunkDirty = true;
                }

                if (chunkDirty) {
                    SubLevelPlayerChunkSender.sendChunk(sender, plot.getLightEngine(), chunk);
                }
            }
        }
    }

    private static void openNearbyDoors(ServerLevel level, BlockPos center, int r) {
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-r, -r, -r),
                center.offset(r, r, r))) {
            BlockState state = level.getBlockState(pos);
            // Only real doors — trapdoors, fence gates and barrels also carry OPEN.
            if (!(state.getBlock() instanceof DoorBlock)) continue;
            if (state.getValue(BlockStateProperties.OPEN)) continue;

            if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                if (state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) continue;
                BlockPos lowerPos = pos.immutable();
                level.setBlock(lowerPos, state.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL);
                BlockPos upperPos = lowerPos.above();
                BlockState upperState = level.getBlockState(upperPos);
                if (upperState.is(state.getBlock())
                        && upperState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && upperState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                        && upperState.hasProperty(BlockStateProperties.OPEN)
                        && !upperState.getValue(BlockStateProperties.OPEN)) {
                    level.setBlock(upperPos, upperState.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL);
                }
            } else {
                level.setBlock(pos.immutable(), state.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL);
            }
        }
    }
}
