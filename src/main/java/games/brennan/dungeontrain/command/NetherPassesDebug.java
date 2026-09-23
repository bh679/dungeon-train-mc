package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.density.BetterNetherCoreBiomes;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import games.brennan.dungeontrain.worldgen.density.NetherCoreBiomes;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@code /dungeontrain debug nether-passes}: for the first few Nether bands, prints the core's world-X
 * range and the core biomes sampled along it (Z=0), and says which bands are BetterNether. Also gives
 * the End core's X range for the same pass, so a check that BetterNether left the End band alone has a
 * place to look. Logged at INFO so a headless RCON run can read it from {@code latest.log}.
 */
final class NetherPassesDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PASSES = 4;
    private static final int CORE_SCAN_STEP = 8;
    private static final int BIOME_SAMPLE_STEP = BetterNetherCoreBiomes.CELL_BLOCKS / 2;

    private NetherPassesDebug() {}

    static int report(CommandSourceStack source) {
        NetherBandContext ctx = NetherBandContext.current();
        if (ctx == null || !ctx.enabled() || ctx.netherCoreBiomes() == null) {
            source.sendFailure(Component.literal("[DungeonTrain] Nether band inactive (no band context)"));
            return 0;
        }
        WorldGenCycle cycle = ctx.cycle();
        NetherCoreBiomes biomes = ctx.netherCoreBiomes();
        send(source, "[DungeonTrain] nether-passes: betterNether=" + biomes.hasBetterNether()
                + " period=" + cycle.period(), ChatFormatting.AQUA);
        for (int pass = 0; pass < PASSES; pass++) {
            send(source, describePass(cycle, biomes, pass), passColour(pass));
        }
        return 1;
    }

    private static String describePass(WorldGenCycle cycle, NetherCoreBiomes biomes, int pass) {
        long bandStart = cycle.startX() - cycle.phaseShift() + pass * cycle.period() + Math.max(0, cycle.owGap());
        long bandEnd = bandStart + cycle.netherLen();
        long coreMin = Long.MAX_VALUE;
        long coreMax = Long.MIN_VALUE;
        for (long x = Math.max(bandStart, cycle.startX()); x < bandEnd; x += CORE_SCAN_STEP) {
            if (x > Integer.MAX_VALUE) break;
            if (cycle.isNetherCore((int) x)) {
                coreMin = Math.min(coreMin, x);
                coreMax = Math.max(coreMax, x);
            }
        }
        if (coreMin > coreMax) return "  pass " + pass + ": no core (before anchor or band disabled)";
        Set<String> seen = new LinkedHashSet<>();
        for (long x = coreMin; x <= coreMax; x += BIOME_SAMPLE_STEP) {
            int ix = (int) x;
            biomes.biomeAt(ix, 0, cycle.cycleIndex(ix)).unwrapKey()
                    .ifPresent(k -> seen.add(k.location().toString()));
        }
        String kind = BetterNetherCoreBiomes.isBetterNetherPass(pass) && biomes.hasBetterNether()
                ? "BetterNether" : "vanilla";
        return "  pass " + pass + " (" + kind + "): core x=" + coreMin + ".." + coreMax
                + " centre=" + ((coreMin + coreMax) / 2) + " biomes=" + seen
                + " | " + describeEndCore(cycle, bandEnd, bandStart - Math.max(0, cycle.owGap()) + cycle.period());
    }

    /** The End core's X range between the Nether band's end and the end of this cycle repeat. */
    private static String describeEndCore(WorldGenCycle cycle, long from, long to) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long x = from; x < to && x <= Integer.MAX_VALUE; x += CORE_SCAN_STEP) {
            if (cycle.isEndCore((int) x)) {
                min = Math.min(min, x);
                max = Math.max(max, x);
            }
        }
        return min > max ? "no End core" : "End core x=" + min + ".." + max + " centre=" + ((min + max) / 2);
    }

    private static ChatFormatting passColour(int pass) {
        return BetterNetherCoreBiomes.isBetterNetherPass(pass) ? ChatFormatting.GREEN : ChatFormatting.GOLD;
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
