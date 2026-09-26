package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
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
 * <p>Built once per world seed and reused; if either cannot be built, that room has no generator and
 * stamps as its plain template — never the live generator, which would show the modded look the room
 * is named against.</p>
 */
final class SampleGenerators {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN STAND_IN_ERRORS = new LogFirstN(5);

    private record Built(long seed, NoiseBasedChunkGenerator nether, NoiseBasedChunkGenerator end) {}

    private static volatile Built built;

    private SampleGenerators() {}

    /**
     * The generator {@code source} samples {@code level} with — {@code live}, except for the vanilla
     * Nether and End, which get their private generator, or {@code null} when it could not be built.
     */
    static NoiseBasedChunkGenerator forSource(ServerLevel level, PortalChunkTerrain.Source source,
                                              NoiseBasedChunkGenerator live) {
        if (!vanillaOnly(source)) return live;
        Built b = builtFor(level.getServer(), level.getSeed());
        return pick(true, source == PortalChunkTerrain.Source.NETHER ? b.nether() : b.end(), live);
    }

    private static boolean vanillaOnly(PortalChunkTerrain.Source source) {
        return source == PortalChunkTerrain.Source.NETHER || source == PortalChunkTerrain.Source.END;
    }

    /**
     * {@code own} for a vanilla-only room — even when it is {@code null}, since {@code live} would
     * show the modded look — else {@code live}.
     */
    static <T> T pick(boolean vanillaOnly, T own, T live) {
        return vanillaOnly ? own : live;
    }

    /**
     * The stand-in generator for {@code source} in a world with no dimension of its own to sample:
     * {@code standIn} (the vanilla preset's) for every room but the vanilla Nether and End, which get
     * the same vanilla-only biome layouts {@link #forSource} gives them in a live world. The preset's
     * own Nether and End sources carry the Better mods' biomes just as the live ones do — so if the
     * vanilla-only generator cannot be built, {@code null}, never the preset.
     */
    static NoiseBasedChunkGenerator forStandIn(MinecraftServer server, PortalChunkTerrain.Source source,
                                               NoiseBasedChunkGenerator standIn, long seed) {
        try {
            if (source == PortalChunkTerrain.Source.NETHER) {
                return new NoiseBasedChunkGenerator(NetherCoreBiomes.vanillaNetherSource(server, null),
                    standIn.generatorSettings());
            }
            if (source == PortalChunkTerrain.Source.END) {
                VanillaEndBiomes layout = VanillaEndBiomes.create(seed,
                    server.registryAccess().lookupOrThrow(Registries.BIOME));
                return new NoiseBasedChunkGenerator(new VanillaEndBiomeSource(layout),
                    Holder.direct(unmarkedCopy(standIn.generatorSettings().value())));
            }
        } catch (Throwable t) {
            STAND_IN_ERRORS.error(LOGGER, "[DungeonTrain] Vanilla " + source
                + " stand-in generator unavailable; the room stamps as its plain template", t);
            return null;
        }
        return standIn;
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
            LOGGER.error("[DungeonTrain] Vanilla Nether sample generator unavailable; vanilla Nether rooms stamp as their plain template", t);
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
            LOGGER.error("[DungeonTrain] Vanilla End sample generator unavailable; vanilla End rooms stamp as their plain template", t);
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
