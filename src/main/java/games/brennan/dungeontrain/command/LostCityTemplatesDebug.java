package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.LostCityTemplatePreloadEvents;
import games.brennan.dungeontrain.worldgen.LostCityTemplatePreload;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * {@code /dungeontrain debug lost-city-templates [status|reset|reach|legacy|at <x>]}: the Big Lost City template
 * cache — what has been pre-loaded, how many templates are cached, pre-loads and evictions so far, and the
 * cold lookups by thread kind (anything off the pre-load thread stalled its caller; see
 * {@code LostCityTemplateLoads}). {@code reset} zeroes the counters. {@code legacy} switches to the pre-change
 * rule (player's own X, one threshold, no demand signal) and {@code reach} back, for a same-seed A/B. Also
 * logged at INFO so a headless RCON run can read it from {@code latest.log}.
 */
final class LostCityTemplatesDebug {

    private static final Logger LOGGER = LogUtils.getLogger();

    private LostCityTemplatesDebug() {}

    static int status(CommandSourceStack source) {
        for (String line : LostCityTemplatePreloadEvents.status(source.getServer())) {
            send(source, "[DungeonTrain] lost-city-templates: " + line, ChatFormatting.AQUA);
        }
        return 1;
    }

    static int reset(CommandSourceStack source) {
        LostCityTemplatePreloadEvents.resetCounters();
        send(source, "[DungeonTrain] lost-city-templates: counters reset", ChatFormatting.GREEN);
        return 1;
    }

    static int setMode(CommandSourceStack source, LostCityTemplatePreload.Mode mode) {
        LostCityTemplatePreload.MODE = mode;
        send(source, "[DungeonTrain] lost-city-templates: mode " + mode
                + (mode == LostCityTemplatePreload.Mode.LEGACY ? " (pre-change rule — A/B mode)" : ""),
                mode == LostCityTemplatePreload.Mode.REACH ? ChatFormatting.GREEN : ChatFormatting.GOLD);
        return 1;
    }

    /** What a player standing at world X {@code x} would need of the cache, under each rule. */
    static int at(CommandSourceStack source, int x) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        int reach = LostCityTemplatePreload.reachBlocks(source.getServer().getPlayerList().getViewDistance());
        send(source, "[DungeonTrain] lost-city-templates: x=" + x + " reach=" + reach
                + " need=" + LostCityTemplatePreload.needAt(cycle, x, reach, LostCityTemplatePreload.Mode.REACH)
                + " legacy=" + LostCityTemplatePreload.needAt(cycle, x, reach, LostCityTemplatePreload.Mode.LEGACY),
                ChatFormatting.AQUA);
        return 1;
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
