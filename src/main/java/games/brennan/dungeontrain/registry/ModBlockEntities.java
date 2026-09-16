package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.prefab.PrefabAnchorBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Mod-side block-entity registry. First entry: the prefab anchor's binding
 * ({@link PrefabAnchorBlockEntity}). The narrative lectern deliberately reuses vanilla's
 * {@code LecternBlockEntity} instead, so until now the project had no register of its own.
 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DungeonTrain.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PrefabAnchorBlockEntity>> PREFAB_ANCHOR =
        BLOCK_ENTITIES.register("prefab_anchor",
            () -> BlockEntityType.Builder.of(PrefabAnchorBlockEntity::new, ModBlocks.PREFAB_ANCHOR.get()).build(null));

    private ModBlockEntities() {}

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
