package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.compat.mixinguard.MixinGuardReport;
import games.brennan.dungeontrain.compat.mixinguard.ThirdPartyMixinTargets;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.Set;
import java.util.TreeSet;

/**
 * {@code /dungeontrain debug mixin-guards}: which guarded third-party mixins are off this boot (a library
 * update moved what they hook), what the player gets instead, and whether the BetterEnd End bands still
 * sample BetterEnd. Logged at INFO for headless RCON runs.
 */
final class MixinGuardsDebug {

    private static final Logger LOGGER = LogUtils.getLogger();

    private MixinGuardsDebug() {}

    static int report(CommandSourceStack source) {
        Set<String> skipped = new TreeSet<>(MixinGuardReport.skipped());
        boolean endIntact = MixinGuardReport.endDeterminismIntact();
        send(source, "[DungeonTrain] mixin-guards: guarded=" + ThirdPartyMixinTargets.guardedMixins().size()
                + " skipped=" + skipped.size()
                + " betterEndBands=" + (endIntact ? "sampled" : "vanilla"),
                skipped.isEmpty() ? ChatFormatting.AQUA : ChatFormatting.YELLOW);
        for (String mixin : skipped) {
            ThirdPartyMixinTargets.Spec spec = ThirdPartyMixinTargets.forMixin(mixin);
            send(source, "  " + mixin.substring(ThirdPartyMixinTargets.MIXIN_PACKAGE.length())
                    + (spec == null ? "" : " → " + spec.degradesTo()), ChatFormatting.YELLOW);
        }
        return skipped.size();
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
