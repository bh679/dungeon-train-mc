package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.world.level.biome.BiomeSource;

/**
 * Per-world snapshot the {@link NetherBandTerrainDensityFunction} reads <em>lazily</em> at
 * compute time to shape the nether-band mountains. Published once at server start (a common-dist
 * {@code ServerStartedEvent} handler — see {@code NetherBandContextEvents}) and cleared on stop.
 *
 * <p>It exists because the density router is built during {@code RandomState} construction, before
 * the {@code ServerLevel}'s per-world {@code DungeonTrainWorldData} (generation seed, train geometry,
 * {@code startsWithTrain}) is readable — so the wrapper can't capture this state at construction and
 * must read it lazily. The band DF lives only in the overworld router (gated by
 * {@link NetherBandHooks}), and there is exactly one overworld, so a single {@code volatile} holder
 * is unambiguous. A {@code null} (not yet published) or {@code !enabled} context makes the wrapper a
 * no-op, so terrain before the snapshot lands — and for no-train / nether-disabled worlds — is
 * byte-identical to vanilla.</p>
 *
 * @param enabled        nether band active for this world ({@code NetherBand.startX != OFF})
 * @param generationSeed per-world DT seed driving {@link games.brennan.dungeontrain.worldgen.feature.MountainNoise}
 * @param seaLevel       overworld sea level (mountain base)
 * @param worldCeiling   highest world-Y the mountain may reach (target-top clamp)
 * @param netherTop      world-Y the mountain tapers down to across the real-Nether core
 * @param baseRelief     blocks of relief a full ({@code relief01 == 1}) stage-1 mountain adds
 * @param cycle          the frozen {@link WorldGenCycle} layout (snapshotted at server start)
 * @param overworldBiomeSource the overworld's biome source — identity gate so the biome-source mixin
 *                       only forces highland biomes on the overworld (the Nether also uses multi_noise)
 * @param highlandBiomes the resolved highland biome palette forced onto band mountain columns
 * @param netherCoreBiomes samples the real Nether biome (all five) for core columns the way the Nether
 *                       does — drives Nether fog/ambient/music, per-biome decoration + surface skin, and
 *                       makes the vanilla Nether decoration's biome filter pass
 */
public record NetherBandContext(boolean enabled, long generationSeed, int seaLevel, int worldCeiling,
                                int netherTop, int baseRelief, WorldGenCycle cycle,
                                BiomeSource overworldBiomeSource, NetherBandBiomeSet highlandBiomes,
                                NetherCoreBiomes netherCoreBiomes) {

    private static volatile NetherBandContext current;

    /** The active context, or {@code null} before {@link #publish} / after {@link #clear}. */
    public static NetherBandContext current() {
        return current;
    }

    public static void publish(NetherBandContext context) {
        current = context;
    }

    public static void clear() {
        current = null;
    }
}
