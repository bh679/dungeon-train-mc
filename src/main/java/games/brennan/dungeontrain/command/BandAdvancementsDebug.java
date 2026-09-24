package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.advancement.BandAdvancements;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.List;

/**
 * {@code /dungeontrain debug band-advancements} — the journey advancement chain in the order the
 * current {@link WorldGenCycle} layout chains it (the parents the datapack rewriter applied at load).
 * Logged at INFO as well so a headless RCON run can read it from {@code latest.log}.
 */
final class BandAdvancementsDebug {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BandAdvancementsDebug() {}

    static int report(CommandSourceStack source) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        List<String> chain = BandAdvancements.chain(cycle.layout());
        send(source, "[DungeonTrain] band-advancements: " + chain.size() + " in chain, "
                + (cycle.hasLayout() ? "layout order" : "classic order (no layout — JSON parents apply)"),
                ChatFormatting.AQUA);
        String parent = BandAdvancements.ANCHOR;
        for (int i = 0; i < chain.size(); i++) {
            send(source, String.format("  %2d %-26s parent=%s", i + 1, chain.get(i), parent), ChatFormatting.WHITE);
            parent = chain.get(i);
        }
        return 1;
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
