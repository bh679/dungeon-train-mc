package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.density.BopEndBiomeSource;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.slf4j.Logger;
import terrablender.api.SurfaceRuleManager;
import terrablender.worldgen.IExtendedNoiseGeneratorSettings;

/**
 * The generator a Biomes O' Plenty End-band pass is sampled from: the End's own settings over
 * {@link BopEndBiomeSource} — vanilla island terrain with vanilla + BoP End biomes on it.
 *
 * <p>The settings are a field-for-field copy of the live End's: BetterEnd marks the settings
 * instance it takes over and swaps its own island terrain in, so an unmarked copy generates vanilla
 * islands (the same trick {@code portal.SampleGenerators} uses for the vanilla End room). The copy
 * also switches on TerraBlender's End surface rules, which a settings object only gets when a live
 * level loads it — without them BoP's End biomes would be dressed in plain end stone.</p>
 *
 * <p>Built once per world seed, off the hot path, and shared by the biome labels
 * ({@code EndCoreBiomes}) and the terrain copy ({@link EndBandSampler}) so both read one layout.
 * {@code null} when the world has no End or TerraBlender cannot build it — BoP passes then stay vanilla.</p>
 */
public final class BopEnd {

    private static final Logger LOGGER = LogUtils.getLogger();

    public record Built(long seed, BopEndBiomeSource source, NoiseBasedChunkGenerator generator) {}

    private static volatile Built built;
    private static volatile long failedSeed = Long.MIN_VALUE;

    private BopEnd() {}

    /** The BoP End for this server's world, or {@code null} when it cannot be built. */
    public static Built get(MinecraftServer server) {
        if (server == null) return null;
        ServerLevel end = server.getLevel(Level.END);
        if (end == null) return null;
        long seed = end.getSeed();
        Built b = built;
        if (b != null && b.seed() == seed) return b;
        if (failedSeed == seed) return null;
        synchronized (BopEnd.class) {
            b = built;
            if (b != null && b.seed() == seed) return b;
            try {
                if (!(end.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator live)) {
                    failedSeed = seed;
                    return null;
                }
                BopEndBiomeSource source = BopEndBiomeSource.create(server.registryAccess(), seed);
                if (!source.hasBop()) {
                    LOGGER.warn("[DungeonTrain] TerraBlender's End has no Biomes O' Plenty biomes — BoP End bands stay vanilla");
                    failedSeed = seed;
                    return null;
                }
                NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(source,
                        Holder.direct(endCopy(live.generatorSettings().value())));
                b = new Built(seed, source, generator);
                built = b;
                return b;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] BoP End generator unavailable; BoP End bands stay vanilla", t);
                failedSeed = seed;
                return null;
            }
        }
    }

    /** Drop the generator (server stopped). */
    public static void clear() {
        built = null;
        failedSeed = Long.MIN_VALUE;
    }

    /** A new, unmarked copy of the End settings with TerraBlender's End surface rules on. */
    private static NoiseGeneratorSettings endCopy(NoiseGeneratorSettings s) {
        NoiseGeneratorSettings copy = new NoiseGeneratorSettings(s.noiseSettings(), s.defaultBlock(), s.defaultFluid(),
                s.noiseRouter(), s.surfaceRule(), s.spawnTarget(), s.seaLevel(), s.disableMobGeneration(),
                s.aquifersEnabled(), s.oreVeinsEnabled(), s.useLegacyRandomSource());
        ((IExtendedNoiseGeneratorSettings) (Object) copy).setRuleCategory(SurfaceRuleManager.RuleCategory.END);
        return copy;
    }
}
