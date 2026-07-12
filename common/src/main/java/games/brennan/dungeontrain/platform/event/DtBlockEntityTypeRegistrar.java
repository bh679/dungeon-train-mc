package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Loader-neutral sink for extending a vanilla {@link BlockEntityType}'s valid-block
 * set — the abstraction over NeoForge's {@code BlockEntityTypeAddBlocksEvent.modify}.
 * Both {@code BlockEntityType} and {@code Block} are vanilla types; a Fabric bridge
 * can back this by mutating the BE type's {@code validBlocks} (Fabric API's
 * equivalent hook) unchanged.
 */
@FunctionalInterface
public interface DtBlockEntityTypeRegistrar {

    void addBlocks(BlockEntityType<?> type, Block... blocks);
}
