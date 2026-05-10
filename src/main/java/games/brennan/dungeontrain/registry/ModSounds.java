package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Mod-side sound-event registry. Mirrors {@link ModItems}'s
 * {@link DeferredRegister} pattern so a future second sound just adds another
 * {@code register(...)} call.
 *
 * <p>Currently registers only the looping {@code train_engine} ambient driven
 * by {@link games.brennan.dungeontrain.client.sound.TrainEngineSound}. The
 * resource pack mapping for this id lives at
 * {@code assets/dungeontrain/sounds.json}.</p>
 */
public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
        DeferredRegister.create(Registries.SOUND_EVENT, DungeonTrain.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> TRAIN_ENGINE = SOUND_EVENTS.register(
        "train_engine",
        () -> SoundEvent.createVariableRangeEvent(
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "train_engine"))
    );

    private ModSounds() {}

    /** Call from the mod constructor to attach the {@link DeferredRegister} to the mod-event bus. */
    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }
}
