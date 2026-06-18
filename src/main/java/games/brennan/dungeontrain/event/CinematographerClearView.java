package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.SubLevelPlayerChunkSender;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Clear-view sub-mode for {@link TrainCinematographerEvents}. While active it
 * swaps the solid blocks the camera is looking at — at head height, in front of
 * the camera — to an invisible {@link Blocks#BARRIER}, so terrain and carriage
 * walls (and decorative blocks like pots / chiseled bookshelves) stop blocking
 * the shot.
 *
 * <p>Barrier (not air) is deliberate: it is solid and non-replaceable, so the
 * blocks above keep their support and gravity blocks (sand/gravel) never fall —
 * which on a moving Sable train would spawn {@code FallingBlockEntity}s and kick
 * off physics chaos (the same hazard {@link games.brennan.dungeontrain.worldgen.FallingBlockAnchor}
 * guards against during worldgen).</p>
 *
 * <p>The swap is reversible and lossless: each player's swapped blocks are tracked
 * with their original {@link BlockState} <em>and</em> block-entity NBT, and restored
 * when the ray moves past them, when clear-view turns off, and on logout — so the
 * world re-solidifies behind the camera and a pot's sherds / a bookshelf's books /
 * a chest's contents all come back. World blocks use
 * {@link SilentBlockOps#setBlockSilent}; Sable carriage blocks use direct sub-level
 * chunk writes + {@link SubLevelPlayerChunkSender} resync, mirroring
 * {@link TrainCinematographerEvents#openNearbyDoorsOnShips}. Writes never fire
 * neighbour updates, so they can't schedule gravity ticks.</p>
 */
public final class CinematographerClearView {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final BlockState BARRIER = Blocks.BARRIER.defaultBlockState();
    private static final double STEP = 0.5;

    /** A swapped block's original state plus its block-entity NBT (null if none). */
    private record Saved(BlockState state, CompoundTag beNbt) {}

    /** Per-player record of blocks swapped to barrier, keyed by position. */
    private static final class Swaps {
        final Map<BlockPos, Saved> world = new HashMap<>();
        final Map<LevelPlot, Map<BlockPos, Saved>> ships = new IdentityHashMap<>();
    }

    private static final Map<UUID, Swaps> SWAPS = new HashMap<>();

    private CinematographerClearView() {}

    /**
     * Make the player's head-height look-line transparent up to {@code reach}
     * blocks, reverting whatever the line has moved off. Covers both the main
     * world and any overlapping Sable ship.
     */
    public static void clearViewAhead(ServerLevel level, ServerPlayer player, double reach) {
        Vec3 dir = player.getViewVector(1.0f);
        if (dir.lengthSqr() < 1.0e-6) return;
        dir = dir.normalize();
        Vec3 eye = player.getEyePosition();

        Set<BlockPos> ray = new LinkedHashSet<>();
        for (double d = 0.0; d <= reach; d += STEP) {
            Vec3 p = eye.add(dir.scale(d));
            ray.add(BlockPos.containing(p.x, p.y, p.z));
        }
        if (ray.isEmpty()) return;

        Swaps swaps = SWAPS.computeIfAbsent(player.getUUID(), k -> new Swaps());
        updateWorld(level, ray, swaps);
        updateShips(level, player, ray, swaps);
    }

    /** Restore every block this player swapped (world + ships) and forget them. */
    public static void restoreAll(ServerPlayer player) {
        Swaps swaps = SWAPS.remove(player.getUUID());
        if (swaps == null) return;

        ServerLevel level = player.serverLevel();
        for (Map.Entry<BlockPos, Saved> e : swaps.world.entrySet()) {
            restoreWorld(level, e.getKey(), e.getValue());
        }
        swaps.world.clear();

        RegistryAccess registries = level.registryAccess();
        Consumer<Packet<? super ClientGamePacketListener>> sender = pk -> player.connection.send(pk);
        for (Map.Entry<LevelPlot, Map<BlockPos, Saved>> pe : swaps.ships.entrySet()) {
            try {
                LevelPlot plot = pe.getKey();
                Map<Long, LevelChunk> chunks = loadedChunks(plot);
                Set<LevelChunk> dirty = new HashSet<>();
                for (Map.Entry<BlockPos, Saved> be : pe.getValue().entrySet()) {
                    restoreShip(chunks, be.getKey(), be.getValue(), dirty, registries);
                }
                resync(sender, plot, dirty);
            } catch (Exception ex) {
                LOGGER.warn("[DungeonTrain] clear-view restore failed for a ship plot", ex);
            }
        }
        swaps.ships.clear();
    }

    // ---- World ----

    private static void updateWorld(ServerLevel level, Set<BlockPos> ray, Swaps swaps) {
        for (BlockPos pos : ray) {
            if (swaps.world.containsKey(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!shouldHide(state)) continue;
            CompoundTag beNbt = null;
            if (state.hasBlockEntity()) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be != null) beNbt = be.saveWithFullMetadata(level.registryAccess());
            }
            swaps.world.put(pos.immutable(), new Saved(state, beNbt));
            SilentBlockOps.setBlockSilent(level, pos, BARRIER);
        }
        Iterator<Map.Entry<BlockPos, Saved>> it = swaps.world.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Saved> e = it.next();
            if (ray.contains(e.getKey())) continue;
            restoreWorld(level, e.getKey(), e.getValue());
            it.remove();
        }
    }

    private static void restoreWorld(ServerLevel level, BlockPos pos, Saved saved) {
        if (level.getBlockState(pos).is(Blocks.BARRIER)) {
            SilentBlockOps.setBlockSilent(level, pos, saved.state(), saved.beNbt());
        }
    }

    // ---- Ships ----

    private static void updateShips(ServerLevel level, ServerPlayer player, Set<BlockPos> ray, Swaps swaps) {
        Map<LevelPlot, Set<BlockPos>> current = new IdentityHashMap<>();
        int[] bb = rayBounds(ray);
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            AABBdc box = ship.worldAABB();
            if (bb[3] < box.minX() || bb[0] > box.maxX()) continue;
            if (bb[4] < box.minY() || bb[1] > box.maxY()) continue;
            if (bb[5] < box.minZ() || bb[2] > box.maxZ()) continue;
            if (!(ship instanceof SableManagedShip sableShip)) continue;

            Set<BlockPos> local = new HashSet<>();
            for (BlockPos wp : ray) {
                Vector3d v = new Vector3d(wp.getX() + 0.5, wp.getY() + 0.5, wp.getZ() + 0.5);
                ship.worldToShip(v);
                local.add(BlockPos.containing(v.x, v.y, v.z));
            }
            current.put(sableShip.subLevel().getPlot(), local);
        }

        // Union of plots overlapping now and plots still holding swaps.
        Set<LevelPlot> plots = Collections.newSetFromMap(new IdentityHashMap<>());
        plots.addAll(current.keySet());
        plots.addAll(swaps.ships.keySet());

        RegistryAccess registries = level.registryAccess();
        Consumer<Packet<? super ClientGamePacketListener>> sender = pk -> player.connection.send(pk);
        for (LevelPlot plot : plots) {
            processShipPlot(sender, plot, current.getOrDefault(plot, Set.of()), swaps, registries);
        }
    }

    private static void processShipPlot(Consumer<Packet<? super ClientGamePacketListener>> sender,
                                        LevelPlot plot, Set<BlockPos> targets, Swaps swaps, RegistryAccess registries) {
        Map<Long, LevelChunk> chunks = loadedChunks(plot);
        Set<LevelChunk> dirty = new HashSet<>();
        Map<BlockPos, Saved> tracked = swaps.ships.get(plot);

        // Swap newly-targeted carriage blocks to barrier (preserving any BE NBT).
        for (BlockPos lp : targets) {
            if (tracked != null && tracked.containsKey(lp)) continue;
            LevelChunk c = chunks.get(ChunkPos.asLong(lp.getX() >> 4, lp.getZ() >> 4));
            if (c == null) continue;
            BlockState st = c.getBlockState(lp);
            if (!shouldHide(st)) continue;
            CompoundTag beNbt = null;
            if (st.hasBlockEntity()) {
                BlockEntity be = c.getBlockEntity(lp);
                if (be != null) beNbt = be.saveWithFullMetadata(registries);
                c.removeBlockEntity(lp);
            }
            if (tracked == null) {
                tracked = new HashMap<>();
                swaps.ships.put(plot, tracked);
            }
            tracked.put(lp.immutable(), new Saved(st, beNbt));
            c.setBlockState(lp, BARRIER, false);
            dirty.add(c);
        }

        // Revert carriage blocks the ray has moved past.
        if (tracked != null) {
            Iterator<Map.Entry<BlockPos, Saved>> it = tracked.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Saved> e = it.next();
                if (targets.contains(e.getKey())) continue;
                restoreShip(chunks, e.getKey(), e.getValue(), dirty, registries);
                it.remove();
            }
            if (tracked.isEmpty()) swaps.ships.remove(plot);
        }

        resync(sender, plot, dirty);
    }

    private static void restoreShip(Map<Long, LevelChunk> chunks, BlockPos lp, Saved saved,
                                    Set<LevelChunk> dirty, RegistryAccess registries) {
        LevelChunk c = chunks.get(ChunkPos.asLong(lp.getX() >> 4, lp.getZ() >> 4));
        if (c == null || !c.getBlockState(lp).is(Blocks.BARRIER)) return;
        c.setBlockState(lp, saved.state(), false);
        if (saved.state().hasBlockEntity() && saved.beNbt() != null) {
            BlockEntity be = c.getBlockEntity(lp);
            if (be != null) {
                CompoundTag positioned = saved.beNbt().copy();
                positioned.putInt("x", lp.getX());
                positioned.putInt("y", lp.getY());
                positioned.putInt("z", lp.getZ());
                be.loadWithComponents(positioned, registries);
                be.setChanged();
            }
        }
        dirty.add(c);
    }

    private static Map<Long, LevelChunk> loadedChunks(LevelPlot plot) {
        Map<Long, LevelChunk> chunks = new HashMap<>();
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk c = holder.getChunk();
            if (c != null) chunks.put(ChunkPos.asLong(c.getPos().x, c.getPos().z), c);
        }
        return chunks;
    }

    private static void resync(Consumer<Packet<? super ClientGamePacketListener>> sender,
                               LevelPlot plot, Set<LevelChunk> dirty) {
        for (LevelChunk c : dirty) {
            SubLevelPlayerChunkSender.sendChunk(sender, plot.getLightEngine(), c);
        }
    }

    private static int[] rayBounds(Set<BlockPos> ray) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : ray) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /**
     * Swap candidates: hide pretty much anything solid except doors. Skips air,
     * existing barriers, doors/trapdoors/fence gates ({@code OPEN} — handled by the
     * door auto-open), and liquids (kept to avoid water/lava flow side effects).
     * Block entities (pots, chiseled bookshelves, chests, …) ARE hidden — their NBT
     * is preserved for a lossless revert.
     */
    private static boolean shouldHide(BlockState state) {
        if (state.isAir()) return false;
        if (state.is(Blocks.BARRIER)) return false;
        if (state.hasProperty(BlockStateProperties.OPEN)) return false;
        return !(state.getBlock() instanceof LiquidBlock);
    }
}
