package games.brennan.dungeontrain.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageContentsEditor;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorModel;
import games.brennan.dungeontrain.editor.PillarEditor;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TrackEditor;
import games.brennan.dungeontrain.editor.TrackTemplateStore;
import games.brennan.dungeontrain.editor.TunnelEditor;
import games.brennan.dungeontrain.track.TrackTemplate;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.tunnel.TunnelTemplate;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * {@code /dungeontrain save ...} — context-aware save that inspects which
 * editor plot the player is standing in and dispatches to the matching
 * store. The {@code /dt} alias on the root (see {@link TrainCommand}) makes
 * every subcommand here reachable as {@code /dt save}.
 *
 * <ul>
 *   <li>{@code save} — save the current plot (carriage / pillar / tunnel).</li>
 *   <li>{@code save default} — save + promote to source tree so the template
 *       ships with the next build. Tunnels have no bundled tier, so this
 *       errors explicitly when run from a tunnel plot.</li>
 *   <li>{@code save all} — iterate every model in the player's current
 *       category, save plots that contain content, skip empty plots.</li>
 *   <li>{@code save all default} — iterate + save + promote per model.</li>
 * </ul>
 */
public final class SaveCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Minimum non-air blocks a plot must have before {@code save all} will capture it. */
    private static final int EMPTY_PLOT_THRESHOLD = 5;

    private SaveCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("save")
            .executes(ctx -> runSave(ctx.getSource(), false))
            .then(Commands.literal("default")
                .executes(ctx -> runSave(ctx.getSource(), true)))
            .then(Commands.literal("all")
                .executes(ctx -> runSaveAll(ctx.getSource(), false))
                .then(Commands.literal("default")
                    .executes(ctx -> runSaveAll(ctx.getSource(), true))));
    }

    /**
     * Save the plot the player is currently standing in. When
     * {@code promoteDefault} is true, also promote the saved template to the
     * source tree so it ships with the next build.
     */
    public static int runSave(CommandSourceStack source, boolean promoteDefault) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        Optional<EditorCategory.Located> located = EditorCategory.locate(player, dims);
        if (located.isEmpty()) {
            source.sendFailure(Component.literal(
                "Not in an editor plot. Use '/dt editor <category>' first."));
            return 0;
        }

        EditorModel model = located.get().model();
        try {
            saveOne(source, player, model);
            if (promoteDefault) promoteOne(source, model);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] /dt save failed for {}", model.id(), t);
            source.sendFailure(Component.literal("save failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Save every model in the player's current category. Skips plots whose
     * captured region has fewer than {@link #EMPTY_PLOT_THRESHOLD} non-air
     * blocks, so untouched plots don't overwrite their templates with empty
     * geometry. Reports counts of saved / skipped / promoted / promote-errored.
     */
    public static int runSaveAll(CommandSourceStack source, boolean promoteDefault) {
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

        EditorCategory category = located.get().category();
        List<EditorModel> models = category.models();
        if (models.isEmpty()) {
            source.sendFailure(Component.literal(
                "Category '" + category.displayName() + "' has no models."));
            return 0;
        }

        int saved = 0;
        int skipped = 0;
        int promoted = 0;
        StringBuilder promoteErrors = new StringBuilder();

        for (EditorModel model : models) {
            if (isPlotEmpty(overworld, model, dims)) {
                skipped++;
                continue;
            }
            try {
                saveOne(null, player, model);
                saved++;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] /dt save all: {} failed", model.id(), t);
                promoteErrors.append("\n  ").append(model.id())
                    .append(": save failed — ").append(t.getMessage());
                continue;
            }
            if (!promoteDefault) continue;
            try {
                if (promoteOneSilent(model)) {
                    promoted++;
                } else {
                    promoteErrors.append("\n  ").append(model.id())
                        .append(": no bundled tier (skipped)");
                }
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] /dt save all: promote {} failed", model.id(), t);
                promoteErrors.append("\n  ").append(model.id())
                    .append(": promote failed — ").append(t.getMessage());
            }
        }

        final int s = saved, sk = skipped, p = promoted;
        final String errs = promoteErrors.toString();
        final String summary = "Saved " + s + ", skipped " + sk + " empty"
            + (promoteDefault ? (" — promoted " + p) : "")
            + " (category: " + category.displayName() + ")"
            + (errs.isEmpty() ? "" : errs);
        source.sendSuccess(() -> Component.literal(summary)
            .withStyle(errs.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return s > 0 ? 1 : 0;
    }

    /** Save one model. When {@code source} is non-null, report progress messages. */
    private static void saveOne(CommandSourceStack source, ServerPlayer player, EditorModel model) throws Exception {
        if (model instanceof EditorModel.CarriageModel carriage) {
            CarriageEditor.SaveResult result = CarriageEditor.save(player, carriage.variant());
            if (source != null) {
                source.sendSuccess(() -> Component.literal(
                    "Editor: saved '" + carriage.id() + "' template (config-dir)."), true);
                if (result.sourceAttempted()) {
                    if (result.sourceWritten()) {
                        source.sendSuccess(() -> Component.literal(
                            "Editor: also wrote bundled copy to source tree (devmode ON)."
                        ).withStyle(ChatFormatting.GREEN), true);
                    } else {
                        source.sendFailure(Component.literal(
                            "Editor: source-tree write failed: " + result.sourceError()
                        ).withStyle(ChatFormatting.YELLOW));
                    }
                }
            }
            return;
        }
        if (model instanceof EditorModel.ContentsModel contentsModel) {
            CarriageContentsEditor.SaveResult result = CarriageContentsEditor.save(player, contentsModel.contents());
            if (source != null) {
                source.sendSuccess(() -> Component.literal(
                    "Editor: saved contents '" + contentsModel.id() + "' template (config-dir)."), true);
                if (result.sourceAttempted()) {
                    if (result.sourceWritten()) {
                        source.sendSuccess(() -> Component.literal(
                            "Editor: also wrote bundled contents copy to source tree (devmode ON)."
                        ).withStyle(ChatFormatting.GREEN), true);
                    } else {
                        source.sendFailure(Component.literal(
                            "Editor: contents source-tree write failed: " + result.sourceError()
                        ).withStyle(ChatFormatting.YELLOW));
                    }
                }
            }
            return;
        }
        if (model instanceof EditorModel.PillarModel pillar) {
            PillarEditor.SaveResult result = PillarEditor.save(player, pillar.section());
            if (source != null) {
                source.sendSuccess(() -> Component.literal(
                    "Editor: saved '" + pillar.id() + "' template (config-dir)."), true);
                if (result.sourceAttempted()) {
                    if (result.sourceWritten()) {
                        source.sendSuccess(() -> Component.literal(
                            "Editor: also wrote bundled pillar copy to source tree (devmode ON)."
                        ).withStyle(ChatFormatting.GREEN), true);
                    } else {
                        source.sendFailure(Component.literal(
                            "Editor: pillar source-tree write failed: " + result.sourceError()
                        ).withStyle(ChatFormatting.YELLOW));
                    }
                }
            }
            return;
        }
        if (model instanceof EditorModel.TunnelModel tunnel) {
            TunnelEditor.save(player, tunnel.variant());
            if (source != null) {
                source.sendSuccess(() -> Component.literal(
                    "Editor: saved '" + tunnel.id() + "' template."), true);
            }
            return;
        }
        if (model instanceof EditorModel.TrackModel) {
            TrackEditor.SaveResult result = TrackEditor.save(player);
            if (source != null) {
                source.sendSuccess(() -> Component.literal(
                    "Editor: saved 'track' template (config-dir)."), true);
                if (result.sourceAttempted()) {
                    if (result.sourceWritten()) {
                        source.sendSuccess(() -> Component.literal(
                            "Editor: also wrote bundled track copy to source tree (devmode ON)."
                        ).withStyle(ChatFormatting.GREEN), true);
                    } else {
                        source.sendFailure(Component.literal(
                            "Editor: track source-tree write failed: " + result.sourceError()
                        ).withStyle(ChatFormatting.YELLOW));
                    }
                }
            }
            return;
        }
        throw new IllegalStateException("Unknown model type: " + model);
    }

    /** Promote the given model to the source tree. Reports via {@code source}. */
    private static void promoteOne(CommandSourceStack source, EditorModel model) {
        if (model instanceof EditorModel.CarriageModel carriage) {
            if (!(carriage.variant() instanceof CarriageVariant.Builtin builtin)) {
                source.sendFailure(Component.literal(
                    "Default save not supported for custom variants — only built-ins have a bundled tier."
                ).withStyle(ChatFormatting.YELLOW));
                return;
            }
            if (!CarriageTemplateStore.sourceTreeAvailable()) {
                source.sendFailure(Component.literal(
                    "Source tree not writable — '/dt save default' requires dev environment (./gradlew runClient). Config-dir save still succeeded."
                ).withStyle(ChatFormatting.YELLOW));
                return;
            }
            try {
                CarriageTemplateStore.promote(builtin.type());
                source.sendSuccess(() -> Component.literal(
                    "Editor: promoted '" + carriage.id() + "' to source tree (will ship with next build)."
                ).withStyle(ChatFormatting.GREEN), true);
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] promote {} failed", carriage.id(), e);
                source.sendFailure(Component.literal(
                    "Promote failed: " + e.getMessage()).withStyle(ChatFormatting.RED));
            }
            return;
        }
        if (model instanceof EditorModel.ContentsModel) {
            // Contents promote: source-tree write is handled inside the
            // SaveResult pathway in CarriageContentsEditor.save() when
            // devmode is on, so "save default" has no extra action here.
            source.sendFailure(Component.literal(
                "Contents templates have no separate bundled tier — '/dt save default' does not apply."
            ).withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (model instanceof EditorModel.PillarModel pillar) {
            if (!PillarTemplateStore.sourceTreeAvailable()) {
                source.sendFailure(Component.literal(
                    "Source tree not writable — '/dt save default' requires dev environment (./gradlew runClient). Config-dir save still succeeded."
                ).withStyle(ChatFormatting.YELLOW));
                return;
            }
            try {
                PillarTemplateStore.promote(pillar.section());
                source.sendSuccess(() -> Component.literal(
                    "Editor: promoted '" + pillar.id() + "' to source tree (will ship with next build)."
                ).withStyle(ChatFormatting.GREEN), true);
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] promote {} failed", pillar.id(), e);
                source.sendFailure(Component.literal(
                    "Promote failed: " + e.getMessage()).withStyle(ChatFormatting.RED));
            }
            return;
        }
        if (model instanceof EditorModel.TunnelModel) {
            source.sendFailure(Component.literal(
                "Tunnel templates have no bundled tier — '/dt save default' does not apply."
            ).withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (model instanceof EditorModel.TrackModel) {
            if (!TrackTemplateStore.sourceTreeAvailable()) {
                source.sendFailure(Component.literal(
                    "Source tree not writable — '/dt save default' requires dev environment (./gradlew runClient). Config-dir save still succeeded."
                ).withStyle(ChatFormatting.YELLOW));
                return;
            }
            try {
                TrackTemplateStore.promote();
                source.sendSuccess(() -> Component.literal(
                    "Editor: promoted 'track' to source tree (will ship with next build)."
                ).withStyle(ChatFormatting.GREEN), true);
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] promote track failed", e);
                source.sendFailure(Component.literal(
                    "Promote failed: " + e.getMessage()).withStyle(ChatFormatting.RED));
            }
            return;
        }
    }

    /**
     * Quiet promote for {@code save all default}. Returns true when a promote
     * was attempted and succeeded, false when the model has no bundled tier
     * (custom carriages, tunnels). Throws on actual I/O failures.
     */
    private static boolean promoteOneSilent(EditorModel model) throws Exception {
        if (model instanceof EditorModel.CarriageModel carriage) {
            if (!(carriage.variant() instanceof CarriageVariant.Builtin builtin)) return false;
            if (!CarriageTemplateStore.sourceTreeAvailable()) {
                throw new Exception("source tree not writable");
            }
            CarriageTemplateStore.promote(builtin.type());
            return true;
        }
        if (model instanceof EditorModel.ContentsModel) {
            // Contents write-through happens inside CarriageContentsEditor.save
            // when devmode is on; there's no separate bundled tier to promote.
            return false;
        }
        if (model instanceof EditorModel.PillarModel pillar) {
            if (!PillarTemplateStore.sourceTreeAvailable()) {
                throw new Exception("source tree not writable");
            }
            PillarTemplateStore.promote(pillar.section());
            return true;
        }
        if (model instanceof EditorModel.TrackModel) {
            if (!TrackTemplateStore.sourceTreeAvailable()) {
                throw new Exception("source tree not writable");
            }
            TrackTemplateStore.promote();
            return true;
        }
        // Tunnel has no bundled tier.
        return false;
    }

    private static boolean isPlotEmpty(ServerLevel level, EditorModel model, CarriageDims dims) {
        if (model instanceof EditorModel.CarriageModel carriage) {
            BlockPos origin = CarriageEditor.plotOrigin(carriage.variant(), dims);
            if (origin == null) return true;
            return countNonAir(level, origin, dims.length(), dims.height(), dims.width())
                < EMPTY_PLOT_THRESHOLD;
        }
        if (model instanceof EditorModel.ContentsModel contentsModel) {
            BlockPos origin = CarriageContentsEditor.plotOrigin(contentsModel.contents());
            if (origin == null) return true;
            return countNonAir(level, origin, dims.length(), dims.height(), dims.width())
                < EMPTY_PLOT_THRESHOLD;
        }
        if (model instanceof EditorModel.PillarModel pillar) {
            BlockPos origin = PillarEditor.plotOrigin(pillar.section(), dims);
            return countNonAir(level, origin, 1, pillar.section().height(), dims.width())
                < EMPTY_PLOT_THRESHOLD;
        }
        if (model instanceof EditorModel.TunnelModel tunnel) {
            BlockPos origin = TunnelEditor.plotOrigin(tunnel.variant());
            if (origin == null) return true;
            return countNonAir(level, origin, TunnelTemplate.LENGTH, TunnelTemplate.HEIGHT, TunnelTemplate.WIDTH)
                < EMPTY_PLOT_THRESHOLD;
        }
        if (model instanceof EditorModel.TrackModel) {
            BlockPos origin = TrackEditor.plotOrigin(dims);
            return countNonAir(level, origin, TrackTemplate.TILE_LENGTH, TrackTemplate.HEIGHT, dims.width())
                < EMPTY_PLOT_THRESHOLD;
        }
        return true;
    }

    private static int countNonAir(ServerLevel level, BlockPos origin, int length, int height, int width) {
        int count = 0;
        for (int dx = 0; dx < length; dx++) {
            for (int dy = 0; dy < height; dy++) {
                for (int dz = 0; dz < width; dz++) {
                    if (!level.getBlockState(origin.offset(dx, dy, dz)).isAir()) {
                        count++;
                        if (count >= EMPTY_PLOT_THRESHOLD) return count;
                    }
                }
            }
        }
        return count;
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
