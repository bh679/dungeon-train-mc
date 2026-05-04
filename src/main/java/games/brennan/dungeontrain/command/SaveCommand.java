package games.brennan.dungeontrain.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageContentsEditor;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.PillarEditor;
import games.brennan.dungeontrain.editor.TrackEditor;
import games.brennan.dungeontrain.editor.TunnelEditor;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Stores;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.TrackTemplate;
import games.brennan.dungeontrain.train.CarriageDims;
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
 *
 * <p>Phase 2 of the Template OOP refactor (PR following #148) collapsed the
 * per-kind {@code instanceof} dispatch in {@code saveOne} / {@code promoteOne}
 * onto {@link Stores#save} / {@link Stores#promote} — every Template carries
 * its own {@code store()} so the command no longer needs to enumerate kinds.
 * The kind-specific failure messages for {@code /dt save default} (custom
 * carriages, contents, tunnels) are preserved by
 * {@link #promoteUnavailableMessage}.</p>
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
                    .executes(ctx -> runSaveAll(ctx.getSource(), true))))
            // `/dt save model <category> <id> [default]` — save a specific
            // template by category+id without requiring the player to be
            // standing in that plot. Used by the unsaved-changes confirmation
            // screen so the per-row Save buttons work without teleporting.
            .then(Commands.literal("model")
                .then(Commands.argument("category", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(ctx -> runSaveModel(ctx.getSource(),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "category"),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id"),
                            false))
                        .then(Commands.literal("default")
                            .executes(ctx -> runSaveModel(ctx.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "category"),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id"),
                                true))))));
    }

    /**
     * Save a specific {@link Template} by {@code (category, id)} without
     * requiring the player to be inside that plot. Used by the worldspace
     * menu's unsaved-changes confirmation screen so per-row Save buttons
     * apply to any listed template, not just the one the player happens to
     * be standing in.
     *
     * <p>{@code id} is the {@link Template#id()} the entry was published
     * under (e.g. {@code standard}, {@code default}, {@code track:default},
     * {@code pillar_top}). For track-side rows the id may include a colon
     * separating kind and variant name — split here before lookup.</p>
     */
    public static int runSaveModel(CommandSourceStack source, String categoryId, String id, boolean promoteDefault) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        Optional<EditorCategory> categoryOpt = EditorCategory.fromId(categoryId);
        if (categoryOpt.isEmpty()) {
            source.sendFailure(Component.literal("Unknown category '" + categoryId + "'."));
            return 0;
        }
        EditorCategory category = categoryOpt.get();

        Template model = findModel(category, id);
        if (model == null) {
            source.sendFailure(Component.literal(
                "Unknown model '" + id + "' in category '" + category.displayName() + "'."));
            return 0;
        }

        try {
            saveOne(source, player, model);
            if (promoteDefault) promoteOne(source, model);
            // Re-push the dirty list so the unsaved-changes screen picks up
            // the newly-clean state and lets Continue switch to "Continue"
            // when nothing is left outstanding.
            ServerLevel overworld = source.getServer().overworld();
            CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
            games.brennan.dungeontrain.net.DungeonTrainNet.sendTo(
                player,
                new games.brennan.dungeontrain.net.EditorUnsavedListPacket(
                    games.brennan.dungeontrain.editor.EditorDirtyCheck.findDirty(overworld, dims)
                )
            );
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] /dt save model {}:{} failed", categoryId, id, t);
            source.sendFailure(Component.literal(
                "save failed: " + t.getClass().getSimpleName() + ": " + t.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Resolve {@code id} to the matching {@link Template} inside
     * {@code category}. The id format mirrors what
     * {@link games.brennan.dungeontrain.editor.EditorDirtyCheck} publishes:
     *
     * <ul>
     *   <li>{@code <variantId>} — carriages, contents</li>
     *   <li>{@code track:<name>} — track tile variants</li>
     *   <li>{@code pillar_<section>:<name>} — pillar section variants</li>
     *   <li>{@code adjunct_<id>:<name>} — pillar adjunct variants</li>
     *   <li>{@code tunnel_<variant>:<name>} — tunnel variants</li>
     * </ul>
     *
     * Track-side {@code <kind>:<name>} ids construct a fresh
     * {@link Template} rather than scanning {@code category.models()},
     * because the category's enumeration only includes default-named
     * variants whereas the dirty list surfaces every name.
     */
    private static Template findModel(EditorCategory category, String id) {
        if (category == EditorCategory.TRACKS && id.contains(".")) {
            int sep = id.indexOf('.');
            String prefix = id.substring(0, sep);
            String name = id.substring(sep + 1);
            if ("track".equals(prefix)) {
                return new Template.TrackModel(name);
            }
            if (prefix.startsWith("pillar_")) {
                games.brennan.dungeontrain.track.PillarSection sec;
                try {
                    sec = games.brennan.dungeontrain.track.PillarSection.valueOf(
                        prefix.substring("pillar_".length()).toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return null;
                }
                return new Template.PillarModel(sec, name);
            }
            if (prefix.startsWith("adjunct_")) {
                games.brennan.dungeontrain.track.PillarAdjunct adj;
                try {
                    adj = games.brennan.dungeontrain.track.PillarAdjunct.valueOf(
                        prefix.substring("adjunct_".length()).toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return null;
                }
                return new Template.AdjunctModel(adj, name);
            }
            if (prefix.startsWith("tunnel_")) {
                games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant tv;
                try {
                    tv = games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant.valueOf(
                        prefix.substring("tunnel_".length()).toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return null;
                }
                return new Template.TunnelModel(tv, name);
            }
        }
        for (Template m : category.models()) {
            if (m.id().equals(id)) return m;
        }
        return null;
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

        Template model = located.get().model();
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
        List<Template> models = category.models();
        if (models.isEmpty()) {
            source.sendFailure(Component.literal(
                "Category '" + category.displayName() + "' has no models."));
            return 0;
        }

        int saved = 0;
        int skipped = 0;
        int promoted = 0;
        StringBuilder promoteErrors = new StringBuilder();

        for (Template model : models) {
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

    /**
     * Save one model via its {@link Template#store()} and report progress.
     * Phase 2 collapse: dispatch lives on {@link Stores#save}; the chat
     * message uses the template's id so player-visible output matches the
     * pre-Phase-2 per-kind arms byte-for-byte.
     */
    private static void saveOne(CommandSourceStack source, ServerPlayer player, Template model) throws Exception {
        SaveResult result = Stores.save(player, model);
        if (source == null) return;
        source.sendSuccess(() -> Component.literal(
            savedMessage(model)), true);
        if (!result.sourceAttempted()) return;
        if (result.sourceWritten()) {
            source.sendSuccess(() -> Component.literal(
                bundledWriteMessage(model)
            ).withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendFailure(Component.literal(
                bundledWriteFailMessage(model) + result.sourceError()
            ).withStyle(ChatFormatting.YELLOW));
        }
    }

    /** Promote {@code model} to source tree. Reports via {@code source}. */
    private static void promoteOne(CommandSourceStack source, Template model) {
        if (!model.canPromote()) {
            source.sendFailure(Component.literal(
                promoteUnavailableMessage(model)
            ).withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (!Stores.canPromote(model)) {
            source.sendFailure(Component.literal(
                "Source tree not writable — '/dt save default' requires dev environment (./gradlew runClient). Config-dir save still succeeded."
            ).withStyle(ChatFormatting.YELLOW));
            return;
        }
        try {
            Stores.promote(model);
            source.sendSuccess(() -> Component.literal(
                "Editor: promoted '" + model.id() + "' to source tree (will ship with next build)."
            ).withStyle(ChatFormatting.GREEN), true);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] promote {} failed", model.id(), e);
            source.sendFailure(Component.literal(
                "Promote failed: " + e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Quiet promote for {@code save all default}. Returns true when a promote
     * was attempted and succeeded, false when the model has no bundled tier
     * (custom carriages, contents, tunnels). Throws on actual I/O failures
     * so the caller can append the message to the summary string.
     */
    private static boolean promoteOneSilent(Template model) throws Exception {
        if (!model.canPromote()) return false;
        if (!Stores.canPromote(model)) throw new Exception("source tree not writable");
        Stores.promote(model);
        return true;
    }

    /**
     * Per-kind chat string for the {@code "Editor: saved 'X' template"}
     * line. Matches the pre-Phase-2 per-kind arms exactly so player-visible
     * output is byte-identical to main; the only variation is the noun
     * ("template" / "contents template").
     */
    private static String savedMessage(Template model) {
        return switch (model.kind()) {
            case CONTENTS -> "Editor: saved contents '" + model.id() + "' template (config-dir).";
            case TRACK    -> "Editor: saved 'track' template (config-dir).";
            default       -> "Editor: saved '" + model.id() + "' template (config-dir).";
        };
    }

    /** Source-tree write success line, kind-specific noun preserved. */
    private static String bundledWriteMessage(Template model) {
        return switch (model.kind()) {
            case CONTENTS -> "Editor: also wrote bundled contents copy to source tree (devmode ON).";
            case PILLAR   -> "Editor: also wrote bundled pillar copy to source tree (devmode ON).";
            case STAIRS   -> "Editor: also wrote bundled adjunct copy to source tree (devmode ON).";
            case TUNNEL   -> "Editor: also wrote bundled tunnel copy to source tree (devmode ON).";
            case TRACK    -> "Editor: also wrote bundled track copy to source tree (devmode ON).";
            default       -> "Editor: also wrote bundled copy to source tree (devmode ON).";
        };
    }

    /** Source-tree write failure prefix, kind-specific noun preserved. */
    private static String bundledWriteFailMessage(Template model) {
        return switch (model.kind()) {
            case CONTENTS -> "Editor: contents source-tree write failed: ";
            case PILLAR   -> "Editor: pillar source-tree write failed: ";
            case STAIRS   -> "Editor: adjunct source-tree write failed: ";
            case TUNNEL   -> "Editor: tunnel source-tree write failed: ";
            case TRACK    -> "Editor: track source-tree write failed: ";
            default       -> "Editor: source-tree write failed: ";
        };
    }

    /**
     * Reason {@code /dt save default} can't promote {@code model}. Three
     * existing kind-specific messages are preserved: contents (no separate
     * tier), tunnel (no bundled tier today), custom carriage (only built-ins
     * have a bundled tier).
     */
    private static String promoteUnavailableMessage(Template model) {
        return switch (model.kind()) {
            case CONTENTS -> "Contents templates have no separate bundled tier — '/dt save default' does not apply.";
            case TUNNEL   -> "Tunnel templates have no bundled tier — '/dt save default' does not apply.";
            case CARRIAGE -> "Default save not supported for custom variants — only built-ins have a bundled tier.";
            default       -> "'/dt save default' is not supported for this template kind.";
        };
    }

    private static boolean isPlotEmpty(ServerLevel level, Template model, CarriageDims dims) {
        if (model instanceof Template.CarriageModel carriage) {
            BlockPos origin = CarriageEditor.plotOrigin(carriage.variant(), dims);
            if (origin == null) return true;
            return countNonAir(level, origin, dims.length(), dims.height(), dims.width())
                < EMPTY_PLOT_THRESHOLD;
        }
        if (model instanceof Template.ContentsModel contentsModel) {
            BlockPos origin = CarriageContentsEditor.plotOrigin(contentsModel.contents(), dims);
            if (origin == null) return true;
            return countNonAir(level, origin, dims.length(), dims.height(), dims.width())
                < EMPTY_PLOT_THRESHOLD;
        }
        if (model instanceof Template.PillarModel pillar) {
            BlockPos origin = PillarEditor.plotOrigin(pillar.section(), dims);
            return countNonAir(level, origin, 1, pillar.section().height(), dims.width())
                < EMPTY_PLOT_THRESHOLD;
        }
        if (model instanceof Template.AdjunctModel adjunctModel) {
            PillarAdjunct a = adjunctModel.adjunct();
            BlockPos origin = PillarEditor.plotOriginAdjunct(a, adjunctModel.name(), dims);
            return countNonAir(level, origin, a.xSize(), a.ySize(), a.zSize())
                < EMPTY_PLOT_THRESHOLD;
        }
        if (model instanceof Template.TunnelModel tunnel) {
            BlockPos origin = TunnelEditor.plotOrigin(tunnel.variant());
            if (origin == null) return true;
            return countNonAir(level, origin, TunnelTemplate.LENGTH, TunnelTemplate.HEIGHT, TunnelTemplate.WIDTH)
                < EMPTY_PLOT_THRESHOLD;
        }
        if (model instanceof Template.TrackModel) {
            BlockPos origin = TrackEditor.plotOrigin(dims);
            return countNonAir(level, origin, TrackTemplate.TILE_LENGTH, TrackTemplate.HEIGHT, dims.width())
                < EMPTY_PLOT_THRESHOLD;
        }
        // PartModel reaches here because EditorCategory.models() doesn't
        // include parts in the /dt save all iteration today. Treat as empty
        // (the part plot is never a save-all target). Phase 3 may surface
        // parts in the iteration alongside its model() rework.
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
