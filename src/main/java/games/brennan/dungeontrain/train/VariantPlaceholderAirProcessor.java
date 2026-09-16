package games.brennan.dungeontrain.train;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Airs every block-variant empty-placeholder sentinel in a play-side template stamp — the
 * {@code dungeontrain:variant_placeholder} ghost cube and its legacy command-block form
 * ({@link CarriageVariantBlocks#isEmptyPlaceholder}).
 *
 * <p>Belt and braces: a template saved from the editor holds the placeholder at every variant
 * cell, and the variant appliers overwrite each of those cells anyway. What this catches is a
 * stray one — a cell whose variant entry was later removed with the world block left behind —
 * which would otherwise ride out into a live carriage as a translucent cube.</p>
 *
 * <p>Gated on {@link StagePlacementScope#active()}: editor stamps run outside the scope on
 * purpose (their plots are captured back into templates on save), and there the placeholder
 * must stay put. Runs after {@link StagePlaceholderProcessor} in the chain of both
 * {@code CarriagePlacer.stampTemplateSectionLocal} and {@code stampTemplateRelit}.</p>
 *
 * <p>Runtime-only — never serialised, so {@link #getType()} returns a sentinel unit codec.</p>
 */
final class VariantPlaceholderAirProcessor extends StructureProcessor {

    private static final StructureProcessorType<VariantPlaceholderAirProcessor> TYPE =
        () -> MapCodec.unit(new VariantPlaceholderAirProcessor());

    VariantPlaceholderAirProcessor() {}

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo processBlock(
        LevelReader world, BlockPos offset, BlockPos pivot,
        StructureTemplate.StructureBlockInfo source,
        StructureTemplate.StructureBlockInfo target,
        StructurePlaceSettings settings
    ) {
        if (target == null) return null;
        if (!StagePlacementScope.active()) return target;
        BlockState state = target.state();
        if (!CarriageVariantBlocks.isEmptyPlaceholder(state)) return target;
        return new StructureTemplate.StructureBlockInfo(target.pos(), Blocks.AIR.defaultBlockState(), null);
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
