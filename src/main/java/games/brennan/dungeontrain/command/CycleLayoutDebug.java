package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * {@code /dungeontrain debug cycle-layout [runs]} — the ordered band layout as world-X ranges: every slot
 * of run 0 (and the doubled runs after it), each with the {@link TrainPhase} the classifier reads at its
 * midpoint, and the legacy eras inside the legacy run. Logged at INFO as well so a headless RCON run can
 * read the whole boundary table from {@code latest.log}. Reports the classic period when no layout is set.
 */
final class CycleLayoutDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_RUNS = 2;

    private CycleLayoutDebug() {}

    static int report(CommandSourceStack source, int runs) {
        ServerLevel overworld = source.getServer().overworld();
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (!cycle.hasLayout()) {
            send(source, "[DungeonTrain] cycle-layout: classic single-period order, period=" + cycle.period()
                    + " startX=" + cycle.startX(), ChatFormatting.AQUA);
            return 1;
        }
        CycleLayout layout = cycle.layout();
        send(source, "[DungeonTrain] cycle-layout: " + layout.count() + " slots, run 0 = " + layout.period()
                + " blocks from X=" + cycle.startX() + "; each run doubles", ChatFormatting.AQUA);
        for (int k = 0; k < Math.max(1, runs); k++) {
            long runStart = cycle.startX() + CycleLayout.runStart(k, layout.period());
            send(source, "  run " + k + " x" + (1L << k) + " from X=" + runStart, ChatFormatting.GOLD);
            for (int i = 0; i < layout.count(); i++) {
                CycleLayout.Slot slot = layout.slot(i);
                long from = runStart + (layout.start(i) << k);
                long to = runStart + ((layout.start(i) + layout.length(i)) << k);
                long mid = (from + to) / 2L;
                String phase = mid > Integer.MAX_VALUE ? "?" : TrainPhase.phaseAt(overworld, (int) mid).token();
                String style = slot.style() == CycleLayout.Style.VANILLA ? "" : " " + slot.style().name().toLowerCase();
                send(source, String.format("    %2d %-11s%-7s core=%-6d X %d..%d  phase@mid=%s", i,
                        slot.type().name().toLowerCase(), style, slot.core(), from, to, phase), ChatFormatting.WHITE);
                if (slot.type() == CycleLayout.Type.LEGACY_RUN) {
                    for (int e = 0; e < layout.eras().length; e++) {
                        LegacyBandKind kind = layout.eras()[e].kind();
                        long cs = runStart + ((layout.start(i) + layout.eraCoreStart(e)) << k);
                        long ce = cs + (layout.eraCoreLen(e) << k);
                        long emid = (cs + ce) / 2L;
                        String ephase = emid > Integer.MAX_VALUE ? "?" : TrainPhase.phaseAt(overworld, (int) emid).token();
                        send(source, String.format("         era %-10s core X %d..%d  phase@mid=%s", kind.token(), cs, ce, ephase),
                                ChatFormatting.GRAY);
                    }
                }
            }
        }
        return 1;
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
