package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.PackageInfo;
import games.brennan.dungeontrain.editor.PackageRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.List;

/**
 * {@code /dungeontrain package ...} subtree. OP-only by virtue of being
 * spliced under the existing {@code /dungeontrain} root (which already
 * requires permission level 2). Subcommands:
 *
 * <ul>
 *   <li>{@code save <name>} — save the active package's working folder
 *       as a {@code .zip} at {@code dtpacks/<name>.zip}. Handles three
 *       cases: first save of unsaved (move content + switch active),
 *       re-save existing (rewrite zip only), and rename (move folder,
 *       delete old zip, write new zip, switch active).</li>
 *   <li>{@code activate <name>} — switch the active package, redirecting
 *       all subsequent edits / saves into that package's working folder.</li>
 *   <li>{@code enable <name>} / {@code disable <name>} — toggle a
 *       package's contribution to gameplay (template stores).</li>
 *   <li>{@code list} — chat dump of the current package set for
 *       debugging.</li>
 * </ul>
 *
 * <p>Designed to be the single mutation entry point. The client-side
 * Package menu dispatches these commands rather than mutating
 * {@link PackageRegistry} directly; that way single-player and
 * dedicated-server flows are identical (the server owns the state, the
 * client just dispatches).</p>
 */
public final class PackageCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PackageCommand() {}

    /** Suggest known package names (including unsaved) for the {@code <name>} argument. */
    private static final SuggestionProvider<CommandSourceStack> PACKAGE_NAME_SUGGESTIONS =
        (ctx, builder) -> {
            for (PackageInfo p : PackageRegistry.all()) {
                builder.suggest(p.name());
            }
            return builder.buildFuture();
        };

    /** Build the {@code package} branch to splice under {@code /dungeontrain}. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("package")
            .then(Commands.literal("list")
                .executes(ctx -> runList(ctx.getSource())))
            .then(Commands.literal("save")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> runSave(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("activate")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(PACKAGE_NAME_SUGGESTIONS)
                    .executes(ctx -> runActivate(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("enable")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(PACKAGE_NAME_SUGGESTIONS)
                    .executes(ctx -> runSetEnabled(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name"), true))))
            .then(Commands.literal("disable")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(PACKAGE_NAME_SUGGESTIONS)
                    .executes(ctx -> runSetEnabled(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name"), false))));
    }

    private static int runList(CommandSourceStack source) {
        List<PackageInfo> all = PackageRegistry.all();
        PackageInfo active = PackageRegistry.active();
        source.sendSuccess(() -> Component.literal("dtpacks (" + all.size() + " package" + (all.size() == 1 ? "" : "s") + "):")
            .withStyle(ChatFormatting.AQUA), false);
        for (PackageInfo p : all) {
            boolean enabled = PackageRegistry.isEnabledByName(p.name());
            boolean isActive = p.name().equals(active.name());
            String marker = (isActive ? "* " : "  ") + p.name();
            String suffix = " [" + (enabled ? "enabled" : "disabled") + (p.hasZip() ? ", zip" : "") + "]";
            ChatFormatting tint = isActive ? ChatFormatting.GREEN
                : (enabled ? ChatFormatting.WHITE : ChatFormatting.GRAY);
            source.sendSuccess(() -> Component.literal(marker + suffix).withStyle(tint), false);
        }
        return 1;
    }

    private static int runSave(CommandSourceStack source, String name) {
        PackageRegistry.SaveResult result = PackageRegistry.saveCurrent(name);
        if (result.success()) {
            source.sendSuccess(() -> Component.literal(result.message()).withStyle(ChatFormatting.GREEN), true);
            LOGGER.info("[DungeonTrain] /dungeontrain package save {} -> {}", name, result.message());
            return 1;
        }
        source.sendFailure(Component.literal(result.message()).withStyle(ChatFormatting.RED));
        return 0;
    }

    private static int runActivate(CommandSourceStack source, String name) {
        PackageRegistry.MutateResult result = PackageRegistry.setActive(name);
        return reportMutation(source, result);
    }

    private static int runSetEnabled(CommandSourceStack source, String name, boolean enabled) {
        PackageRegistry.MutateResult result = PackageRegistry.setEnabled(name, enabled);
        return reportMutation(source, result);
    }

    private static int reportMutation(CommandSourceStack source, PackageRegistry.MutateResult result) {
        if (result.success()) {
            source.sendSuccess(() -> Component.literal(result.message()).withStyle(ChatFormatting.AQUA), true);
            return 1;
        }
        source.sendFailure(Component.literal(result.message()).withStyle(ChatFormatting.RED));
        return 0;
    }
}
