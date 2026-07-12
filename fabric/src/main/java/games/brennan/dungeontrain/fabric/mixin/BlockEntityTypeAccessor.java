package games.brennan.dungeontrain.fabric.mixin;

import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Gap-filler accessor for {@code BlockEntityType.validBlocks} — Fabric has no
 * {@code BlockEntityTypeAddBlocksEvent}, so DT binds the narrative lectern to the
 * vanilla {@code LECTERN} BE by replacing the (normally immutable) valid-block set
 * with an augmented copy. Drives {@code DtEvents.BLOCK_ENTITY_TYPE_ADD_BLOCKS}.
 */
@Mixin(BlockEntityType.class)
public interface BlockEntityTypeAccessor {

    @Accessor("validBlocks")
    Set<Block> dungeonTrain$getValidBlocks();

    @Mutable
    @Accessor("validBlocks")
    void dungeonTrain$setValidBlocks(Set<Block> validBlocks);
}
