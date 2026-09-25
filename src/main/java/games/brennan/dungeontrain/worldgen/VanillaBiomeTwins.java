package games.brennan.dungeontrain.worldgen;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * The <b>vanilla twin</b> of every biome WWOO changed, and the rule for when it stands in.
 *
 * <p>WWOO rewrites vanilla biome files, and that reaches past the features {@link VanillaBiomeFeatures}
 * confines: the biome's colours, fog and sky, climate (snow and ice), ambient sounds and music, and
 * mob spawns all belong to the biome id and would apply on every lap. So for each live
 * {@code minecraft:} biome whose climate, effects or spawns differ from vanilla's, the vanilla
 * {@code Biome} (rebuilt from code by {@link VanillaWorldgenLookup#create()}, which datapacks can't
 * touch) is kept as its twin. {@code BiomeMixin} and {@code ChunkGeneratorSpawnsMixin} answer from the
 * twin wherever the question has a position outside the WWOO stretch; inside it, WWOO answers.</p>
 *
 * <p>Built per side: the server at start, the client at login (a dedicated-server client has its own
 * biome objects). Singleplayer builds both into the same table — the keys are distinct objects.</p>
 */
public final class VanillaBiomeTwins {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Biome, Biome> TWINS = new IdentityHashMap<>();
    private static final Map<Biome, MobSpawnSettings> SPAWNS = new IdentityHashMap<>();
    private static volatile boolean any;
    /** The client camera's world X, set by the client at init — {@code null} on a dedicated server. */
    private static volatile DoubleSupplier cameraX;
    /** Client-side "this world has the train" gate; the server reads {@link NetherBandContext} instead. */
    private static volatile boolean clientWorldHasTrain;

    private VanillaBiomeTwins() {}

    /** The vanilla twin to answer for {@code live} at world {@code x}, or {@code null} to let it answer itself. */
    public static Biome twinFor(Biome live, double x) {
        if (!any) return null;
        Biome twin;
        synchronized (TWINS) {
            twin = TWINS.get(live);
        }
        if (twin == null) return null;
        return outsideWwoo(x) ? twin : null;
    }

    /** As {@link #twinFor} but for a question with no position — gated on the camera (client only). */
    public static Biome twinForCamera(Biome live) {
        DoubleSupplier cam = cameraX;
        return cam == null ? null : twinFor(live, cam.getAsDouble());
    }

    /** The mob spawns a twinned biome offers at world {@code x}, or {@code null} to keep the live ones. */
    public static MobSpawnSettings spawnsFor(Biome live, double x) {
        if (twinFor(live, x) == null) return null;
        synchronized (SPAWNS) {
            return SPAWNS.get(live);
        }
    }

    /** True when the WWOO stretch is elsewhere: the twin answers. Pure apart from the cycle lookup. */
    static boolean outsideWwoo(double x) {
        NetherBandContext ctx = NetherBandContext.current();
        WorldGenCycle cycle;
        boolean hasTrain;
        if (ctx != null && ctx.cycle() != null) {
            cycle = ctx.cycle(); // server: the world's published snapshot
            hasTrain = ctx.hasTrain();
        } else {
            cycle = WorldGenCycle.fromConfig(); // client: synced train flag, same gate as the server
            hasTrain = clientWorldHasTrain;
        }
        return SecondLapOverworld.at(cycle, hasTrain, (int) Math.floor(x)) != SecondLapOverworld.Stretch.WWOO;
    }

    public static void setCameraX(DoubleSupplier supplier) {
        cameraX = supplier;
    }

    public static void setClientWorldHasTrain(boolean value) {
        clientWorldHasTrain = value;
    }

    public static int count() {
        synchronized (TWINS) {
            return TWINS.size();
        }
    }

    /** Add this side's twins to the table (server at start, client at login). */
    public static void build(RegistryAccess live, String side) {
        long t0 = System.nanoTime();
        try {
            Map<Biome, Biome> twins = new IdentityHashMap<>();
            Map<Biome, MobSpawnSettings> spawns = new IdentityHashMap<>();
            collect(live, twins, spawns);
            synchronized (TWINS) {
                TWINS.putAll(twins);
            }
            if (LOGGER.isDebugEnabled()) {
                Registry<Biome> reg = live.registryOrThrow(Registries.BIOME);
                LOGGER.debug("[DungeonTrain] Vanilla biome twins ({}): {}", side,
                        twins.keySet().stream().map(b -> String.valueOf(reg.getKey(b))).sorted().toList());
            }
            synchronized (SPAWNS) {
                SPAWNS.putAll(spawns);
            }
            any = count() > 0;
            LOGGER.info("[DungeonTrain] Vanilla biome twins ({}): {} biomes differ from vanilla ({} ms)",
                    side, twins.size(), (System.nanoTime() - t0) / 1_000_000L);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Failed to build vanilla biome twins ({}); WWOO's biome look applies everywhere", side, t);
        }
    }

    public static void clear() {
        synchronized (TWINS) {
            TWINS.clear();
        }
        synchronized (SPAWNS) {
            SPAWNS.clear();
        }
        any = false;
    }

    private static void collect(RegistryAccess live, Map<Biome, Biome> twins, Map<Biome, MobSpawnSettings> spawns) {
        HolderLookup.Provider vanilla = VanillaWorldgenLookup.create();
        HolderLookup.RegistryLookup<Biome> vanillaBiomes = vanilla.lookupOrThrow(Registries.BIOME);
        Registry<Biome> liveBiomes = live.registryOrThrow(Registries.BIOME);
        RegistryOps<JsonElement> liveOps = live.createSerializationContext(JsonOps.INSTANCE);
        RegistryOps<JsonElement> vanillaOps = vanilla.createSerializationContext(JsonOps.INSTANCE);
        WwooDatapack wwoo = WwooDatapack.get();
        for (Map.Entry<ResourceKey<Biome>, Biome> e : liveBiomes.entrySet()) {
            if (!wwoo.biomes().contains(e.getKey())) continue; // BetterNether/BetterEnd edits to other vanilla biomes stay
            Optional<Holder.Reference<Biome>> ref = vanillaBiomes.get(e.getKey());
            if (ref.isEmpty()) continue;
            Biome liveBiome = e.getValue();
            Biome twin = ref.get().value();
            // Climate + effects (the network shape); spawns from the datapack originals, before modifiers.
            boolean lookDiffers = differs(Biome.NETWORK_CODEC.encodeStart(liveOps, liveBiome).result(),
                    Biome.NETWORK_CODEC.encodeStart(vanillaOps, twin).result());
            MobSpawnSettings liveOriginal = liveBiome.modifiableBiomeInfo().getOriginalBiomeInfo().mobSpawnSettings();
            // Client biomes arrive with EMPTY spawns (network codec) — nothing to compare there.
            boolean spawnsDiffer = liveOriginal != MobSpawnSettings.EMPTY && differs(MobSpawnSettings.CODEC.codec().encodeStart(liveOps, liveOriginal).result(),
                    MobSpawnSettings.CODEC.codec().encodeStart(vanillaOps, twin.getMobSettings()).result());
            if (!lookDiffers && !spawnsDiffer) continue;
            twins.put(liveBiome, twin);
            spawns.put(liveBiome, mergedSpawns(liveBiome, twin));
        }
    }

    /** Vanilla's spawns plus whatever biome modifiers added on top of the live datapack list. */
    private static MobSpawnSettings mergedSpawns(Biome live, Biome twin) {
        MobSpawnSettings.Builder b = new MobSpawnSettings.Builder();
        MobSpawnSettings vanilla = twin.getMobSettings();
        MobSpawnSettings original = live.modifiableBiomeInfo().getOriginalBiomeInfo().mobSpawnSettings();
        MobSpawnSettings modified = live.getMobSettings();
        for (MobCategory cat : MobCategory.values()) {
            Set<MobSpawnSettings.SpawnerData> seen = new HashSet<>();
            for (MobSpawnSettings.SpawnerData d : vanilla.getMobs(cat).unwrap()) {
                b.addSpawn(cat, d);
                seen.add(d);
            }
            Set<MobSpawnSettings.SpawnerData> before = new HashSet<>(original.getMobs(cat).unwrap());
            for (MobSpawnSettings.SpawnerData d : modified.getMobs(cat).unwrap()) {
                if (!before.contains(d) && seen.add(d)) b.addSpawn(cat, d); // added by a biome modifier
            }
        }
        for (var type : vanilla.getEntityTypes()) {
            MobSpawnSettings.MobSpawnCost cost = vanilla.getMobSpawnCost(type);
            if (cost != null) b.addMobCharge(type, cost.charge(), cost.energyBudget());
        }
        b.creatureGenerationProbability(vanilla.getCreatureProbability());
        return b.build();
    }

    private static boolean differs(Optional<JsonElement> a, Optional<JsonElement> b) {
        return a.isPresent() && b.isPresent() && !a.get().equals(b.get());
    }
}
