package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.entity.PhotographFrameBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Dungeon Train's own block entity types. */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DungeonTrain.MOD_ID);

    /** The master cell of a block photo frame — see {@link PhotographFrameBlockEntity}. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PhotographFrameBlockEntity>> PHOTOGRAPH_FRAME =
        BLOCK_ENTITY_TYPES.register("photograph_frame", () -> BlockEntityType.Builder
            .of(PhotographFrameBlockEntity::new, ModBlocks.PHOTOGRAPH_FRAME.get())
            .build(null));

    private ModBlockEntities() {}

    /** Call from the mod constructor to attach the register to the mod-event bus. */
    public static void register(IEventBus modBus) {
        BLOCK_ENTITY_TYPES.register(modBus);
    }
}
