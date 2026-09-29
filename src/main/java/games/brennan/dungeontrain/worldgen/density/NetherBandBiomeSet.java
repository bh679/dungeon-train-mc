package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.NetherBandBiomes;
import games.brennan.dungeontrain.worldgen.structure.AncientCitySite;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link NetherBandBiomes} palette resolved to live {@code Holder<Biome>}s for one world,
 * with the per-world seed baked in. Built once at server start (registry access off the hot path)
 * and stored in {@link NetherBandContext}; the biome-source mixin calls {@link #biomeFor} per quart.
 */
public final class NetherBandBiomeSet {

    private final Holder<Biome>[][] zones; // [zoneIndex][choice]
    private final Holder<Biome>[][] bopZones; // same zones in BoP's look; a missing zone is the vanilla one
    private final Holder<Biome>[] cavePre;    // mountain-interior caves before the core
    private final Holder<Biome>[] cavePost;   // ... and after it (deep dark joins)
    private final long seed;

    private NetherBandBiomeSet(Holder<Biome>[][] zones, Holder<Biome>[][] bopZones,
                               Holder<Biome>[] cavePre, Holder<Biome>[] cavePost, long seed) {
        this.zones = zones;
        this.bopZones = bopZones;
        this.cavePre = cavePre;
        this.cavePost = cavePost;
        this.seed = seed;
    }

    /**
     * Cave biome for a mountain-interior column ({@code BandBiomeDecision.Result.CAVE}), region-varied. On
     * the fall side, the pass's ancient-city region ({@link AncientCitySite}) is always deep dark.
     */
    public Holder<Biome> caveBiomeFor(NetherBandContext ctx, int worldX, int worldZ, boolean pastCore) {
        Holder<Biome>[] palette = pastCore ? cavePost : cavePre;
        if (pastCore && (worldZ >> NetherBandBiomes.CAVE_REGION_SHIFT) == AncientCitySite.REGION_Z) {
            long cell = ancientCityCell(ctx, ctx.cycle().netherPassIndex(worldX));
            if (cell != AncientCitySite.NONE && cell == (worldX >> NetherBandBiomes.CAVE_REGION_SHIFT)) {
                for (Holder<Biome> h : palette) if (h.is(Biomes.DEEP_DARK)) return h;
            }
        }
        return palette[NetherBandBiomes.pickCave(seed, worldX, worldZ, palette.length)];
    }

    /** Per-pass ancient-city region, memoised per published context (the site walk costs a few noise samples). */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> cityCells = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile NetherBandContext cityCtx;

    /** Region-X of pass {@code pass}'s ancient city under {@code ctx}, or {@link AncientCitySite#NONE}. */
    public long ancientCityCell(NetherBandContext ctx, long pass) {
        if (ctx == null || ctx.cycle() == null || ctx.netherCore() == null) return AncientCitySite.NONE;
        if (cityCtx != ctx) {                      // a republished context → drop the memo
            cityCells.clear();
            cityCtx = ctx;
        }
        return cityCells.computeIfAbsent(pass, p -> AncientCitySite.cellX(ctx.cycle(), ctx.generationSeed(), p,
                ctx.seaLevel(), ctx.worldCeiling(), ctx.netherTop(), ctx.baseRelief(), ctx.netherCore().bedY()));
    }

    /** Highland biome for a band column at this world position — altitude-zoned + region-varied. */
    public Holder<Biome> biomeFor(int worldX, int worldY, int worldZ) {
        Holder<Biome>[] zone = zones[NetherBandBiomes.zoneIndex(worldY)];
        return zone[NetherBandBiomes.pickWithinZone(seed, worldX, worldZ, zone.length)];
    }

    /** As {@link #biomeFor}, in Biomes O' Plenty's look — for mountain stages bordering its stretch. */
    public Holder<Biome> bopBiomeFor(int worldX, int worldY, int worldZ) {
        Holder<Biome>[] zone = bopZones[NetherBandBiomes.zoneIndex(worldY)];
        return zone[NetherBandBiomes.pickWithinZone(seed, worldX, worldZ, zone.length)];
    }

    /**
     * Resolve {@link NetherBandBiomes#ZONES} keys to holders via the world's biome registry, and
     * {@link NetherBandBiomes#BOP_ZONES} where present (a zone with none present falls back to vanilla).
     */
    @SuppressWarnings("unchecked")
    public static NetherBandBiomeSet resolve(HolderGetter<Biome> biomes, long seed) {
        List<List<ResourceKey<Biome>>> keyZones = NetherBandBiomes.ZONES;
        Holder<Biome>[][] zones = new Holder[keyZones.size()][];
        Holder<Biome>[][] bopZones = new Holder[keyZones.size()][];
        for (int z = 0; z < keyZones.size(); z++) {
            List<ResourceKey<Biome>> keys = keyZones.get(z);
            Holder<Biome>[] resolved = new Holder[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                resolved[i] = biomes.getOrThrow(keys.get(i));
            }
            zones[z] = resolved;
            List<Holder<Biome>> bop = new ArrayList<>();
            for (ResourceKey<Biome> key : NetherBandBiomes.BOP_ZONES.get(z)) biomes.get(key).ifPresent(bop::add);
            bopZones[z] = bop.isEmpty() ? resolved : bop.toArray(new Holder[0]);
        }
        return new NetherBandBiomeSet(zones, bopZones, resolveAll(biomes, NetherBandBiomes.CAVE_PRE),
                resolveAll(biomes, NetherBandBiomes.CAVE_POST), seed);
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome>[] resolveAll(HolderGetter<Biome> biomes, List<ResourceKey<Biome>> keys) {
        Holder<Biome>[] resolved = new Holder[keys.size()];
        for (int i = 0; i < keys.size(); i++) resolved[i] = biomes.getOrThrow(keys.get(i));
        return resolved;
    }
}
