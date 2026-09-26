package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.advancement.LapThemeProgress;
import games.brennan.dungeontrain.worldgen.LapTheme;
import games.brennan.dungeontrain.worldgen.LapThemePlan;
import games.brennan.dungeontrain.worldgen.LapThemes;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Map;

/**
 * {@code /dungeontrain debug lap-themes} — this world's theme-lap decisions (each lap's theme and X
 * range; never decides one) and every online player's cross-world progress per theme.
 * {@code lap-themes progress <theme> <fraction>} overwrites the caller's own progress, for testing the
 * picker (dev clients run as operators, which is Free Play, so riding the train records nothing).
 * Also logged at INFO for RCON runs.
 */
final class LapThemesDebug {

    private static final Logger LOGGER = LogUtils.getLogger();

    private LapThemesDebug() {}

    static int report(CommandSourceStack source) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        int groups = cycle.themeGroupsPerRun();
        if (groups == 0) {
            send(source, "[DungeonTrain] lap-themes: this cycle order has no theme laps (:t1 / :t2)", ChatFormatting.AQUA);
            return 1;
        }
        LapThemePlan plan = LapThemes.current();
        Map<Long, LapTheme> decided = plan == null ? Map.of() : plan.decisions();
        send(source, "[DungeonTrain] lap-themes: " + groups + " theme laps per run, " + decided.size()
                + " decided" + (plan == null ? " (no plan published)" : ""), ChatFormatting.AQUA);
        long last = decided.keySet().stream().mapToLong(Long::longValue).max().orElse(-1L);
        for (long n = 0; n <= Math.max(last + 1, 3); n++) {
            long[] range = cycle.themeLapRange(n);
            LapTheme theme = cycle.peekThemeOfLap(n);
            String label = theme == null ? "undecided" : theme.id();
            send(source, String.format("  lap %d (run %d %s) %-9s X %s", n, n / groups,
                    cycle.themeKindOfLap(n), label, range == null ? "?" : range[0] + ".." + range[1]),
                    theme == null ? ChatFormatting.GRAY : ChatFormatting.WHITE);
        }
        for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) {
            send(source, "  progress " + p.getGameProfile().getName() + ": " + LapThemeProgress.of(p.getUUID()),
                    ChatFormatting.GOLD);
        }
        return 1;
    }

    static int setProgress(CommandSourceStack source, String themeId, double fraction) {
        LapTheme theme = LapTheme.byId(themeId);
        if (theme == null) {
            source.sendFailure(Component.literal("Unknown theme '" + themeId + "' (vanilla | bop | better)"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Run this as a player"));
            return 0;
        }
        LapThemeProgress.set(player.getUUID(), theme, fraction);
        send(source, "[DungeonTrain] lap-themes: " + player.getGameProfile().getName() + " progress now "
                + LapThemeProgress.of(player.getUUID()), ChatFormatting.AQUA);
        return 1;
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
