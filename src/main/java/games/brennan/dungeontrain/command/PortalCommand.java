package games.brennan.dungeontrain.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalEndpoint;
import games.brennan.dungeontrain.portal.PortalPair;
import games.brennan.dungeontrain.portal.PortalRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.UUID;

/**
 * Debug + test commands for the cross-dim portal feature.
 *
 * <p>All subcommands are op-level 2 — same permission floor as
 * {@code /dt spawn}. They exist for verification and field debugging, not
 * for player-facing gameplay.</p>
 *
 * <h2>Subcommands</h2>
 * <ul>
 *   <li>{@code /dt portal list} — print every registered portal pair
 *       (dim + pos on each side, pair UUID).</li>
 *   <li>{@code /dt portal clear} — wipe every pair from the registry.
 *       Does NOT remove placed blocks; intended for "reset the
 *       registry for the next test" workflows.</li>
 * </ul>
 *
 * <p>Force-spawn / forced-crossing helpers will arrive with Phase 13
 * once the Gate-2 test plan firms up.</p>
 */
public final class PortalCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PortalCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("portal")
            .then(Commands.literal("list").executes(ctx -> runList(ctx.getSource())))
            .then(Commands.literal("clear").executes(ctx -> runClear(ctx.getSource())));
    }

    private static int runList(CommandSourceStack source) {
        PortalRegistry registry = PortalRegistry.get(source.getServer());
        Collection<PortalPair> all = registry.all();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No registered portal pairs."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(all.size() + " registered portal pair(s):")
            .withStyle(ChatFormatting.AQUA), false);
        for (PortalPair pair : all) {
            PortalEndpoint a = pair.a();
            PortalEndpoint b = pair.b();
            source.sendSuccess(() -> Component.literal(String.format(
                "  %s: %s %s  ↔  %s %s",
                pair.id().toString().substring(0, 8),
                a.dim().location(), a.pos().toShortString(),
                b.dim().location(), b.pos().toShortString())), false);
        }
        return all.size();
    }

    private static int runClear(CommandSourceStack source) {
        PortalRegistry registry = PortalRegistry.get(source.getServer());
        Collection<PortalPair> all = registry.all();
        int count = all.size();
        for (PortalPair pair : all) {
            registry.removePair(pair.id());
        }
        LOGGER.info("[Portal] /dt portal clear removed {} pair(s)", count);
        source.sendSuccess(() -> Component.literal("Removed " + count + " portal pair(s) from the registry.")
            .withStyle(ChatFormatting.YELLOW), true);
        return count;
    }
}
