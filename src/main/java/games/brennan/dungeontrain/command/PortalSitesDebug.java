package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalChunkTerrain;
import games.brennan.dungeontrain.worldgen.SecondLapOverworld;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /dungeontrain debug portal-sites [count]}: for {@code count} dimensional-carriage pair keys
 * under this world's seed, which stretch each overworld source's sample site sits in — the old
 * scattered first site next to the site the stretch rules now accept. The plain room should be all
 * {@code VANILLA}, and each modded room all its own stretch. Judged by site alone (no chunk is
 * generated). Logged at INFO for headless RCON runs.
 */
final class PortalSitesDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final int DEFAULT_COUNT = 2000;

    private static final PortalChunkTerrain.Source[] SOURCES = {
        PortalChunkTerrain.Source.OVERWORLD,
        PortalChunkTerrain.Source.OVERWORLD_WWOO,
        PortalChunkTerrain.Source.OVERWORLD_BOP,
    };

    private PortalSitesDebug() {}

    static int report(CommandSourceStack source, int count) {
        ServerLevel overworld = source.getServer().overworld();
        long seed = overworld.getSeed();
        NetherBandContext ctx = NetherBandContext.current();
        WorldGenCycle cycle = ctx != null && ctx.cycle() != null ? ctx.cycle() : WorldGenCycle.fromConfig();

        Map<String, Integer> scattered = new TreeMap<>();
        for (int key = 0; key < count; key++) {
            scattered.merge(label(cycle, PortalChunkTerrain.firstScatteredSite(seed, key)), 1, Integer::sum);
        }
        send(source, "[DungeonTrain] portal-sites: " + count + " pairs, seed=" + seed
            + " | scattered first site (old rule): " + scattered, ChatFormatting.AQUA);

        for (PortalChunkTerrain.Source src : SOURCES) {
            Map<String, Integer> accepted = new TreeMap<>();
            int none = 0;
            long minX = Long.MAX_VALUE;
            long maxX = Long.MIN_VALUE;
            for (int key = 0; key < count; key++) {
                ChunkPos site = PortalChunkTerrain.firstAcceptedSite(overworld, src, seed, key);
                if (site == null) {
                    none++;
                    continue;
                }
                accepted.merge(label(cycle, site), 1, Integer::sum);
                minX = Math.min(minX, site.getMinBlockX());
                maxX = Math.max(maxX, site.getMinBlockX());
            }
            boolean onTarget = accepted.size() == 1 && accepted.containsKey(src.stretch().name()) && none == 0;
            send(source, "  " + src + " -> " + accepted + " exhausted=" + none
                + (accepted.isEmpty() ? "" : " x=" + minX + ".." + maxX)
                + (onTarget ? " OK" : " CHECK"), onTarget ? ChatFormatting.GREEN : ChatFormatting.RED);
        }
        return 1;
    }

    /** The look a site's chunk wears: its stretch, or {@code BAND} when a band or legacy era covers it. */
    private static String label(WorldGenCycle cycle, ChunkPos site) {
        int x = site.getMiddleBlockX();
        if (cycle != null && !cycle.isOverworldGapAt(x)) return "BAND";
        return SecondLapOverworld.at(cycle, x).name();
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
