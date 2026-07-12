package games.brennan.dungeontrain.train;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link StructureProcessor} that <em>writes</em> each processed template block
 * with a lighting-free section-local write ({@link SilentBlockOps#setBlockSectionLocal})
 * and then returns {@code null} so {@link StructureTemplate#placeInWorld}'s own
 * per-block {@code level.setBlock(..., flags)} is skipped for that cell.
 *
 * <p>Why route placement through {@code placeInWorld} at all when we do the writes
 * ourselves: {@code placeInWorld} owns the palette selection, {@code calculateRelativePosition}
 * geometry, rotation/mirror of each {@link BlockState}, and the whole
 * {@link StructureProcessor} chain (so an earlier {@link PartRegionFilterProcessor}
 * still drops the cells the parts overlay will claim). Reusing that logic verbatim
 * — and only substituting the final write — keeps carriage geometry byte-for-byte
 * identical to the old flag-3 path while paying none of its cost.</p>
 *
 * <p>The cost it removes: a spawning carriage's blocks are placed in the world only
 * to be lifted into a Sable sub-level the <b>same tick</b> (which re-airs the source
 * cells), so the light-engine {@code checkBlock} + neighbour-shape cascade + client
 * mark + Sable's {@code LevelChunk.setBlockState} physics-neighbourhood mixin that a
 * flag-3 {@code setBlock} triggers per block are all discarded. That was ~58 % of a
 * spawn tick (the {@code place} phase, measured 49–184 ms per group). Block-entity
 * cells (chests/barrels/signs/…) keep the BE-creating
 * {@link SilentBlockOps#setBlockSilent(ServerLevel, BlockPos, BlockState, CompoundTag)}
 * path so their NBT round-trips for Sable to move; air / {@code STRUCTURE_VOID} cells
 * are left untouched (the footprint is pre-cleared to air).</p>
 *
 * <p>Runtime-only — never serialised, so {@link #getType()} returns a sentinel unit
 * codec (mirrors {@link PartRegionFilterProcessor}). Add it <em>last</em> to the
 * {@link StructurePlaceSettings} processor list so it sees each cell's final state.</p>
 */
final class SectionLocalStampProcessor extends StructureProcessor {

    private static final StructureProcessorType<SectionLocalStampProcessor> TYPE =
        () -> MapCodec.unit(new SectionLocalStampProcessor(null));

    @Nullable
    private final ServerLevel level;

    SectionLocalStampProcessor(@Nullable ServerLevel level) {
        this.level = level;
    }

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo processBlock(
        LevelReader world, BlockPos offset, BlockPos pivot,
        StructureTemplate.StructureBlockInfo source,
        StructureTemplate.StructureBlockInfo target,
        StructurePlaceSettings settings
    ) {
        // Null level ⇒ the codec sentinel instance; never invoked during a real
        // placement. Guard defensively so a stray decode can't NPE.
        if (level != null && target != null) {
            BlockState state = target.state();
            if (!state.isAir() && !state.is(Blocks.STRUCTURE_VOID)) {
                BlockPos pos = target.pos();
                CompoundTag nbt = target.nbt();
                if (nbt != null || state.hasBlockEntity()) {
                    // BE cell — must instantiate + load the block entity so Sable
                    // carries its NBT (loot, sign text, …) into the sub-level.
                    SilentBlockOps.setBlockSilent(level, pos, state, nbt);
                } else {
                    SilentBlockOps.setBlockSectionLocal(level, pos, state);
                }
            }
        }
        // Drop the cell from placeInWorld's list: we already wrote it (or it was
        // air / structure-void). placeInWorld must not re-place it with lighting.
        return null;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
