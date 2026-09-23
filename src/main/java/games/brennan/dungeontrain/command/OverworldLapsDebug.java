package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.SecondLapOverworld;
import games.brennan.dungeontrain.worldgen.VanillaBiomeFeatures;
import games.brennan.dungeontrain.worldgen.VanillaBiomeTwins;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.WwooDecorationPass;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import games.brennan.dungeontrain.worldgen.density.OverworldStretchBiomes;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BiomeTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.slf4j.Logger;

import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /dungeontrain debug overworld-laps}: for the first few laps, prints the X range of the
 * overworld gap before and after the Nether band, which mod (if any) it belongs to, and a biome census
 * sampled straight from the overworld biome source along it — plus how many sampled Nether- and
 * End-core biomes are Biomes O' Plenty (should be none). Logged at INFO for headless RCON runs.
 */
final class OverworldLapsDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int LAPS = 4;
    private static final int SAMPLE_STEP_X = 250;
    private static final int[] SAMPLE_Z = {-600, -200, 200, 600};
    private static final int[] SAMPLE_Y_ABOVE_SEA = {-40, 8};

    private OverworldLapsDebug() {}

    static int report(CommandSourceStack source) {
        NetherBandContext ctx = NetherBandContext.current();
        OverworldStretchBiomes stretchBiomes = OverworldStretchBiomes.current();
        if (ctx == null || ctx.cycle() == null) {
            source.sendFailure(Component.literal("[DungeonTrain] No band context yet"));
            return 0;
        }
        ServerLevel overworld = source.getServer().overworld();
        BiomeSource biomeSource = overworld.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = overworld.getChunkSource().randomState().sampler();
        WorldGenCycle cycle = ctx.cycle();
        send(source, "[DungeonTrain] overworld-laps: period=" + cycle.period()
                + " bopRegions=" + (stretchBiomes == null ? "none" : stretchBiomes.bopRegionCount())
                + " wwooFeatures=" + VanillaBiomeFeatures.describe()
                + " wwooTwins=" + VanillaBiomeTwins.count(), ChatFormatting.AQUA);
        send(source, "  decoration: " + WwooDecorationPass.describeCounters(), ChatFormatting.AQUA);
        for (int lap = 0; lap < LAPS; lap++) {
            long[] nether = cycle.netherPassRange(lap);
            if (nether == null) break;
            // The gaps either side of Nether pass `lap`: the overworld slot before it and after it (ordered
            // layout), or the classic owGap on each side of the period's one Nether band.
            long leadStart = Math.max(cycle.startX(), nether[0] - cycle.overworldGapBefore(lap));
            long leadEnd = nether[0];
            long postStart = nether[1];
            long postEnd = postStart + cycle.overworldGapAfter(lap);
            send(source, describe(cycle, biomeSource, sampler, overworld.getSeaLevel(), lap, "lead", leadStart, leadEnd),
                    colour(cycle, leadStart));
            send(source, describe(cycle, biomeSource, sampler, overworld.getSeaLevel(), lap, "post-Nether", postStart, postEnd),
                    colour(cycle, postStart));
            send(source, "    Nether/End core BoP samples: " + bandCoreBop(ctx, cycle, leadEnd, postEnd + cycle.endLen())
                    + " | overworld source at cores: " + coreLabels(cycle, biomeSource, sampler, overworld.getSeaLevel(),
                    leadEnd, postEnd + cycle.endLen()), ChatFormatting.GRAY);
        }
        return 1;
    }

    private static String describe(WorldGenCycle cycle, BiomeSource biomeSource, Climate.Sampler sampler, int seaLevel,
                                   int lap, String label, long from, long to) {
        if (from >= to || to > Integer.MAX_VALUE) return "  lap " + lap + " " + label + ": empty";
        Map<String, Integer> census = new TreeMap<>();
        for (long x = from; x < to; x += SAMPLE_STEP_X) {
            for (int z : SAMPLE_Z) {
                for (int dy : SAMPLE_Y_ABOVE_SEA) {
                    Holder<Biome> b = biomeSource.getNoiseBiome(QuartPos.fromBlock((int) x),
                            QuartPos.fromBlock(seaLevel + dy), QuartPos.fromBlock(z), sampler);
                    String id = b.unwrapKey().map(k -> k.location().toString()).orElse("?");
                    census.merge(id, 1, Integer::sum);
                }
            }
        }
        return "  lap " + lap + " " + label + " (" + SecondLapOverworld.at(cycle, (int) from) + "): x="
                + from + ".." + (to - 1) + " centre=" + ((from + to) / 2) + " biomes=" + census;
    }

    /** Count of Biomes O' Plenty picks among Nether- and End-core samples across this lap. */
    private static String bandCoreBop(NetherBandContext ctx, WorldGenCycle cycle, long from, long to) {
        int nether = 0;
        int end = 0;
        int samples = 0;
        for (long x = from; x < to && x <= Integer.MAX_VALUE; x += SAMPLE_STEP_X / 5) {
            int ix = (int) x;
            for (int z : SAMPLE_Z) {
                if (ctx.netherCoreBiomes() != null && cycle.isNetherCore(ix)) {
                    samples++;
                    if (OverworldStretchBiomes.isBop(ctx.netherCoreBiomes().biomeAt(ix, z, cycle.netherPassIndex(ix)))) nether++;
                }
                if (ctx.endCoreBiomes() != null && cycle.isEndCore(ix)) {
                    samples++;
                    if (OverworldStretchBiomes.isBop(ctx.endCoreBiomes().biomeAt(ix, z, cycle.endPassIndex(ix)))) end++;
                }
            }
        }
        return "nether=" + nether + " end=" + end + " of " + samples;
    }

    /** How many core columns the overworld source itself labels Nether / End — the biome mixin at work. */
    private static String coreLabels(WorldGenCycle cycle, BiomeSource biomeSource, Climate.Sampler sampler, int seaLevel,
                                     long from, long to) {
        int nether = 0, netherTagged = 0, end = 0, endTagged = 0;
        int qy = QuartPos.fromBlock(seaLevel + 8);
        for (long x = from; x < to && x <= Integer.MAX_VALUE; x += SAMPLE_STEP_X) {
            int ix = (int) x;
            boolean isNether = cycle.isNetherCore(ix);
            boolean isEnd = cycle.isEndCore(ix);
            if (!isNether && !isEnd) continue;
            Holder<Biome> b = biomeSource.getNoiseBiome(QuartPos.fromBlock(ix), qy, 0, sampler);
            if (isNether) { nether++; if (b.is(BiomeTags.IS_NETHER)) netherTagged++; }
            if (isEnd) { end++; if (b.is(BiomeTags.IS_END)) endTagged++; }
        }
        return "nether " + netherTagged + "/" + nether + ", end " + endTagged + "/" + end;
    }

    private static ChatFormatting colour(WorldGenCycle cycle, long x) {
        return SecondLapOverworld.at(cycle, (int) Math.min(Integer.MAX_VALUE, x)) == SecondLapOverworld.Stretch.VANILLA
                ? ChatFormatting.GOLD : ChatFormatting.GREEN;
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
