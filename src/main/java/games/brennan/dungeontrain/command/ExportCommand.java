package games.brennan.dungeontrain.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentExporter;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code /dungeontrain editor export} — package the player-authored content
 * under {@code <config>/dungeontrain/user/} into a shareable zip under
 * {@code <game>/exports/}. No arguments; the resulting path is sent back to
 * the invoking player as a clickable chat message (click → reveal in OS file
 * manager).
 *
 * <p>Server-side only. Mirrors the existing editor subcommand permission
 * level (OP) so an MP server admin can grant or revoke access uniformly with
 * the other editor commands.</p>
 */
public final class ExportCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ExportCommand() {}

    /** Build the {@code export} branch to splice under {@code /dungeontrain editor}. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("export").executes(ctx -> run(ctx.getSource()));
    }

    private static int run(CommandSourceStack source) {
        try {
            UserContentExporter.Result result = UserContentExporter.export();
            Component message = formatSuccess(result);
            source.sendSuccess(() -> message, false);
            net.minecraft.server.level.ServerPlayer sp = source.getPlayer();
            if (sp != null) {
                games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
                    .trigger(sp, "exported_package");
            }
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Export failed: {}", e.toString());
            source.sendFailure(Component.literal("Export failed: " + e.getMessage()));
            return 0;
        }
    }

    private static Component formatSuccess(UserContentExporter.Result result) {
        Path zip = result.zipFile();
        String absolute = zip.toAbsolutePath().toString();
        Component link = Component.literal(zip.getFileName().toString())
            .withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, absolute))
                .withHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    Component.literal("Open " + absolute))));
        return Component.literal("Exported " + result.fileCount() + " file(s) to ")
            .append(link)
            .append(Component.literal(" (" + formatBytes(result.totalBytes()) + ")"));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KiB", bytes / 1024.0);
        return String.format("%.1f MiB", bytes / (1024.0 * 1024.0));
    }
}
