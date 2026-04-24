package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageContentsEditor;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriageEditor.SaveResult;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.editor.EditorModel;
import games.brennan.dungeontrain.editor.PillarEditor;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TrackEditor;
import games.brennan.dungeontrain.editor.TrackTemplateStore;
import games.brennan.dungeontrain.editor.TunnelEditor;
import games.brennan.dungeontrain.editor.TunnelTemplateStore;
import games.brennan.dungeontrain.editor.VariantOverlayRenderer;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code /dungeontrain editor ...} subtree — enter, save (with optional
 * rename), exit, list, reset, new, devmode, promote. Accepts both carriage
 * variants (resolved via {@link CarriageVariantRegistry}) and tunnel
 * variants ({@code tunnel_section}, {@code tunnel_portal}) for enter/save/
 * exit/list/reset. The {@code new}, {@code devmode}, and {@code promote}
 * subcommands are carriage-only. Wired into the root {@code dungeontrain}
 * command from {@link TrainCommand#register}.
 */
public final class EditorCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Prefix that distinguishes tunnel variants from carriage variants in command input. */
    private static final String TUNNEL_PREFIX = "tunnel_";

    /** Built-ins that cannot be renamed via {@code save <new_name>}. */
    private static final Set<String> PROTECTED_BUILTINS = Set.of("standard", "flatbed");

    private static final SuggestionProvider<CommandSourceStack> VARIANT_SUGGESTIONS =
        (ctx, builder) -> {
            for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
                builder.suggest(v.id());
            }
            for (TunnelVariant v : TunnelVariant.values()) {
                builder.suggest(TUNNEL_PREFIX + v.name().toLowerCase(Locale.ROOT));
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> BUILTIN_SUGGESTIONS =
        (ctx, builder) -> {
            for (CarriageType t : CarriageType.values()) {
                builder.suggest(t.name().toLowerCase(Locale.ROOT));
            }
            return builder.buildFuture();
        };

    /** Like VARIANT_SUGGESTIONS but carriage-only (no tunnels — weight is a carriage concept). */
    private static final SuggestionProvider<CommandSourceStack> CARRIAGE_VARIANT_SUGGESTIONS =
        (ctx, builder) -> {
            for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
                builder.suggest(v.id());
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PILLAR_SECTION_SUGGESTIONS =
        (ctx, builder) -> {
            for (PillarSection s : PillarSection.values()) {
                builder.suggest(s.id());
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> CONTENTS_SUGGESTIONS =
        (ctx, builder) -> {
            for (CarriageContents c : CarriageContentsRegistry.allContents()) {
                builder.suggest(c.id());
            }
            return builder.buildFuture();
        };

    private EditorCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build(CommandBuildContext buildContext) {
        return Commands.literal("editor")
            .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.CARRIAGES))
            .then(Commands.literal("carriages")
                .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.CARRIAGES)))
            .then(Commands.literal("tracks")
                .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.TRACKS)))
            .then(Commands.literal("architecture")
                .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.ARCHITECTURE)))
            .then(Commands.literal("enter")
                .executes(ctx -> runEnterCarriage(ctx.getSource(), CarriageVariant.of(CarriageType.STANDARD)))
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(VARIANT_SUGGESTIONS)
                    .executes(ctx -> runEnter(ctx.getSource(),
                        StringArgumentType.getString(ctx, "variant")))))
            .then(Commands.literal("save")
                .executes(ctx -> runSave(ctx.getSource(), null))
                .then(Commands.argument("new_name", StringArgumentType.word())
                    .executes(ctx -> runSave(ctx.getSource(),
                        StringArgumentType.getString(ctx, "new_name")))))
            .then(Commands.literal("exit").executes(ctx -> runExit(ctx.getSource())))
            .then(Commands.literal("list").executes(ctx -> runList(ctx.getSource())))
            .then(Commands.literal("reset")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(VARIANT_SUGGESTIONS)
                    .executes(ctx -> runReset(ctx.getSource(),
                        StringArgumentType.getString(ctx, "variant")))))
            .then(Commands.literal("new")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> runNew(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name"),
                        CarriageVariant.of(CarriageType.STANDARD)))
                    .then(Commands.argument("source", StringArgumentType.word())
                        .suggests(VARIANT_SUGGESTIONS)
                        .executes(ctx -> {
                            CarriageVariant src = parseVariant(ctx.getSource(),
                                StringArgumentType.getString(ctx, "source"));
                            if (src == null) return 0;
                            return runNew(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"), src);
                        }))))
            .then(Commands.literal("devmode")
                .executes(ctx -> runDevMode(ctx.getSource(), !EditorDevMode.isEnabled()))
                .then(Commands.literal("on").executes(ctx -> runDevMode(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runDevMode(ctx.getSource(), false))))
            .then(Commands.literal("promote")
                .then(Commands.literal("all").executes(ctx -> runPromoteAll(ctx.getSource())))
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(BUILTIN_SUGGESTIONS)
                    .executes(ctx -> {
                        CarriageType type = parseBuiltin(ctx.getSource(),
                            StringArgumentType.getString(ctx, "variant"));
                        if (type == null) return 0;
                        return runPromote(ctx.getSource(), type);
                    })))
            .then(Commands.literal("contents")
                .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.CONTENTS))
                .then(Commands.literal("enter")
                    .then(Commands.argument("contents", StringArgumentType.word())
                        .suggests(CONTENTS_SUGGESTIONS)
                        .executes(ctx -> runContentsEnter(ctx.getSource(),
                            StringArgumentType.getString(ctx, "contents"), null))
                        .then(Commands.argument("shell_variant", StringArgumentType.word())
                            .suggests(CARRIAGE_VARIANT_SUGGESTIONS)
                            .executes(ctx -> runContentsEnter(ctx.getSource(),
                                StringArgumentType.getString(ctx, "contents"),
                                StringArgumentType.getString(ctx, "shell_variant"))))))
                .then(Commands.literal("save")
                    .executes(ctx -> runContentsSave(ctx.getSource(), null))
                    .then(Commands.argument("new_name", StringArgumentType.word())
                        .executes(ctx -> runContentsSave(ctx.getSource(),
                            StringArgumentType.getString(ctx, "new_name")))))
                .then(Commands.literal("list").executes(ctx -> runContentsList(ctx.getSource())))
                .then(Commands.literal("reset")
                    .then(Commands.argument("contents", StringArgumentType.word())
                        .suggests(CONTENTS_SUGGESTIONS)
                        .executes(ctx -> runContentsReset(ctx.getSource(),
                            StringArgumentType.getString(ctx, "contents")))))
                .then(Commands.literal("new")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> runContentsNew(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name"),
                            CarriageContents.of(CarriageContents.ContentsType.DEFAULT)))
                        .then(Commands.argument("source", StringArgumentType.word())
                            .suggests(CONTENTS_SUGGESTIONS)
                            .executes(ctx -> {
                                CarriageContents src = parseContents(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "source"));
                                if (src == null) return 0;
                                return runContentsNew(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "name"), src);
                            })))))
            .then(Commands.literal("pillar")
                .then(Commands.literal("enter")
                    .then(Commands.argument("section", StringArgumentType.word())
                        .suggests(PILLAR_SECTION_SUGGESTIONS)
                        .executes(ctx -> {
                            PillarSection s = parseSection(ctx.getSource(),
                                StringArgumentType.getString(ctx, "section"));
                            if (s == null) return 0;
                            return runPillarEnter(ctx.getSource(), s);
                        })))
                .then(Commands.literal("save").executes(ctx -> runPillarSave(ctx.getSource())))
                .then(Commands.literal("list").executes(ctx -> runPillarList(ctx.getSource())))
                .then(Commands.literal("reset")
                    .then(Commands.argument("section", StringArgumentType.word())
                        .suggests(PILLAR_SECTION_SUGGESTIONS)
                        .executes(ctx -> {
                            PillarSection s = parseSection(ctx.getSource(),
                                StringArgumentType.getString(ctx, "section"));
                            if (s == null) return 0;
                            return runPillarReset(ctx.getSource(), s);
                        })))
                .then(Commands.literal("promote")
                    .then(Commands.literal("all").executes(ctx -> runPillarPromoteAll(ctx.getSource())))
                    .then(Commands.argument("section", StringArgumentType.word())
                        .suggests(PILLAR_SECTION_SUGGESTIONS)
                        .executes(ctx -> {
                            PillarSection s = parseSection(ctx.getSource(),
                                StringArgumentType.getString(ctx, "section"));
                            if (s == null) return 0;
                            return runPillarPromote(ctx.getSource(), s);
                        }))))
            .then(Commands.literal("track")
                .then(Commands.literal("enter").executes(ctx -> runTrackEnter(ctx.getSource())))
                .then(Commands.literal("save").executes(ctx -> runTrackSave(ctx.getSource())))
                .then(Commands.literal("list").executes(ctx -> runTrackList(ctx.getSource())))
                .then(Commands.literal("reset").executes(ctx -> runTrackReset(ctx.getSource())))
                .then(Commands.literal("promote").executes(ctx -> runTrackPromote(ctx.getSource()))))
            .then(Commands.literal("weight")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(CARRIAGE_VARIANT_SUGGESTIONS)
                    .then(Commands.argument("value",
                            IntegerArgumentType.integer(CarriageWeights.MIN, CarriageWeights.MAX))
                        .executes(ctx -> runWeightSet(ctx.getSource(),
                            StringArgumentType.getString(ctx, "variant"),
                            IntegerArgumentType.getInteger(ctx, "value"))))))
            .then(buildVariantSubtree(buildContext));
    }

    private static int runWeightSet(CommandSourceStack source, String rawVariant, int value) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        try {
            int stored = CarriageWeights.set(variant.id(), value);
            source.sendSuccess(() -> Component.literal(
                "Editor: weight " + variant.id() + "=" + stored
                    + " (saved to " + CarriageWeights.configPath() + "). "
                    + "Existing carriages keep their variant until they scroll out."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor weight set failed for {}", variant.id(), t);
            source.sendFailure(Component.literal("weight failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static PillarSection parseSection(CommandSourceStack source, String raw) {
        try {
            return PillarSection.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(
                "Unknown pillar section '" + raw + "'. Valid: top, middle, bottom"
            ));
            return null;
        }
    }

    /**
     * {@code /dungeontrain editor variant ...} — inspect + reset per-position
     * random variant blocks for the plot the player is standing in.
     *
     * <p>Authoring is in-world: <b>sneak + right-click</b> a block in the
     * plot with a different block held in main hand to append that block to
     * the variants list at the targeted position. See
     * {@link games.brennan.dungeontrain.editor.VariantBlockInteractions}. The
     * commands below only clear / list / toggle overlay; they no longer edit
     * the state list directly.</p>
     *
     * <p>All changes mutate the in-memory sidecar eagerly;
     * {@code /editor save} snapshots it to disk alongside the NBT template.</p>
     */
    @SuppressWarnings("unused") // buildContext retained for symmetry with prior signature + future subcommands
    private static LiteralArgumentBuilder<CommandSourceStack> buildVariantSubtree(CommandBuildContext buildContext) {
        return Commands.literal("variant")
            .then(Commands.literal("clear").executes(ctx -> runVariantClear(ctx.getSource())))
            .then(Commands.literal("list").executes(ctx -> runVariantList(ctx.getSource())))
            .then(Commands.literal("overlay")
                .then(Commands.literal("on").executes(ctx -> runVariantOverlay(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runVariantOverlay(ctx.getSource(), false))));
    }

    /** Resolve the (plot variant, local pos) the player is currently targeting, or null with an error sent. */
    private record VariantTarget(CarriageVariant variant, BlockPos localPos, CarriageDims dims) {}

    private static VariantTarget resolveTarget(CommandSourceStack source, ServerPlayer player) {
        ServerLevel level = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        CarriageVariant plotVariant = CarriageEditor.plotContaining(player.blockPosition(), dims);
        if (plotVariant == null) {
            source.sendFailure(Component.literal(
                "Not in an editor plot. Use '/dungeontrain editor enter <variant>' first."));
            return null;
        }
        HitResult hit = player.pick(8.0, 1.0f, false);
        if (!(hit instanceof BlockHitResult bhr) || bhr.getType() == HitResult.Type.MISS) {
            source.sendFailure(Component.literal(
                "Look directly at a block inside the plot first (8-block reach)."));
            return null;
        }
        BlockPos plotOrigin = CarriageEditor.plotOrigin(plotVariant);
        if (plotOrigin == null) {
            source.sendFailure(Component.literal("Plot origin missing for '" + plotVariant.id() + "'."));
            return null;
        }
        BlockPos local = bhr.getBlockPos().subtract(plotOrigin);
        if (local.getX() < 0 || local.getX() >= dims.length()
            || local.getY() < 0 || local.getY() >= dims.height()
            || local.getZ() < 0 || local.getZ() >= dims.width()) {
            source.sendFailure(Component.literal(
                "Target block is outside the plot footprint (local " + local + " vs dims "
                    + dims.length() + "x" + dims.height() + "x" + dims.width() + ")."));
            return null;
        }
        return new VariantTarget(plotVariant, local, dims);
    }

    private static int runVariantClear(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        VariantTarget target = resolveTarget(source, player);
        if (target == null) return 0;

        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(target.variant(), target.dims());
        boolean removed = sidecar.remove(target.localPos());
        final String pos = target.localPos().getX() + "," + target.localPos().getY() + "," + target.localPos().getZ();
        if (removed) {
            source.sendSuccess(() -> Component.literal(
                "Editor: cleared variant at local " + pos + " on '" + target.variant().id()
                    + "'. Run '/dungeontrain editor save' to persist."
            ).withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendSuccess(() -> Component.literal(
                "Editor: no variant at local " + pos + " to clear."
            ), false);
        }
        return removed ? 1 : 0;
    }

    private static int runVariantList(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ServerLevel level = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        CarriageVariant plotVariant = CarriageEditor.plotContaining(player.blockPosition(), dims);
        if (plotVariant == null) {
            source.sendFailure(Component.literal(
                "Not in an editor plot. Use '/dungeontrain editor enter <variant>' first."));
            return 0;
        }
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(plotVariant, dims);
        if (sidecar.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "Variants for '" + plotVariant.id() + "': (none)"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("Variants for '").append(plotVariant.id()).append("' (")
            .append(sidecar.size()).append(" entries):");
        for (CarriageVariantBlocks.Entry e : sidecar.entries()) {
            sb.append("\n  ").append(e.localPos().getX()).append(",")
                .append(e.localPos().getY()).append(",").append(e.localPos().getZ())
                .append(" → ");
            boolean first = true;
            for (BlockState s : e.states()) {
                if (!first) sb.append(", ");
                sb.append(BuiltInRegistries.BLOCK.getKey(s.getBlock()));
                first = false;
            }
        }
        final String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int runVariantOverlay(CommandSourceStack source, boolean on) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        VariantOverlayRenderer.setEnabled(player, on);
        source.sendSuccess(() -> Component.literal(
            "Editor overlay: " + (on ? "ON" : "off") + "."
        ), false);
        return 1;
    }

    /** True when the raw input targets a tunnel variant (prefix {@code tunnel_}). */
    private static boolean isTunnelInput(String raw) {
        return raw != null && raw.toLowerCase(Locale.ROOT).startsWith(TUNNEL_PREFIX);
    }

    /** Parse a tunnel variant argument or return {@code null} (and send an error). */
    private static TunnelVariant parseTunnelVariant(CommandSourceStack source, String raw) {
        String body = raw.toLowerCase(Locale.ROOT).substring(TUNNEL_PREFIX.length());
        try {
            return TunnelVariant.valueOf(body.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(
                "Unknown tunnel variant '" + raw + "'. Valid: tunnel_section, tunnel_portal"
            ));
            return null;
        }
    }

    private static CarriageVariant parseVariant(CommandSourceStack source, String raw) {
        String id = raw.toLowerCase(Locale.ROOT);
        return CarriageVariantRegistry.find(id).orElseGet(() -> {
            source.sendFailure(Component.literal(
                "Unknown variant '" + raw + "'. Valid: " + listIds()
            ));
            return null;
        });
    }

    /** Parse a built-in enum name for commands that only accept built-ins (promote). */
    private static CarriageType parseBuiltin(CommandSourceStack source, String raw) {
        try {
            return CarriageType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(
                "Unknown built-in '" + raw + "'. Valid: standard, windowed, solid_roof, flatbed"
            ));
            return null;
        }
    }

    private static String listIds() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            if (!first) sb.append(", ");
            sb.append(v.id());
            first = false;
        }
        for (TunnelVariant v : TunnelVariant.values()) {
            sb.append(", ").append(TUNNEL_PREFIX).append(v.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return null;
        }
    }

    /**
     * Category-level enter: stamp every plot in {@code category} so the player
     * can walk between all of them, then teleport them to the first model.
     * Architecture has no models yet and returns a "coming soon" message.
     */
    private static int runEnterCategory(CommandSourceStack source, EditorCategory category) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        if (category == EditorCategory.ARCHITECTURE) {
            source.sendSuccess(() -> Component.literal(
                "Architecture editor: coming soon. Walls, floor, and roof templates aren't implemented yet."
            ).withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }

        java.util.Optional<EditorModel> first = category.firstModel();
        if (first.isEmpty()) {
            source.sendFailure(Component.literal(
                "Category '" + category.displayName() + "' has no models."));
            return 0;
        }

        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        // Clear every plot from every category first — switching from one
        // category to another should leave no stale models behind.
        EditorCategory.clearAllPlots(overworld, dims);

        // Stamp every plot so the full list is visible at once.
        for (EditorModel model : category.models()) {
            stampCategoryModel(overworld, model, dims);
        }

        // Teleport to the first via the existing enter path (also handles session + outline).
        try {
            EditorModel head = first.get();
            if (head instanceof EditorModel.CarriageModel cm) {
                CarriageEditor.enter(player, cm.variant());
            } else if (head instanceof EditorModel.ContentsModel cm) {
                CarriageContentsEditor.enter(player, cm.contents(), null);
            } else if (head instanceof EditorModel.PillarModel pm) {
                PillarEditor.enter(player, pm.section());
            } else if (head instanceof EditorModel.TunnelModel tm) {
                TunnelEditor.enter(player, tm.variant());
            } else if (head instanceof EditorModel.TrackModel) {
                TrackEditor.enter(player);
            }
            final EditorModel firstModel = head;
            source.sendSuccess(() -> Component.literal(
                "Editor: entered '" + category.displayName() + "' at '" + firstModel.displayName() + "'."
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor enter-category failed", t);
            source.sendFailure(Component.literal("enter failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static void stampCategoryModel(ServerLevel overworld, EditorModel model, CarriageDims dims) {
        if (model instanceof EditorModel.CarriageModel cm) {
            CarriageEditor.stampPlot(overworld, cm.variant(), dims);
        } else if (model instanceof EditorModel.ContentsModel cm) {
            CarriageContentsEditor.stampPlot(overworld, cm.contents(), dims);
        } else if (model instanceof EditorModel.PillarModel pm) {
            PillarEditor.stampPlot(overworld, pm.section(), dims);
        } else if (model instanceof EditorModel.TunnelModel tm) {
            TunnelEditor.stampPlot(overworld, tm.variant());
        } else if (model instanceof EditorModel.TrackModel) {
            TrackEditor.stampPlot(overworld, dims);
        }
    }

    private static int runEnter(CommandSourceStack source, String raw) {
        if (isTunnelInput(raw)) {
            TunnelVariant v = parseTunnelVariant(source, raw);
            if (v == null) return 0;
            return runEnterTunnel(source, v);
        }
        CarriageVariant v = parseVariant(source, raw);
        if (v == null) return 0;
        return runEnterCarriage(source, v);
    }

    private static int runEnterCarriage(CommandSourceStack source, CarriageVariant variant) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        try {
            CarriageEditor.enter(player, variant);
            source.sendSuccess(() -> Component.literal(
                "Editor: entered '" + variant.id()
                    + "' plot at " + CarriageEditor.plotOrigin(variant)
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor enter failed", t);
            source.sendFailure(Component.literal("enter failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runEnterTunnel(CommandSourceStack source, TunnelVariant variant) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        try {
            TunnelEditor.enter(player, variant);
            source.sendSuccess(() -> Component.literal(
                "Editor: entered '" + TUNNEL_PREFIX + variant.name().toLowerCase(Locale.ROOT)
                    + "' plot at " + TunnelEditor.plotOrigin(variant)
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor enter (tunnel) failed", t);
            source.sendFailure(Component.literal("enter failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runSave(CommandSourceStack source, String newName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        CarriageDims dimsForDispatch = DungeonTrainWorldData.get(source.getServer().overworld()).dims();

        // Contents plots take priority over carriage plots so a player
        // standing in a contents plot saves the contents template (not the
        // shell template).
        CarriageContents contentsInPlot = CarriageContentsEditor.plotContaining(player.blockPosition(), dimsForDispatch);
        if (contentsInPlot != null) {
            return runContentsSave(source, newName);
        }

        // Tunnel plots take priority over carriage plots too.
        TunnelVariant tunnel = TunnelEditor.plotContaining(player.blockPosition());
        if (tunnel != null) {
            if (newName != null) {
                source.sendFailure(Component.literal(
                    "Tunnel templates don't support rename; run '/dungeontrain editor save' without arguments."
                ));
                return 0;
            }
            try {
                TunnelEditor.save(player, tunnel);
                final TunnelVariant t = tunnel;
                source.sendSuccess(() -> Component.literal(
                    "Editor: saved '" + TUNNEL_PREFIX + t.name().toLowerCase(Locale.ROOT) + "' template."
                ), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor save (tunnel) failed", t);
                source.sendFailure(Component.literal("save failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                ).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        CarriageVariant current = CarriageEditor.plotContaining(player.blockPosition(), dims);
        if (current == null) {
            source.sendFailure(Component.literal(
                "Not in an editor plot. Use '/dungeontrain editor enter <variant>' first."
            ));
            return 0;
        }

        if (newName == null) {
            try {
                SaveResult result = CarriageEditor.save(player, current);
                source.sendSuccess(() -> Component.literal(
                    "Editor: saved '" + current.id() + "' template (config-dir)."
                ), true);
                if (result.sourceAttempted()) {
                    if (result.sourceWritten()) {
                        source.sendSuccess(() -> Component.literal(
                            "Editor: also wrote bundled copy to source tree (will ship with next build)."
                        ).withStyle(ChatFormatting.GREEN), true);
                    } else {
                        source.sendFailure(Component.literal(
                            "Editor: source-tree write failed: " + result.sourceError()
                        ).withStyle(ChatFormatting.YELLOW));
                    }
                }
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor save failed", t);
                source.sendFailure(Component.literal("save failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                ).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        // Rename path.
        if (PROTECTED_BUILTINS.contains(current.id())) {
            source.sendFailure(Component.literal(
                "Cannot rename '" + current.id() + "' — it is a protected built-in."
            ));
            return 0;
        }
        String newId = newName.toLowerCase(Locale.ROOT);
        if (!CarriageVariant.NAME_PATTERN.matcher(newId).matches()) {
            source.sendFailure(Component.literal(
                "Invalid name '" + newName + "'. Use lowercase letters, digits or underscore (1-32 chars)."
            ));
            return 0;
        }
        if (CarriageVariant.isReservedBuiltinName(newId)) {
            source.sendFailure(Component.literal(
                "Name '" + newId + "' is reserved for a built-in."
            ));
            return 0;
        }
        if (CarriageVariantRegistry.find(newId).isPresent()) {
            source.sendFailure(Component.literal(
                "Name '" + newId + "' is already taken."
            ));
            return 0;
        }
        try {
            CarriageVariant.Custom renamed = (CarriageVariant.Custom) CarriageVariant.custom(newId);
            CarriageEditor.saveAs(player, current, renamed);
            source.sendSuccess(() -> Component.literal(
                "Editor: saved and renamed '" + current.id() + "' → '" + renamed.id() + "'."
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor save-rename failed", t);
            source.sendFailure(Component.literal("save failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runExit(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        // Try tunnel-session first (separate session map from CarriageEditor).
        // A user who entered a carriage plot, then a tunnel plot, needs to run
        // exit twice to unwind both sessions.
        boolean exited = TunnelEditor.exit(player) || CarriageEditor.exit(player);
        if (!exited) {
            source.sendFailure(Component.literal(
                "No saved editor session — nothing to exit to."
            ));
            return 0;
        }
        // Clear every plot so the sky stays tidy for the next session.
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        EditorCategory.clearAllPlots(overworld, dims);
        source.sendSuccess(() -> Component.literal(
            "Editor: exited, returned to previous location."
        ), true);
        return 1;
    }

    private static int runList(CommandSourceStack source) {
        CarriageWeights weights = CarriageWeights.current();
        StringBuilder sb = new StringBuilder("Carriage variants:");
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            boolean config = CarriageTemplateStore.exists(v);
            boolean bundled = CarriageTemplateStore.bundled(v);
            String kind = v.isBuiltin() ? "builtin" : "custom";
            String status;
            if (config) status = "config override";
            else if (bundled) status = "bundled default";
            else status = v.isBuiltin() ? "fallback (hardcoded)" : "missing (no file)";
            int weight = weights.weightFor(v.id());
            sb.append("\n  ").append(v.id())
                .append(" — ").append(kind).append(" | ").append(status)
                .append(" | weight=").append(weight)
                .append(" [config: ").append(config ? "yes" : "no")
                .append(", bundled: ").append(bundled ? "yes" : "no").append("]");
        }
        sb.append("\nTunnel variants:");
        for (TunnelVariant v : TunnelVariant.values()) {
            boolean saved = TunnelTemplateStore.exists(v);
            sb.append("\n  ").append(TUNNEL_PREFIX).append(v.name().toLowerCase(Locale.ROOT))
                .append(" — ").append(saved ? "saved" : "fallback (procedural)");
        }
        sb.append("\nDev mode: ").append(EditorDevMode.isEnabled() ? "ON" : "off");
        sb.append("\nSource tree writable: ").append(CarriageTemplateStore.sourceTreeAvailable() ? "yes" : "no");
        String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int runReset(CommandSourceStack source, String raw) {
        if (isTunnelInput(raw)) {
            TunnelVariant v = parseTunnelVariant(source, raw);
            if (v == null) return 0;
            try {
                boolean deleted = TunnelTemplateStore.delete(v);
                final TunnelVariant tv = v;
                source.sendSuccess(() -> Component.literal(
                    deleted
                        ? "Editor: deleted '" + TUNNEL_PREFIX + tv.name().toLowerCase(Locale.ROOT) + "' template."
                        : "Editor: no '" + TUNNEL_PREFIX + tv.name().toLowerCase(Locale.ROOT) + "' template to delete."
                ), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor reset (tunnel) failed", t);
                source.sendFailure(Component.literal("reset failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                ).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        CarriageVariant variant = parseVariant(source, raw);
        if (variant == null) return 0;
        try {
            boolean deleted = CarriageTemplateStore.delete(variant);
            boolean wasCustom = !variant.isBuiltin();
            if (wasCustom) CarriageVariantRegistry.unregister(variant.id());
            source.sendSuccess(() -> Component.literal(
                deleted
                    ? ("Editor: deleted '" + variant.id() + "' template"
                        + (wasCustom ? " and removed from registry." : "."))
                    : ("Editor: no '" + variant.id() + "' template to delete.")
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor reset failed", t);
            source.sendFailure(Component.literal("reset failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runNew(CommandSourceStack source, String rawName, CarriageVariant sourceVariant) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        String name = rawName.toLowerCase(Locale.ROOT);
        if (!CarriageVariant.NAME_PATTERN.matcher(name).matches()) {
            source.sendFailure(Component.literal(
                "Invalid name '" + rawName + "'. Use lowercase letters, digits or underscore (1-32 chars)."
            ));
            return 0;
        }
        if (CarriageVariant.isReservedBuiltinName(name)) {
            source.sendFailure(Component.literal(
                "Name '" + name + "' is reserved for a built-in."
            ));
            return 0;
        }
        if (CarriageVariantRegistry.find(name).isPresent()) {
            source.sendFailure(Component.literal(
                "Name '" + name + "' is already taken."
            ));
            return 0;
        }

        try {
            CarriageVariant.Custom target = (CarriageVariant.Custom) CarriageVariant.custom(name);
            var origin = CarriageEditor.duplicate(player, sourceVariant, target);
            source.sendSuccess(() -> Component.literal(
                "Editor: created '" + target.id() + "' from '" + sourceVariant.id()
                    + "' at plot " + origin
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor new failed", t);
            source.sendFailure(Component.literal("new failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runDevMode(CommandSourceStack source, boolean on) {
        EditorDevMode.set(on);
        boolean writable = CarriageTemplateStore.sourceTreeAvailable();
        if (on) {
            if (writable) {
                source.sendSuccess(() -> Component.literal(
                    "Editor dev mode: ON — '/editor save' will also write to source tree."
                ).withStyle(ChatFormatting.GREEN), true);
            } else {
                source.sendSuccess(() -> Component.literal(
                    "Editor dev mode: ON — but source tree is NOT writable. Are you running ./gradlew runClient?"
                ).withStyle(ChatFormatting.YELLOW), true);
            }
        } else {
            source.sendSuccess(() -> Component.literal(
                "Editor dev mode: off — '/editor save' writes to config-dir only."
            ), true);
        }
        return 1;
    }

    private static int runPromote(CommandSourceStack source, CarriageType type) {
        try {
            CarriageTemplateStore.promote(type);
            source.sendSuccess(() -> Component.literal(
                "Editor: promoted '" + type.name().toLowerCase(Locale.ROOT)
                    + "' template to source tree (will ship with next build)."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor promote failed for {}", type, t);
            source.sendFailure(Component.literal("promote failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPromoteAll(CommandSourceStack source) {
        if (!CarriageTemplateStore.sourceTreeAvailable()) {
            source.sendFailure(Component.literal(
                "promote all failed: source tree not writable. Are you running ./gradlew runClient?"
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
        int promoted = 0;
        int skipped = 0;
        StringBuilder errors = new StringBuilder();
        for (CarriageType type : CarriageType.values()) {
            CarriageVariant variant = CarriageVariant.of(type);
            if (!CarriageTemplateStore.exists(variant)) {
                skipped++;
                continue;
            }
            try {
                CarriageTemplateStore.promote(type);
                promoted++;
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] editor promote-all failed for {}", type, e);
                errors.append("\n  ").append(type.name().toLowerCase(Locale.ROOT))
                    .append(": ").append(e.getMessage());
            }
        }
        final int p = promoted;
        final int s = skipped;
        final String errStr = errors.toString();
        source.sendSuccess(() -> Component.literal(
            "Editor: promote all — " + p + " promoted, " + s + " skipped (no config copy)."
                + (errStr.isEmpty() ? "" : "\nErrors:" + errStr)
        ).withStyle(errStr.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return p > 0 ? 1 : 0;
    }

    private static int runPillarEnter(CommandSourceStack source, PillarSection section) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        try {
            PillarEditor.enter(player, section);
            source.sendSuccess(() -> Component.literal(
                "Editor: entered pillar '" + section.id()
                    + "' plot at " + PillarEditor.plotOrigin(section)
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar enter failed", t);
            source.sendFailure(Component.literal("pillar enter failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPillarSave(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        PillarSection section = PillarEditor.plotContaining(player.blockPosition(), dims);
        if (section == null) {
            source.sendFailure(Component.literal(
                "Not in a pillar editor plot. Use '/dungeontrain editor pillar enter <top|middle|bottom>' first."
            ));
            return 0;
        }
        try {
            PillarEditor.SaveResult result = PillarEditor.save(player, section);
            source.sendSuccess(() -> Component.literal(
                "Editor: saved pillar '" + section.id() + "' template (config-dir)."
            ), true);
            if (result.sourceAttempted()) {
                if (result.sourceWritten()) {
                    source.sendSuccess(() -> Component.literal(
                        "Editor: also wrote bundled pillar copy to source tree (will ship with next build)."
                    ).withStyle(ChatFormatting.GREEN), true);
                } else {
                    source.sendFailure(Component.literal(
                        "Editor: pillar source-tree write failed: " + result.sourceError()
                    ).withStyle(ChatFormatting.YELLOW));
                }
            }
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar save failed", t);
            source.sendFailure(Component.literal("pillar save failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPillarList(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder("Pillar templates:");
        for (PillarSection section : PillarSection.values()) {
            boolean config = PillarTemplateStore.exists(section);
            boolean bundled = PillarTemplateStore.bundled(section);
            String status;
            if (config) status = "config override";
            else if (bundled) status = "bundled default";
            else status = "fallback (stone brick)";
            sb.append("\n  ").append(section.id())
                .append(" (height=").append(section.height()).append(")")
                .append(" — ").append(status)
                .append(" [config: ").append(config ? "yes" : "no")
                .append(", bundled: ").append(bundled ? "yes" : "no").append("]");
        }
        sb.append("\nDev mode: ").append(EditorDevMode.isEnabled() ? "ON" : "off");
        sb.append("\nSource tree writable: ").append(PillarTemplateStore.sourceTreeAvailable() ? "yes" : "no");
        String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int runPillarReset(CommandSourceStack source, PillarSection section) {
        try {
            boolean deleted = PillarTemplateStore.delete(section);
            source.sendSuccess(() -> Component.literal(
                deleted
                    ? "Editor: deleted pillar '" + section.id() + "' template."
                    : "Editor: no pillar '" + section.id() + "' template to delete."
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar reset failed", t);
            source.sendFailure(Component.literal("pillar reset failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPillarPromote(CommandSourceStack source, PillarSection section) {
        try {
            PillarTemplateStore.promote(section);
            source.sendSuccess(() -> Component.literal(
                "Editor: promoted pillar '" + section.id()
                    + "' template to source tree (will ship with next build)."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar promote failed for {}", section, t);
            source.sendFailure(Component.literal("pillar promote failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static CarriageContents parseContents(CommandSourceStack source, String raw) {
        String id = raw.toLowerCase(Locale.ROOT);
        return CarriageContentsRegistry.find(id).orElseGet(() -> {
            source.sendFailure(Component.literal(
                "Unknown contents '" + raw + "'. Valid: " + listContentsIds()
            ));
            return null;
        });
    }

    private static String listContentsIds() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (CarriageContents c : CarriageContentsRegistry.allContents()) {
            if (!first) sb.append(", ");
            sb.append(c.id());
            first = false;
        }
        return sb.toString();
    }

    private static int runContentsEnter(CommandSourceStack source, String contentsRaw, String shellRaw) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriageContents contents = parseContents(source, contentsRaw);
        if (contents == null) return 0;
        CarriageVariant shell = shellRaw == null
            ? null
            : CarriageVariantRegistry.find(shellRaw.toLowerCase(Locale.ROOT)).orElse(null);
        if (shellRaw != null && shell == null) {
            source.sendFailure(Component.literal(
                "Unknown shell variant '" + shellRaw + "'. Valid: " + listIds()
            ));
            return 0;
        }
        try {
            CarriageContentsEditor.enter(player, contents, shell);
            final CarriageVariant shellUsed = CarriageContentsEditor.resolveShellOrDefault(shellRaw);
            source.sendSuccess(() -> Component.literal(
                "Editor: entered contents '" + contents.id()
                    + "' (shell=" + shellUsed.id() + ") plot at "
                    + CarriageContentsEditor.plotOrigin(contents)
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents enter failed", t);
            source.sendFailure(Component.literal("contents enter failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runContentsSave(CommandSourceStack source, String newName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        CarriageContents current = CarriageContentsEditor.plotContaining(player.blockPosition(), dims);
        if (current == null) {
            source.sendFailure(Component.literal(
                "Not in a contents editor plot. Use '/dungeontrain editor contents enter <name>' first."
            ));
            return 0;
        }

        if (newName == null) {
            try {
                CarriageContentsEditor.SaveResult result = CarriageContentsEditor.save(player, current);
                source.sendSuccess(() -> Component.literal(
                    "Editor: saved contents '" + current.id() + "' template (config-dir)."
                ), true);
                if (result.sourceAttempted()) {
                    if (result.sourceWritten()) {
                        source.sendSuccess(() -> Component.literal(
                            "Editor: also wrote bundled contents copy to source tree (will ship with next build)."
                        ).withStyle(ChatFormatting.GREEN), true);
                    } else {
                        source.sendFailure(Component.literal(
                            "Editor: contents source-tree write failed: " + result.sourceError()
                        ).withStyle(ChatFormatting.YELLOW));
                    }
                }
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor contents save failed", t);
                source.sendFailure(Component.literal("contents save failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                ).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        // Rename path.
        if (current.isBuiltin()) {
            source.sendFailure(Component.literal(
                "Cannot rename '" + current.id() + "' — it is a built-in contents variant."
            ));
            return 0;
        }
        String newId = newName.toLowerCase(Locale.ROOT);
        if (!CarriageContents.NAME_PATTERN.matcher(newId).matches()) {
            source.sendFailure(Component.literal(
                "Invalid name '" + newName + "'. Use lowercase letters, digits or underscore (1-32 chars)."
            ));
            return 0;
        }
        if (CarriageContents.isReservedBuiltinName(newId)) {
            source.sendFailure(Component.literal(
                "Name '" + newId + "' is reserved for a built-in."
            ));
            return 0;
        }
        if (CarriageContentsRegistry.find(newId).isPresent()) {
            source.sendFailure(Component.literal(
                "Name '" + newId + "' is already taken."
            ));
            return 0;
        }
        try {
            CarriageContents.Custom renamed = (CarriageContents.Custom) CarriageContents.custom(newId);
            CarriageContentsEditor.saveAs(player, current, renamed);
            source.sendSuccess(() -> Component.literal(
                "Editor: saved and renamed contents '" + current.id() + "' → '" + renamed.id() + "'."
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents save-rename failed", t);
            source.sendFailure(Component.literal("contents save failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runContentsList(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder("Carriage contents variants:");
        for (CarriageContents c : CarriageContentsRegistry.allContents()) {
            boolean config = CarriageContentsStore.exists(c);
            boolean bundled = CarriageContentsStore.bundled(c);
            String kind = c.isBuiltin() ? "builtin" : "custom";
            String status;
            if (config) status = "config override";
            else if (bundled) status = "bundled default";
            else status = c.isBuiltin() ? "fallback (hardcoded)" : "missing (no file)";
            sb.append("\n  ").append(c.id())
                .append(" — ").append(kind).append(" | ").append(status)
                .append(" [config: ").append(config ? "yes" : "no")
                .append(", bundled: ").append(bundled ? "yes" : "no").append("]");
        }
        sb.append("\nDev mode: ").append(EditorDevMode.isEnabled() ? "ON" : "off");
        sb.append("\nSource tree writable: ").append(CarriageContentsStore.sourceTreeAvailable() ? "yes" : "no");
        String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int runContentsReset(CommandSourceStack source, String raw) {
        CarriageContents contents = parseContents(source, raw);
        if (contents == null) return 0;
        try {
            boolean deleted = CarriageContentsStore.delete(contents);
            boolean wasCustom = !contents.isBuiltin();
            if (wasCustom) CarriageContentsRegistry.unregister(contents.id());
            source.sendSuccess(() -> Component.literal(
                deleted
                    ? ("Editor: deleted contents '" + contents.id() + "' template"
                        + (wasCustom ? " and removed from registry." : "."))
                    : ("Editor: no contents '" + contents.id() + "' template to delete.")
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents reset failed", t);
            source.sendFailure(Component.literal("contents reset failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runContentsNew(CommandSourceStack source, String rawName, CarriageContents sourceContents) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        String name = rawName.toLowerCase(Locale.ROOT);
        if (!CarriageContents.NAME_PATTERN.matcher(name).matches()) {
            source.sendFailure(Component.literal(
                "Invalid name '" + rawName + "'. Use lowercase letters, digits or underscore (1-32 chars)."
            ));
            return 0;
        }
        if (CarriageContents.isReservedBuiltinName(name)) {
            source.sendFailure(Component.literal(
                "Name '" + name + "' is reserved for a built-in."
            ));
            return 0;
        }
        if (CarriageContentsRegistry.find(name).isPresent()) {
            source.sendFailure(Component.literal(
                "Name '" + name + "' is already taken."
            ));
            return 0;
        }

        try {
            CarriageContents.Custom target = (CarriageContents.Custom) CarriageContents.custom(name);
            var origin = CarriageContentsEditor.duplicate(player, sourceContents, target);
            source.sendSuccess(() -> Component.literal(
                "Editor: created contents '" + target.id() + "' from '" + sourceContents.id()
                    + "' at plot " + origin
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents new failed", t);
            source.sendFailure(Component.literal("contents new failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPillarPromoteAll(CommandSourceStack source) {
        if (!PillarTemplateStore.sourceTreeAvailable()) {
            source.sendFailure(Component.literal(
                "pillar promote all failed: source tree not writable. Are you running ./gradlew runClient?"
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
        int promoted = 0;
        int skipped = 0;
        StringBuilder errors = new StringBuilder();
        for (PillarSection section : PillarSection.values()) {
            if (!PillarTemplateStore.exists(section)) {
                skipped++;
                continue;
            }
            try {
                PillarTemplateStore.promote(section);
                promoted++;
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] editor pillar promote-all failed for {}", section, e);
                errors.append("\n  ").append(section.id())
                    .append(": ").append(e.getMessage());
            }
        }
        final int p = promoted;
        final int s = skipped;
        final String errStr = errors.toString();
        source.sendSuccess(() -> Component.literal(
            "Editor: pillar promote all — " + p + " promoted, " + s + " skipped (no config copy)."
                + (errStr.isEmpty() ? "" : "\nErrors:" + errStr)
        ).withStyle(errStr.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return p > 0 ? 1 : 0;
    }

    private static int runTrackEnter(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        try {
            TrackEditor.enter(player);
            source.sendSuccess(() -> Component.literal(
                "Editor: entered track plot at " + TrackEditor.plotOrigin()
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor track enter failed", t);
            source.sendFailure(Component.literal("track enter failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runTrackSave(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        if (!TrackEditor.plotContaining(player.blockPosition(), dims)) {
            source.sendFailure(Component.literal(
                "Not in the track editor plot. Use '/dungeontrain editor track enter' first."
            ));
            return 0;
        }
        try {
            TrackEditor.SaveResult result = TrackEditor.save(player);
            source.sendSuccess(() -> Component.literal(
                "Editor: saved track template (config-dir)."
            ), true);
            if (result.sourceAttempted()) {
                if (result.sourceWritten()) {
                    source.sendSuccess(() -> Component.literal(
                        "Editor: also wrote bundled track copy to source tree (will ship with next build)."
                    ).withStyle(ChatFormatting.GREEN), true);
                } else {
                    source.sendFailure(Component.literal(
                        "Editor: track source-tree write failed: " + result.sourceError()
                    ).withStyle(ChatFormatting.YELLOW));
                }
            }
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor track save failed", t);
            source.sendFailure(Component.literal("track save failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runTrackList(CommandSourceStack source) {
        boolean config = TrackTemplateStore.exists();
        boolean bundled = TrackTemplateStore.bundled();
        String status;
        if (config) status = "config override";
        else if (bundled) status = "bundled default";
        else status = "fallback (hardcoded bed + rails)";
        StringBuilder sb = new StringBuilder("Track template:");
        sb.append("\n  track — ").append(status)
            .append(" [config: ").append(config ? "yes" : "no")
            .append(", bundled: ").append(bundled ? "yes" : "no").append("]");
        sb.append("\nDev mode: ").append(EditorDevMode.isEnabled() ? "ON" : "off");
        sb.append("\nSource tree writable: ").append(TrackTemplateStore.sourceTreeAvailable() ? "yes" : "no");
        String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int runTrackReset(CommandSourceStack source) {
        try {
            boolean deleted = TrackTemplateStore.delete();
            source.sendSuccess(() -> Component.literal(
                deleted
                    ? "Editor: deleted track template."
                    : "Editor: no track template to delete."
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor track reset failed", t);
            source.sendFailure(Component.literal("track reset failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runTrackPromote(CommandSourceStack source) {
        try {
            TrackTemplateStore.promote();
            source.sendSuccess(() -> Component.literal(
                "Editor: promoted track template to source tree (will ship with next build)."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor track promote failed", t);
            source.sendFailure(Component.literal("track promote failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }
}
