package games.brennan.dungeontrain.registry;

import com.mojang.serialization.Codec;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Dungeon Train's item data components. Registered from the mod constructor on the mod bus like
 * {@link ModItems#register(IEventBus)}.
 */
public final class ModDataComponents {

    private static final DeferredRegister.DataComponents COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, DungeonTrain.MOD_ID);

    /**
     * Whether a hand-held Vista TV is switched on — {@code compat/vista/TvItems}. Persistent so the
     * set stays on across a save, synced so the client draws the screen.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> TV_ON =
        COMPONENTS.registerComponentType("tv_on",
            b -> b.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));

    private ModDataComponents() {}

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }
}
