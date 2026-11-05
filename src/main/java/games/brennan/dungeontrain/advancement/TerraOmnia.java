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
 *
 * <p>Five Biomes O' Plenty biomes are so rare that riding every one of them was the wall at the end, so
 * Terra Omnia asks for only {@link #RARE_NEEDED} of the {@link #RARE} ones. Coffee Table Book wants a photo in
 * {@link #COFFEE_TABLE_RARE_NEEDED} of them ({@link #isCoffeeTableComplete}).</p>
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
     * Registry entries a rider can never be in, so Terra Omnia never asks for them: the superflat void preset's
     * biome and TerraBlender's stand-in for biomes it resolves later (no world places either); BetterEnd's cave
     * biomes, which only exist underground, never at the track; and BetterNether's edge and variant sub-biomes,
     * which DT's Nether never places ({@code BetterNetherCoreBiomes#isStandaloneBiomePath}). A track probe over
     * 150 cycles found none of them.
     */
    static final Set<ResourceLocation> EXCLUDED = Set.of(
        ResourceLocation.withDefaultNamespace("the_void"),
        ResourceLocation.fromNamespaceAndPath("terrablender", "deferred_placeholder"),
        ResourceLocation.fromNamespaceAndPath("betterend", "empty_aurora_cave"),
        ResourceLocation.fromNamespaceAndPath("betterend", "empty_end_cave"),
        ResourceLocation.fromNamespaceAndPath("betterend", "empty_smaragdant_cave"),
        ResourceLocation.fromNamespaceAndPath("betterend", "jade_cave"),
        ResourceLocation.fromNamespaceAndPath("betterend", "lush_aurora_cave"),
        ResourceLocation.fromNamespaceAndPath("betterend", "lush_smaragdant_cave"),
        ResourceLocation.fromNamespaceAndPath("betternether", "nether_mushroom_forest_edge"),
        ResourceLocation.fromNamespaceAndPath("betternether", "nether_swampland_terraces"),
        ResourceLocation.fromNamespaceAndPath("betternether", "upside_down_forest_cleared"),
        ResourceLocation.fromNamespaceAndPath("betternether", "wart_forest_edge"));

    /** The rare biomes Terra Omnia asks only {@link #RARE_NEEDED} of. Twice as common in the BoP stretch too. */
    public static final Set<ResourceLocation> RARE = Set.of(
        ResourceLocation.fromNamespaceAndPath("biomesoplenty", "wintry_origin_valley"),
        ResourceLocation.fromNamespaceAndPath("biomesoplenty", "origin_valley"),
        ResourceLocation.fromNamespaceAndPath("biomesoplenty", "hot_springs"),
        ResourceLocation.fromNamespaceAndPath("biomesoplenty", "auroral_garden"),
        ResourceLocation.fromNamespaceAndPath("biomesoplenty", "tropics"));

    /** How many of {@link #RARE} Terra Omnia asks for. */
    public static final int RARE_NEEDED = 2;

    /** How many of {@link #RARE} Coffee Table Book asks for a photo in. */
    public static final int COFFEE_TABLE_RARE_NEEDED = 4;

    private static final int OVERWORLD = 0;
    private static final int NETHER = 1;
    private static final int END = 2;
    private static final int OTHER = 3 + MOD_ORDER.size();

    private TerraOmnia() {}

    /**
     * Whether {@code ridden} finishes Terra Omnia over {@code all}: every biome outside {@link #RARE}, and
     * {@link #RARE_NEEDED} of the rare ones {@code all} holds (all of them when it holds fewer). An empty
     * {@code all} completes nothing.
     */
    public static boolean isComplete(Set<ResourceLocation> ridden, Collection<ResourceLocation> all) {
        Progress p = progress(ridden, all);
        return p.needed() > 0 && p.counted() >= p.needed();
    }

    /** Whether {@code ridden} finishes Terra Omnia over the biomes it asks for in {@code registry}. */
    public static boolean isComplete(Set<ResourceLocation> ridden, Registry<Biome> registry) {
        return isComplete(ridden, ordered(registry).keySet());
    }

    /**
     * Whether {@code have} finishes Coffee Table Book over {@code all}: every biome outside {@link #RARE}, and
     * {@link #COFFEE_TABLE_RARE_NEEDED} of the rare ones {@code all} holds (all of them when it holds fewer). An
     * empty {@code all} completes nothing.
     */
    public static boolean isCoffeeTableComplete(Set<ResourceLocation> have, Collection<ResourceLocation> all) {
        Progress p = coffeeTableProgress(have, all);
        return p.needed() > 0 && p.counted() >= p.needed();
    }

    /** Whether {@code have} finishes Coffee Table Book over the biomes Terra Omnia lists in {@code registry}. */
    public static boolean isCoffeeTableComplete(Set<ResourceLocation> have, Registry<Biome> registry) {
        return isCoffeeTableComplete(have, ordered(registry).keySet());
    }

    /** A biome count: biomes that count toward the advancement out of how many it needs. */
    public record Progress(int counted, int needed) {}

    /** The rare biomes count only up to {@link #RARE_NEEDED}; every other biome in {@code all} counts once. Pure. */
    public static Progress progress(Set<ResourceLocation> ridden, Collection<ResourceLocation> all) {
        return progress(ridden, all, RARE_NEEDED);
    }

    /** The rare biomes count only up to {@link #COFFEE_TABLE_RARE_NEEDED}; every other biome in {@code all} counts once. Pure. */
    public static Progress coffeeTableProgress(Set<ResourceLocation> have, Collection<ResourceLocation> all) {
        return progress(have, all, COFFEE_TABLE_RARE_NEEDED);
    }

    private static Progress progress(Set<ResourceLocation> ridden, Collection<ResourceLocation> all, int rareWanted) {
        int common = 0;
        int commonRidden = 0;
        int rare = 0;
        int rareRidden = 0;
        for (ResourceLocation id : all) {
            boolean held = ridden.contains(id);
            if (RARE.contains(id)) {
                rare++;
                if (held) rareRidden++;
            } else {
                common++;
                if (held) commonRidden++;
            }
        }
        int rareNeeded = Math.min(rareWanted, rare);
        return new Progress(commonRidden + Math.min(rareRidden, rareNeeded), common + rareNeeded);
    }

    /** Whether {@code biome} is a rare one {@code ridden} no longer needs, because enough of the others are held. */
    public static boolean isSpareRare(ResourceLocation biome, Set<ResourceLocation> ridden) {
        if (!RARE.contains(biome) || ridden.contains(biome)) return false;
        return ridden.stream().filter(RARE::contains).count() >= RARE_NEEDED;
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
