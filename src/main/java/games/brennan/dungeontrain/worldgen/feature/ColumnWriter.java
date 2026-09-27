package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Section-cached raw writer for one chunk column — fetches the owning {@link LevelChunkSection} only
 * when crossing a section boundary, dropping orphaned block entities before overwriting (the Sable-safe
 * path; see {@link DisintegrationFeature}). Shared by the Nether band's core stamp
 * ({@link NetherCoreStamp}, CARVERS status) and its FEATURES-step passes ({@link NetherTransitionFeature}).
 */
final class ColumnWriter {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ChunkAccess chunk;
    private int curIdx = -1;
    private LevelChunkSection section;
    private int baseY;

    ColumnWriter(ChunkAccess chunk) {
        this.chunk = chunk;
    }

    private boolean ensure(int y) {
        int idx = chunk.getSectionIndex(y);
        if (idx < 0 || idx >= chunk.getSectionsCount()) return false;
        if (idx != curIdx) {
            curIdx = idx;
            section = chunk.getSection(idx);
            baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(idx));
        }
        return true;
    }

    /** Solid ground (not air, not a fluid) — the surface cells the crossfade may recolour to netherrack. */
    boolean isSolidGround(int dx, int y, int dz) {
        if (!ensure(y)) return false;
        BlockState cur = section.getBlockState(dx, y - baseY, dz);
        return !cur.isAir() && cur.getFluidState().isEmpty();
    }

    boolean isSame(int dx, int y, int dz, BlockState state) {
        if (!ensure(y)) return false;
        return section.getBlockState(dx, y - baseY, dz) == state;
    }

    boolean isAir(int dx, int y, int dz) {
        if (!ensure(y)) return false;
        return section.getBlockState(dx, y - baseY, dz).isAir();
    }

    /** The block currently in this cell, or air when the Y is outside the chunk's sections. */
    BlockState state(int dx, int y, int dz) {
        if (!ensure(y)) return AIR;
        return section.getBlockState(dx, y - baseY, dz);
    }

    /** Water (source, flowing, or waterlogged) — the cells the crossfade drains to air. */
    boolean isWater(int dx, int y, int dz) {
        if (!ensure(y)) return false;
        return section.getBlockState(dx, y - baseY, dz).getFluidState().is(FluidTags.WATER);
    }

    void set(int dx, int y, int dz, BlockState state) {
        if (!ensure(y)) return;
        int ly = y - baseY;
        if (section.getBlockState(dx, ly, dz).hasBlockEntity()) {
            chunk.removeBlockEntity(new BlockPos(chunk.getPos().getMinBlockX() + dx, y, chunk.getPos().getMinBlockZ() + dz));
        }
        section.setBlockState(dx, ly, dz, state, false);
    }
}
