package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.density.NetherCoreBiomes;
import games.brennan.dungeontrain.worldgen.density.VanillaEndBiomeSource;
import games.brennan.dungeontrain.worldgen.density.VanillaEndBiomes;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.slf4j.Logger;

/**
 * Which chunk generator a dimensional carriage's sample is cut with.
 *
 * <p>The Better rooms and every overworld room use the dimension's own generator, as a sample always
 * did. The vanilla Nether and End rooms cannot: BetterNether's biomes are injected into the live
 * Nether's biome source, and BetterEnd both patches the End's biome source and swaps its own island
 * terrain in. So each gets a private generator of its own, never attached to a level:</p>
 * <ul>
 *   <li><b>Nether</b> — the live settings over a {@code minecraft:}-only biome source
 *       ({@link NetherCoreBiomes#vanillaNetherSource}). Nether terrain does not depend on the biome, so
 *       the biomes, and the features and structures they carry, are the whole difference.</li>
 *   <li><b>End</b> — vanilla's End biome layout ({@link VanillaEndBiomeSource}) over a field-for-field
 *       copy of the live settings. BetterEnd marks the settings <i>instance</i> it takes over and reads
 *       that mark when each noise chunk is made, so an unmarked copy generates vanilla islands.</li>
 * </ul>
 *
 * <p>Built once per world seed and reused; if either cannot be built, the room falls back to the live
 * generator rather than going without terrain.</p>
 */
final class SampleGenerators {

    private static final Logger LOGGER = LogUtils.getLogger();

    private record Built(long seed, NoiseBasedChunkGenerator nether, NoiseBasedChunkGenerator end) {}

    private static volatile Built built;

    private SampleGenerators() {}

    /**
     * The generator {@code source} samples {@code level} with — {@code live} unless the source is the
     * vanilla Nether or End and its private generator could be built.
     */
    static NoiseBasedChunkGenerator forSource(ServerLevel level, PortalChunkTerrain.Source source,
                                              NoiseBasedChunkGenerator live) {
        if (source != PortalChunkTerrain.Source.NETHER && source != PortalChunkTerrain.Source.END) return live;
        Built b = builtFor(level.getServer(), level.getSeed());
        NoiseBasedChunkGenerator own = source == PortalChunkTerrain.Source.NETHER ? b.nether() : b.end();
        return own != null ? own : live;
    }

    /** Drop the generators — the server stopped, or the seed they were built for is gone. */
    static void clear() {
        built = null;
    }

    private static Built builtFor(MinecraftServer server, long seed) {
        Built b = built;
        if (b != null && b.seed() == seed) return b;
        synchronized (SampleGenerators.class) {
            b = built;
            if (b == null || b.seed() != seed) {
                b = new Built(seed, vanillaNether(server), vanillaEnd(server, seed));
                built = b;
            }
            return b;
        }
    }

    private static NoiseBasedChunkGenerator vanillaNether(MinecraftServer server) {
        try {
            ServerLevel nether = server.getLevel(Level.NETHER);
            if (nether == null || !(nether.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator live)) {
                return null;
            }
            return new NoiseBasedChunkGenerator(NetherCoreBiomes.vanillaNetherSource(server, nether),
                live.generatorSettings());
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Vanilla Nether sample generator unavailable; Nether rooms sample the live Nether", t);
            return null;
        }
    }

    private static NoiseBasedChunkGenerator vanillaEnd(MinecraftServer server, long seed) {
        try {
            ServerLevel end = server.getLevel(Level.END);
            if (end == null || !(end.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator live)) {
                return null;
            }
            VanillaEndBiomes layout = VanillaEndBiomes.create(seed,
                server.registryAccess().lookupOrThrow(Registries.BIOME));
            return new NoiseBasedChunkGenerator(new VanillaEndBiomeSource(layout),
                Holder.direct(unmarkedCopy(live.generatorSettings().value())));
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Vanilla End sample generator unavailable; End rooms sample the live End", t);
            return null;
        }
    }

    /** The same settings as a new instance — one no mod has marked as its own to take over. */
    private static NoiseGeneratorSettings unmarkedCopy(NoiseGeneratorSettings s) {
        return new NoiseGeneratorSettings(s.noiseSettings(), s.defaultBlock(), s.defaultFluid(), s.noiseRouter(),
            s.surfaceRule(), s.spawnTarget(), s.seaLevel(), s.disableMobGeneration(), s.aquifersEnabled(),
            s.oreVeinsEnabled(), s.useLegacyRandomSource());
    }
}
