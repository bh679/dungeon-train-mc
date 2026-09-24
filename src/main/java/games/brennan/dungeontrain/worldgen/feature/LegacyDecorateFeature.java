package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.alpha.AlphaPopulator;
import games.brennan.dungeontrain.worldgen.legacy.indev.IndevFloatingLevel;
import games.brennan.dungeontrain.worldgen.legacy.indev.IndevFloatingPopulator;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBiome;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaPopulator;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaWorld;
import games.brennan.dungeontrain.worldgen.legacy.infdev.InfdevPopulator;
import games.brennan.dungeontrain.worldgen.legacy.farlands.FarLandsShift;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.slf4j.Logger;

/**
 * Worldgen feature that runs an old generator's own decoration step in a legacy-band chunk — for Beta and Skylands,
 * {@link BetaPopulator}: lakes, dungeons, ores, trees, flowers, reeds, cacti, springs and snow, all as
 * Beta placed them. Vanilla's biome features are skipped in those chunks
 * ({@code ChunkGeneratorDecorationMixin}), so this is the chunk's whole decoration.
 *
 * <p>Wired by datapack ({@code configured_feature}/{@code placed_feature/legacy_decorate.json} →
 * {@code neoforge/biome_modifier/track_bed_overworld.json}), ahead of the track bed so the tunnel clears
 * any tree or lake that lands in the corridor. A no-op in every non-legacy chunk.</p>
 */
public class LegacyDecorateFeature extends Feature<NoneFeatureConfiguration> {

    private static final Logger LOGGER = LogUtils.getLogger();

    public LegacyDecorateFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        ChunkPos chunk = new ChunkPos(ctx.origin());
        ServerLevel serverLevel = level.getLevel();
        LegacyBandKind kind = LegacyBands.kindOfChunk(serverLevel, chunk.x, chunk.z);
        if (kind == null || kind.isPreset()) return false; // presets get vanilla's own decoration
        long genT0 = GenProfiler.t0();
        try {
            long seed = DungeonTrainWorldData.get(serverLevel).getGenerationSeed();
            int yOffset = LegacyBands.yOffset(kind, serverLevel);
            BetaWorld world = switch (kind) {
                case FLOATING -> new BetaWorld(level, yOffset, IndevFloatingLevel.HEIGHT);
                case CAVES_OF_CHAOS -> new BetaWorld(level, yOffset, BetaTerrain.Profile.CAVES_OF_CHAOS.height());
                default -> new BetaWorld(level, yOffset);
            };
            switch (kind) {
                case BETA -> BetaPopulator.populate(world, LegacyBands.beta(seed), chunk.x, chunk.z);
                case CAVES_OF_CHAOS -> BetaPopulator.populate(world, LegacyBands.chaos(seed), chunk.x, chunk.z);
                case SKYLANDS -> BetaPopulator.populate(world, seed, LegacyBands.sky(seed).forestNoise(),
                        BetaBiome.SKY, null, chunk.x, chunk.z);
                case ALPHA -> AlphaPopulator.populate(level, LegacyBands.alpha(seed), chunk.x, chunk.z,
                        LegacyBands.isAlphaWinter(WorldGenCycle.fromConfig(), chunk.x));
                case INFDEV -> InfdevPopulator.populate(world, LegacyBands.infdev(seed),
                        LegacyBands.infdevVersion(WorldGenCycle.fromConfig(), chunk.x), chunk.x, chunk.z);
                case FLOATING -> IndevFloatingPopulator.populate(world, seed, chunk.x, chunk.z);
                // Classic planted its trees, flowers and mushrooms while building the level — already written.
                case CLASSIC, LARGE_BIOMES, AMPLIFIED -> {
                    return false;
                }
                case VOID -> { /* nothing to decorate */ }
                case FAR_LANDS -> {
                    FarLandsShift shift = FarLandsShift.of(WorldGenCycle.fromConfig(), chunk.x, chunk.z);
                    BetaPopulator.populate(BetaWorld.shifted(level, yOffset, shift.dxBlocks(), shift.dzBlocks()),
                            LegacyBands.beta(seed), chunk.x + shift.dxChunks(), chunk.z + shift.dzChunks());
                }
            }
            return true;
        } catch (Throwable t) {
            // Never break worldgen — a failed decoration leaves bare old terrain.
            LOGGER.error("[DungeonTrain] {} decoration failed at chunk {}", kind.token(), chunk, t);
            return false;
        } finally {
            GenProfiler.add(GenProfiler.Bucket.LEGACY, genT0);
        }
    }
}
