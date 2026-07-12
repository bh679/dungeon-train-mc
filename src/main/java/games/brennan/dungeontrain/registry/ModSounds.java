package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.platform.DtRegistrar;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import java.util.function.Supplier;

/**
 * Mod-side sound-event registry. Registered via {@link DtRegistrar}
 * (loader-neutral) instead of a direct {@code DeferredRegister} — see
 * {@link games.brennan.dungeontrain.advancement.ModAdvancementTriggers} for
 * the pattern and the root attach timing.
 *
 * <p>Currently registers only the looping {@code train_engine} ambient driven
 * by {@link games.brennan.dungeontrain.client.sound.TrainEngineSound}. The
 * resource pack mapping for this id lives at
 * {@code assets/dungeontrain/sounds.json}.</p>
 */
public final class ModSounds {

    public static final Supplier<SoundEvent> TRAIN_ENGINE = DtRegistrar.get().register(
        Registries.SOUND_EVENT,
        "train_engine",
        () -> SoundEvent.createVariableRangeEvent(
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "train_engine"))
    );

    private ModSounds() {}

    /** Call from the mod constructor to force this class's static fields (and their registrations) to run. */
    public static void init() {}
}
