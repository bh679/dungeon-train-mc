package games.brennan.dungeontrain.train;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.block.flag.FlagPlaceholders;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Swaps every Dungeon Train flag placeholder in a template stamp for its rolled banner (state and
 * block-entity tag) — see {@link FlagPlaceholders#resolveInScope}. Runs right after
 * {@link StagePlaceholderProcessor} in both {@code CarriagePlacer} stamp helpers. Outside a
 * {@link StagePlacementScope} (editor stamps) it leaves the flag alone.
 *
 * <p>Runtime-only — never serialised, so {@link #getType()} returns a sentinel unit codec.</p>
 */
final class FlagPlaceholderProcessor extends StructureProcessor {

    private static final StructureProcessorType<FlagPlaceholderProcessor> TYPE =
        () -> MapCodec.unit(new FlagPlaceholderProcessor(null));

    @Nullable
    private final ServerLevel level;

    FlagPlaceholderProcessor(@Nullable ServerLevel level) {
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
        FlagPlaceholders.Resolved resolved =
            FlagPlaceholders.resolveInScope(target.state(), level.registryAccess(), level.getSeed());
        if (resolved == null) return target;
        return new StructureTemplate.StructureBlockInfo(target.pos(), resolved.state(), resolved.nbt());
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
