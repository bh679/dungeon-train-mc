package games.brennan.dungeontrain.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageContentsEditor;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorModel;
import games.brennan.dungeontrain.editor.PillarEditor;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TrackEditor;
import games.brennan.dungeontrain.editor.TrackTemplateStore;
import games.brennan.dungeontrain.editor.TunnelEditor;
import games.brennan.dungeontrain.track.PillarAdjunct;
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

        EditorModel model = located.get().model();
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

    /** Re-stamp the plot via the normal tier resolution — same as {@code enter} does. */
    private static void resetToSaved(ServerLevel overworld, EditorModel model, CarriageDims dims) {
        if (model instanceof EditorModel.CarriageModel carriage) {
            CarriageEditor.stampPlot(overworld, carriage.variant(), dims);
            return;
        }
        if (model instanceof EditorModel.ContentsModel contentsModel) {
            CarriageContentsEditor.stampPlot(overworld, contentsModel.contents(), dims);
            return;
        }
        if (model instanceof EditorModel.PillarModel pillar) {
            PillarEditor.stampPlot(overworld, pillar.section(), dims);
            return;
        }
        if (model instanceof EditorModel.AdjunctModel adjunct) {
            PillarEditor.stampPlot(overworld, adjunct.adjunct(), dims);
            return;
        }
        if (model instanceof EditorModel.TunnelModel tunnel) {
            TunnelEditor.stampPlot(overworld, tunnel.variant());
            return;
        }
        if (model instanceof EditorModel.TrackModel) {
            TrackEditor.stampPlot(overworld, dims);
            return;
        }
    }

    /**
     * Re-stamp the plot from the bundled tier only. Errors when no bundled
     * copy exists. Tunnels have no bundled tier today and will always error.
     */
    private static int resetToDefault(CommandSourceStack source, ServerLevel overworld,
                                      EditorModel model, CarriageDims dims) {
        if (model instanceof EditorModel.CarriageModel carriage) {
            Optional<StructureTemplate> bundled =
                CarriageTemplateStore.getBundled(overworld, carriage.variant(), dims);
            if (bundled.isEmpty()) {
                source.sendFailure(Component.literal(
                    "No bundled template for '" + carriage.id() + "' — nothing to reset to."
                ).withStyle(ChatFormatting.YELLOW));
                return 0;
            }
            BlockPos origin = CarriageEditor.plotOrigin(carriage.variant(), dims);
            if (origin == null) {
                source.sendFailure(Component.literal(
                    "Missing plot origin for '" + carriage.id() + "'."
                ).withStyle(ChatFormatting.RED));
                return 0;
            }
            games.brennan.dungeontrain.train.CarriageTemplate.eraseAt(overworld, origin, dims);
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            bundled.get().placeInWorld(overworld, origin, origin, settings, overworld.getRandom(), 3);
            source.sendSuccess(() -> Component.literal(
                "Editor: reset '" + carriage.id() + "' to bundled default."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        if (model instanceof EditorModel.PillarModel pillar) {
            Optional<StructureTemplate> bundled =
                PillarTemplateStore.getBundled(overworld, pillar.section(), dims);
            if (bundled.isEmpty()) {
                source.sendFailure(Component.literal(
                    "No bundled template for '" + pillar.id() + "' — nothing to reset to."
                ).withStyle(ChatFormatting.YELLOW));
                return 0;
            }
            BlockPos origin = PillarEditor.plotOrigin(pillar.section(), dims);
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            bundled.get().placeInWorld(overworld, origin, origin, settings, overworld.getRandom(), 3);
            source.sendSuccess(() -> Component.literal(
                "Editor: reset '" + pillar.id() + "' to bundled default."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        if (model instanceof EditorModel.AdjunctModel adjunctModel) {
            PillarAdjunct a = adjunctModel.adjunct();
            Optional<StructureTemplate> bundled =
                PillarTemplateStore.getBundledAdjunct(overworld, a);
            if (bundled.isEmpty()) {
                source.sendFailure(Component.literal(
                    "No bundled template for '" + adjunctModel.id() + "' — nothing to reset to."
                ).withStyle(ChatFormatting.YELLOW));
                return 0;
            }
            BlockPos origin = PillarEditor.plotOriginAdjunct(a, adjunctModel.name(), dims);
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            bundled.get().placeInWorld(overworld, origin, origin, settings, overworld.getRandom(), 3);
            source.sendSuccess(() -> Component.literal(
                "Editor: reset '" + adjunctModel.id() + "' to bundled default."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        if (model instanceof EditorModel.TunnelModel tunnel) {
            source.sendFailure(Component.literal(
                "Tunnel templates have no bundled tier — '/dt reset default' does not apply to '" + tunnel.id() + "'."
            ).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        if (model instanceof EditorModel.ContentsModel contentsModel) {
            source.sendFailure(Component.literal(
                "Contents templates have no separate bundled tier — '/dt reset default' does not apply to '" + contentsModel.id() + "'."
            ).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        if (model instanceof EditorModel.TrackModel) {
            Optional<StructureTemplate> bundled =
                TrackTemplateStore.getBundled(overworld, dims);
            if (bundled.isEmpty()) {
                source.sendFailure(Component.literal(
                    "No bundled template for 'track' — nothing to reset to."
                ).withStyle(ChatFormatting.YELLOW));
                return 0;
            }
            BlockPos origin = TrackEditor.plotOrigin(dims);
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            bundled.get().placeInWorld(overworld, origin, origin, settings, overworld.getRandom(), 3);
            source.sendSuccess(() -> Component.literal(
                "Editor: reset 'track' to bundled default."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        return 0;
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
