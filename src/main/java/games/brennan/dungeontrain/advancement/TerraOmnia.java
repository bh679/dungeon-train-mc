package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Terra Omnia": ride through every biome the game has. Kept for good, across deaths and worlds
 * ({@link GlobalBiomeProgress}). "Every biome" is the whole biome
 * registry less the {@link #EXCLUDED} entries no world ever places. Shared by the server (the award and the one-time
 * credit for the old 150-biome version, {@link TerraOmniaMigration}) and the client's checklist, so both
 * list the biomes in one order: the vanilla Overworld, the Nether, the End, then Biomes O' Plenty,
 * BetterNether, BetterEnd and Deeper Darker, then anything else; alphabetical within each.
 */
public final class TerraOmnia {

    /** The advancement. */
    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/ridden_biomes_all");

    /** The retired 150-biome version, still in the profiles of everyone who earned it. */
    public static final ResourceLocation OLD_ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/ridden_biomes_150");

    /** How many biomes the retired version asked for: what its earners are credited with. */
    public static final int OLD_THRESHOLD = 150;

    /** The {@code gameplay_action} fired once every biome has been ridden through. */
    public static final String ACTION = "rode_every_biome";

    /** Modded biome namespaces, in the order they follow the vanilla dimensions. */
    private static final List<String> MOD_ORDER = List.of("biomesoplenty", "betternether", "betterend", "deeperdarker");

    /**
     * Registry entries no world ever places, so nobody could stand in them: the superflat void preset's biome,
     * and TerraBlender's stand-in for biomes it resolves later.
     */
    static final Set<ResourceLocation> EXCLUDED = Set.of(
        ResourceLocation.withDefaultNamespace("the_void"),
        ResourceLocation.fromNamespaceAndPath("terrablender", "deferred_placeholder"));

    private static final int OVERWORLD = 0;
    private static final int NETHER = 1;
    private static final int END = 2;
    private static final int OTHER = 3 + MOD_ORDER.size();

    private TerraOmnia() {}

    /** Whether {@code ridden} holds every biome in {@code all}. An empty registry completes nothing. */
    public static boolean isComplete(Set<ResourceLocation> ridden, Collection<ResourceLocation> all) {
        return !all.isEmpty() && ridden.containsAll(all);
    }

    /** Whether {@code ridden} holds every biome Terra Omnia asks for in {@code registry}. */
    public static boolean isComplete(Set<ResourceLocation> ridden, Registry<Biome> registry) {
        return isComplete(ridden, ordered(registry).keySet());
    }

    /**
     * Whether the stored biome ids {@code have} cover every id in {@code registryIds} a world can place
     * (less {@link #EXCLUDED}). Unparsable stored ids are ignored. Used by the every-biome photo
     * advancement, so it asks for the same biomes as Terra Omnia. Pure.
     */
    public static boolean coversEvery(Collection<String> have, Collection<ResourceLocation> registryIds) {
        Set<ResourceLocation> parsed = new HashSet<>();
        for (String s : have) {
            ResourceLocation id = ResourceLocation.tryParse(s);
            if (id != null) parsed.add(id);
        }
        List<ResourceLocation> asked = new ArrayList<>(registryIds);
        asked.removeIf(EXCLUDED::contains);
        return isComplete(parsed, asked);
    }

    /** Every biome Terra Omnia asks for in {@code registry}, keyed in checklist order, each with its group. */
    public static Map<ResourceLocation, Integer> ordered(Registry<Biome> registry) {
        Map<ResourceLocation, Integer> groups = new LinkedHashMap<>();
        registry.holders().forEach(h -> groups.put(h.key().location(), groupOf(h)));
        return ordered(groups);
    }

    /** {@code groups} less {@link #EXCLUDED}, sorted by group, then id. Pure; package-private for tests. */
    static Map<ResourceLocation, Integer> ordered(Map<ResourceLocation, Integer> groups) {
        List<Map.Entry<ResourceLocation, Integer>> entries = new ArrayList<>(groups.entrySet());
        entries.removeIf(e -> EXCLUDED.contains(e.getKey()));
        entries.sort(Map.Entry.<ResourceLocation, Integer>comparingByValue()
            .thenComparing(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString))));
        Map<ResourceLocation, Integer> out = new LinkedHashMap<>();
        entries.forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** A biome's group: a vanilla one by the dimension its tags place it in, a modded one by its mod. */
    private static int groupOf(Holder<Biome> biome) {
        String namespace = biome.unwrapKey().map(k -> k.location().getNamespace()).orElse("");
        boolean nether = biome.is(BiomeTags.IS_NETHER);
        boolean end = biome.is(BiomeTags.IS_END);
        return groupOf(namespace, nether, end);
    }

    /** Pure form of {@link #groupOf(Holder)}; package-private for tests. */
    static int groupOf(String namespace, boolean nether, boolean end) {
        if ("minecraft".equals(namespace)) return nether ? NETHER : end ? END : OVERWORLD;
        int mod = MOD_ORDER.indexOf(namespace);
        return mod >= 0 ? 3 + mod : OTHER;
    }

    /**
     * The biomes to add so {@code have} reaches {@code target}, taken from {@code order} front first and
     * skipping any already held. Empty when {@code have} is already there. Pure.
     */
    public static List<ResourceLocation> topUp(Set<ResourceLocation> have, Collection<ResourceLocation> order, int target) {
        List<ResourceLocation> add = new ArrayList<>();
        int missing = target - have.size();
        for (ResourceLocation id : order) {
            if (add.size() >= missing) break;
            if (!have.contains(id)) add.add(id);
        }
        return List.copyOf(add);
    }
}
