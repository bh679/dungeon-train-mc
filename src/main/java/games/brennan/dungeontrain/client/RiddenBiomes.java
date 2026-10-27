package games.brennan.dungeontrain.client;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

/**
 * The client's copy of the biomes the local player has ridden through this life, from the server
 * ({@link games.brennan.dungeontrain.net.RiddenBiomesPacket}). Read by Terra Omnia's checklist. Client thread only.
 */
public final class RiddenBiomes {

    private static Set<ResourceLocation> ridden = Set.of();

    private RiddenBiomes() {}

    /** Replace the copy with the server's list; ids that don't parse are dropped. */
    public static void set(List<String> biomes) {
        ridden = biomes.stream().map(ResourceLocation::tryParse).filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** The biomes ridden through this life. */
    public static Set<ResourceLocation> get() {
        return ridden;
    }
}
