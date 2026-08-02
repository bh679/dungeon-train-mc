package games.brennan.dungeontrain.worldgen.structure;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Mod-side structure-type registry — same {@link DeferredRegister} pattern as
 * {@link games.brennan.dungeontrain.worldgen.feature.ModFeatures}, attached to the mod-event bus from the
 * mod constructor.
 *
 * <p>Registers {@link BandEndCityStructure} under {@code dungeontrain:end_city}, which the datapack JSONs
 * in {@code data/dungeontrain/worldgen/structure/end_city.json} and
 * {@code .../structure_set/end_city.json} reference.</p>
 */
public final class ModStructureTypes {

    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(
        Registries.STRUCTURE_TYPE, DungeonTrain.MOD_ID);

    public static final DeferredHolder<StructureType<?>, StructureType<BandEndCityStructure>> END_CITY =
        STRUCTURE_TYPES.register("end_city", () -> () -> BandEndCityStructure.CODEC);

    /** Registry key of the band's End city — the id the band's own code gates on. */
    public static final ResourceKey<Structure> END_CITY_KEY = ResourceKey.create(
        Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "end_city"));

    /**
     * Registry key of the structure set that spreads those cities. The overworld generator would drop the
     * set (its biome source doesn't list End biomes), so {@code ChunkGeneratorStructureStateMixin} keeps
     * it by this key.
     */
    public static final ResourceKey<StructureSet> END_CITY_SET_KEY = ResourceKey.create(
        Registries.STRUCTURE_SET, ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "end_city"));

    private ModStructureTypes() {}

    /** Call from the mod constructor to attach the {@link DeferredRegister} to the mod-event bus. */
    public static void register(IEventBus modBus) {
        STRUCTURE_TYPES.register(modBus);
    }
}
