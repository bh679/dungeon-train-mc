package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.MixBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /dungeontrain debug mix-pick} — which band each chunk around the caller generates as in the mix
 * zone: a letter grid (one letter per candidate, {@code .} for a chunk that keeps the base cycle), the key,
 * and the pick at the caller's own chunk. Logged at INFO as well so a headless RCON run can read it.
 */
final class MixPickDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RADIUS = 8;
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private MixPickDebug() {}

    static int report(CommandSourceStack source) {
        ServerLevel overworld = source.getServer().overworld();
        WorldGenCycle base = WorldGenCycle.fromConfig();
        List<MixBand.Candidate> candidates = MixBand.candidates(base);
        BlockPos at = BlockPos.containing(source.getPosition());
        int cx0 = at.getX() >> 4;
        int cz0 = at.getZ() >> 4;
        send(source, "[DungeonTrain] mix-pick: " + candidates.size() + " candidates; in zone here: "
                + base.mixPicksAt(at.getX()), ChatFormatting.AQUA);
        Map<String, Integer> counts = new TreeMap<>();
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            StringBuilder row = new StringBuilder("  ");
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                MixBand.Pick p = MixBand.pickAt(overworld, cx0 + dx, cz0 + dz);
                if (p == null) {
                    row.append('.');
                    continue;
                }
                int i = candidates.indexOf(p.candidate());
                row.append(i >= 0 && i < LETTERS.length() ? LETTERS.charAt(i) : '?');
                counts.merge(p.candidate().token(), 1, Integer::sum);
            }
            send(source, row.toString(), ChatFormatting.WHITE);
        }
        StringBuilder key = new StringBuilder("  key:");
        for (int i = 0; i < candidates.size() && i < LETTERS.length(); i++) {
            key.append(' ').append(LETTERS.charAt(i)).append('=').append(candidates.get(i).token());
        }
        send(source, key.toString(), ChatFormatting.GRAY);
        send(source, "  counts: " + counts, ChatFormatting.GRAY);
        MixBand.Pick here = MixBand.pickAt(overworld, cx0, cz0);
        send(source, "  here: " + (here == null ? "base cycle" : here.candidate().token() + " (dx " + here.dx() + ")"),
                ChatFormatting.GOLD);
        return 1;
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
