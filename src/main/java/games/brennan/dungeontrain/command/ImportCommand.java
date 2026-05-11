package games.brennan.dungeontrain.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentImporter;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * {@code /dungeontrain editor import} — process any zips waiting in
 * {@code <game>/imports/} and refresh every editor registry without
 * waiting for a server restart. Surfaces a summary to the invoking
 * player so they see how many new files landed.
 *
 * <p>Same dispatch path as the Package menu's Reload button. The menu and
 * the slash command both fan out to
 * {@link UserContentImporter#reloadAll()}, which is the canonical entry
 * for "scan imports, clear caches, reload registries, reload weights".</p>
 *
 * <p>Server-side only. OP-level by virtue of being parented under the
 * existing {@code /dungeontrain editor} subtree — same permission model as
 * the other editor commands.</p>
 */
public final class ImportCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ImportCommand() {}

    /** Build the {@code import} branch to splice under {@code /dungeontrain editor}. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("import").executes(ctx -> run(ctx.getSource()));
    }

    private static int run(CommandSourceStack source) {
        try {
            UserContentImporter.Summary summary = UserContentImporter.reloadAll();
            source.sendSuccess(() -> formatSuccess(summary), false);
            return 1;
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Import/reload failed: {}", e.toString());
            source.sendFailure(Component.literal("Import failed: " + e.getMessage()));
            return 0;
        }
    }

    private static Component formatSuccess(UserContentImporter.Summary summary) {
        String body;
        if (summary.packagesProcessed() == 0) {
            body = "No new packages found. Registries reloaded.";
        } else {
            body = "Reloaded — " + summary.packagesProcessed() + " package(s), "
                + summary.filesImported() + " new file(s), "
                + summary.filesSkipped() + " skipped, "
                + summary.filesRejected() + " rejected.";
        }
        return Component.literal(body).withStyle(ChatFormatting.AQUA);
    }
}
