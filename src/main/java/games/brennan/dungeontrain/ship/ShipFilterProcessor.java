package games.brennan.dungeontrain.ship;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * {@link StructureProcessor} that drops any template block whose target
 * world position is currently owned by a managed ship. Used when stamping
 * tunnel templates so a section / portal stamped in the chunk the train
 * is currently in doesn't overwrite carriage voxels with the template's
 * cells.
 *
 * <p>{@link StructureTemplate#placeInWorld} calls {@code level.setBlock}
 * directly, which the underlying physics mod routes to ship voxel storage
 * when the position lands inside a ship — so a tunnel stamp covering the
 * train's current position would zero out carriage voxels.</p>
 *
 * <p>Replaces the old {@code tunnel.VSShipFilterProcessor} — implementation
 * delegates through {@link Shipyards#isInShip(LevelReader, BlockPos)} so it
 * works against whichever physics mod is wired up.</p>
 */
public final class ShipFilterProcessor extends StructureProcessor {

    public static final ShipFilterProcessor INSTANCE = new ShipFilterProcessor();

    /**
     * Runtime-only processor — never serialised, so the codec just produces
     * {@link #INSTANCE} on decode and writes nothing on encode.
     */
    private static final StructureProcessorType<ShipFilterProcessor> TYPE =
        () -> Codec.unit(INSTANCE);

    private ShipFilterProcessor() {}

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo processBlock(
        LevelReader level, BlockPos origin, BlockPos pivot,
        StructureTemplate.StructureBlockInfo source,
        StructureTemplate.StructureBlockInfo target,
        StructurePlaceSettings settings
    ) {
        if (Shipyards.isInShip(level, target.pos())) {
            return null;
        }
        return target;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
