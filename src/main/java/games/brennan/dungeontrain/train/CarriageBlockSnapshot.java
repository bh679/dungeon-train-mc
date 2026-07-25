package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Captures and restores a carriage's blocks + block-entities as a portable NBT blob for the shared
 * carriage pool. A live carriage's voxels live in its Sable sub-level's plot chunks — a host-level read
 * at the same coordinates returns air (see {@link games.brennan.dungeontrain.ship.CarriageDeck}) — so
 * capture reads directly from {@link LevelPlot#getLoadedChunks()} (the idiom proven across shipped
 * runtime code). Placement writes into a level at world coordinates, so it runs at spawn (before Sable
 * assembly lifts the blocks into a sub-level), exactly like the normal template stamp.
 *
 * <p>Contents are frozen exactly as-left: block entities (chest {@code Items}, sign text, lecterns) are
 * round-tripped verbatim via {@code saveWithFullMetadata} / {@code loadCustomOnly}. Free entities
 * (armor stands, item frames) are NOT captured in this version.</p>
 *
 * <p>Wire format (a gzipped-then-base64 {@link CompoundTag}):
 * {@code { v:1, l, h, w, cells:[ { p:[dx,dy,dz], s:<blockstate>, b?:<be nbt> } ] } } — only non-air
 * cells are stored; placement clears the footprint to air first, so the rest is implicitly empty.</p>
 */
public final class CarriageBlockSnapshot {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FORMAT_VERSION = 1;
    /** setBlock flag for placement: notify clients, skip neighbour-shape updates + drops. */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private CarriageBlockSnapshot() {}

    // ---- capture (read from a live sub-level's plot) ----

    /**
     * Read the carriage footprint {@code [origin, origin+dims)} in shipyard space from {@code ship}'s
     * sub-level plot into a snapshot tag. {@code origin} is the per-carriage shipyard origin.
     */
    public static CompoundTag capture(SableManagedShip ship, BlockPos origin, CarriageDims dims,
                                      HolderLookup.Provider registries) {
        LevelPlot plot = ship.subLevel().getPlot();
        ListTag cells = new ListTag();
        for (int dx = 0; dx < dims.length(); dx++) {
            for (int dy = 0; dy < dims.height(); dy++) {
                for (int dz = 0; dz < dims.width(); dz++) {
                    BlockPos abs = origin.offset(dx, dy, dz);
                    BlockState state = blockInPlot(plot, abs);
                    if (state.isAir()) continue;
                    CompoundTag cell = new CompoundTag();
                    cell.put("p", new net.minecraft.nbt.IntArrayTag(new int[]{dx, dy, dz}));
                    cell.put("s", NbtUtils.writeBlockState(state));
                    if (state.hasBlockEntity()) {
                        BlockEntity be = beInPlot(plot, abs);
                        if (be != null) {
                            CompoundTag beTag = be.saveWithFullMetadata(registries);
                            beTag.remove("x");
                            beTag.remove("y");
                            beTag.remove("z");
                            cell.put("b", beTag);
                        }
                    }
                    cells.add(cell);
                }
            }
        }
        CompoundTag root = new CompoundTag();
        root.putInt("v", FORMAT_VERSION);
        root.putInt("l", dims.length());
        root.putInt("h", dims.height());
        root.putInt("w", dims.width());
        root.put("cells", cells);
        return root;
    }

    // ---- placement (write into a level at world coords — spawn-time, pre-assembly) ----

    /**
     * Stamp a snapshot into {@code level} at {@code worldOrigin}: the footprint is cleared to air, then
     * every stored cell is written (block state + block-entity NBT). Returns the set of NON-AIR world
     * positions written (the carriage's blocks, to hand to Sable's {@code assemble}), or {@code null}
     * on failure. Runs at spawn, before assembly, so host-level writes at world coords are correct.
     */
    public static java.util.Set<BlockPos> place(ServerLevel level, BlockPos worldOrigin, CompoundTag snap) {
        try {
            int l = snap.getInt("l"), h = snap.getInt("h"), w = snap.getInt("w");
            HolderGetter<Block> blocks = level.holderLookup(Registries.BLOCK);
            HolderLookup.Provider registries = level.registryAccess();
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int dx = 0; dx < l; dx++) {
                for (int dy = 0; dy < h; dy++) {
                    for (int dz = 0; dz < w; dz++) {
                        level.setBlock(worldOrigin.offset(dx, dy, dz), air, PLACE_FLAGS);
                    }
                }
            }
            java.util.Set<BlockPos> placed = new java.util.HashSet<>();
            ListTag cells = snap.getList("cells", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < cells.size(); i++) {
                CompoundTag cell = cells.getCompound(i);
                int[] p = cell.getIntArray("p");
                if (p.length != 3) continue;
                BlockPos abs = worldOrigin.offset(p[0], p[1], p[2]);
                BlockState state = NbtUtils.readBlockState(blocks, cell.getCompound("s"));
                level.setBlock(abs, state, PLACE_FLAGS);
                placed.add(abs.immutable());
                if (cell.contains("b", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    BlockEntity be = level.getBlockEntity(abs);
                    if (be != null) {
                        be.loadCustomOnly(cell.getCompound("b"), registries);
                        be.setChanged();
                    }
                }
            }
            return placed;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Failed to place shared-carriage snapshot at {}: {}", worldOrigin, e.toString());
            return null;
        }
    }

    // ---- encode / decode (gzip + base64 for the relay wire) ----

    public static String encode(CompoundTag tag) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    public static CompoundTag decode(String base64) throws IOException {
        byte[] raw = Base64.getDecoder().decode(base64);
        return NbtIo.readCompressed(new ByteArrayInputStream(raw), NbtAccounter.unlimitedHeap());
    }

    // ---- plot-chunk read helpers (mirrors CarriageDeck.blockInPlot) ----

    private static BlockState blockInPlot(LevelPlot plot, BlockPos shipLocal) {
        LevelChunk chunk = chunkAt(plot, shipLocal);
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(shipLocal);
    }

    private static BlockEntity beInPlot(LevelPlot plot, BlockPos shipLocal) {
        LevelChunk chunk = chunkAt(plot, shipLocal);
        return chunk == null ? null : chunk.getBlockEntity(shipLocal);
    }

    private static LevelChunk chunkAt(LevelPlot plot, BlockPos shipLocal) {
        long key = net.minecraft.world.level.ChunkPos.asLong(shipLocal.getX() >> 4, shipLocal.getZ() >> 4);
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk != null && chunk.getPos().toLong() == key) return chunk;
        }
        return null;
    }
}
