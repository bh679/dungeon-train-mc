package games.brennan.dungeontrain.worldgen;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * What each biome decorates with <b>outside</b> the William Wythers' Overhauled Overworld stretch.
 *
 * <p>WWOO is a datapack: it rewrites 53 {@code minecraft:} biome files, swapping their features for its
 * own {@code wythers:} ones, and it overrides a few {@code minecraft:} placed/configured features (lava
 * lakes, and — through its auto-enabled {@code remove_ores} pack — dirt/gravel/stone-variant ores).
 * Choosing biomes can't confine that, so this class rebuilds each biome's vanilla feature list and
 * {@link WwooDecorationPass} decorates from it everywhere except the WWOO stretch.</p>
 *
 * <p>The vanilla lists come from {@link VanillaRegistries#createLookup()}, which builds the vanilla
 * worldgen registries from code — datapacks can't touch it. Per biome and step, the <b>target</b> list
 * is the vanilla one plus whatever NeoForge biome modifiers added (so DT's own features and other
 * mods' injected ones survive). A live feature whose JSON differs from the vanilla one is
 * <b>overridden</b>: it never places outside the stretch, and the vanilla version places instead.</p>
 *
 * <p>Built once per server at start ({@link #publish}); {@code null} means "no WWOO changes found" and
 * leaves decoration untouched.</p>
 */
public final class VanillaBiomeFeatures {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final int STEPS = GenerationStep.Decoration.values().length;
    /** Feature-seed indices for the vanilla pass start here, clear of the live loop's own indices. */
    private static final int EXTRA_SEED_BASE = 10_000;

    private static volatile VanillaBiomeFeatures current;

    /** One vanilla feature the pass places, with its stable feature-seed index. */
    record Extra(ResourceKey<PlacedFeature> key, PlacedFeature feature, int seedIndex) {}

    /**
     * A biome WWOO changed. {@code liveAllowed}: live features that may still place here outside the
     * stretch. {@code extraAllowed}/{@code extraPerStep}: the vanilla features the pass places here.
     */
    record BiomeRule(Set<ResourceKey<PlacedFeature>> liveAllowed, Set<ResourceKey<PlacedFeature>> extraAllowed,
                     List<List<Extra>> extraPerStep) {}

    private final Map<ResourceKey<Biome>, BiomeRule> rules;
    private final Set<ResourceKey<PlacedFeature>> veto;
    private final Map<PlacedFeature, ResourceKey<PlacedFeature>> keyOf;
    private final int overriddenCount;

    private VanillaBiomeFeatures(Map<ResourceKey<Biome>, BiomeRule> rules, Set<ResourceKey<PlacedFeature>> veto,
                                 Map<PlacedFeature, ResourceKey<PlacedFeature>> keyOf, int overriddenCount) {
        this.rules = rules;
        this.veto = veto;
        this.keyOf = keyOf;
        this.overriddenCount = overriddenCount;
    }

    public static VanillaBiomeFeatures current() {
        return current;
    }

    public static void publish(VanillaBiomeFeatures value) {
        current = value;
    }

    public static void clear() {
        current = null;
    }

    /** One-line summary for {@code /dungeontrain debug overworld-laps}. */
    public static String describe() {
        VanillaBiomeFeatures v = current;
        if (v == null) return "untouched";
        return "biomes=" + v.rules.size() + " overridden=" + v.overriddenCount + " vetoed=" + v.veto.size();
    }

    BiomeRule rule(ResourceKey<Biome> biome) {
        return biome == null ? null : rules.get(biome);
    }

    boolean vetoed(PlacedFeature feature) {
        ResourceKey<PlacedFeature> key = keyOf.get(feature);
        return key != null && veto.contains(key);
    }

    ResourceKey<PlacedFeature> keyOf(PlacedFeature feature) {
        return keyOf.get(feature);
    }

    /** Build from the live registries; {@code null} when WWOO changed nothing (or on failure). */
    public static VanillaBiomeFeatures resolve(MinecraftServer server) {
        long t0 = System.nanoTime();
        try {
            VanillaBiomeFeatures built = build(server.registryAccess());
            LOGGER.info("[DungeonTrain] Vanilla biome features outside the WWOO stretch: {} ({} ms)",
                    built == null ? "no changes found" : built.rules.size() + " biomes, "
                            + built.overriddenCount + " overridden features, " + built.veto.size() + " vetoed",
                    (System.nanoTime() - t0) / 1_000_000L);
            return built;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Failed to build vanilla biome features; WWOO applies everywhere this session", t);
            return null;
        }
    }

    private static VanillaBiomeFeatures build(RegistryAccess live) {
        HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
        Registry<Biome> liveBiomes = live.registryOrThrow(Registries.BIOME);
        Registry<PlacedFeature> livePlaced = live.registryOrThrow(Registries.PLACED_FEATURE);
        HolderLookup.RegistryLookup<Biome> vanillaBiomes = vanilla.lookupOrThrow(Registries.BIOME);
        HolderLookup.RegistryLookup<PlacedFeature> vanillaPlaced = vanilla.lookupOrThrow(Registries.PLACED_FEATURE);

        Set<ResourceKey<PlacedFeature>> overridden = overriddenFeatures(live, vanilla, livePlaced, vanillaPlaced);

        Map<ResourceKey<Biome>, List<Set<ResourceKey<PlacedFeature>>>> targets = new HashMap<>();
        Map<ResourceKey<Biome>, List<Set<ResourceKey<PlacedFeature>>>> lives = new HashMap<>();
        Set<ResourceKey<PlacedFeature>> allLive = new HashSet<>();
        Set<ResourceKey<PlacedFeature>> allTarget = new HashSet<>();
        for (Map.Entry<ResourceKey<Biome>, Biome> e : liveBiomes.entrySet()) {
            Biome biome = e.getValue();
            List<Set<ResourceKey<PlacedFeature>>> modified = keysPerStep(biome.getGenerationSettings().features());
            List<Set<ResourceKey<PlacedFeature>>> target = modified;
            Optional<Holder.Reference<Biome>> vanillaBiome = vanillaBiomes.get(e.getKey());
            if (vanillaBiome.isPresent()) {
                List<Set<ResourceKey<PlacedFeature>>> original = keysPerStep(biome.modifiableBiomeInfo()
                        .getOriginalBiomeInfo().generationSettings().features());
                target = keysPerStep(vanillaBiome.get().value().getGenerationSettings().features());
                for (int s = 0; s < STEPS; s++) {
                    for (ResourceKey<PlacedFeature> k : modified.get(s)) {
                        if (!original.get(s).contains(k)) target.get(s).add(k); // added by a biome modifier
                    }
                }
            }
            targets.put(e.getKey(), target);
            lives.put(e.getKey(), modified);
            modified.forEach(allLive::addAll);
            target.forEach(allTarget::addAll);
        }

        Set<ResourceKey<PlacedFeature>> veto = new HashSet<>(overridden);
        for (ResourceKey<PlacedFeature> k : allLive) if (!allTarget.contains(k)) veto.add(k);

        Map<PlacedFeature, ResourceKey<PlacedFeature>> keyOf = new IdentityHashMap<>();
        livePlaced.entrySet().forEach(e -> keyOf.put(e.getValue(), e.getKey()));
        List<Map<ResourceKey<PlacedFeature>, Integer>> seedIndex = new ArrayList<>();
        for (int s = 0; s < STEPS; s++) seedIndex.add(new TreeMap<>(Comparator.comparing(k -> k.location().toString())));

        Map<ResourceKey<Biome>, BiomeRule> rules = new HashMap<>();
        Map<ResourceKey<Biome>, List<Set<ResourceKey<PlacedFeature>>>> extras = new HashMap<>();
        for (ResourceKey<Biome> biome : targets.keySet()) {
            List<Set<ResourceKey<PlacedFeature>>> target = targets.get(biome);
            List<Set<ResourceKey<PlacedFeature>>> liveList = lives.get(biome);
            if (target.equals(liveList) && Collections.disjoint(flatten(liveList), overridden)) continue;
            List<Set<ResourceKey<PlacedFeature>>> extra = new ArrayList<>();
            for (int s = 0; s < STEPS; s++) {
                Set<ResourceKey<PlacedFeature>> e = new LinkedHashSet<>();
                for (ResourceKey<PlacedFeature> k : target.get(s)) {
                    if (!liveList.get(s).contains(k) || overridden.contains(k)) e.add(k);
                }
                Map<ResourceKey<PlacedFeature>, Integer> stepSeeds = seedIndex.get(s);
                e.forEach(k -> stepSeeds.put(k, 0));
                extra.add(e);
            }
            extras.put(biome, extra);
        }
        for (int s = 0; s < STEPS; s++) {
            int i = EXTRA_SEED_BASE;
            for (Map.Entry<ResourceKey<PlacedFeature>, Integer> e : seedIndex.get(s).entrySet()) e.setValue(i++);
        }

        Map<ResourceKey<PlacedFeature>, PlacedFeature> placeable = new HashMap<>();
        for (Map.Entry<ResourceKey<Biome>, List<Set<ResourceKey<PlacedFeature>>>> e : extras.entrySet()) {
            ResourceKey<Biome> biome = e.getKey();
            List<Set<ResourceKey<PlacedFeature>>> target = targets.get(biome);
            List<Set<ResourceKey<PlacedFeature>>> liveList = lives.get(biome);
            Set<ResourceKey<PlacedFeature>> liveAllowed = new HashSet<>();
            Set<ResourceKey<PlacedFeature>> extraAllowed = new HashSet<>();
            List<List<Extra>> perStep = new ArrayList<>();
            for (int s = 0; s < STEPS; s++) {
                for (ResourceKey<PlacedFeature> k : liveList.get(s)) {
                    if (target.get(s).contains(k) && !overridden.contains(k)) liveAllowed.add(k);
                }
                List<Extra> step = new ArrayList<>();
                for (ResourceKey<PlacedFeature> k : e.getValue().get(s)) {
                    PlacedFeature pf = placeable.computeIfAbsent(k,
                            key -> placeableFeature(key, overridden, livePlaced, vanillaPlaced));
                    if (pf == null) continue;
                    keyOf.putIfAbsent(pf, k);
                    extraAllowed.add(k);
                    step.add(new Extra(k, pf, seedIndex.get(s).get(k)));
                }
                step.sort((a, b) -> Integer.compare(a.seedIndex(), b.seedIndex()));
                perStep.add(List.copyOf(step));
            }
            rules.put(biome, new BiomeRule(Set.copyOf(liveAllowed), Set.copyOf(extraAllowed), List.copyOf(perStep)));
        }
        if (rules.isEmpty()) return null;
        return new VanillaBiomeFeatures(Map.copyOf(rules), Set.copyOf(veto), keyOf, overridden.size());
    }

    /** The feature the pass places for {@code key}: the live one unless it was overridden or is missing. */
    private static PlacedFeature placeableFeature(ResourceKey<PlacedFeature> key, Set<ResourceKey<PlacedFeature>> overridden,
                                                  Registry<PlacedFeature> livePlaced,
                                                  HolderLookup.RegistryLookup<PlacedFeature> vanillaPlaced) {
        if (!overridden.contains(key)) {
            PlacedFeature live = livePlaced.get(key);
            if (live != null) return live;
        }
        return vanillaPlaced.get(key).map(Holder::value).orElse(null);
    }

    /** Vanilla placed features whose live JSON (or configured feature's JSON) differs from vanilla's. */
    private static Set<ResourceKey<PlacedFeature>> overriddenFeatures(RegistryAccess live, HolderLookup.Provider vanilla,
                                                                      Registry<PlacedFeature> livePlaced,
                                                                      HolderLookup.RegistryLookup<PlacedFeature> vanillaPlaced) {
        RegistryOps<JsonElement> liveOps = live.createSerializationContext(JsonOps.INSTANCE);
        RegistryOps<JsonElement> vanillaOps = vanilla.createSerializationContext(JsonOps.INSTANCE);
        Set<ResourceKey<PlacedFeature>> out = new HashSet<>();
        vanillaPlaced.listElements().forEach(ref -> {
            PlacedFeature livePf = livePlaced.get(ref.key());
            if (livePf == null) return;
            if (differs(PlacedFeature.DIRECT_CODEC.encodeStart(liveOps, livePf).result(),
                    PlacedFeature.DIRECT_CODEC.encodeStart(vanillaOps, ref.value()).result())
                    || differs(ConfiguredFeature.DIRECT_CODEC.encodeStart(liveOps, livePf.feature().value()).result(),
                    ConfiguredFeature.DIRECT_CODEC.encodeStart(vanillaOps, ref.value().feature().value()).result())) {
                out.add(ref.key());
            }
        });
        return out;
    }

    /** Only a definite difference counts — an encode failure on either side is treated as "same". */
    private static boolean differs(Optional<JsonElement> a, Optional<JsonElement> b) {
        return a.isPresent() && b.isPresent() && !a.get().equals(b.get());
    }

    private static List<Set<ResourceKey<PlacedFeature>>> keysPerStep(List<HolderSet<PlacedFeature>> steps) {
        List<Set<ResourceKey<PlacedFeature>>> out = new ArrayList<>(STEPS);
        for (int s = 0; s < STEPS; s++) {
            Set<ResourceKey<PlacedFeature>> keys = new LinkedHashSet<>();
            if (s < steps.size()) steps.get(s).forEach(h -> h.unwrapKey().ifPresent(keys::add));
            out.add(keys);
        }
        return out;
    }

    private static Set<ResourceKey<PlacedFeature>> flatten(List<Set<ResourceKey<PlacedFeature>>> steps) {
        Set<ResourceKey<PlacedFeature>> out = new HashSet<>();
        steps.forEach(out::addAll);
        return out;
    }
}
