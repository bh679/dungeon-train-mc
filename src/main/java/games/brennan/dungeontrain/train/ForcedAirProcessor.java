package games.brennan.dungeontrain.train;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.block.ForcedAir;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Turns Forced Air in a template into plain air when it is stamped live — see {@link ForcedAir}.
 * Only on {@code CarriagePlacer.stampTemplateRelit}'s chain, where vanilla {@code placeInWorld}
 * writes the air; the section-local chain's capture processor writes it itself.
 *
 * <p>Runtime-only, never serialised — {@link #getType()} is a sentinel unit codec, like
 * {@link VariantPlaceholderAirProcessor}'s.</p>
 */
final class ForcedAirProcessor extends StructureProcessor {

    private static final StructureProcessorType<ForcedAirProcessor> TYPE =
        () -> MapCodec.unit(new ForcedAirProcessor());

    ForcedAirProcessor() {}

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo processBlock(
        LevelReader world, BlockPos offset, BlockPos pivot,
        StructureTemplate.StructureBlockInfo source,
        StructureTemplate.StructureBlockInfo target,
        StructurePlaceSettings settings
    ) {
        if (target == null) return null;
        BlockState air = ForcedAir.forStamp(target.state());
        if (air == target.state()) return target;
        return new StructureTemplate.StructureBlockInfo(target.pos(), air, null);
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
