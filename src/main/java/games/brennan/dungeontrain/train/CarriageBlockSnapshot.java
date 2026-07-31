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

    /**
     * A capture's two outputs: the portable {@link #tag} (blocks + block-entities, encoded for the
     * relay via {@link #encode}) and the {@link #text} — the player-authored, human-readable text
     * scraped from the block-entities ({@link CarriageTextScan}) for moderation. {@code text} is empty
     * when the carriage carries no sign/book/named-item text.
     */
    public record Captured(CompoundTag tag, String text) {}

    // ---- capture (read from a live sub-level's plot) ----

    /**
     * Read the carriage footprint {@code [origin, origin+dims)} in shipyard space from {@code ship}'s
     * sub-level plot into a snapshot tag, and scrape its block-entities' authored text for moderation.
     * {@code origin} is the per-carriage shipyard origin.
     */
    public static Captured capture(SableManagedShip ship, BlockPos origin, CarriageDims dims,
                                   HolderLookup.Provider registries) {
        LevelPlot plot = ship.subLevel().getPlot();
        ListTag cells = new ListTag();
        StringBuilder text = new StringBuilder();
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
                            CarriageTextScan.appendBlockEntity(be, text);
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
        return new Captured(root, text.toString());
    }

    // ---- delta capture (only the changed cells — the per-change upload path) ----

    /**
     * Capture ONLY the given shipyard-space {@code positions} from {@code ship}'s plot into a compact
     * delta tag {@code { v, set:[{p,s,b?}], del:[[dx,dy,dz]] } }, plus the authored text those cells
     * carry (for moderation). A position whose block is now air becomes a {@code del} entry (removal);
     * anything else becomes a {@code set} cell (same shape as a full-capture cell). Positions outside the
     * footprint are ignored. Encode with {@link #encode} and fold onto a base tag with
     * {@link #applyDeltaCells}. {@code origin} is the per-carriage shipyard origin, matching {@link #capture}.
     */
    public static Captured captureCells(SableManagedShip ship, BlockPos origin, CarriageDims dims,
                                        java.util.Collection<BlockPos> positions, HolderLookup.Provider registries) {
        LevelPlot plot = ship.subLevel().getPlot();
        ListTag set = new ListTag();
        ListTag del = new ListTag();
        StringBuilder text = new StringBuilder();
        for (BlockPos abs : positions) {
            int dx = abs.getX() - origin.getX();
            int dy = abs.getY() - origin.getY();
            int dz = abs.getZ() - origin.getZ();
            if (dx < 0 || dy < 0 || dz < 0
                    || dx >= dims.length() || dy >= dims.height() || dz >= dims.width()) continue; // outside footprint
            BlockState state = blockInPlot(plot, abs);
            if (state.isAir()) {
                del.add(new net.minecraft.nbt.IntArrayTag(new int[]{dx, dy, dz}));
                continue;
            }
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
                    CarriageTextScan.appendBlockEntity(be, text);
                }
            }
            set.add(cell);
        }
        CompoundTag root = new CompoundTag();
        root.putInt("v", FORMAT_VERSION);
        root.put("set", set);
        root.put("del", del);
        // TEMP Gate-2 DEBUG — REVERT: show exactly what a delta captured (the player's edited cells).
        StringBuilder dbgNames = new StringBuilder();
        for (int i = 0; i < set.size() && i < 8; i++) {
            dbgNames.append(set.getCompound(i).getCompound("s").getString("Name")).append(' ');
        }
        LOGGER.info("[DBG capture] captureCells asked={} → set={} del={} names=[{}]",
                positions.size(), set.size(), del.size(), dbgNames.toString().trim());
        return new Captured(root, text.toString());
    }

    /**
     * Fold a delta tag (from {@link #captureCells}) onto a decoded base snapshot, returning a NEW base
     * tag (the input is not mutated). {@code set} cells replace/add by their {@code p} offset; {@code
     * del} offsets are removed. Dimensions/version carry over from {@code baseTag}. Applying deltas in
     * ascending {@code seq} order (the caller's responsibility) makes reconstruction deterministic.
     */
    public static CompoundTag applyDeltaCells(CompoundTag baseTag, CompoundTag deltaTag) {
        java.util.LinkedHashMap<Long, CompoundTag> byPos = new java.util.LinkedHashMap<>();
        ListTag baseCells = baseTag.getList("cells", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < baseCells.size(); i++) {
            CompoundTag cell = baseCells.getCompound(i);
            int[] p = cell.getIntArray("p");
            if (p.length == 3) byPos.put(packOffset(p[0], p[1], p[2]), cell.copy());
        }
        ListTag set = deltaTag.getList("set", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < set.size(); i++) {
            CompoundTag cell = set.getCompound(i);
            int[] p = cell.getIntArray("p");
            if (p.length == 3) byPos.put(packOffset(p[0], p[1], p[2]), cell.copy());
        }
        ListTag del = deltaTag.getList("del", net.minecraft.nbt.Tag.TAG_INT_ARRAY);
        for (int i = 0; i < del.size(); i++) {
            int[] p = del.getIntArray(i);
            if (p.length == 3) byPos.remove(packOffset(p[0], p[1], p[2]));
        }
        CompoundTag out = new CompoundTag();
        out.putInt("v", baseTag.getInt("v"));
        out.putInt("l", baseTag.getInt("l"));
        out.putInt("h", baseTag.getInt("h"));
        out.putInt("w", baseTag.getInt("w"));
        ListTag cells = new ListTag();
        for (CompoundTag cell : byPos.values()) cells.add(cell);
        out.put("cells", cells);
        return out;
    }

    /** Pack a per-carriage cell offset (each 0..{@code MAX_DIM}) into a long map key. */
    private static long packOffset(int dx, int dy, int dz) {
        return ((long) (dx & 0x3FF) << 20) | ((long) (dy & 0x3FF) << 10) | (dz & 0x3FF);
    }

    // ---- placement (write into a level at world coords — spawn-time, pre-assembly) ----

    /**
     * A stamped carriage is considered to have failed when the world afterwards holds fewer than this
     * fraction of the snapshot's non-air cells. A handful of cells can legitimately go missing (a state
     * that no longer resolves, a block that pops off on placement), but a wholesale shortfall means the
     * writes did not land and the caller must fall back rather than assemble a carriage made of air.
     */
    private static final double MIN_PLACED_FRACTION = 0.5;

    /**
     * Stamp a snapshot into {@code level} at {@code worldOrigin}: the footprint is cleared to air, then
     * every stored cell is written (block state + block-entity NBT). Returns the set of NON-AIR world
     * positions that the level actually holds afterwards (the carriage's blocks, to hand to Sable's
     * {@code assemble}), or {@code null} on failure. Runs at spawn, before assembly, so host-level
     * writes at world coords are correct.
     *
     * <p>The returned set is re-read from the world via {@link CarriagePlacer#collectFootprint}, exactly
     * as the local-template path does in {@code CarriagePlacer.finishPlace}. Trusting the write instead
     * (accumulating each {@code setBlock} target) would hand Sable phantom coordinates whenever a write
     * failed to land, assembling an invisible carriage with no warning anywhere — the two spawn paths
     * must agree on what "placed" means.</p>
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
            int wanted = 0;
            ListTag cells = snap.getList("cells", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < cells.size(); i++) {
                CompoundTag cell = cells.getCompound(i);
                int[] p = cell.getIntArray("p");
                if (p.length != 3) continue;
                BlockPos abs = worldOrigin.offset(p[0], p[1], p[2]);
                BlockState state = NbtUtils.readBlockState(blocks, cell.getCompound("s"));
                if (state.isAir()) {
                    // An unresolvable state decodes to air — placing it would silently punch a hole.
                    LOGGER.warn("[DungeonTrain] shared-carriage cell at {} decoded to AIR from {} — skipping.",
                            abs, cell.getCompound("s"));
                    continue;
                }
                wanted++;
                level.setBlock(abs, state, PLACE_FLAGS);
                if (cell.contains("b", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    BlockEntity be = level.getBlockEntity(abs);
                    if (be != null) {
                        be.loadCustomOnly(cell.getCompound("b"), registries);
                        be.setChanged();
                    }
                }
            }
            // Ground truth: what does the level actually hold now? (Same helper the template path uses.)
            CarriageDims dims = new CarriageDims(l, w, h);
            java.util.Set<BlockPos> placed = CarriagePlacer.collectFootprint(level, worldOrigin, dims);
            if (wanted > 0 && placed.size() < wanted * MIN_PLACED_FRACTION) {
                LOGGER.warn("[DungeonTrain] shared-carriage snapshot did NOT land at {}: wrote {} cells, world holds {} — falling back to a fresh carriage.",
                        worldOrigin, wanted, placed.size());
                return null;
            }
            if (placed.size() != wanted) {
                LOGGER.warn("[DungeonTrain] shared-carriage snapshot at {} placed {}/{} cells (partial).",
                        worldOrigin, placed.size(), wanted);
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
