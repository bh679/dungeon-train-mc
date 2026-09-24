package games.brennan.dungeontrain.worldgen.legacy.infdev;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaCaves;
import games.brennan.dungeontrain.worldgen.legacy.noise.PerlinOctaveNoise;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * The Infdev band's generators for one world seed: 20100227 ({@link Infdev227Terrain}) and the three
 * density snapshots ({@link InfdevDensityTerrain}). Each version seeds its own noise from the world seed,
 * exactly as its original did. Immutable after construction and shared across worldgen workers.
 *
 * <p>{@link #generate} returns the old-world block column in Beta's layout
 * ({@link games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain#index}, old ids from
 * {@link games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks}), top water on old {@code y = 63}.
 * Decoration is separate — {@link InfdevPopulator}.</p>
 */
public final class InfdevTerrain {

    private final long seed;
    private final Infdev227Terrain v227;
    private final Map<InfdevVersion, InfdevDensityTerrain> density = new EnumMap<>(InfdevVersion.class);

    public InfdevTerrain(long seed) {
        this.seed = seed;
        this.v227 = new Infdev227Terrain(seed);
        BetaCaves caves = new BetaCaves(seed);
        for (InfdevVersion v : InfdevVersion.values()) {
            if (v.isDensity()) density.put(v, new InfdevDensityTerrain(seed, v, caves));
        }
    }

    public long seed() {
        return seed;
    }

    /** Generate {@code version}'s column for chunk {@code (chunkX, chunkZ)}. */
    public byte[] generate(int chunkX, int chunkZ, InfdevVersion version) {
        return version == InfdevVersion.V227 ? v227.generate(chunkX, chunkZ) : density.get(version).generate(chunkX, chunkZ);
    }

    /** The noise behind {@code version}'s per-chunk tree count. */
    public PerlinOctaveNoise forestNoise(InfdevVersion version) {
        return version == InfdevVersion.V227 ? v227.forestNoise() : density.get(version).forestNoise();
    }

    Infdev227Terrain v227() {
        return v227;
    }

    /** The per-chunk surface {@link Random} Infdev's surface passes used. */
    static Random surfaceRandom(int chunkX, int chunkZ) {
        return new Random((long) chunkX * 0x4f9939f508L + (long) chunkZ * 0x1ef1565bd5L);
    }
}
