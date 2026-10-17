package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * {@link StructureProcessor} that keeps a standing block when a template would lay decoration over it.
 *
 * <p>BetterNether's crimson glowing woods are prebuilt trees ({@code betternether:trees/crimson_glow_tree_*})
 * stamped by WorldWeaver twelve to a chunk, so they overlap. Each tree's weeping-vine chains hang from its
 * shroomlight, stem or hyphae; when a second tree is stamped over the first, its {@code wall_moss} (and
 * vine heads) replace those anchors. Wall moss has no sturdy underside, so on the chunk's first tick every
 * chain beneath it fails {@code GrowingPlantBlock#canSurvive} and pops into items — about two per generated
 * chunk, which is the ~2,300 dropped items behind the 0.1286.0 lag report. This drops the template cell
 * instead: the anchor stays, the moss is simply not drawn there, the chain below keeps its support.</p>
 *
 * <p>The rule is generic — a template block with no collision never replaces a world block with a full
 * collision box — and reads only the world, so chunk output stays seed-stable. Air is a deliberate write
 * (templates say "leave alone" with structure voids) and passes through. Runtime-only: attached by
 * {@code mixin/wover/WoverTemplateAnchorMixin} while {@link NetherCoreDecoration#isActive()}, never
 * serialised, so the codec yields an inert sentinel. Mirrors {@code tunnel/NetherFadeMaskProcessor}.</p>
 */
public final class TemplateAnchorKeepProcessor extends StructureProcessor {

    public static final TemplateAnchorKeepProcessor INSTANCE = new TemplateAnchorKeepProcessor();

    private static final StructureProcessorType<TemplateAnchorKeepProcessor> TYPE = () -> MapCodec.unit(INSTANCE);

    private TemplateAnchorKeepProcessor() {}

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo processBlock(
        LevelReader level, BlockPos origin, BlockPos pivot,
        StructureTemplate.StructureBlockInfo source,
        StructureTemplate.StructureBlockInfo target,
        StructurePlaceSettings settings
    ) {
        BlockPos pos = target.pos();
        return keeps(level.getBlockState(pos), target.state(), level, pos) ? null : target;
    }

    /**
     * True when the world block stays and the template block is dropped: the world block is a full
     * collision box (shroomlight, stem, hyphae, rock) and the incoming one has no collision at all (wall
     * moss, vines, mushrooms, roots). Pure apart from the shape lookups — unit-tested.
     */
    public static boolean keeps(BlockState existing, BlockState incoming, BlockGetter level, BlockPos pos) {
        if (incoming.isAir()) return false;
        if (!existing.isCollisionShapeFullBlock(level, pos)) return false;
        return incoming.getCollisionShape(level, pos).isEmpty();
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
