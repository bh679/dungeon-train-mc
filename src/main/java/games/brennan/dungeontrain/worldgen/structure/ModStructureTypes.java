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
 * <p>Registers the band's own structures — {@link BandEndCityStructure} for the End band, and the four
 * Nether ones ({@link BandNetherFortressStructure}, {@link BandBastionRemnantStructure},
 * {@link BandNetherFossilStructure}, {@link BandRuinedPortalStructure}) for the Nether band's core. Each is
 * referenced by a pair of datapack JSONs of the same name under
 * {@code data/dungeontrain/worldgen/structure/} and {@code .../structure_set/}.</p>
 *
 * <p>Every one of them is a band structure the overworld generator would otherwise refuse to place, which
 * is why {@link #isBandStructure} exists: the overworld's biome source never lists the Nether/End biomes DT
 * forces onto band columns at generation time, so {@code ChunkGeneratorStructureStateMixin} has to keep
 * these sets alive by type rather than by biome.</p>
 */
public final class ModStructureTypes {

    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(
        Registries.STRUCTURE_TYPE, DungeonTrain.MOD_ID);

    public static final DeferredHolder<StructureType<?>, StructureType<BandEndCityStructure>> END_CITY =
        STRUCTURE_TYPES.register("end_city", () -> () -> BandEndCityStructure.CODEC);

    public static final DeferredHolder<StructureType<?>, StructureType<BandNetherFortressStructure>> NETHER_FORTRESS =
        STRUCTURE_TYPES.register("nether_fortress", () -> () -> BandNetherFortressStructure.CODEC);

    public static final DeferredHolder<StructureType<?>, StructureType<BandBastionRemnantStructure>> BASTION_REMNANT =
        STRUCTURE_TYPES.register("bastion_remnant", () -> () -> BandBastionRemnantStructure.CODEC);

    public static final DeferredHolder<StructureType<?>, StructureType<BandNetherFossilStructure>> NETHER_FOSSIL =
        STRUCTURE_TYPES.register("nether_fossil", () -> () -> BandNetherFossilStructure.CODEC);

    public static final DeferredHolder<StructureType<?>, StructureType<BandRuinedPortalStructure>> RUINED_PORTAL_NETHER =
        STRUCTURE_TYPES.register("ruined_portal_nether", () -> () -> BandRuinedPortalStructure.CODEC);

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

    /**
     * True for any structure this mod registers — the band's End city and its four Nether structures.
     *
     * <p>Both {@code ChunkGeneratorStructureStateMixin} (which keeps their structure sets from being
     * filtered out of the overworld generator) and {@code ChunkGeneratorDecorationMixin} (which lets their
     * pieces through on chunks whose vanilla decoration is skipped) ask this. Registry lookups can throw
     * before registration completes, so an unresolvable type answers {@code false} — the pre-existing
     * "treat as vanilla" behaviour — rather than propagating out of a mixin.</p>
     */
    public static boolean isBandStructure(StructureType<?> type) {
        try {
            return type == END_CITY.get()
                || type == NETHER_FORTRESS.get()
                || type == BASTION_REMNANT.get()
                || type == NETHER_FOSSIL.get()
                || type == RUINED_PORTAL_NETHER.get();
        } catch (Throwable t) {
            return false;
        }
    }

    private ModStructureTypes() {}

    /** Call from the mod constructor to attach the {@link DeferredRegister} to the mod-event bus. */
    public static void register(IEventBus modBus) {
        STRUCTURE_TYPES.register(modBus);
    }
}
