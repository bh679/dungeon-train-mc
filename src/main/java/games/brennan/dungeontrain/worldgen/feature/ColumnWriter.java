package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Section-cached raw writer for one chunk column — fetches the owning {@link LevelChunkSection} only
 * when crossing a section boundary, dropping orphaned block entities before overwriting (the Sable-safe
 * path; see {@link DisintegrationFeature}). Shared by the Nether band's core stamp
 * ({@link NetherCoreStamp}, CARVERS status) and its FEATURES-step passes ({@link NetherTransitionFeature}).
 *
 * <p>The orphan check ({@link BlockState#hasBlockEntity()}) is gated per section on the palette
 * ({@link LevelChunkSection#maybeHas}): a section whose palette holds no block-entity state cannot hold
 * an orphan, so its blocks are overwritten without asking each one. Moonlight hooks that method and it
 * was the hottest leaf of the core stamp (~21% in a 2026-10 profile). The gate is conservative — a
 * global palette answers {@code true} — and is flipped on when a block-entity state is written through
 * it, so every orphan is still found. One writer per chunk computes each section's gate once.
 */
final class ColumnWriter {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static final byte GATE_UNKNOWN = 0;
    private static final byte GATE_NO = 1;
    private static final byte GATE_MAYBE = 2;

    private final ChunkAccess chunk;
    private final byte[] beGate;
    private int curIdx = -1;
    private LevelChunkSection section;
    private int baseY;
    /** Memo of the last written state's {@code hasBlockEntity()} — stamps write long runs of one state. */
    private BlockState lastWritten;
    private boolean lastWrittenHasBe;

    ColumnWriter(ChunkAccess chunk) {
        this.chunk = chunk;
        this.beGate = new byte[chunk.getSectionsCount()];
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
        if (sectionMayHoldBlockEntity() && section.getBlockState(dx, ly, dz).hasBlockEntity()) {
            chunk.removeBlockEntity(new BlockPos(chunk.getPos().getMinBlockX() + dx, y, chunk.getPos().getMinBlockZ() + dz));
        }
        section.setBlockState(dx, ly, dz, state, false);
        if (beGate[curIdx] == GATE_NO && writtenHasBlockEntity(state)) beGate[curIdx] = GATE_MAYBE;
    }

    /** The current section's gate, computed from its palette the first time this writer touches it. */
    private boolean sectionMayHoldBlockEntity() {
        byte gate = beGate[curIdx];
        if (gate == GATE_UNKNOWN) {
            gate = sectionMayHoldBlockEntity(section) ? GATE_MAYBE : GATE_NO;
            beGate[curIdx] = gate;
        }
        return gate == GATE_MAYBE;
    }

    private boolean writtenHasBlockEntity(BlockState state) {
        if (state != lastWritten) {
            lastWritten = state;
            lastWrittenHasBe = state.hasBlockEntity();
        }
        return lastWrittenHasBe;
    }

    /** Palette gate: {@code false} when the section is empty or no state in its palette carries a block entity. */
    static boolean sectionMayHoldBlockEntity(LevelChunkSection section) {
        return !section.hasOnlyAir() && paletteMayHoldBlockEntity(section.getStates());
    }

    /** {@link PalettedContainer#maybeHas} over the block-entity predicate; a global palette answers {@code true}. */
    static boolean paletteMayHoldBlockEntity(PalettedContainer<BlockState> states) {
        return states.maybeHas(BlockState::hasBlockEntity);
    }
}
