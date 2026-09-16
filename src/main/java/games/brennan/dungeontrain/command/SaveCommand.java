package games.brennan.dungeontrain.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Stores;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
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
            source.sendFailure(Component.translatable("chat.dungeontrain.save.unknown_category", categoryId));
            return 0;
        }
        EditorCategory category = categoryOpt.get();

        Template model = findModel(category, id);
        if (model == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.unknown_model_category", id, Component.translatable("gui.dungeontrain.editor_menu.hud.category." + category.id())));
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
            source.sendFailure(Component.translatable("chat.dungeontrain.save.save_failed", t.getClass().getSimpleName(), t.getMessage())
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
                return new Template.Track(name);
            }
            if (prefix.startsWith("pillar_")) {
                games.brennan.dungeontrain.track.PillarSection sec;
                try {
                    sec = games.brennan.dungeontrain.track.PillarSection.valueOf(
                        prefix.substring("pillar_".length()).toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return null;
                }
                return new Template.Pillar(sec, name);
            }
            if (prefix.startsWith("adjunct_")) {
                games.brennan.dungeontrain.track.PillarAdjunct adj;
                try {
                    adj = games.brennan.dungeontrain.track.PillarAdjunct.valueOf(
                        prefix.substring("adjunct_".length()).toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return null;
                }
                return new Template.Adjunct(adj, name);
            }
            if (prefix.startsWith("tunnel_")) {
                games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant tv;
                try {
                    tv = games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant.valueOf(
                        prefix.substring("tunnel_".length()).toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return null;
                }
                return new Template.Tunnel(tv, name);
            }
        }
        if (category == EditorCategory.PORTALS && id.contains(".")) {
            int sep = id.indexOf('.');
            if (games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM.id()
                    .equals(id.substring(0, sep))) {
                return new Template.PortalRoom(id.substring(sep + 1));
            }
            return null;
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
            source.sendFailure(Component.translatable("chat.dungeontrain.save.not_plot_use_dt"));
            return 0;
        }

        Template model = located.get().model();
        try {
            saveOne(source, player, model);
            if (promoteDefault) promoteOne(source, model);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] /dt save failed for {}", model.id(), t);
            source.sendFailure(Component.translatable("chat.dungeontrain.save.save_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Save every model in the player's current category that the player has
     * <i>actually edited</i> this session. A plot counts as edited when its
     * live geometry diverges from the post-stamp snapshot captured by
     * {@link games.brennan.dungeontrain.editor.EditorPlotSnapshots} — same
     * signal the unsaved-list / category-switch confirm screen uses. Skips:
     * <ul>
     *   <li>Plots whose region holds fewer than {@link #EMPTY_PLOT_THRESHOLD}
     *       non-air blocks (defensive, prevents stamping an empty footprint
     *       over a saved template).</li>
     *   <li>Plots whose dirty-check key isn't in the unsaved set — i.e. the
     *       player hasn't entered the plot this session, or the live state
     *       still matches the snapshot.</li>
     * </ul>
     * Parts ({@link games.brennan.dungeontrain.template.TemplateKind#PART})
     * aren't covered by the dirty scan; the {@link #isPlotEmpty} filter is
     * still applied for them so unused part plots don't sweep up.
     * Reports counts of saved / skipped / promoted / promote-errored.
     */
    public static int runSaveAll(CommandSourceStack source, boolean promoteDefault) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        Optional<EditorCategory.Located> located = EditorCategory.locate(player, dims);
        if (located.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.not_plot_use_dt"));
            return 0;
        }

        EditorCategory category = located.get().category();
        List<Template> models = category.models();
        if (models.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.category_has_no_models", Component.translatable("gui.dungeontrain.editor_menu.hud.category." + category.id())));
            return 0;
        }

        java.util.Set<String> dirtyKeys =
            EditorDirtyCheck.unsavedModelIds(overworld, dims, category.id());

        int saved = 0;
        int skippedEmpty = 0;
        int skippedClean = 0;
        int promoted = 0;
        StringBuilder promoteErrors = new StringBuilder();

        for (Template model : models) {
            if (isPlotEmpty(overworld, model, dims)) {
                skippedEmpty++;
                continue;
            }
            String dKey = EditorDirtyCheck.dirtyKeyFor(model);
            if (dKey != null && !dirtyKeys.contains(dKey)) {
                skippedClean++;
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

        final int s = saved, skE = skippedEmpty, skC = skippedClean, p = promoted;
        final String errs = promoteErrors.toString();
        final Component summary = Component.translatable("chat.dungeontrain.save.summary", s, skE, skC,
            promoteDefault ? Component.translatable("chat.dungeontrain.save.summary_promoted", p) : Component.empty(),
            Component.translatable("gui.dungeontrain.editor_menu.hud.category." + category.id()), errs);
        source.sendSuccess(() -> summary.copy()
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
        source.sendSuccess(() -> savedMessage(model), true);
        if (!result.sourceAttempted()) return;
        if (result.sourceWritten()) {
            source.sendSuccess(() -> bundledWriteMessage(model).copy().withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendFailure(bundledWriteFailMessage(model, result.sourceError()).copy()
                .withStyle(ChatFormatting.YELLOW));
        }
    }

    /**
     * Player-facing save helper for non-command callers (template label menu).
     * Mirrors {@link #saveOne} but reports through {@link ServerPlayer#sendSystemMessage}
     * instead of {@link CommandSourceStack#sendSuccess}, so the panel button
     * produces byte-identical chat output to {@code /dt save} including the
     * dev-mode source-tree promote line.
     *
     * @return true on success, false on save failure (the failure message is
     *         already shown to the player).
     */
    public static boolean saveOnePlayerVisible(ServerPlayer player, Template model) {
        try {
            SaveResult result = Stores.save(player, model);
            player.sendSystemMessage(savedMessage(model)
                .copy().withStyle(ChatFormatting.GREEN));
            if (result.sourceAttempted()) {
                if (result.sourceWritten()) {
                    player.sendSystemMessage(bundledWriteMessage(model)
                        .copy().withStyle(ChatFormatting.GREEN));
                } else {
                    player.sendSystemMessage(bundledWriteFailMessage(model, result.sourceError())
                        .copy().withStyle(ChatFormatting.YELLOW));
                }
            }
            return true;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] saveOnePlayerVisible {} failed", model.id(), t);
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.save.save_failed", t.getClass().getSimpleName(), t.getMessage())
                .copy().withStyle(ChatFormatting.RED));
            return false;
        }
    }

    /** Promote {@code model} to source tree. Reports via {@code source}. */
    private static void promoteOne(CommandSourceStack source, Template model) {
        if (!model.canPromote()) {
            source.sendFailure(promoteUnavailableMessage(model).copy().withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (!Stores.canPromote(model)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.source_tree_not_writable").withStyle(ChatFormatting.YELLOW));
            return;
        }
        try {
            Stores.promote(model);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.save.promoted_source_tree_will", model.id()).withStyle(ChatFormatting.GREEN), true);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] promote {} failed", model.id(), e);
            source.sendFailure(Component.translatable("chat.dungeontrain.save.promote_failed", e.getMessage()).withStyle(ChatFormatting.RED));
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
    private static Component savedMessage(Template model) {
        return switch (model.kind()) {
            case CONTENTS -> Component.translatable("chat.dungeontrain.save.saved_contents", model.id());
            case TRACK    -> Component.translatable("chat.dungeontrain.save.saved_track");
            default       -> Component.translatable("chat.dungeontrain.save.saved", model.id());
        };
    }

    /**
     * Source-tree write success line, kind-specific noun preserved.
     *
     * <p>The trailing clause is the point of the message, not decoration. A source-tree write edits
     * the template that <b>ships to every player</b>, and the line used to report that as neutrally
     * as it reported the config-dir write beside it — so a camel posed in the {@code default}
     * dimensional carriage went into the mod's own shipped room, and the author had been told, in a
     * sentence that did not sound like it mattered.</p>
     */
    private static Component bundledWriteMessage(Template model) {
        return Component.translatable("chat.dungeontrain.save.bundled_written." + kindKey(model),
            Component.translatable("chat.dungeontrain.save.ships_to_everyone"));
    }

    /** Source-tree write failure line, kind-specific noun preserved. */
    private static Component bundledWriteFailMessage(Template model, String error) {
        return Component.translatable("chat.dungeontrain.save.bundled_failed." + kindKey(model), error);
    }

    /** The kinds that have their own wording; everything else reads the generic line. */
    private static String kindKey(Template model) {
        return switch (model.kind()) {
            case CONTENTS, PILLAR, STAIRS, TUNNEL, PORTAL_ROOM, TRACK -> model.kind().name().toLowerCase(java.util.Locale.ROOT);
            default -> "generic";
        };
    }

    /**
     * Reason {@code /dt save default} can't promote {@code model}. Three
     * existing kind-specific messages are preserved: contents (no separate
     * tier), tunnel (no bundled tier today), custom carriage (only built-ins
     * have a bundled tier).
     */
    private static Component promoteUnavailableMessage(Template model) {
        String kind = switch (model.kind()) {
            case CONTENTS, TUNNEL, PORTAL_ROOM, CARRIAGE -> model.kind().name().toLowerCase(java.util.Locale.ROOT);
            default -> "generic";
        };
        return Component.translatable("chat.dungeontrain.save.no_bundled_tier." + kind);
    }

    private static boolean isPlotEmpty(ServerLevel level, Template model, CarriageDims dims) {
        // Phase-4 collapse: Template.editorPlotOrigin + Template.plotSize
        // route the dispatch through the record's own knowledge of its plot
        // shape. Replaces the 7-arm instanceof chain that pre-Phase-4 dropped
        // the variant `name` for Pillar / Tunnel (silent bug — the wrong
        // plot was scanned for custom variants).
        BlockPos origin = model.editorPlotOrigin(level, dims);
        if (origin == null) return true;
        Vec3i size = model.plotSize(dims);
        return countNonAir(level, origin, size.getX(), size.getY(), size.getZ())
            < EMPTY_PLOT_THRESHOLD;
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
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return null;
        }
    }
}
