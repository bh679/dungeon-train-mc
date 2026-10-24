package games.brennan.dungeontrain.registry;

import com.mojang.serialization.Codec;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.effects.builder.DriftGiftTag;
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

    /**
     * Which Live Feed stream a TV item remembers (1 or 2; absent = none): set when a tuned, switched-on
     * TV is broken ({@code event/TvDropMemory}) and when its wearer solos a pane from the inventory
     * ({@code net/TvStreamChoicePacket}); read when it is placed or worn. Persistent, synced.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> TV_STREAM =
        COMPONENTS.registerComponentType("tv_stream",
            b -> b.persistent(Codec.intRange(1, 2)).networkSynchronized(ByteBufCodecs.VAR_INT));

    /**
     * Drift Gift Repaid's "already paid for" mark on a stack left in a drifting carriage's container:
     * who was paid, and how much per item, so a take-back can be charged. Persistent (it travels with
     * the carriage to the next world), not synced. See {@code perks/effects/builder/DriftGiftTag}.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<DriftGiftTag>> DRIFT_GIFT_PAID =
        COMPONENTS.registerComponentType("drift_gift_paid",
            b -> b.persistent(DriftGiftTag.CODEC));

    private ModDataComponents() {}

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }
}
