package games.brennan.dungeontrain.worldgen.density;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.mixin.MultiNoiseBiomeSourceAccessor;
import games.brennan.dungeontrain.worldgen.SecondLapOverworld;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.slf4j.Logger;
import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.Regions;
import terrablender.worldgen.IExtendedParameterList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The overworld biome for a column, confined by stretch: Biomes O' Plenty only in the
 * {@link SecondLapOverworld.Stretch#BOP BOP} stretch, vanilla everywhere else.
 *
 * <p>TerraBlender blends BoP's regions into the live overworld source for the whole world, and also
 * moves vanilla biomes around doing it. So this class re-picks every overworld biome from its own
 * climate tables, sampled with the live overworld sampler — biomes still follow the terrain climate:</p>
 * <ul>
 *   <li><b>vanilla</b> — vanilla's own overworld preset table ({@code knownPresets}, which TerraBlender
 *       leaves alone), keys filtered to {@code minecraft:}. Identical to the pre-BoP world, so existing
 *       saves keep their biome layout outside the BoP stretch.</li>
 *   <li><b>BoP</b> — one table per BoP TerraBlender region, from the region's own biome points. The
 *       region at a column is TerraBlender's own region layout ({@code getUniqueness}), with non-BoP
 *       regions mapped onto a BoP one, so every column of the stretch is a BoP region. A point BoP
 *       leaves as TerraBlender's "deferred" placeholder falls through to vanilla, as TerraBlender does.</li>
 * </ul>
 *
 * <p>The region layout is read from the source being asked — TerraBlender gives each chunk fill its
 * own clone of the source and layout, because the layout's cache isn't thread-safe.</p>
 *
 * <p>Published at the same moments as {@link NetherBandContext}. While nothing is published, a source
 * already marked as the overworld still gets a vanilla pick from {@link #vanillaFallback} rather than
 * TerraBlender's — so a lost context can never put BoP biomes into a vanilla stretch.</p>
 */
public final class OverworldStretchBiomes {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String BOP_NAMESPACE = "biomesoplenty";

    private static volatile OverworldStretchBiomes current;

    /** Vanilla's overworld climate table ({@code minecraft:} keys only). Registry-free, so built once per JVM. */
    private static volatile Climate.ParameterList<ResourceKey<Biome>> vanillaTable;

    /** Key → holder for the last source {@link #vanillaFallback} served, cached against its biome set. */
    private static volatile HolderLookup fallbackHolders;

    private record HolderLookup(Set<Holder<Biome>> possible, Map<ResourceKey<Biome>, Holder<Biome>> byKey) {}

    /**
     * The tables hold resolved holders, not keys: {@link #pick} runs once per quart, so the registry
     * lookup (a hash probe + an {@code Optional}) is done once per table entry at {@link #resolve}
     * instead. Each table keeps its key table's points in the same order, so its search tree — built
     * from the points alone — is identical and finds the same entry. The vanilla table maps an entry
     * missing from the registry to {@link #fallback}; a BoP table maps TerraBlender's deferred
     * placeholder and missing entries to {@code null}, which falls through to the vanilla table.
     */
    private final Climate.ParameterList<Holder<Biome>> vanilla;
    private final List<Climate.ParameterList<Holder<Biome>>> bopRegions;
    private final Map<ResourceLocation, Integer> bopRegionIndex;
    private final Holder<Biome> fallback;

    private OverworldStretchBiomes(Climate.ParameterList<Holder<Biome>> vanilla,
                                   List<Climate.ParameterList<Holder<Biome>>> bopRegions,
                                   Map<ResourceLocation, Integer> bopRegionIndex, Holder<Biome> fallback) {
        this.vanilla = vanilla;
        this.bopRegions = bopRegions;
        this.bopRegionIndex = bopRegionIndex;
        this.fallback = fallback;
    }

    public static OverworldStretchBiomes current() {
        return current;
    }

    public static void publish(OverworldStretchBiomes value) {
        current = value;
    }

    public static void clear() {
        current = null;
    }

    /** Number of BoP regions found — 0 means the BoP stretch falls back to vanilla. */
    public int bopRegionCount() {
        return bopRegions.size();
    }

    /**
     * The biome for the quart {@code (qx, qy, qz)} in this stretch. The {@link SecondLapOverworld.Stretch#WWOO}
     * stretch picks vanilla biomes — WWOO's look comes from its features, not its biome ids.
     */
    public Holder<Biome> pick(SecondLapOverworld.Stretch stretch, MultiNoiseBiomeSource source,
                              int qx, int qy, int qz, Climate.Sampler sampler) {
        Climate.TargetPoint target = sampler.sample(qx, qy, qz);
        if (stretch == SecondLapOverworld.Stretch.BOP && !bopRegions.isEmpty()) {
            Holder<Biome> h = bopRegionAt(regionLayout(source), qx, qy, qz).findValue(target);
            if (h != null) return h;                       // deferred / missing → vanilla
        }
        return vanilla.findValue(target);                  // missing entries are the fallback already
    }

    /**
     * TerraBlender's region layout is its internal {@code IExtendedParameterList}, not its API, and DT
     * declares TerraBlender {@code [floor,)}. Probed once: if an update moved it, the BoP stretch uses its
     * first region throughout (the no-layout path below) instead of failing on every quart.
     */
    private static final boolean REGION_LAYOUT_USABLE = probeRegionLayout();

    private static boolean probeRegionLayout() {
        try {
            Class<?> api = Class.forName("terrablender.worldgen.IExtendedParameterList", false,
                    OverworldStretchBiomes.class.getClassLoader());
            boolean usable = api.getMethod("isInitialized").getReturnType() == boolean.class
                    && api.getMethod("getUniqueness", int.class, int.class, int.class).getReturnType() == int.class
                    && api.getMethod("getRegion", int.class).getReturnType() == Region.class;
            if (!usable) warnRegionLayoutMoved("method signatures changed");
            return usable;
        } catch (ReflectiveOperationException | LinkageError e) {
            warnRegionLayoutMoved(e.toString());
            return false;
        }
    }

    private static void warnRegionLayoutMoved(String why) {
        LOGGER.warn("[DungeonTrain] TerraBlender's IExtendedParameterList no longer matches ({}); the Biomes O' "
                + "Plenty stretch uses one BoP region throughout. Re-check the TerraBlender list in gradle.properties.", why);
    }

    /** TerraBlender's region layout on this source (or its clone), or {@code null} without one. */
    private static IExtendedParameterList<?> regionLayout(MultiNoiseBiomeSource source) {
        if (!REGION_LAYOUT_USABLE) return null;
        return ((MultiNoiseBiomeSourceAccessor) source).dungeontrain$parameters()
                instanceof IExtendedParameterList<?> ext ? ext : null;
    }

    private Climate.ParameterList<Holder<Biome>> bopRegionAt(IExtendedParameterList<?> regionLayout,
                                                             int qx, int qy, int qz) {
        if (regionLayout == null || !regionLayout.isInitialized()) return bopRegions.get(0);
        int index = regionLayout.getUniqueness(qx, qy, qz);
        Region region = regionLayout.getRegion(index);
        Integer own = region == null ? null : bopRegionIndex.get(region.getName());
        return bopRegions.get(own != null ? own : Math.floorMod(index, bopRegions.size()));
    }

    /** Build from the server's biome registry and TerraBlender's regions; {@code null} on any failure. */
    public static OverworldStretchBiomes resolve(MinecraftServer server) {
        try {
            Registry<Biome> biomes = server.registryAccess().registryOrThrow(Registries.BIOME);
            Holder<Biome> fallback = biomes.getHolderOrThrow(Biomes.PLAINS);
            Function<ResourceKey<Biome>, Holder<Biome>> holder = key -> biomes.getHolder(key).orElse(null);

            Climate.ParameterList<Holder<Biome>> vanilla = withHolders(vanillaTable(),
                    key -> { Holder<Biome> h = holder.apply(key); return h != null ? h : fallback; });

            List<Climate.ParameterList<Holder<Biome>>> bopRegions = new ArrayList<>();
            Map<ResourceLocation, Integer> bopRegionIndex = new HashMap<>();
            for (Region region : Regions.get(RegionType.OVERWORLD)) {
                if (!BOP_NAMESPACE.equals(region.getName().getNamespace())) continue;
                List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> points = new ArrayList<>();
                region.addBiomes(biomes, p -> {
                    ResourceKey<Biome> key = p.getSecond();
                    if (key == Region.DEFERRED_PLACEHOLDER || biomes.containsKey(key)) points.add(p);
                });
                if (points.isEmpty()) continue;
                bopRegionIndex.put(region.getName(), bopRegions.size());
                bopRegions.add(withHolders(new Climate.ParameterList<>(points),
                        key -> key == Region.DEFERRED_PLACEHOLDER ? null : holder.apply(key)));
            }

            return new OverworldStretchBiomes(vanilla, List.copyOf(bopRegions),
                    Map.copyOf(bopRegionIndex), fallback);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Failed to build the second-lap overworld biome tables; overworld stays as generated", t);
            return null;
        }
    }

    /**
     * The same table with every key mapped through {@code holder} — same points, same order, so the
     * search tree is identical to the key table's and every lookup lands on the mapped entry.
     */
    static Climate.ParameterList<Holder<Biome>> withHolders(Climate.ParameterList<ResourceKey<Biome>> keyed,
                                                            Function<ResourceKey<Biome>, Holder<Biome>> holder) {
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> mapped = new ArrayList<>(keyed.values().size());
        for (Pair<Climate.ParameterPoint, ResourceKey<Biome>> p : keyed.values()) {
            mapped.add(Pair.of(p.getFirst(), holder.apply(p.getSecond())));
        }
        return new Climate.ParameterList<>(mapped);
    }

    /**
     * The vanilla overworld biome for the quart, without any published context: the same table and
     * sampler as {@link #pick}'s vanilla branch, resolved to a holder through the source's own biomes
     * (TerraBlender's source still carries every vanilla one). {@code null} if the biome isn't there.
     */
    public static Holder<Biome> vanillaFallback(MultiNoiseBiomeSource source, int qx, int qy, int qz,
                                                Climate.Sampler sampler) {
        ResourceKey<Biome> key = vanillaTable().findValue(sampler.sample(qx, qy, qz));
        return holdersOf(source).get(key);
    }

    /** Vanilla's overworld preset table filtered to {@code minecraft:} keys — cached, registry-free. */
    public static Climate.ParameterList<ResourceKey<Biome>> vanillaTable() {
        Climate.ParameterList<ResourceKey<Biome>> table = vanillaTable;
        if (table == null) {
            table = vanillaOnly(MultiNoiseBiomeSourceParameterList.knownPresets()
                    .get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD));
            vanillaTable = table;
        }
        return table;
    }

    private static Map<ResourceKey<Biome>, Holder<Biome>> holdersOf(MultiNoiseBiomeSource source) {
        // possibleBiomes() is memoised on the source and shared by TerraBlender's shallow clones,
        // so its identity is a cheap cache key.
        Set<Holder<Biome>> possible = source.possibleBiomes();
        HolderLookup lookup = fallbackHolders;
        if (lookup == null || lookup.possible() != possible) {
            Map<ResourceKey<Biome>, Holder<Biome>> byKey = new HashMap<>();
            for (Holder<Biome> h : possible) h.unwrapKey().ifPresent(k -> byKey.putIfAbsent(k, h));
            lookup = new HolderLookup(possible, Map.copyOf(byKey));
            fallbackHolders = lookup;
        }
        return lookup.byKey();
    }

    private static Climate.ParameterList<ResourceKey<Biome>> vanillaOnly(Climate.ParameterList<ResourceKey<Biome>> preset) {
        List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> kept = new ArrayList<>();
        for (Pair<Climate.ParameterPoint, ResourceKey<Biome>> p : preset.values()) {
            if (ResourceLocation.DEFAULT_NAMESPACE.equals(p.getSecond().location().getNamespace())) kept.add(p);
        }
        return new Climate.ParameterList<>(kept);
    }

    /** True for a Biomes O' Plenty biome — the Nether and End bands swap these for vanilla. */
    public static boolean isBop(Holder<Biome> biome) {
        return biome != null && biome.unwrapKey()
                .map(k -> BOP_NAMESPACE.equals(k.location().getNamespace()))
                .orElse(false);
    }
}
