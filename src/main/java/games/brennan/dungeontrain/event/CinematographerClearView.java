package games.brennan.dungeontrain.event;

import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.SubLevelPlayerChunkSender;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Clear-view sub-mode for {@link TrainCinematographerEvents}. While active, it
 * removes the solid blocks the camera is looking at — at head height, in front
 * of the camera — so terrain and carriage walls stop blocking the shot. Doors
 * (anything with {@link BlockStateProperties#OPEN}) are left for the existing
 * auto-open path, and liquids are skipped to avoid flows mid-shot.
 *
 * <p>Removal is permanent and silent. World blocks go through
 * {@link SilentBlockOps#clearBlockSilent}; Sable train carriage blocks are
 * cleared via direct sub-level chunk access + resync, mirroring
 * {@link TrainCinematographerEvents#openNearbyDoorsOnShips}.</p>
 */
public final class CinematographerClearView {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final double STEP = 0.5;

    private CinematographerClearView() {}

    /**
     * Bore a one-block-wide line of sight from the player's head along their
     * look direction, up to {@code reach} blocks, removing solid non-door,
     * non-liquid blocks on both the main world and any overlapping Sable ship.
     */
    public static void clearViewAhead(ServerLevel level, ServerPlayer player, double reach) {
        Vec3 dir = player.getViewVector(1.0f);
        if (dir.lengthSqr() < 1.0e-6) return;
        dir = dir.normalize();
        Vec3 eye = player.getEyePosition();

        // Unique world block positions along the look ray, near -> far.
        Set<BlockPos> ray = new LinkedHashSet<>();
        for (double d = 0.0; d <= reach; d += STEP) {
            Vec3 p = eye.add(dir.scale(d));
            ray.add(BlockPos.containing(p.x, p.y, p.z));
        }
        if (ray.isEmpty()) return;

        // Main world terrain.
        for (BlockPos pos : ray) {
            if (shouldRemove(level.getBlockState(pos))) {
                SilentBlockOps.clearBlockSilent(level, pos);
            }
        }

        // Sable train carriages (sub-levels).
        clearViewOnShips(level, player, ray);
    }

    /**
     * Clears the same ray on any Sable ship whose world AABB overlaps it.
     *
     * <p>{@code EmbeddedPlotLevelAccessor.getBlockState/setBlock} delegate to the
     * host level for DT's NBT-loaded trains and read AIR, so — like the door
     * scan — we go through the plot's {@link LevelChunk} storage directly and
     * resend each modified chunk via {@link SubLevelPlayerChunkSender}.</p>
     */
    private static void clearViewOnShips(ServerLevel level, ServerPlayer player, Set<BlockPos> worldRay) {
        // Ray bounding box for quick ship rejection.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : worldRay) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }

        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            AABBdc bb = ship.worldAABB();
            if (maxX < bb.minX() || minX > bb.maxX()) continue;
            if (maxY < bb.minY() || minY > bb.maxY()) continue;
            if (maxZ < bb.minZ() || minZ > bb.maxZ()) continue;
            if (!(ship instanceof SableManagedShip sableShip)) continue;

            // Map world ray cells into ship-local cells.
            Set<BlockPos> localTargets = new HashSet<>();
            for (BlockPos wp : worldRay) {
                Vector3d local = new Vector3d(wp.getX() + 0.5, wp.getY() + 0.5, wp.getZ() + 0.5);
                ship.worldToShip(local);
                localTargets.add(BlockPos.containing(local.x, local.y, local.z));
            }
            if (localTargets.isEmpty()) continue;

            LevelPlot plot = sableShip.subLevel().getPlot();
            Consumer<Packet<? super ClientGamePacketListener>> sender =
                    packet -> player.connection.send(packet);

            for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                if (chunk == null) continue;

                int cx = chunk.getPos().x;
                int cz = chunk.getPos().z;
                boolean chunkDirty = false;
                for (BlockPos target : localTargets) {
                    if ((target.getX() >> 4) != cx || (target.getZ() >> 4) != cz) continue;
                    BlockState state = chunk.getBlockState(target);
                    if (!shouldRemove(state)) continue;
                    if (state.hasBlockEntity()) {
                        chunk.removeBlockEntity(target);
                    }
                    chunk.setBlockState(target, AIR, false);
                    chunkDirty = true;
                }

                if (chunkDirty) {
                    SubLevelPlayerChunkSender.sendChunk(sender, plot.getLightEngine(), chunk);
                }
            }
        }
    }

    /** Solid blocks only: skip air, door-family (OPEN) blocks, and liquids. */
    private static boolean shouldRemove(BlockState state) {
        if (state.isAir()) return false;
        if (state.hasProperty(BlockStateProperties.OPEN)) return false;
        return !(state.getBlock() instanceof LiquidBlock);
    }
}
