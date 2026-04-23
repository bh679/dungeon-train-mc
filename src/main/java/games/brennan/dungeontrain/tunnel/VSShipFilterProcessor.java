package games.brennan.dungeontrain.tunnel;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * {@link StructureProcessor} that drops any template block whose target
 * world position is currently managed by a Valkyrien Skies ship. Used when
 * stamping {@link TunnelTemplate} so that a section / portal stamped in the
 * chunk the train is currently in doesn't overwrite carriage voxels with
 * the template's air cells.
 *
 * <p>{@link StructureTemplate#placeInWorld} calls {@code level.setBlock}
 * directly. VS mixins route that setBlock to the ship's voxel storage when
 * the position lands inside a ship, so a tunnel stamp that covers the
 * train's current position would zero out carriage wall / floor voxels —
 * manifesting as "a carriage is missing" after the train moves through a
 * newly-painted section.</p>
 */
public final class VSShipFilterProcessor extends StructureProcessor {

    public static final VSShipFilterProcessor INSTANCE = new VSShipFilterProcessor();

    /**
     * Runtime-only processor — never serialised, so the codec just needs to
     * produce {@link #INSTANCE} on decode and write nothing on encode.
     */
    private static final StructureProcessorType<VSShipFilterProcessor> TYPE =
        () -> Codec.unit(INSTANCE);

    private VSShipFilterProcessor() {}

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo processBlock(
        LevelReader level, BlockPos origin, BlockPos pivot,
        StructureTemplate.StructureBlockInfo source,
        StructureTemplate.StructureBlockInfo target,
        StructurePlaceSettings settings
    ) {
        if (level instanceof ServerLevel serverLevel) {
            if (VSGameUtilsKt.getShipObjectManagingPos(serverLevel, target.pos()) != null) {
                return null;
            }
        }
        return target;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
