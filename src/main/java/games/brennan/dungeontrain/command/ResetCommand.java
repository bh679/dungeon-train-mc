package games.brennan.dungeontrain.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * {@code /dungeontrain reset ...} (aliased via {@code /dt reset}) — re-stamp
 * the player's current plot from what's on disk, discarding any edits since
 * the last save.
 *
 * <ul>
 *   <li>{@code reset} — reload the current plot via normal tier resolution
 *       (config → bundled → fallback) and re-stamp. Effectively "revert my
 *       edits since last save".</li>
 *   <li>{@code reset default} — reload from the bundled tier only, ignoring
 *       any config-dir override. Errors cleanly when no bundled template
 *       exists (custom carriages and all tunnels today).</li>
 * </ul>
 *
 * <p>Distinct from {@code /dungeontrain editor reset <variant>} which
 * <em>deletes</em> the config-dir file. This command only re-stamps the
 * in-world plot; it never mutates files.</p>
 *
 * <p>Phase 3 of the Template OOP refactor collapsed the per-kind
 * {@code instanceof} chains in {@link #resetToSaved} and
 * {@link #resetToDefault} onto {@link Template#restampPlot},
 * {@link Template#bundled}, and {@link Template#editorPlotOrigin} — each
 * record knows its own editor + storage delegate, so this command no
 * longer enumerates kinds.</p>
 */
public final class ResetCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ResetCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("reset")
            .executes(ctx -> runReset(ctx.getSource(), false))
            .then(Commands.literal("default")
                .executes(ctx -> runReset(ctx.getSource(), true)));
    }

    private static int runReset(CommandSourceStack source, boolean defaultOnly) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        Optional<EditorCategory.Located> located = EditorCategory.locate(player, dims);
        if (located.isEmpty()) {
            source.sendFailure(Component.literal(
                "Not in an editor plot. Use '/dt editor <category>' first."));
            return 0;
        }

        Template model = located.get().model();
        try {
            if (defaultOnly) {
                return resetToDefault(source, overworld, model, dims);
            }
            resetToSaved(overworld, model, dims);
            source.sendSuccess(() -> Component.literal(
                "Editor: reset '" + model.id() + "' to last saved template."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] /dt reset failed for {}", model.id(), t);
            source.sendFailure(Component.literal("reset failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Re-stamp the plot via the normal tier resolution — same as {@code enter}
     * does. Phase-3 collapse onto {@link Template#restampPlot} (per-record
     * override delegates to the appropriate per-editor {@code stampPlot}).
     * Parts use the default no-op since carriage-part plots are entered via a
     * different flow.
     */
    private static void resetToSaved(ServerLevel overworld, Template model, CarriageDims dims) {
        model.restampPlot(overworld, dims);
    }

    /**
     * Re-stamp the plot from the bundled tier only. Errors when no bundled
     * copy exists. Contents and tunnels have no bundled tier today —
     * {@link Template#hasBundledTier()} is false for those, and the message
     * is preserved by {@link #noBundledTierMessage}.
     */
    private static int resetToDefault(CommandSourceStack source, ServerLevel overworld,
                                      Template model, CarriageDims dims) {
        if (!model.hasBundledTier()) {
            source.sendFailure(Component.literal(noBundledTierMessage(model))
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        Optional<StructureTemplate> bundled = model.bundled(overworld, dims);
        if (bundled.isEmpty()) {
            source.sendFailure(Component.literal(
                "No bundled template for '" + model.id() + "' — nothing to reset to."
            ).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        BlockPos origin = model.editorPlotOrigin(overworld, dims);
        if (origin == null) {
            source.sendFailure(Component.literal(
                "Missing plot origin for '" + model.id() + "'."
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
        model.eraseEditorPlot(overworld, origin, dims);
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
        bundled.get().placeInWorld(overworld, origin, origin, settings, overworld.getRandom(), 3);
        source.sendSuccess(() -> Component.literal(
            "Editor: reset '" + model.id() + "' to bundled default."
        ).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * Per-kind chat string for the {@code "/dt reset default"} no-tier
     * failure. Mirrors {@code SaveCommand.promoteUnavailableMessage} but with
     * reset-specific wording — kept as a separate helper to preserve the
     * subtle differences between save and reset chat output.
     */
    private static String noBundledTierMessage(Template model) {
        return switch (model.kind()) {
            case CONTENTS -> "Contents templates have no separate bundled tier — '/dt reset default' does not apply to '"
                + model.id() + "'.";
            case TUNNEL -> "Tunnel templates have no bundled tier — '/dt reset default' does not apply to '"
                + model.id() + "'.";
            default -> "'/dt reset default' is not supported for this template kind.";
        };
    }

    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return null;
        }
    }
}
