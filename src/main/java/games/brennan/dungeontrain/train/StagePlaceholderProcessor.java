package games.brennan.dungeontrain.train;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.block.stage.StagePlaceholderBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Swaps every stage placeholder block in a template stamp for the real block of the stage in
 * {@link StagePlacementScope} — see {@link StagePlaceholderBlocks#resolve(BlockState, String)}.
 * Runs first in the processor chain of both {@code CarriagePlacer.stampTemplateSectionLocal} and
 * {@code stampTemplateRelit}, so every shell / part / portal / contents stamp passes through it
 * with the block still rewritable (the same seam {@link BakedItemStatsProcessor} uses).
 *
 * <p>Which stage (and whether an unselected editor preview keeps placeholders visible) is the
 * scope's call — see {@link StagePlacementScope#resolve}.</p>
 *
 * <p>Runtime-only — never serialised, so {@link #getType()} returns a sentinel unit codec.</p>
 */
final class StagePlaceholderProcessor extends StructureProcessor {

    private static final StructureProcessorType<StagePlaceholderProcessor> TYPE =
        () -> MapCodec.unit(new StagePlaceholderProcessor());

    StagePlaceholderProcessor() {}

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo processBlock(
        LevelReader world, BlockPos offset, BlockPos pivot,
        StructureTemplate.StructureBlockInfo source,
        StructureTemplate.StructureBlockInfo target,
        StructurePlaceSettings settings
    ) {
        if (target == null) return null;
        BlockState state = target.state();
        if (!StagePlaceholderBlocks.isPlaceholder(state)) return target;
        BlockState resolved = StagePlacementScope.resolve(state);
        if (resolved == state) return target;
        return new StructureTemplate.StructureBlockInfo(target.pos(), resolved, target.nbt());
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
