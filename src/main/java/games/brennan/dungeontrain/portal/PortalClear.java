package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Deleting a portal structure's blocks: silently, without dropping anything, and cheaply.
 *
 * <p><b>Why not {@code setBlock}.</b> The erase paths used to clear a cell with
 * {@code level.setBlock(pos, AIR, Block.UPDATE_ALL)}. Per cell that runs neighbour updates, the
 * shape-update cascade, a light-engine {@code checkBlock}, heightmap and POI upkeep, and Sable's
 * {@code LevelChunk.setBlockState} physics hook — and it drops things: a container's
 * {@code onRemove} spills its contents (a room's chiselled bookshelves rained books), and the shape
 * cascade routes plants through {@code destroyBlock(dropBlock = true)}, which is where the break
 * particles came from. A structure's footprint is order ten thousand cells, so the cost was paid ten
 * thousand times over, mostly on cells that were <b>already air</b>.</p>
 *
 * <p><b>What this does instead.</b> Walk the box chunk column → section → cell so the lookups hoist
 * out of the inner loop; skip whole sections that hold nothing ({@link LevelChunkSection#hasOnlyAir})
 * and cells that are already air; evict a block entity before its block goes, so there is nothing
 * left to drop; and write air straight into the section palette, which is what skips every hook
 * above. Clients still see it — vanilla's {@code ChunkHolder} coalesces the per-cell
 * {@code blockChanged} notices into one section-blocks packet per section per tick.</p>
 *
 * <p><b>Lighting is handled deliberately, not skipped.</b> A section-local write bypasses the light
 * engine (see {@code SilentBlockOps}), which would leave a deleted room's ceiling lights shining on
 * empty air. Rather than relight every cell, this relights only the cells that were <b>emitting</b>
 * — a handful per room — and tells the light engine when a section has emptied out entirely.</p>
 *
 * <p><b>Concurrency.</b> Section-local writes require the column to be loaded, for the reason
 * {@code SilentBlockOps.setBlockSectionLocal} documents at length: the palette grows in place and an
 * async light worker reading it can throw. A structure is only ever erased in the neighbourhood that
 * stamped it, and the {@code getChunkNow} guard falls back to the (slower, relighting) silent path
 * rather than dropping a write.</p>
 */
public final class PortalClear {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private PortalClear() {}

    /** Receives one span of cells: the part of a box inside a single chunk section. */
    @FunctionalInterface
    public interface SpanSink {
        void accept(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
    }

    /**
     * Split {@code box} into the spans a section-local walk visits — one per chunk section it
     * overlaps, each clamped to {@code [levelMinY, levelMaxY]}.
     *
     * <p>Pure integer geometry, so the walk can be unit-tested without a level: together the spans
     * cover every cell of the (clamped) box exactly once and nothing outside it, and no span crosses
     * a chunk or section boundary.</p>
     */
    public static void forEachSpan(BoundingBox box, int levelMinY, int levelMaxY, SpanSink sink) {
        int minY = Math.max(box.minY(), levelMinY);
        int maxY = Math.min(box.maxY(), levelMaxY);
        if (minY > maxY) return;

        for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
            int xLo = Math.max(box.minX(), chunkX << 4);
            int xHi = Math.min(box.maxX(), (chunkX << 4) + 15);
            for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
                int zLo = Math.max(box.minZ(), chunkZ << 4);
                int zHi = Math.min(box.maxZ(), (chunkZ << 4) + 15);
                for (int sectionY = minY >> 4; sectionY <= maxY >> 4; sectionY++) {
                    int yLo = Math.max(minY, sectionY << 4);
                    int yHi = Math.min(maxY, (sectionY << 4) + 15);
                    sink.accept(xLo, yLo, zLo, xHi, yHi, zHi);
                }
            }
        }
    }

    /**
     * Clear every cell of {@code box} that the mask does not spare, through the light engine.
     *
     * <p>For a box in a plot the world can see — an editor preview under open sky — where a cell left
     * as air has to stop occluding skylight. Still silent and still drop-free: the block entity goes
     * first, drops are suppressed, and the shape cascade is skipped so a plant losing its substrate
     * cannot break with particles.</p>
     */
    public static int clearBoxRelit(ServerLevel level, BoundingBox box, PortalCorridorMask mask) {
        int changed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    if (mask.covers(x, y, z)) continue;
                    if (state.hasBlockEntity() && chunk != null) {
                        SilentBlockOps.evictBlockEntity(chunk, pos);
                    }
                    SilentBlockOps.setBlockSilentNoCascade(level, pos, AIR, null);
                    changed++;
                }
            }
        }
        discardLooseItems(level, box);
        return changed;
    }

    /**
     * Clear every cell of {@code box} that the mask does not spare, and remove the loose items lying
     * in it.
     *
     * @return how many cells actually changed — everything else was already air
     */
    public static int clearBox(ServerLevel level, BoundingBox box, PortalCorridorMask mask) {
        int[] cleared = {0};
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // Cells that were emitting light: the only ones whose removal a section write leaves visibly
        // wrong, so the only ones worth relighting.
        List<BlockPos> emitters = new ArrayList<>();

        forEachSpan(box, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1,
            (minX, minY, minZ, maxX, maxY, maxZ) -> {
                LevelChunk chunk = level.getChunkSource().getChunkNow(minX >> 4, minZ >> 4);
                if (chunk == null) {
                    cleared[0] += clearSpanUnloaded(level, pos, mask, minX, minY, minZ, maxX, maxY, maxZ);
                    return;
                }

                int sIdx = chunk.getSectionIndex(minY);
                if (sIdx < 0 || sIdx >= chunk.getSectionsCount()) return;
                LevelChunkSection section = chunk.getSection(sIdx);
                // Nothing in this section to delete — the common case for a footprint's margins, and
                // for the whole box once a structure has already drained.
                if (section.hasOnlyAir()) return;

                int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
                int changed = 0;
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            int lx = x & 15;
                            int lz = z & 15;
                            int ly = y - baseY;
                            BlockState state = section.getBlockState(lx, ly, lz);
                            if (state.isAir()) continue;
                            if (mask.covers(x, y, z)) continue;

                            pos.set(x, y, z);
                            // Before the block goes, not after: a container that still has its block
                            // entity when it is replaced spills its contents as items.
                            if (state.hasBlockEntity()) SilentBlockOps.evictBlockEntity(chunk, pos);
                            if (state.getLightEmission() > 0) emitters.add(pos.immutable());

                            section.setBlockState(lx, ly, lz, AIR, false);
                            level.getChunkSource().blockChanged(pos);
                            changed++;
                        }
                    }
                }

                if (changed > 0) {
                    chunk.setUnsaved(true);
                    // Whether this section still holds anything is the one thing the light engine
                    // cannot work out for itself after a palette-level write.
                    level.getChunkSource().getLightEngine().updateSectionStatus(
                        SectionPos.of(minX >> 4, baseY >> 4, minZ >> 4), section.hasOnlyAir());
                    cleared[0] += changed;
                }
            });

        for (BlockPos emitter : emitters) {
            level.getChunkSource().getLightEngine().checkBlock(emitter);
        }

        discardLooseItems(level, box);
        return cleared[0];
    }

    /**
     * The fallback for a column that is not loaded: correct, relighting, and slow. Reached only if a
     * structure's own chunks have gone while it was still standing, which the approach range makes
     * rare — but dropping the write instead would leave blocks behind that nothing would ever sweep.
     */
    private static int clearSpanUnloaded(ServerLevel level, BlockPos.MutableBlockPos pos,
                                         PortalCorridorMask mask,
                                         int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int changed = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    if (level.getBlockState(pos).isAir()) continue;
                    if (mask.covers(x, y, z)) continue;
                    SilentBlockOps.setBlockSilent(level, pos, AIR);
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * Remove the items and experience lying in a volume being deleted.
     *
     * <p>Rooms used to rain their containers' contents every time one was erased, and
     * {@code carryStructureOccupants} then ferried the resulting stacks to each new site, so they
     * accumulated for the life of a world. The drops are fixed at source now; this clears what is
     * already there and keeps the floor of a re-stamped room clean. Mobs are left alone — they are
     * carried, not deleted.</p>
     */
    private static void discardLooseItems(ServerLevel level, BoundingBox box) {
        AABB aabb = AABB.of(box);
        for (Entity entity : level.getEntities((Entity) null, aabb, PortalClear::isLoose)) {
            entity.discard();
        }
    }

    /** True for the entity types a deleted room should take with it. */
    public static boolean isLoose(Entity entity) {
        return entity instanceof ItemEntity || entity instanceof ExperienceOrb;
    }
}
