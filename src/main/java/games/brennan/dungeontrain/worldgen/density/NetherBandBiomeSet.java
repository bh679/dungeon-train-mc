package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.NetherBandBiomes;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import java.util.List;

/**
 * The {@link NetherBandBiomes} palette resolved to live {@code Holder<Biome>}s for one world,
 * with the per-world seed baked in. Built once at server start (registry access off the hot path)
 * and stored in {@link NetherBandContext}; the biome-source mixin calls {@link #biomeFor} per quart.
 */
public final class NetherBandBiomeSet {

    private final Holder<Biome>[][] zones; // [zoneIndex][choice]
    private final long seed;

    private NetherBandBiomeSet(Holder<Biome>[][] zones, long seed) {
        this.zones = zones;
        this.seed = seed;
    }

    /** Highland biome for a band column at this world position — altitude-zoned + region-varied. */
    public Holder<Biome> biomeFor(int worldX, int worldY, int worldZ) {
        Holder<Biome>[] zone = zones[NetherBandBiomes.zoneIndex(worldY)];
        return zone[NetherBandBiomes.pickWithinZone(seed, worldX, worldZ, zone.length)];
    }

    /** Resolve {@link NetherBandBiomes#ZONES} keys to holders via the world's biome registry. */
    @SuppressWarnings("unchecked")
    public static NetherBandBiomeSet resolve(HolderGetter<Biome> biomes, long seed) {
        List<List<ResourceKey<Biome>>> keyZones = NetherBandBiomes.ZONES;
        Holder<Biome>[][] zones = new Holder[keyZones.size()][];
        for (int z = 0; z < keyZones.size(); z++) {
            List<ResourceKey<Biome>> keys = keyZones.get(z);
            Holder<Biome>[] resolved = new Holder[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                resolved[i] = biomes.getOrThrow(keys.get(i));
            }
            zones[z] = resolved;
        }
        return new NetherBandBiomeSet(zones, seed);
    }
}
