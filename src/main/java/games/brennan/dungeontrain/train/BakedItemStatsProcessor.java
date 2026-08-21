package games.brennan.dungeontrain.train;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.difficulty.BakedItemStats;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Rolls impossible baked item stats again on their way out of a template — see
 * {@link BakedItemStats} for how a saved template comes to hold a chestplate with four million
 * armour, and why the fix has to happen at read time rather than in the data.
 *
 * <p><b>Placed as a processor</b> because that is the one point every template stamp passes through
 * with the block entity's NBT still in hand and still rewritable: {@code placeInWorld} hands each
 * cell to the processor chain and then loads the returned tag into the block entity. The alternative
 * — repairing each container after the stamp — would mean finding them again, and would miss the
 * cells a later pass overwrites.</p>
 *
 * <p>Add it <b>before</b> {@link SectionLocalStampProcessor}, which returns {@code null} for every
 * cell and so ends the chain. Order does not otherwise matter: this rewrites only the tag, never the
 * state or the position, so it neither sees nor disturbs what a mask or a region filter is doing.</p>
 *
 * <p>Runtime-only — never serialised, so {@link #getType()} returns a sentinel unit codec, mirroring
 * {@link SectionLocalStampProcessor} and {@code PartRegionFilterProcessor}.</p>
 */
final class BakedItemStatsProcessor extends StructureProcessor {

    private static final StructureProcessorType<BakedItemStatsProcessor> TYPE =
        () -> MapCodec.unit(new BakedItemStatsProcessor(null));

    @Nullable
    private final ServerLevel level;

    BakedItemStatsProcessor(@Nullable ServerLevel level) {
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
        // Null level ⇒ the codec sentinel instance; never invoked during a real placement.
        if (level == null || target == null) return target;
        CompoundTag nbt = target.nbt();
        if (nbt == null) return target;

        CompoundTag repaired = BakedItemStats.repair(level, target.pos(), nbt);
        // Same instance back ⇒ nothing was wrong, which is almost always. Return the cell untouched
        // rather than allocating an identical StructureBlockInfo per block entity in every template.
        if (repaired == nbt) return target;
        return new StructureTemplate.StructureBlockInfo(target.pos(), target.state(), repaired);
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
