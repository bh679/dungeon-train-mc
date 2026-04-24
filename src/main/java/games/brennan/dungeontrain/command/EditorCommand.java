package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriageEditor.SaveResult;
import games.brennan.dungeontrain.editor.CarriagePartEditor;
import games.brennan.dungeontrain.editor.CarriagePartRegistry;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageVariantPartsStore;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.editor.PillarEditor;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TunnelEditor;
import games.brennan.dungeontrain.editor.TunnelTemplateStore;
import games.brennan.dungeontrain.editor.VariantOverlayRenderer;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
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

    private static final SuggestionProvider<CommandSourceStack> PILLAR_SECTION_SUGGESTIONS =
        (ctx, builder) -> {
            for (PillarSection s : PillarSection.values()) {
                builder.suggest(s.id());
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PART_KIND_SUGGESTIONS =
        (ctx, builder) -> {
            for (CarriagePartKind k : CarriagePartKind.values()) builder.suggest(k.id());
            return builder.buildFuture();
        };

    /** Suggest part names for the {@code kind} argument parsed earlier in the command. */
    private static final SuggestionProvider<CommandSourceStack> PART_NAME_SUGGESTIONS =
        (ctx, builder) -> {
            CarriagePartKind kind = null;
            try {
                kind = CarriagePartKind.fromId(StringArgumentType.getString(ctx, "kind"));
            } catch (IllegalArgumentException ignored) {
                // 'kind' not yet typed; offer nothing — user will resolve it on next keystroke.
            }
            if (kind != null) {
                for (String name : CarriagePartRegistry.names(kind)) builder.suggest(name);
            }
            return builder.buildFuture();
        };

    /** Carriage-only variant suggester (no tunnel prefixes) for {@code part set/show/clear}. */
    private static final SuggestionProvider<CommandSourceStack> CARRIAGE_VARIANT_SUGGESTIONS =
        (ctx, builder) -> {
            for (CarriageVariant v : CarriageVariantRegistry.allVariants()) builder.suggest(v.id());
            return builder.buildFuture();
        };

    private EditorCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build(CommandBuildContext buildContext) {
        return Commands.literal("editor")
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
            .then(buildPartSubtree(buildContext))
            .then(buildVariantSubtree(buildContext));
    }

    /**
     * {@code /dungeontrain editor part ...} — authoring and assignment for
     * reusable FLOOR / WALLS / ROOF / DOORS templates that a carriage variant
     * composes from at spawn time. Each assignment slot is a <b>list</b> of
     * candidate names; spawn picks one deterministically per-carriage-index
     * so a single variant can render differently from car to car.
     *
     * <p>Subcommands:
     * <ul>
     *   <li>{@code enter <kind> <name>} — teleport to the kind's plot and stamp
     *       the current template for {@code name} (empty cage if none on disk).</li>
     *   <li>{@code save [new_name]} — save the current session plot under
     *       either the session's name or the explicit {@code new_name}.</li>
     *   <li>{@code list [kind]} — show registered parts per kind (or one kind).</li>
     *   <li>{@code reset <kind> <name>} — delete the config-dir NBT for a part.</li>
     *   <li>{@code promote all|<kind> <name>} — copy config-dir NBT into the source
     *       tree so it ships with the next build. Dev-checkout only.</li>
     *   <li>{@code set <variant> <kind> <name>} — replace the slot's list with a
     *       single entry (use {@code name=none} to skip that kind).</li>
     *   <li>{@code add <variant> <kind> <name>} — append a candidate to the slot's
     *       list; at spawn, one candidate is picked at random per carriage.</li>
     *   <li>{@code remove <variant> <kind> <name>} — remove one candidate from a
     *       slot; emptying the list falls back to {@code [none]}.</li>
     *   <li>{@code show <variant>} — print the variant's parts assignment.</li>
     *   <li>{@code clear <variant>} — delete the parts.json; variant reverts to
     *       its monolithic NBT.</li>
     * </ul>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> buildPartSubtree(@SuppressWarnings("unused") CommandBuildContext buildContext) {
        return Commands.literal("part")
            .then(Commands.literal("enter")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests(PART_KIND_SUGGESTIONS)
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(PART_NAME_SUGGESTIONS)
                        .executes(c -> runPartEnter(c.getSource(),
                            StringArgumentType.getString(c, "kind"),
                            StringArgumentType.getString(c, "name"))))))
            .then(Commands.literal("save")
                .executes(c -> runPartSave(c.getSource(), null))
                .then(Commands.argument("new_name", StringArgumentType.word())
                    .executes(c -> runPartSave(c.getSource(),
                        StringArgumentType.getString(c, "new_name")))))
            .then(Commands.literal("list")
                .executes(c -> runPartList(c.getSource(), null))
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests(PART_KIND_SUGGESTIONS)
                    .executes(c -> runPartList(c.getSource(),
                        StringArgumentType.getString(c, "kind")))))
            .then(Commands.literal("reset")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests(PART_KIND_SUGGESTIONS)
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(PART_NAME_SUGGESTIONS)
                        .executes(c -> runPartReset(c.getSource(),
                            StringArgumentType.getString(c, "kind"),
                            StringArgumentType.getString(c, "name"))))))
            .then(Commands.literal("promote")
                .then(Commands.literal("all").executes(c -> runPartPromoteAll(c.getSource())))
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests(PART_KIND_SUGGESTIONS)
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(PART_NAME_SUGGESTIONS)
                        .executes(c -> runPartPromote(c.getSource(),
                            StringArgumentType.getString(c, "kind"),
                            StringArgumentType.getString(c, "name"))))))
            .then(Commands.literal("set")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(CARRIAGE_VARIANT_SUGGESTIONS)
                    .then(Commands.argument("kind", StringArgumentType.word())
                        .suggests(PART_KIND_SUGGESTIONS)
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PART_NAME_SUGGESTIONS)
                            .executes(c -> runPartSet(c.getSource(),
                                StringArgumentType.getString(c, "variant"),
                                StringArgumentType.getString(c, "kind"),
                                StringArgumentType.getString(c, "name")))))))
            .then(Commands.literal("add")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(CARRIAGE_VARIANT_SUGGESTIONS)
                    .then(Commands.argument("kind", StringArgumentType.word())
                        .suggests(PART_KIND_SUGGESTIONS)
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PART_NAME_SUGGESTIONS)
                            .executes(c -> runPartAdd(c.getSource(),
                                StringArgumentType.getString(c, "variant"),
                                StringArgumentType.getString(c, "kind"),
                                StringArgumentType.getString(c, "name")))))))
            .then(Commands.literal("remove")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(CARRIAGE_VARIANT_SUGGESTIONS)
                    .then(Commands.argument("kind", StringArgumentType.word())
                        .suggests(PART_KIND_SUGGESTIONS)
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PART_NAME_SUGGESTIONS)
                            .executes(c -> runPartRemove(c.getSource(),
                                StringArgumentType.getString(c, "variant"),
                                StringArgumentType.getString(c, "kind"),
                                StringArgumentType.getString(c, "name")))))))
            .then(Commands.literal("show")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(CARRIAGE_VARIANT_SUGGESTIONS)
                    .executes(c -> runPartShow(c.getSource(),
                        StringArgumentType.getString(c, "variant")))))
            .then(Commands.literal("clear")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(CARRIAGE_VARIANT_SUGGESTIONS)
                    .executes(c -> runPartClear(c.getSource(),
                        StringArgumentType.getString(c, "variant")))));
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

        // Tunnel plots take priority — if the player is inside a tunnel plot,
        // save the tunnel template (ignoring any new_name argument; tunnel
        // templates don't support rename).
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

        // Part plot next — only the session knows which (kind, name) the
        // player is editing, so delegate to the part-specific save runner
        // which reads it. A new_name flips to the part-save rename path.
        CarriagePartKind partKind = CarriagePartEditor.plotContaining(player.blockPosition(), dims);
        if (partKind != null) {
            return runPartSave(source, newName);
        }

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
        source.sendSuccess(() -> Component.literal(
            "Editor: exited, returned to previous location."
        ), true);
        return 1;
    }

    private static int runList(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder("Carriage variants:");
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            boolean config = CarriageTemplateStore.exists(v);
            boolean bundled = CarriageTemplateStore.bundled(v);
            String kind = v.isBuiltin() ? "builtin" : "custom";
            String status;
            if (config) status = "config override";
            else if (bundled) status = "bundled default";
            else status = v.isBuiltin() ? "fallback (hardcoded)" : "missing (no file)";
            sb.append("\n  ").append(v.id())
                .append(" — ").append(kind).append(" | ").append(status)
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

    private static CarriagePartKind parsePartKind(CommandSourceStack source, String raw) {
        CarriagePartKind kind = CarriagePartKind.fromId(raw);
        if (kind == null) {
            source.sendFailure(Component.literal(
                "Unknown part kind '" + raw + "'. Valid: floor, walls, roof, doors"
            ));
        }
        return kind;
    }

    private static boolean validatePartName(CommandSourceStack source, String raw) {
        String norm = raw.toLowerCase(Locale.ROOT);
        if (!CarriagePartRegistry.NAME_PATTERN.matcher(norm).matches()) {
            source.sendFailure(Component.literal(
                "Invalid part name '" + raw + "'. Use lowercase letters, digits or underscore (1-32 chars)."
            ));
            return false;
        }
        if (CarriagePartKind.NONE.equals(norm)) {
            source.sendFailure(Component.literal(
                "'" + CarriagePartKind.NONE + "' is reserved — it means 'skip this part'."
            ));
            return false;
        }
        return true;
    }

    private static int runPartEnter(CommandSourceStack source, String rawKind, String rawName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriagePartKind kind = parsePartKind(source, rawKind);
        if (kind == null) return 0;
        if (!validatePartName(source, rawName)) return 0;
        String name = rawName.toLowerCase(Locale.ROOT);
        try {
            CarriagePartEditor.enter(player, kind, name);
            source.sendSuccess(() -> Component.literal(
                "Editor: entered part '" + kind.id() + ":" + name
                    + "' plot at " + CarriagePartEditor.plotOrigin(kind)
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part enter failed", t);
            source.sendFailure(Component.literal("part enter failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartSave(CommandSourceStack source, String newName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        var session = CarriagePartEditor.currentSession(player);
        if (session.isEmpty()) {
            source.sendFailure(Component.literal(
                "No active part editor session. Run '/dungeontrain editor part enter <kind> <name>' first."
            ));
            return 0;
        }
        CarriagePartKind kind = session.get().kind();
        String targetName;
        if (newName == null) {
            targetName = session.get().name();
        } else {
            if (!validatePartName(source, newName)) return 0;
            targetName = newName.toLowerCase(Locale.ROOT);
        }
        try {
            CarriagePartEditor.SaveResult result = CarriagePartEditor.save(player, kind, targetName);
            final String name = targetName;
            source.sendSuccess(() -> Component.literal(
                "Editor: saved part '" + kind.id() + ":" + name + "' (config-dir)."
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
            LOGGER.error("[DungeonTrain] editor part save failed", t);
            source.sendFailure(Component.literal("part save failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartList(CommandSourceStack source, String rawKind) {
        CarriagePartKind filter = null;
        if (rawKind != null) {
            filter = parsePartKind(source, rawKind);
            if (filter == null) return 0;
        }
        StringBuilder sb = new StringBuilder("Carriage parts:");
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            if (filter != null && filter != kind) continue;
            sb.append("\n  [").append(kind.id()).append("]");
            List<String> names = CarriagePartRegistry.registeredNames(kind);
            if (names.isEmpty()) {
                sb.append(" (no templates registered)");
                continue;
            }
            for (String name : names) {
                boolean config = CarriagePartTemplateStore.exists(kind, name);
                boolean bundled = CarriagePartTemplateStore.bundled(kind, name);
                String status;
                if (config) status = "config override";
                else if (bundled) status = "bundled default";
                else status = "registered (no file?)";
                sb.append("\n    ").append(name)
                    .append(" — ").append(status)
                    .append(" [config: ").append(config ? "yes" : "no")
                    .append(", bundled: ").append(bundled ? "yes" : "no").append("]");
            }
        }
        String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int runPartReset(CommandSourceStack source, String rawKind, String rawName) {
        CarriagePartKind kind = parsePartKind(source, rawKind);
        if (kind == null) return 0;
        String name = rawName.toLowerCase(Locale.ROOT);
        try {
            boolean deleted = CarriagePartTemplateStore.delete(kind, name);
            boolean stillBundled = CarriagePartTemplateStore.bundled(kind, name);
            if (!stillBundled) CarriagePartRegistry.unregister(kind, name);
            final String msg = deleted
                ? ("Editor: deleted part '" + kind.id() + ":" + name + "' (config-dir copy)"
                    + (stillBundled ? " — bundled default remains." : " — no bundled fallback, registry entry removed."))
                : ("Editor: no config-dir part '" + kind.id() + ":" + name + "' to delete.");
            source.sendSuccess(() -> Component.literal(msg), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part reset failed", t);
            source.sendFailure(Component.literal("part reset failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartPromote(CommandSourceStack source, String rawKind, String rawName) {
        CarriagePartKind kind = parsePartKind(source, rawKind);
        if (kind == null) return 0;
        String name = rawName.toLowerCase(Locale.ROOT);
        try {
            CarriagePartTemplateStore.promote(kind, name);
            source.sendSuccess(() -> Component.literal(
                "Editor: promoted part '" + kind.id() + ":" + name
                    + "' to source tree (will ship with next build)."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part promote failed for {}:{}", kind.id(), name, t);
            source.sendFailure(Component.literal("part promote failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartPromoteAll(CommandSourceStack source) {
        if (!CarriagePartTemplateStore.sourceTreeAvailable()) {
            source.sendFailure(Component.literal(
                "part promote all failed: source tree not writable. Are you running ./gradlew runClient?"
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
        int promoted = 0;
        int skipped = 0;
        StringBuilder errors = new StringBuilder();
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            for (String name : CarriagePartRegistry.registeredNames(kind)) {
                if (!CarriagePartTemplateStore.exists(kind, name)) {
                    skipped++;
                    continue;
                }
                try {
                    CarriagePartTemplateStore.promote(kind, name);
                    promoted++;
                } catch (Exception e) {
                    LOGGER.error("[DungeonTrain] editor part promote-all failed for {}:{}", kind.id(), name, e);
                    errors.append("\n  ").append(kind.id()).append(":").append(name)
                        .append(": ").append(e.getMessage());
                }
            }
        }
        final int p = promoted;
        final int s = skipped;
        final String errStr = errors.toString();
        source.sendSuccess(() -> Component.literal(
            "Editor: part promote all — " + p + " promoted, " + s + " skipped (no config copy)."
                + (errStr.isEmpty() ? "" : "\nErrors:" + errStr)
        ).withStyle(errStr.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return p > 0 ? 1 : 0;
    }

    /** True if {@code raw} is either a valid known part name or the {@code "none"} sentinel. Error already sent on false. */
    private static boolean validateSlotName(CommandSourceStack source, CarriagePartKind kind, String raw) {
        String norm = raw.toLowerCase(Locale.ROOT);
        if (CarriagePartKind.NONE.equals(norm)) return true;
        if (!CarriagePartRegistry.NAME_PATTERN.matcher(norm).matches()) {
            source.sendFailure(Component.literal(
                "Invalid part name '" + raw + "'. Use lowercase letters, digits or underscore (1-32 chars), or 'none'."
            ));
            return false;
        }
        if (!CarriagePartRegistry.isKnown(kind, norm)) {
            source.sendFailure(Component.literal(
                "Unknown part '" + kind.id() + ":" + norm
                    + "'. Author it via '/dungeontrain editor part enter " + kind.id() + " " + norm + "' and save first."
            ).withStyle(ChatFormatting.YELLOW));
            return false;
        }
        return true;
    }

    private static int runPartSet(CommandSourceStack source, String rawVariant, String rawKind, String rawName) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        CarriagePartKind kind = parsePartKind(source, rawKind);
        if (kind == null) return 0;
        if (!validateSlotName(source, kind, rawName)) return 0;
        String name = rawName.toLowerCase(Locale.ROOT);
        try {
            CarriagePartAssignment existing = CarriageVariantPartsStore.get(variant).orElse(CarriagePartAssignment.EMPTY);
            CarriagePartAssignment updated = existing.with(kind, List.of(name));
            CarriageVariantPartsStore.save(variant, updated);
            source.sendSuccess(() -> Component.literal(
                "Editor: '" + variant.id() + "' parts — " + kind.id() + " = [" + name + "]"
                    + " (current: " + formatAssignment(updated) + ")"
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part set failed", t);
            source.sendFailure(Component.literal("part set failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartAdd(CommandSourceStack source, String rawVariant, String rawKind, String rawName) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        CarriagePartKind kind = parsePartKind(source, rawKind);
        if (kind == null) return 0;
        if (!validateSlotName(source, kind, rawName)) return 0;
        String name = rawName.toLowerCase(Locale.ROOT);
        try {
            CarriagePartAssignment existing = CarriageVariantPartsStore.get(variant).orElse(CarriagePartAssignment.EMPTY);
            CarriagePartAssignment updated = existing.withAppended(kind, name);
            CarriageVariantPartsStore.save(variant, updated);
            source.sendSuccess(() -> Component.literal(
                "Editor: '" + variant.id() + "' parts — appended '" + name + "' to " + kind.id()
                    + " (current: " + formatAssignment(updated) + ")"
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part add failed", t);
            source.sendFailure(Component.literal("part add failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartRemove(CommandSourceStack source, String rawVariant, String rawKind, String rawName) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        CarriagePartKind kind = parsePartKind(source, rawKind);
        if (kind == null) return 0;
        String name = rawName.toLowerCase(Locale.ROOT);
        try {
            CarriagePartAssignment existing = CarriageVariantPartsStore.get(variant).orElse(CarriagePartAssignment.EMPTY);
            if (!existing.names(kind).contains(name)) {
                source.sendFailure(Component.literal(
                    "'" + variant.id() + "' parts: '" + name + "' is not in " + kind.id()
                        + " (current: " + formatSlot(existing.names(kind)) + ")"
                ));
                return 0;
            }
            CarriagePartAssignment updated = existing.withRemoved(kind, name);
            CarriageVariantPartsStore.save(variant, updated);
            source.sendSuccess(() -> Component.literal(
                "Editor: '" + variant.id() + "' parts — removed '" + name + "' from " + kind.id()
                    + " (current: " + formatAssignment(updated) + ")"
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part remove failed", t);
            source.sendFailure(Component.literal("part remove failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartShow(CommandSourceStack source, String rawVariant) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        var assignment = CarriageVariantPartsStore.get(variant);
        if (assignment.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "'" + variant.id() + "' has no parts.json — renders as monolithic NBT."
            ), false);
            return 1;
        }
        String desc = formatAssignment(assignment.get());
        boolean config = CarriageVariantPartsStore.exists(variant);
        boolean bundled = CarriageVariantPartsStore.bundled(variant);
        String origin = config ? "config override" : (bundled ? "bundled default" : "memory only");
        source.sendSuccess(() -> Component.literal(
            "'" + variant.id() + "' parts (" + origin + "): " + desc
        ), false);
        return 1;
    }

    private static int runPartClear(CommandSourceStack source, String rawVariant) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        try {
            boolean deleted = CarriageVariantPartsStore.delete(variant);
            source.sendSuccess(() -> Component.literal(
                deleted
                    ? "Editor: cleared parts.json for '" + variant.id() + "' — will render monolithic NBT."
                    : "Editor: no parts.json for '" + variant.id() + "' to clear."
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part clear failed", t);
            source.sendFailure(Component.literal("part clear failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static String formatAssignment(CarriagePartAssignment a) {
        return "floor=" + formatSlot(a.floor())
            + ", walls=" + formatSlot(a.walls())
            + ", roof=" + formatSlot(a.roof())
            + ", doors=" + formatSlot(a.doors());
    }

    /** Format a slot list for human output — single-element lists unwrap to the bare name. */
    private static String formatSlot(List<String> list) {
        if (list.size() == 1) return list.get(0);
        return list.toString();
    }
}
