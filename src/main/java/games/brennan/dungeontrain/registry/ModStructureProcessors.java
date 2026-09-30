package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.LostCityBiteProcessor;
import games.brennan.dungeontrain.worldgen.LostCityFacadeProcessor;
import games.brennan.dungeontrain.worldgen.LostCityStretchProcessor;
import games.brennan.dungeontrain.worldgen.LostCitySwapProcessor;
import games.brennan.dungeontrain.worldgen.LostCityTruncateProcessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Data-driven structure processors: the types a {@code worldgen/processor_list} JSON may name.
 * DT's other processors are runtime-only and never serialised, so they need no registry entry.
 * Mirrors {@link ModSounds}'s {@link DeferredRegister} pattern.
 */
public final class ModStructureProcessors {

    public static final DeferredRegister<StructureProcessorType<?>> PROCESSORS =
        DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, DungeonTrain.MOD_ID);

    static {
        PROCESSORS.register("lost_city_swap", () -> LostCitySwapProcessor.TYPE);
        PROCESSORS.register("lost_city_truncate", () -> LostCityTruncateProcessor.TYPE);
        PROCESSORS.register("lost_city_bite", () -> LostCityBiteProcessor.TYPE);
        PROCESSORS.register("lost_city_stretch", () -> LostCityStretchProcessor.TYPE);
        PROCESSORS.register("lost_city_facade", () -> LostCityFacadeProcessor.TYPE);
    }

    private ModStructureProcessors() {}

    /** Call from the mod constructor to attach the {@link DeferredRegister} to the mod-event bus. */
    public static void register(IEventBus modBus) {
        PROCESSORS.register(modBus);
    }
}
