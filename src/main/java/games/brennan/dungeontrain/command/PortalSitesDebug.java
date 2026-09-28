package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalChunkTerrain;
import games.brennan.dungeontrain.portal.PortalSampleProbe;
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
        PortalChunkTerrain.Source.NETHER,
        PortalChunkTerrain.Source.NETHER_BETTER,
        PortalChunkTerrain.Source.END,
        PortalChunkTerrain.Source.END_BETTER,
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
            ServerLevel level = PortalSampleProbe.levelFor(source.getServer(), src);
            if (level == null) {
                send(source, "  " + src + " -> no level", ChatFormatting.RED);
                continue;
            }
            Map<String, Integer> accepted = new TreeMap<>();
            int none = 0;
            long minX = Long.MAX_VALUE;
            long maxX = Long.MIN_VALUE;
            for (int key = 0; key < count; key++) {
                ChunkPos site = PortalChunkTerrain.firstAcceptedSite(level, src, seed, key);
                if (site == null) {
                    none++;
                    continue;
                }
                accepted.merge(label(cycle, level, src, site), 1, Integer::sum);
                minX = Math.min(minX, site.getMinBlockX());
                maxX = Math.max(maxX, site.getMinBlockX());
            }
            boolean onTarget = accepted.size() == 1 && accepted.containsKey(expected(src)) && none == 0;
            send(source, "  " + src + " -> " + accepted + " exhausted=" + none
                + (accepted.isEmpty() ? "" : " x=" + minX + ".." + maxX)
                + (onTarget ? " OK" : " CHECK"), onTarget ? ChatFormatting.GREEN : ChatFormatting.RED);
        }
        return 1;
    }

    /**
     * {@code /dungeontrain debug portal-sites probe-end [count]}: for {@code count} BetterEnd room sites,
     * generate the same chunk with the vanilla End room's generator and with the live End's, and log
     * each ground summary side by side — the island shapes and biomes should differ.
     */
    static int probeEnd(CommandSourceStack source, int count) {
        ServerLevel end = PortalSampleProbe.levelFor(source.getServer(), PortalChunkTerrain.Source.END);
        if (end == null) {
            source.sendFailure(Component.literal("[DungeonTrain] No End level"));
            return 0;
        }
        long seed = end.getSeed();
        send(source, "[DungeonTrain] portal-sites probe-end: " + count + " sites, seed=" + seed, ChatFormatting.AQUA);
        for (int key = 0; key < count; key++) {
            ChunkPos site = PortalChunkTerrain.firstAcceptedSite(end, PortalChunkTerrain.Source.END_BETTER, seed, key);
            if (site == null) continue;
            send(source, "  site " + site + " vanilla: "
                + PortalSampleProbe.describeGround(end, PortalChunkTerrain.Source.END, site), ChatFormatting.GOLD);
            send(source, "  site " + site + " better:  "
                + PortalSampleProbe.describeGround(end, PortalChunkTerrain.Source.END_BETTER, site), ChatFormatting.GREEN);
        }
        return 1;
    }

    /** What every accepted site of {@code src} should be labelled. */
    private static String expected(PortalChunkTerrain.Source src) {
        if (src.stretch() != null) return src.stretch().name();
        return src.biomeNamespace() != null ? src.biomeNamespace() : "minecraft";
    }

    /**
     * Overworld rooms: the stretch a site sits in, or {@code BAND}. Nether and End rooms: the namespace
     * of the biome the room's own generator puts there.
     */
    private static String label(WorldGenCycle cycle, ServerLevel level, PortalChunkTerrain.Source src, ChunkPos site) {
        if (src.stretch() == null) return String.valueOf(PortalSampleProbe.sampledBiomeNamespace(level, src, site));
        return label(cycle, site);
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
