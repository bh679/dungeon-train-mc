package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.DimensionalPortalBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Mod-side block-entity-type registry. The first BE type in the project — added
 * for the dimensional portal core. We're not extending {@link ModBlocks} with
 * BE wiring because future BEs may be unrelated to specific blocks (tickers,
 * accessors), so a dedicated registry keeps responsibilities clear.
 *
 * <p>NeoForge {@code build(null)} for the {@link BlockEntityType.Builder}
 * disables datafixing — acceptable for a v1 type with simple int-array +
 * string NBT. If we later evolve the NBT schema we can attach a real
 * {@code Type<?>} here.</p>
 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> TYPES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DungeonTrain.MOD_ID);

    /** BE backing {@link games.brennan.dungeontrain.portal.DimensionalPortalCoreBlock}. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DimensionalPortalBlockEntity>> DIMENSIONAL_PORTAL =
        TYPES.register("dimensional_portal", () -> BlockEntityType.Builder
            .of(DimensionalPortalBlockEntity::new, ModBlocks.DIMENSIONAL_PORTAL_CORE.get())
            .build(null));

    private ModBlockEntities() {}

    /** Call from the mod constructor to attach the register to the mod-event bus. */
    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
