package games.brennan.dungeontrain.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import games.brennan.dungeontrain.editor.EditorRegionDiff;
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
            .executes(ctx -> EditorRegionDiff.recording(ctx.getSource(), "Reset",
                () -> runReset(ctx.getSource(), false)))
            .then(Commands.literal("default")
                .executes(ctx -> EditorRegionDiff.recording(ctx.getSource(), "Reset",
                    () -> runReset(ctx.getSource(), true))));
    }

    private static int runReset(CommandSourceStack source, boolean defaultOnly) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        Optional<EditorCategory.Located> located = EditorCategory.locate(player, dims);
        if (located.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.not_plot_use_dt"));
            return 0;
        }

        Template model = located.get().model();
        try {
            if (defaultOnly) {
                return resetToDefault(source, overworld, model, dims);
            }
            resetToSaved(overworld, model, dims);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.save.reset_last_saved_template", model.id()).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] /dt reset failed for {}", model.id(), t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.reset_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
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
        // The erase + restamp is DT rewriting the plot, not a gameplay event — held under the
        // stamp guard so authored observers in the plot do not pulse (ObserverBlockStampMixin).
        CarriageStampGuard.run(() -> model.restampPlot(overworld, dims));
    }

    /**
     * Player-facing reset helper for non-command callers (template label menu's
     * R button). Mirrors the {@code /dt reset} success-path output exactly so
     * the panel button and the slash command produce byte-identical chat.
     *
     * @return true on success, false on reset failure (the failure message is
     *         already shown to the player).
     */
    public static boolean resetToSavedPlayerVisible(ServerPlayer player, Template model) {
        try {
            ServerLevel overworld = player.getServer().overworld();
            CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
            resetToSaved(overworld, model, dims);
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.save.reset_last_saved_template", model.id())
                .copy().withStyle(ChatFormatting.GREEN));
            return true;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] resetToSavedPlayerVisible {} failed", model.id(), t);
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.editor.reset_failed", t.getClass().getSimpleName(), t.getMessage())
                .copy().withStyle(ChatFormatting.RED));
            return false;
        }
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
            source.sendFailure(noBundledTierMessage(model).copy().withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        Optional<StructureTemplate> bundled = model.bundled(overworld, dims);
        if (bundled.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.no_bundled_template_nothing", model.id()).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        BlockPos origin = model.editorPlotOrigin(overworld, dims);
        if (origin == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.missing_plot_origin", model.id()).withStyle(ChatFormatting.RED));
            return 0;
        }
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
        CarriageStampGuard.run(() -> {
            model.eraseEditorPlot(overworld, origin, dims);
            bundled.get().placeInWorld(overworld, origin, origin, settings, overworld.getRandom(), CarriageStampGuard.STAMP_FLAGS);
        });
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.save.reset_bundled_default", model.id()).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * Per-kind chat string for the {@code "/dt reset default"} no-tier
     * failure. Mirrors {@code SaveCommand.promoteUnavailableMessage} but with
     * reset-specific wording — kept as a separate helper to preserve the
     * subtle differences between save and reset chat output.
     */
    private static Component noBundledTierMessage(Template model) {
        return switch (model.kind()) {
            case CONTENTS -> Component.translatable("chat.dungeontrain.save.reset_no_tier.contents", model.id());
            case TUNNEL -> Component.translatable("chat.dungeontrain.save.reset_no_tier.tunnel", model.id());
            case PORTAL_ROOM -> Component.translatable("chat.dungeontrain.save.reset_no_tier.portal_room", model.variantName());
            default -> Component.translatable("chat.dungeontrain.save.reset_no_tier.generic");
        };
    }

    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return null;
        }
    }
}
