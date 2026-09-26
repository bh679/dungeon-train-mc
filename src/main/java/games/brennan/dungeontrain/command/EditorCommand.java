package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageContentsEditor;
import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.TemplateDeletes;
import games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriageEditor.SaveResult;
import games.brennan.dungeontrain.editor.CarriagePartEditor;
import games.brennan.dungeontrain.editor.CarriagePartRegistry;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriagePartVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageVariantContentsAllowStore;
import games.brennan.dungeontrain.editor.CarriageVariantPartsStore;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorDoorGhosts;
import games.brennan.dungeontrain.editor.EditorEditApplier;
import games.brennan.dungeontrain.editor.EditorEditHistory;
import games.brennan.dungeontrain.editor.EditorPlotTransform;
import games.brennan.dungeontrain.editor.EditorPlotTransformer;
import games.brennan.dungeontrain.editor.EditorRegionDiff;
import games.brennan.dungeontrain.editor.PortalRoomEditor;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomResize;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.editor.EditorStampQueue;
import games.brennan.dungeontrain.editor.EditorStampedCategoryState;
import games.brennan.dungeontrain.editor.EditorWelcome;
import games.brennan.dungeontrain.editor.PillarEditor;
import games.brennan.dungeontrain.template.FlipOptions;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TrackEditor;
import games.brennan.dungeontrain.editor.TrackTemplateStore;
import games.brennan.dungeontrain.editor.TunnelEditor;
import games.brennan.dungeontrain.editor.TunnelTemplateStore;
import games.brennan.dungeontrain.editor.EditorStrayBlocks;
import games.brennan.dungeontrain.editor.VariantOverlayRenderer;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsAllowList;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriagePartPlacer;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
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

    /**
     * Suggester for {@code /dungeontrain editor pillar ...} targets — covers
     * both {@link PillarSection} (TOP/MIDDLE/BOTTOM) and {@link PillarAdjunct}
     * (STAIRS) so {@code enter}, {@code reset}, and {@code promote} all see
     * the same unified namespace.
     */
    private static final SuggestionProvider<CommandSourceStack> PILLAR_TARGET_SUGGESTIONS =
        (ctx, builder) -> {
            for (PillarSection s : PillarSection.values()) {
                builder.suggest(s.id());
            }
            for (PillarAdjunct a : PillarAdjunct.values()) {
                builder.suggest(a.id());
            }
            return builder.buildFuture();
        };

    /**
     * Suggester for the {@code <kind>} arg in {@code /dt editor tracks
     * new/reset}. Accepts the editor model id ({@code track},
     * {@code pillar_top}, {@code tunnel_section}, ...) which is what the
     * EditorMenuScreen has to hand from the HUD status packet.
     */
    private static final SuggestionProvider<CommandSourceStack> TRACK_KIND_SUGGESTIONS =
        (ctx, builder) -> {
            builder.suggest("track");
            for (PillarSection s : PillarSection.values()) {
                builder.suggest("pillar_" + s.id());
            }
            for (PillarAdjunct a : PillarAdjunct.values()) {
                builder.suggest("adjunct_" + a.id());
            }
            for (TunnelVariant v : TunnelVariant.values()) {
                builder.suggest(TUNNEL_PREFIX + v.name().toLowerCase(Locale.ROOT));
            }
            // Shared with /dt editor portals, which addresses its models the same way.
            builder.suggest(games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM.id());
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PORTAL_ROOM_NAME_SUGGESTIONS =
        (ctx, builder) -> {
            for (String name : games.brennan.dungeontrain.track.variant.TrackVariantRegistry
                    .namesFor(games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM)) {
                builder.suggest(name);
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PORTAL_ROOM_MODE_SUGGESTIONS =
        (ctx, builder) -> {
            for (games.brennan.dungeontrain.portal.PortalRoomMode mode
                    : games.brennan.dungeontrain.portal.PortalRoomMode.values()) {
                builder.suggest(mode.id());
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PORTAL_ROOM_COPIES_SUGGESTIONS =
        (ctx, builder) -> {
            for (games.brennan.dungeontrain.portal.PortalRoomCopies.Kind c
                    : games.brennan.dungeontrain.portal.PortalRoomCopies.Kind.values()) {
                builder.suggest(c.id());
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PORTAL_ROOM_CONTENTS_SUGGESTIONS =
        (ctx, builder) -> {
            for (games.brennan.dungeontrain.portal.PortalRoomContents c
                    : games.brennan.dungeontrain.portal.PortalRoomContents.values()) {
                builder.suggest(c.id());
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PORTAL_ROOM_DOOR_WALL_SUGGESTIONS =
        (ctx, builder) -> {
            for (games.brennan.dungeontrain.portal.PortalRoomDoorWall doorWall
                    : games.brennan.dungeontrain.portal.PortalRoomDoorWall.values()) {
                builder.suggest(doorWall.id());
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PORTAL_ROOM_FOG_SUGGESTIONS =
        (ctx, builder) -> {
            for (games.brennan.dungeontrain.portal.PortalRoomFog fog
                    : games.brennan.dungeontrain.portal.PortalRoomFog.values()) {
                builder.suggest(fog.id());
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PORTAL_ROOM_SKY_SUGGESTIONS =
        (ctx, builder) -> {
            for (games.brennan.dungeontrain.portal.PortalRoomSky sky
                    : games.brennan.dungeontrain.portal.PortalRoomSky.values()) {
                builder.suggest(sky.id());
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PORTAL_ROOM_EXITS_SUGGESTIONS =
        (ctx, builder) -> {
            for (games.brennan.dungeontrain.portal.PortalRoomExits.Kind k
                    : games.brennan.dungeontrain.portal.PortalRoomExits.Kind.values()) {
                builder.suggest(k.id());
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PORTAL_ROOM_BOOKS_SUGGESTIONS =
        (ctx, builder) -> {
            for (games.brennan.dungeontrain.portal.PortalRoomBooks.Kind k
                    : games.brennan.dungeontrain.portal.PortalRoomBooks.Kind.values()) {
                builder.suggest(k.id());
            }
            // The compound form, so the whole setting is reachable from the command line rather than
            // only from the edit screen: weights then the band of author.
            builder.suggest("mix:2:1:1");
            builder.suggest("mix:2:1:1:10:50");
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> CONTENTS_SUGGESTIONS =
        (ctx, builder) -> {
            for (CarriageContents c : CarriageContentsRegistry.allContents()) {
                builder.suggest(c.id());
            }
            return builder.buildFuture();
        };

    /** The four fields of a contents template's {@link FlipOptions}: three axes plus the room scope. */
    private static final SuggestionProvider<CommandSourceStack> FLIP_FIELD_SUGGESTIONS =
        (ctx, builder) -> {
            for (String field : new String[] {"x", "y", "z", "rooms"}) builder.suggest(field);
            return builder.buildFuture();
        };

    /**
     * Parents-only suggester for places where sub-variant ids would be
     * meaningless — chiefly the per-carriage-variant allow-list, which
     * operates at the parent level. Children of any group are filtered out
     * via {@link CarriageContentsGroupStore#allChildIds}.
     */
    private static final SuggestionProvider<CommandSourceStack> TOP_LEVEL_CONTENTS_SUGGESTIONS =
        (ctx, builder) -> {
            java.util.Set<String> children = CarriageContentsGroupStore.allChildIds();
            for (CarriageContents c : CarriageContentsRegistry.allContents()) {
                if (children.contains(c.id())) continue;
                builder.suggest(c.id());
            }
            return builder.buildFuture();
        };

    /**
     * Suggest variant names for the track kind parsed earlier in the command —
     * used by {@code /dt editor tracks weight <kind> <name> ...}. Defensive:
     * if the {@code kind} arg is missing or unrecognised, suggest nothing
     * rather than spamming the player with errors mid-typing.
     */
    private static final SuggestionProvider<CommandSourceStack> TRACK_VARIANT_NAME_SUGGESTIONS =
        (ctx, builder) -> {
            games.brennan.dungeontrain.track.variant.TrackKind kind = null;
            try {
                String raw = StringArgumentType.getString(ctx, "kind");
                kind = resolveTrackKindSilent(raw);
            } catch (IllegalArgumentException ignored) {
                // 'kind' not yet typed; offer nothing — user will resolve it on next keystroke.
            }
            if (kind != null) {
                for (String name : TrackVariantRegistry.namesFor(kind)) builder.suggest(name);
            }
            return builder.buildFuture();
        };

    /** Suggests the four spawn-phase tokens for {@code /dt editor ... phase <id> <phase> on|off}. */
    private static final SuggestionProvider<CommandSourceStack> PHASE_SUGGESTIONS =
        (ctx, builder) -> {
            for (TrainPhase p : TrainPhase.values()) builder.suggest(p.token());
            return builder.buildFuture();
        };

    /** The {@code apply … <stage>} keyword that detaches a template back to a Custom inline gate. */
    private static final String STAGE_CUSTOM_TOKEN = "custom";

    /** Existing Stage ids — for {@code /dt editor stage {delete|rename|minlevel|…} <id>}. */
    private static final SuggestionProvider<CommandSourceStack> STAGE_SUGGESTIONS =
        (ctx, builder) -> {
            for (String id : games.brennan.dungeontrain.editor.StageStore.allIds()) builder.suggest(id);
            return builder.buildFuture();
        };

    /** Stage ids plus the {@code custom} keyword — for {@code stage apply … <stage|custom>}. */
    static final SuggestionProvider<CommandSourceStack> STAGE_OR_CUSTOM_SUGGESTIONS =
        (ctx, builder) -> {
            builder.suggest(STAGE_CUSTOM_TOKEN);
            for (String id : games.brennan.dungeontrain.editor.StageStore.allIds()) builder.suggest(id);
            return builder.buildFuture();
        };

    /** Blocks used by the previously-typed stage — for {@code stage replaceblock <id> <from>}. */
    private static final SuggestionProvider<CommandSourceStack> STAGE_BLOCK_SUGGESTIONS =
        (ctx, builder) -> {
            try {
                String id = StringArgumentType.getString(ctx, "id");
                ServerLevel overworld = ctx.getSource().getServer().overworld();
                for (String blockId : games.brennan.dungeontrain.editor.StageBlockIndex
                        .blocksForStage(overworld, id).aggregatedBlockIds()) {
                    builder.suggest(blockId);
                }
            } catch (IllegalArgumentException ignored) {
                // "id" not parsed yet — nothing to suggest.
            }
            return builder.buildFuture();
        };

    /** Every registered block id — for {@code stage replaceblock … <to>}. */
    private static final SuggestionProvider<CommandSourceStack> ALL_BLOCK_SUGGESTIONS =
        (ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggestResource(
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet(), builder);

    private static final SuggestionProvider<CommandSourceStack> PART_KIND_SUGGESTIONS =
        (ctx, builder) -> {
            for (CarriagePartKind k : CarriagePartKind.values()) builder.suggest(k.id());
            return builder.buildFuture();
        };

    /** Source tokens for {@code editor part new <kind> <source> <name>}. */
    private static final SuggestionProvider<CommandSourceStack> PART_NEW_SOURCE_SUGGESTIONS =
        (ctx, builder) -> {
            builder.suggest("blank");
            builder.suggest("current");
            builder.suggest("standard");
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

    private EditorCommand() {}

    /**
     * Attach the {@code (kind, name)} variant subcommands — mirror, new, reset, weight, and the
     * three gate nodes — to a category literal.
     *
     * <p>Shared by {@code tracks} and {@code portals}: both address their models as a
     * {@link games.brennan.dungeontrain.track.variant.TrackKind} plus a variant name, so the
     * handlers are identical and only the command prefix differs. Every node is freshly built per
     * call — brigadier builders are single-use.</p>
     */
    /** Runs a reset with the parent-deletion mode the author chose. */
    @FunctionalInterface
    private interface ParentModeExecutor {
        int run(CommandContext<CommandSourceStack> ctx,
                games.brennan.dungeontrain.editor.ParentDeletes.Mode mode);
    }

    /**
     * Hang one literal per {@link games.brennan.dungeontrain.editor.ParentDeletes.Mode} under
     * {@code node} — the optional trailing {@code all|unparent|promote} the two reset commands take
     * when their target is a sub-variant parent.
     */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T parentModeNodes(
        T node, ParentModeExecutor exec
    ) {
        for (games.brennan.dungeontrain.editor.ParentDeletes.Mode mode
                : games.brennan.dungeontrain.editor.ParentDeletes.Mode.values()) {
            node = node.then(Commands.literal(mode.literal()).executes(ctx -> exec.run(ctx, mode)));
        }
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> attachTrackVariantNodes(
        LiteralArgumentBuilder<CommandSourceStack> node
    ) {
        return node
            // Explicit (kind, name) mirror toggle — scripting / out-of-plot use.
            .then(Commands.literal("mirror")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests(TRACK_KIND_SUGGESTIONS)
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(TRACK_VARIANT_NAME_SUGGESTIONS)
                        .then(trackMirrorAxisNode("x"))
                        .then(trackMirrorAxisNode("y"))
                        .then(trackMirrorAxisNode("z"))
                        .then(trackMirrorAxisNode("v")))))
            .then(Commands.literal("new")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests(TRACK_KIND_SUGGESTIONS)
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> runTrackNewVariant(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "kind"),
                            StringArgumentType.getString(ctx, "name"))))))
            // `reset <kind> [mode]` acts on the plot the player stands in; `reset <kind> <name> [mode]`
            // is the same delete addressed by name (the editor screen's Remove). The mode literals
            // sit beside the name argument — brigadier tries literals first, so a variant that
            // happens to be called `all` would need the standing form.
            .then(Commands.literal("reset")
                .then(parentModeNodes(Commands.argument("kind", StringArgumentType.word())
                    .suggests(TRACK_KIND_SUGGESTIONS)
                    .executes(ctx -> runTrackResetActiveVariant(
                        ctx.getSource(),
                        StringArgumentType.getString(ctx, "kind"))),
                    (ctx, mode) -> runTrackResetActiveVariant(
                        ctx.getSource(),
                        StringArgumentType.getString(ctx, "kind"), mode))
                    .then(parentModeNodes(Commands.argument("name", StringArgumentType.word())
                        .suggests(TRACK_VARIANT_NAME_SUGGESTIONS)
                        .executes(ctx -> runTrackResetNamedVariant(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "kind"),
                            StringArgumentType.getString(ctx, "name"), null)),
                        (ctx, mode) -> runTrackResetNamedVariant(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "kind"),
                            StringArgumentType.getString(ctx, "name"), mode)))))
            // Addressed by (kind, name) rather than by where the player is standing, so the editor
            // screen can rename what its pane is showing — the same shape the carriage and contents
            // renames take. The menu sends the kind spelled out (`… portals rename portal_room <id>`)
            // rather than a kind-implied second form, which would be an argument-vs-argument
            // ambiguity at the same node for the sake of one word.
            .then(Commands.literal("rename")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests(TRACK_KIND_SUGGESTIONS)
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(TRACK_VARIANT_NAME_SUGGESTIONS)
                        .then(Commands.argument("new_name", StringArgumentType.word())
                            .executes(ctx -> runTrackRenameVariant(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "kind"),
                                StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "new_name")))))))
            // Display label for a track-side template — what the editor screen's Rename does.
            // Works on bundled rooms, which `rename` above must refuse (nothing on disk to move).
            .then(Commands.literal("label")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests(TRACK_KIND_SUGGESTIONS)
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(TRACK_VARIANT_NAME_SUGGESTIONS)
                        .executes(ctx -> EditorLabelCommands.runTrackLabel(ctx.getSource(),
                            parseTrackKind(ctx.getSource(), StringArgumentType.getString(ctx, "kind")),
                            StringArgumentType.getString(ctx, "name"), ""))
                        .then(Commands.argument("label", StringArgumentType.greedyString())
                            .executes(ctx -> EditorLabelCommands.runTrackLabel(ctx.getSource(),
                                parseTrackKind(ctx.getSource(), StringArgumentType.getString(ctx, "kind")),
                                StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "label")))))))
            // Who originally built a track-side template — see EditorBuilderCommands.
            .then(Commands.literal("builder")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests(TRACK_KIND_SUGGESTIONS)
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(TRACK_VARIANT_NAME_SUGGESTIONS)
                        .then(Commands.argument("uuid", StringArgumentType.word())
                            .executes(ctx -> EditorBuilderCommands.runTrackBuilder(ctx.getSource(),
                                parseTrackKind(ctx.getSource(), StringArgumentType.getString(ctx, "kind")),
                                StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "uuid"), ""))
                            .then(Commands.argument("builder_name", StringArgumentType.greedyString())
                                .executes(ctx -> EditorBuilderCommands.runTrackBuilder(ctx.getSource(),
                                    parseTrackKind(ctx.getSource(), StringArgumentType.getString(ctx, "kind")),
                                    StringArgumentType.getString(ctx, "name"),
                                    StringArgumentType.getString(ctx, "uuid"),
                                    StringArgumentType.getString(ctx, "builder_name"))))))))
            .then(Commands.literal("weight")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests(TRACK_KIND_SUGGESTIONS)
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(TRACK_VARIANT_NAME_SUGGESTIONS)
                        .then(Commands.literal("inc").executes(ctx -> runTrackWeightAdjust(ctx.getSource(),
                            StringArgumentType.getString(ctx, "kind"),
                            StringArgumentType.getString(ctx, "name"), +1)))
                        .then(Commands.literal("dec").executes(ctx -> runTrackWeightAdjust(ctx.getSource(),
                            StringArgumentType.getString(ctx, "kind"),
                            StringArgumentType.getString(ctx, "name"), -1)))
                        .then(Commands.argument("value",
                                IntegerArgumentType.integer(TrackVariantWeights.MIN, TrackVariantWeights.MAX))
                            .executes(ctx -> runTrackWeightSet(ctx.getSource(),
                                StringArgumentType.getString(ctx, "kind"),
                                StringArgumentType.getString(ctx, "name"),
                                IntegerArgumentType.getInteger(ctx, "value")))))))
            .then(minLevelTrack())
            .then(maxLevelTrack())
            .then(phaseTrack());
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build(CommandBuildContext buildContext) {
        return Commands.literal("editor")
            .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.CARRIAGES))
            // Test the Carriage for a carriage or contents template — see CarriageTestCommand.
            .then(CarriageTestCommand.build())
            .then(Commands.literal("carriages")
                .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.CARRIAGES)))
            // Red ghosts over blocks left outside the plots. Its own toggle rather than a rider on
            // the variant overlay: that one hides annotations, this one hides a warning.
            .then(Commands.literal("strays")
                .then(Commands.literal("on").executes(ctx -> runStrayGhosts(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runStrayGhosts(ctx.getSource(), false))))
            // Amber ghosts on the two portal corridor doorways in each room plot. Separate from
            // `strays` because the two say opposite things — a stray is a block to remove, a door is
            // a space to leave alone — so an author silencing one has no reason to lose the other.
            .then(Commands.literal("doorghosts")
                .then(Commands.literal("on").executes(ctx -> runDoorGhosts(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runDoorGhosts(ctx.getSource(), false))))
            // Position-resolved mirror toggle — works in any editor plot. Backs
            // the X-menu Mirror X / Y / Z toggles for every category.
            .then(Commands.literal("mirror")
                .then(mirrorAxisNode("x"))
                .then(mirrorAxisNode("y"))
                .then(mirrorAxisNode("z"))
                .then(mirrorAxisNode("v"))
                .then(Commands.literal("rebuild")
                    .executes(ctx -> runMirrorRebuild(ctx.getSource()))))
            .then(attachTrackVariantNodes(Commands.literal("tracks")
                .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.TRACKS))))
            // PORTALS takes the same (kind, name) variant subcommands — the pocket room is a
            // TrackKind under the hood, so weight / gate / new / reset are literally the same
            // handlers — plus one of its own: length, the axis only a portal room may choose.
            .then(attachTrackVariantNodes(portalRoomSettingNodes(Commands.literal("portals")
                .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.PORTALS))))
                .then(Commands.literal("enter")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                        .executes(ctx -> runPortalRoomEnter(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")))))
                // The room settings tree, once for the plot the player is standing in and
                // once more under `room <name>` for a room they are only looking at — see
                // portalRoomSettingNodes.
                .then(Commands.literal("room")
                    .then(portalRoomSettingNodes(Commands.argument("room", StringArgumentType.word())
                        .suggests(PORTAL_ROOM_NAME_SUGGESTIONS))))
                // Sub-variants: one named room standing for several designs, drawn by weight.
                .then(portalRoomGroupNode()))
            .then(WholeEditorCommand.build())
            .then(ChunkFrameCommand.build())
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
            .then(Commands.literal("rename")
                .then(Commands.argument("id", StringArgumentType.word())
                    .then(Commands.argument("new_name", StringArgumentType.word())
                        .executes(ctx -> runRenameCarriageById(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id"),
                            StringArgumentType.getString(ctx, "new_name"))))))
            // The rename the editor screen actually offers: a display label held in weights.json,
            // so the id (and its file) stays put and a bundled template can be renamed too. `rename`
            // above is the id change, kept for the rare case the file name itself must move.
            .then(Commands.literal("label")
                .then(Commands.argument("id", StringArgumentType.word())
                    .suggests(VARIANT_SUGGESTIONS)
                    .executes(ctx -> EditorLabelCommands.runCarriageLabel(ctx.getSource(),
                        StringArgumentType.getString(ctx, "id"), ""))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> EditorLabelCommands.runCarriageLabel(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id"),
                            StringArgumentType.getString(ctx, "name"))))))
            // Who originally built the carriage: `builder <id> <uuid|none> [name…]` — the credit
            // the editor's data sheet shows and the Credits page thanks. See EditorBuilderCommands.
            .then(Commands.literal("builder")
                .then(Commands.argument("id", StringArgumentType.word())
                    .suggests(VARIANT_SUGGESTIONS)
                    .then(Commands.argument("uuid", StringArgumentType.word())
                        .executes(ctx -> EditorBuilderCommands.runCarriageBuilder(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id"),
                            StringArgumentType.getString(ctx, "uuid"), ""))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                            .executes(ctx -> EditorBuilderCommands.runCarriageBuilder(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "uuid"),
                                StringArgumentType.getString(ctx, "name")))))))
            // The train's own footprint — shared by every carriage, part and track in the world.
            // Not to be confused with `editor portals size`, which is one room's box.
            .then(Commands.literal("size")
                .then(trainSizeNode("length"))
                .then(trainSizeNode("width"))
                .then(trainSizeNode("height")))
            .then(Commands.literal("exit").executes(ctx -> runExit(ctx.getSource())))
            .then(Commands.literal("list").executes(ctx -> runList(ctx.getSource())))
            .then(Commands.literal("unsaved").executes(ctx -> runUnsaved(ctx.getSource())))
            .then(Commands.literal("blocks").executes(ctx -> runBlocks(ctx.getSource())))
            .then(Commands.literal("reset")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(VARIANT_SUGGESTIONS)
                    .executes(ctx -> EditorRegionDiff.recording(ctx.getSource(), "Reset",
                        () -> runReset(ctx.getSource(),
                            StringArgumentType.getString(ctx, "variant"))))))
            .then(Commands.literal("clear")
                .executes(ctx -> EditorRegionDiff.recording(ctx.getSource(), "Clear",
                    () -> runClear(ctx.getSource()))))
            .then(Commands.literal("offset")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(ctx -> runTransform(ctx.getSource(),
                                EditorPlotTransform.offset(
                                    IntegerArgumentType.getInteger(ctx, "x"),
                                    IntegerArgumentType.getInteger(ctx, "y"),
                                    IntegerArgumentType.getInteger(ctx, "z"))))))))
            .then(Commands.literal("rotate")
                .then(Commands.literal("90").executes(ctx -> runTransform(ctx.getSource(),
                    EditorPlotTransform.rotation(90))))
                .then(Commands.literal("180").executes(ctx -> runTransform(ctx.getSource(),
                    EditorPlotTransform.rotation(180))))
                .then(Commands.literal("270").executes(ctx -> runTransform(ctx.getSource(),
                    EditorPlotTransform.rotation(270)))))
            .then(Commands.literal("flip")
                .then(Commands.literal("x").executes(ctx -> runTransform(ctx.getSource(),
                    EditorPlotTransform.flip(Direction.Axis.X))))
                .then(Commands.literal("y").executes(ctx -> runTransform(ctx.getSource(),
                    EditorPlotTransform.flip(Direction.Axis.Y))))
                .then(Commands.literal("z").executes(ctx -> runTransform(ctx.getSource(),
                    EditorPlotTransform.flip(Direction.Axis.Z)))))
            .then(Commands.literal("undo")
                .executes(ctx -> runUndoRedo(ctx.getSource(), /*redoing*/ false)))
            .then(Commands.literal("redo")
                .executes(ctx -> runUndoRedo(ctx.getSource(), /*redoing*/ true)))
            .then(Commands.literal("new")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> runNew(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name"),
                        CarriageVariant.of(CarriageType.STANDARD)))
                    .then(Commands.argument("source", StringArgumentType.word())
                        .suggests(VARIANT_SUGGESTIONS)
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            String src = StringArgumentType.getString(ctx, "source");
                            if ("blank".equalsIgnoreCase(src)) {
                                return runNewBlank(ctx.getSource(), name);
                            }
                            CarriageVariant variant = parseVariant(ctx.getSource(), src);
                            if (variant == null) return 0;
                            return runNew(ctx.getSource(), name, variant);
                        }))))
            .then(Commands.literal("devmode")
                .executes(ctx -> runDevMode(ctx.getSource(), !EditorDevMode.isEnabled()))
                .then(Commands.literal("on").executes(ctx -> runDevMode(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runDevMode(ctx.getSource(), false))))
            .then(Commands.literal("shared")
                .executes(ctx -> runSharedToggle(ctx.getSource(), null))
                .then(Commands.literal("on").executes(ctx -> runSharedToggle(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runSharedToggle(ctx.getSource(), false))))
            .then(Commands.literal("partmenu")
                .then(Commands.literal("on").executes(ctx -> runPartMenu(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runPartMenu(ctx.getSource(), false))))
            .then(Commands.literal("editormenus")
                .then(Commands.literal("on").executes(ctx -> runEditorMenus(ctx.getSource(),
                    games.brennan.dungeontrain.editor.EditorMenusMode.ON)))
                .then(Commands.literal("auto").executes(ctx -> runEditorMenus(ctx.getSource(),
                    games.brennan.dungeontrain.editor.EditorMenusMode.AUTO)))
                .then(Commands.literal("off").executes(ctx -> runEditorMenus(ctx.getSource(),
                    games.brennan.dungeontrain.editor.EditorMenusMode.OFF))))
            .then(Commands.literal("helppanel")
                .then(Commands.literal("on").executes(ctx -> runHelpPanel(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runHelpPanel(ctx.getSource(), false))))
            .then(Commands.literal("observers")
                .then(Commands.literal("on").executes(ctx -> runObservers(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runObservers(ctx.getSource(), false))))
            .then(Commands.literal("mobs")
                .then(Commands.literal("blocks").executes(ctx -> runMobsMode(ctx.getSource(), false)))
                .then(Commands.literal("live").executes(ctx -> runMobsMode(ctx.getSource(), true))))
            .then(Commands.literal("carriage-contents")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(CARRIAGE_VARIANT_SUGGESTIONS)
                    .then(Commands.argument("contents", StringArgumentType.word())
                        .suggests(TOP_LEVEL_CONTENTS_SUGGESTIONS)
                        .then(Commands.literal("on").executes(ctx -> runCarriageContentsAllow(ctx.getSource(),
                            StringArgumentType.getString(ctx, "variant"),
                            StringArgumentType.getString(ctx, "contents"), true)))
                        .then(Commands.literal("off").executes(ctx -> runCarriageContentsAllow(ctx.getSource(),
                            StringArgumentType.getString(ctx, "variant"),
                            StringArgumentType.getString(ctx, "contents"), false))))))
            // The same allow-list, for a portal room's furnishing pool. Reached from the plot
            // panel's Contents button, which only shows while the room's Contents setting is on.
            .then(Commands.literal("portal-room-contents")
                .then(Commands.argument("room", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                    .then(Commands.argument("contents", StringArgumentType.word())
                        .suggests(TOP_LEVEL_CONTENTS_SUGGESTIONS)
                        .then(Commands.literal("on").executes(ctx -> runPortalRoomContentsAllow(ctx.getSource(),
                            StringArgumentType.getString(ctx, "room"),
                            StringArgumentType.getString(ctx, "contents"), true)))
                        .then(Commands.literal("off").executes(ctx -> runPortalRoomContentsAllow(ctx.getSource(),
                            StringArgumentType.getString(ctx, "room"),
                            StringArgumentType.getString(ctx, "contents"), false))))))
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
            .then(ExportCommand.build())
            .then(ImportCommand.build())
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
                .then(Commands.literal("rename")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(CONTENTS_SUGGESTIONS)
                        .then(Commands.argument("new_name", StringArgumentType.word())
                            .executes(ctx -> runRenameContentsById(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "new_name"))))))
                // Display label — see the carriages `label` node.
                .then(Commands.literal("label")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(CONTENTS_SUGGESTIONS)
                        .executes(ctx -> EditorLabelCommands.runContentsLabel(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id"), ""))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                            .executes(ctx -> EditorLabelCommands.runContentsLabel(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "name"))))))
                // Original builder — see the carriages `builder` node.
                .then(Commands.literal("builder")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(CONTENTS_SUGGESTIONS)
                        .then(Commands.argument("uuid", StringArgumentType.word())
                            .executes(ctx -> EditorBuilderCommands.runContentsBuilder(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "uuid"), ""))
                            .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> EditorBuilderCommands.runContentsBuilder(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "uuid"),
                                    StringArgumentType.getString(ctx, "name")))))))
                .then(Commands.literal("save")
                    .executes(ctx -> runContentsSave(ctx.getSource(), null))
                    .then(Commands.argument("new_name", StringArgumentType.word())
                        .executes(ctx -> runContentsSave(ctx.getSource(),
                            StringArgumentType.getString(ctx, "new_name")))))
                .then(Commands.literal("list").executes(ctx -> runContentsList(ctx.getSource())))
                .then(Commands.literal("reset")
                    .then(parentModeNodes(Commands.argument("contents", StringArgumentType.word())
                        .suggests(CONTENTS_SUGGESTIONS)
                        .executes(ctx -> runContentsReset(ctx.getSource(),
                            StringArgumentType.getString(ctx, "contents"))),
                        (ctx, mode) -> runContentsReset(ctx.getSource(),
                            StringArgumentType.getString(ctx, "contents"), mode))))
                .then(Commands.literal("new")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> runContentsNew(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name"),
                            CarriageContents.of(CarriageContents.ContentsType.DEFAULT)))
                        .then(Commands.argument("source", StringArgumentType.word())
                            .suggests(CONTENTS_SUGGESTIONS)
                            .executes(ctx -> {
                                String name = StringArgumentType.getString(ctx, "name");
                                String src = StringArgumentType.getString(ctx, "source");
                                if ("blank".equalsIgnoreCase(src)) {
                                    return runContentsNewBlank(ctx.getSource(), name);
                                }
                                CarriageContents contents = parseContents(ctx.getSource(), src);
                                if (contents == null) return 0;
                                return runContentsNew(ctx.getSource(), name, contents);
                            }))))
                .then(Commands.literal("weight")
                    .then(Commands.argument("contents", StringArgumentType.word())
                        .suggests(CONTENTS_SUGGESTIONS)
                        .then(Commands.literal("inc").executes(ctx -> runContentsWeightAdjust(ctx.getSource(),
                            StringArgumentType.getString(ctx, "contents"), +1)))
                        .then(Commands.literal("dec").executes(ctx -> runContentsWeightAdjust(ctx.getSource(),
                            StringArgumentType.getString(ctx, "contents"), -1)))
                        .then(Commands.argument("value",
                                IntegerArgumentType.integer(CarriageContentsWeights.MIN, CarriageContentsWeights.MAX))
                            .executes(ctx -> runContentsWeightSet(ctx.getSource(),
                                StringArgumentType.getString(ctx, "contents"),
                                IntegerArgumentType.getInteger(ctx, "value"))))))
                // Which axes this contents may be randomly flipped along when it is stamped
                // (`rooms` is the portal-room scope flag, not an axis). See FlipOptions.
                .then(Commands.literal("flip")
                    .then(Commands.argument("contents", StringArgumentType.word())
                        .suggests(CONTENTS_SUGGESTIONS)
                        .then(Commands.argument("field", StringArgumentType.word())
                            .suggests(FLIP_FIELD_SUGGESTIONS)
                            .then(Commands.literal("on").executes(ctx -> runContentsFlipSet(ctx.getSource(),
                                StringArgumentType.getString(ctx, "contents"),
                                StringArgumentType.getString(ctx, "field"), true)))
                            .then(Commands.literal("off").executes(ctx -> runContentsFlipSet(ctx.getSource(),
                                StringArgumentType.getString(ctx, "contents"),
                                StringArgumentType.getString(ctx, "field"), false))))))
                .then(minLevelSingle(CONTENTS_SUGGESTIONS, EditorCommand::applyContentsGate))
                .then(maxLevelSingle(CONTENTS_SUGGESTIONS, EditorCommand::applyContentsGate))
                .then(phaseSingle(CONTENTS_SUGGESTIONS, EditorCommand::applyContentsGate))
                .then(Commands.literal("group")
                    .then(Commands.literal("new")
                        .then(Commands.argument("parent", StringArgumentType.word())
                            .suggests(CONTENTS_SUGGESTIONS)
                            .then(Commands.argument("name", StringArgumentType.word())
                                // Bare form clones the parent — a sub-variant is a variation on it,
                                // so an empty box is the wrong default. Same [source] shape as
                                // `contents new <name> [source]` above.
                                .executes(ctx -> runContentsGroupNew(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "parent"),
                                    StringArgumentType.getString(ctx, "name"),
                                    /*sourceRaw*/ null))
                                .then(Commands.argument("source", StringArgumentType.word())
                                    .suggests(CONTENTS_SUGGESTIONS)
                                    .executes(ctx -> runContentsGroupNew(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "parent"),
                                        StringArgumentType.getString(ctx, "name"),
                                        StringArgumentType.getString(ctx, "source")))))))
                    .then(Commands.literal("add")
                        .then(Commands.argument("parent", StringArgumentType.word())
                            .suggests(CONTENTS_SUGGESTIONS)
                            .then(Commands.argument("child", StringArgumentType.word())
                                .suggests(CONTENTS_SUGGESTIONS)
                                .executes(ctx -> runContentsGroupAdd(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "parent"),
                                    StringArgumentType.getString(ctx, "child"),
                                    CarriageContentsGroup.DEFAULT_WEIGHT))
                                .then(Commands.argument("weight",
                                        IntegerArgumentType.integer(CarriageContentsGroup.MIN_WEIGHT, CarriageContentsGroup.MAX_WEIGHT))
                                    .executes(ctx -> runContentsGroupAdd(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "parent"),
                                        StringArgumentType.getString(ctx, "child"),
                                        IntegerArgumentType.getInteger(ctx, "weight")))))))
                    .then(Commands.literal("set-weight")
                        .then(Commands.argument("parent", StringArgumentType.word())
                            .suggests(CONTENTS_SUGGESTIONS)
                            .then(Commands.argument("child", StringArgumentType.word())
                                .suggests(CONTENTS_SUGGESTIONS)
                                .then(Commands.literal("inc").executes(ctx -> runContentsGroupWeightAdjust(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "parent"),
                                    StringArgumentType.getString(ctx, "child"), +1)))
                                .then(Commands.literal("dec").executes(ctx -> runContentsGroupWeightAdjust(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "parent"),
                                    StringArgumentType.getString(ctx, "child"), -1)))
                                .then(Commands.argument("value",
                                        IntegerArgumentType.integer(CarriageContentsGroup.MIN_WEIGHT, CarriageContentsGroup.MAX_WEIGHT))
                                    .executes(ctx -> runContentsGroupWeightSet(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "parent"),
                                        StringArgumentType.getString(ctx, "child"),
                                        IntegerArgumentType.getInteger(ctx, "value")))))))
                    // Per-member spawn gate (min/max Diff-Level + phase) — the Sub-Variants companion's
                    // gate cells dispatch these (parent + child keyed, like set-weight above).
                    .then(minLevelGroup())
                    .then(maxLevelGroup())
                    .then(phaseGroup())
                    .then(Commands.literal("remove")
                        .then(Commands.argument("parent", StringArgumentType.word())
                            .suggests(CONTENTS_SUGGESTIONS)
                            .then(Commands.argument("child", StringArgumentType.word())
                                .suggests(CONTENTS_SUGGESTIONS)
                                .executes(ctx -> runContentsGroupRemove(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "parent"),
                                    StringArgumentType.getString(ctx, "child"))))))
                    // `move <child> <new_parent>` — re-parent in one step, member record intact.
                    .then(Commands.literal("move")
                        .then(Commands.argument("child", StringArgumentType.word())
                            .suggests(CONTENTS_SUGGESTIONS)
                            .then(Commands.argument("new_parent", StringArgumentType.word())
                                .suggests(CONTENTS_SUGGESTIONS)
                                .executes(ctx -> runContentsGroupMove(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "child"),
                                    StringArgumentType.getString(ctx, "new_parent"))))))
                    .then(Commands.literal("list")
                        .then(Commands.argument("parent", StringArgumentType.word())
                            .suggests(CONTENTS_SUGGESTIONS)
                            .executes(ctx -> runContentsGroupList(ctx.getSource(),
                                StringArgumentType.getString(ctx, "parent")))))
                    .then(Commands.literal("clear")
                        .then(Commands.argument("parent", StringArgumentType.word())
                            .suggests(CONTENTS_SUGGESTIONS)
                            .executes(ctx -> runContentsGroupClear(ctx.getSource(),
                                StringArgumentType.getString(ctx, "parent")))))))
            .then(Commands.literal("pillar")
                .then(Commands.literal("enter")
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(PILLAR_TARGET_SUGGESTIONS)
                        .executes(ctx -> {
                            String raw = StringArgumentType.getString(ctx, "target");
                            PillarSection s = tryParseSection(raw);
                            if (s != null) return runPillarEnter(ctx.getSource(), s);
                            PillarAdjunct a = tryParseAdjunct(raw);
                            if (a != null) return runPillarEnterAdjunct(ctx.getSource(), a);
                            ctx.getSource().sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_pillar_target_valid", raw, pillarTargetList()));
                            return 0;
                        })))
                .then(Commands.literal("save").executes(ctx -> runPillarSave(ctx.getSource())))
                .then(Commands.literal("list").executes(ctx -> runPillarList(ctx.getSource())))
                .then(Commands.literal("reset")
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(PILLAR_TARGET_SUGGESTIONS)
                        .executes(ctx -> {
                            String raw = StringArgumentType.getString(ctx, "target");
                            PillarSection s = tryParseSection(raw);
                            if (s != null) return runPillarReset(ctx.getSource(), s);
                            PillarAdjunct a = tryParseAdjunct(raw);
                            if (a != null) return runPillarResetAdjunct(ctx.getSource(), a);
                            ctx.getSource().sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_pillar_target_valid", raw, pillarTargetList()));
                            return 0;
                        })))
                .then(Commands.literal("promote")
                    .then(Commands.literal("all").executes(ctx -> runPillarPromoteAll(ctx.getSource())))
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(PILLAR_TARGET_SUGGESTIONS)
                        .executes(ctx -> {
                            String raw = StringArgumentType.getString(ctx, "target");
                            PillarSection s = tryParseSection(raw);
                            if (s != null) return runPillarPromote(ctx.getSource(), s);
                            PillarAdjunct a = tryParseAdjunct(raw);
                            if (a != null) return runPillarPromoteAdjunct(ctx.getSource(), a);
                            ctx.getSource().sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_pillar_target_valid", raw, pillarTargetList()));
                            return 0;
                        }))))
            .then(Commands.literal("track")
                .then(Commands.literal("enter").executes(ctx -> runTrackEnter(ctx.getSource())))
                .then(Commands.literal("save").executes(ctx -> runTrackSave(ctx.getSource())))
                .then(Commands.literal("list").executes(ctx -> runTrackList(ctx.getSource())))
                .then(Commands.literal("reset").executes(ctx -> runTrackReset(ctx.getSource())))
                .then(Commands.literal("promote").executes(ctx -> runTrackPromote(ctx.getSource()))))
            .then(buildPartSubtree(buildContext))
            // `/dt editor view <category> <id>` — teleport into the named
            // plot WITHOUT re-stamping it from disk. The unsaved-changes
            // confirmation screen calls this from its per-row View button so
            // the player can inspect a dirty plot in-place before deciding
            // whether to save. (The regular `enter` paths re-stamp on entry,
            // which would wipe the unsaved edits we're about to ask about.)
            .then(Commands.literal("view")
                .then(Commands.argument("category", StringArgumentType.word())
                    .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> runEditorView(ctx.getSource(),
                            StringArgumentType.getString(ctx, "category"),
                            StringArgumentType.getString(ctx, "id"))))))
            .then(Commands.literal("weight")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(CARRIAGE_VARIANT_SUGGESTIONS)
                    .then(Commands.literal("inc").executes(ctx -> runWeightAdjust(ctx.getSource(),
                        StringArgumentType.getString(ctx, "variant"), +1)))
                    .then(Commands.literal("dec").executes(ctx -> runWeightAdjust(ctx.getSource(),
                        StringArgumentType.getString(ctx, "variant"), -1)))
                    .then(Commands.argument("value",
                            IntegerArgumentType.integer(CarriageWeights.MIN, CarriageWeights.MAX))
                        .executes(ctx -> runWeightSet(ctx.getSource(),
                            StringArgumentType.getString(ctx, "variant"),
                            IntegerArgumentType.getInteger(ctx, "value"))))))
            .then(minLevelSingle(CARRIAGE_VARIANT_SUGGESTIONS, EditorCommand::applyCarriageGate))
            .then(maxLevelSingle(CARRIAGE_VARIANT_SUGGESTIONS, EditorCommand::applyCarriageGate))
            .then(phaseSingle(CARRIAGE_VARIANT_SUGGESTIONS, EditorCommand::applyCarriageGate))
            .then(buildStageSubtree())
            .then(buildVariantSubtree(buildContext));
    }

    /**
     * {@code /dungeontrain editor part ...} — authoring and assignment for
     * reusable FLOOR / WALLS / ROOF / DOORS templates that a carriage variant
     * overlays on top of its monolithic NBT at spawn time. Each assignment
     * slot is a <b>list</b> of candidate names; spawn picks one
     * deterministically per-carriage-index so a single variant can render
     * differently from car to car.
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
            // Toggle / set a part's editor-grid visibility (the part-list ☑/☐ checkbox).
            .then(Commands.literal("display")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests(PART_KIND_SUGGESTIONS)
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(PART_NAME_SUGGESTIONS)
                        .executes(c -> runPartDisplay(c.getSource(),
                            StringArgumentType.getString(c, "kind"),
                            StringArgumentType.getString(c, "name"), "toggle"))
                        .then(Commands.argument("mode", StringArgumentType.word())
                            .executes(c -> runPartDisplay(c.getSource(),
                                StringArgumentType.getString(c, "kind"),
                                StringArgumentType.getString(c, "name"),
                                StringArgumentType.getString(c, "mode")))))))
            .then(Commands.literal("new")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests(PART_KIND_SUGGESTIONS)
                    .then(Commands.argument("source", StringArgumentType.word())
                        .suggests(PART_NEW_SOURCE_SUGGESTIONS)
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes(c -> runPartNew(c.getSource(),
                                StringArgumentType.getString(c, "kind"),
                                StringArgumentType.getString(c, "source"),
                                StringArgumentType.getString(c, "name")))))))
            .then(Commands.literal("save")
                .executes(c -> runPartSave(c.getSource(), null))
                .then(Commands.literal("all").executes(c -> runPartSaveAll(c.getSource())))
                .then(Commands.argument("new_name", StringArgumentType.word())
                    .executes(c -> runPartSave(c.getSource(),
                        StringArgumentType.getString(c, "new_name")))))
            .then(Commands.literal("rename")
                .then(Commands.argument("new_name", StringArgumentType.word())
                    .executes(c -> runPartRename(c.getSource(),
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
                                StringArgumentType.getString(c, "name"),
                                1))
                            .then(Commands.argument("weight", IntegerArgumentType.integer(1, 100))
                                .executes(c -> runPartAdd(c.getSource(),
                                    StringArgumentType.getString(c, "variant"),
                                    StringArgumentType.getString(c, "kind"),
                                    StringArgumentType.getString(c, "name"),
                                    IntegerArgumentType.getInteger(c, "weight"))))))))
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

    private static int runWeightSet(CommandSourceStack source, String rawVariant, int value) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        try {
            int stored = CarriageWeights.set(variant.id(), value);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.weight_saved_existing_carriages", variant.id(), stored, CarriageWeights.configPath().toString()).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor weight set failed for {}", variant.id(), t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.weight_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Read the current weight for {@code rawVariant} and persist it after
     * applying {@code delta}. {@link CarriageWeights#set} clamps to
     * {@code [MIN, MAX]} internally, so calling this at the bounds rewrites
     * the same value (a no-op the player can see in the unchanged HUD).
     */
    private static int runWeightAdjust(CommandSourceStack source, String rawVariant, int delta) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        int current = CarriageWeights.current().weightFor(variant.id());
        return runWeightSet(source, rawVariant, current + delta);
    }

    /**
     * {@code /dt editor tracks weight <kind> <name> <value>} — set the pick
     * weight for the {@code (kind, name)} track variant and persist to the
     * kind's own {@code weights.json}. Mirrors {@link #runWeightSet} but for
     * track-side variants.
     */
    private static int runTrackWeightSet(CommandSourceStack source, String rawKind, String name, int value) {
        games.brennan.dungeontrain.track.variant.TrackKind kind = parseTrackKind(source, rawKind);
        if (kind == null) return 0;
        if (name == null || name.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.variant_name_required"));
            return 0;
        }
        try {
            int stored = TrackVariantWeights.set(kind, name, value);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.weight_saved", kind.id(), name, stored, TrackVariantWeights.configPath(kind).toString()).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor tracks weight set failed for {}:{}", kind.id(), name, t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.track_weight_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Re-mirror the plot the player is standing in from its authored master
     * octant — {@code /dungeontrain editor mirror rebuild}, also the X-menu's
     * Mirror → Rebuild row.
     *
     * <p>This used to happen implicitly inside every editor {@code save()},
     * which made saving destructive: deliberate asymmetry (and anything placed
     * by a path the live mirror handlers never see — clipboard paste,
     * {@code /fill}, edits made before the axis was toggled on) was silently
     * overwritten from the master. Saving now captures the plot as it stands,
     * and the rebuild happens only when asked for here.</p>
     *
     * <p>World-only: the author still hits Save to capture the result.</p>
     */
    private static int runMirrorRebuild(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return 0;
        }
        CarriageDims dims = DungeonTrainWorldData.get(player.serverLevel()).dims();
        games.brennan.dungeontrain.editor.BlockVariantPlot plot =
            games.brennan.dungeontrain.editor.BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.stand_inside_plot_rebuild")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        // Rebuild rewrites every image octant in one pass; the region diff around
        // it is what makes that one Ctrl+Z rather than an unrecoverable pass.
        boolean[] rebuilt = new boolean[1];
        EditorRegionDiff.record(player, "Mirror rebuild", plot.key(),
            () -> rebuilt[0] = games.brennan.dungeontrain.editor.EditorMirrorRebuild.run(
                player.serverLevel(), plot));
        if (!rebuilt[0]) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_mirror_axis_plot")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.mirrored_from_master", plot.key())
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** Brigadier subtree: {@code <axis> on|off} → {@link #runMirrorAtPosition} (position-resolved). */
    private static LiteralArgumentBuilder<CommandSourceStack> mirrorAxisNode(String axis) {
        return Commands.literal(axis)
            .then(Commands.literal("on").executes(ctx -> runMirrorAtPosition(ctx.getSource(), axis, true)))
            .then(Commands.literal("off").executes(ctx -> runMirrorAtPosition(ctx.getSource(), axis, false)));
    }

    /** Brigadier subtree: {@code <axis> on|off} → {@link #runTrackMirror} for the ambient (kind, name). */
    private static LiteralArgumentBuilder<CommandSourceStack> trackMirrorAxisNode(String axis) {
        return Commands.literal(axis)
            .then(Commands.literal("on").executes(ctx -> runTrackMirror(ctx.getSource(),
                StringArgumentType.getString(ctx, "kind"), StringArgumentType.getString(ctx, "name"), axis, true)))
            .then(Commands.literal("off").executes(ctx -> runTrackMirror(ctx.getSource(),
                StringArgumentType.getString(ctx, "kind"), StringArgumentType.getString(ctx, "name"), axis, false)));
    }

    /** Apply one {@code x|y|z} axis (or the {@code v} variant-mirror flag) to a track sidecar, preserving the rest. */
    private static void applyMirrorAxis(games.brennan.dungeontrain.track.variant.TrackVariantBlocks cfg,
                                        String axis, boolean on) {
        if (axis.equals("v")) {
            cfg.setMirrorVariants(on);
            return;
        }
        boolean x = cfg.mirrorX(), y = cfg.mirrorY(), z = cfg.mirrorZ();
        switch (axis) {
            case "x" -> x = on;
            case "y" -> y = on;
            case "z" -> z = on;
            default -> { return; }
        }
        cfg.setMirrorAxes(x, y, z);
    }

    /**
     * Toggle one editor mirror axis for a track-side variant by explicit
     * {@code (kind, name)} — used by the {@code editor tracks mirror …} command
     * for scripting / out-of-plot edits. The X-menu instead uses the
     * position-resolved {@link #runMirrorAtPosition}. Persists the flag in the
     * variant's {@code variants.json} so the editor's live + save-time mirroring
     * reflects the authored octant across the enabled axes.
     */
    private static int runTrackMirror(CommandSourceStack source, String rawKind, String name, String axis, boolean on) {
        games.brennan.dungeontrain.track.variant.TrackKind kind = parseTrackKind(source, rawKind);
        if (kind == null) return 0;
        if (name == null || name.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.variant_name_required"));
            return 0;
        }
        CarriageDims dims = DungeonTrainWorldData.get(source.getLevel()).dims();
        // Name-aware, not kind.dims(dims): a portal room's footprint is per-room and lives in its
        // template, and TrackKind.dims answers with the built-in room's size for every one of them.
        // This command saves straight after loading, so the wrong footprint here truncated the file
        // on disk. Same reasoning as the sub-variant copy path below. The template load primes
        // PortalRoomSizes, which is where the per-room footprint comes from.
        if (kind == games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM) {
            games.brennan.dungeontrain.editor.PortalRoomTemplateStore.sizeOf(source.getLevel(), name, dims);
        }
        Vec3i footprint = games.brennan.dungeontrain.editor.TrackSidePlots.footprint(kind, name, dims);
        games.brennan.dungeontrain.track.variant.TrackVariantBlocks cfg =
            games.brennan.dungeontrain.track.variant.TrackVariantBlocks.loadFor(kind, name, footprint);
        applyMirrorAxis(cfg, axis, on);
        try {
            cfg.save(kind, name);
            if (EditorDevMode.isEnabled()) cfg.saveToSource(kind, name);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.mirror", kind.id(), name, axis.toUpperCase(Locale.ROOT), Component.translatable(on ? "chat.dungeontrain.common.on" : "chat.dungeontrain.common.off")).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor tracks mirror failed for {}:{}", kind.id(), name, e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.track_mirror_failed", e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Toggle one editor mirror axis on whatever editor plot the player is
     * standing in — any category (carriage / contents / part / track-side).
     * Resolved via {@link games.brennan.dungeontrain.editor.BlockVariantPlot};
     * backs the X-menu Mirror X / Y / Z toggles.
     */
    private static int runMirrorAtPosition(CommandSourceStack source, String axis, boolean on) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return 0;
        }
        CarriageDims dims = DungeonTrainWorldData.get(player.serverLevel()).dims();
        games.brennan.dungeontrain.editor.BlockVariantPlot plot =
            games.brennan.dungeontrain.editor.BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.stand_inside_plot_toggle")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (axis.equals("v")) {
            plot.setMirrorVariants(on);
        } else {
            boolean x = plot.mirrorX(), y = plot.mirrorY(), z = plot.mirrorZ();
            switch (axis) {
                case "x" -> x = on;
                case "y" -> y = on;
                case "z" -> z = on;
                default -> { return 0; }
            }
            plot.setMirrorAxes(x, y, z);
        }
        try {
            plot.save();
            // The builder's pause menu lights its X/Y/Z/V cells from the bounds packet, so push a
            // fresh one — otherwise the cell you just toggled keeps showing the old state until
            // something else happens to resend it.
            games.brennan.dungeontrain.net.BuilderBoundsPacket.sendTo(player, player.serverLevel());
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.mirror_2", axis.toUpperCase(Locale.ROOT), Component.translatable(on ? "chat.dungeontrain.common.on" : "chat.dungeontrain.common.off"))
                .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor mirror failed", e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.mirror_failed", e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /** Read-modify-write nudge for track variant weight. Bounds clamp via {@link TrackVariantWeights#set}. */
    private static int runTrackWeightAdjust(CommandSourceStack source, String rawKind, String name, int delta) {
        games.brennan.dungeontrain.track.variant.TrackKind kind = parseTrackKind(source, rawKind);
        if (kind == null) return 0;
        if (name == null || name.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.variant_name_required"));
            return 0;
        }
        int current = TrackVariantWeights.weightFor(kind, name);
        return runTrackWeightSet(source, rawKind, name, current + delta);
    }

    /**
     * {@code /dt editor contents weight <id> <value>} — set the pick weight
     * for the contents id and persist to {@code config/dungeontrain/user/contents/weights.json}.
     * Mirrors {@link #runWeightSet} but for carriage-interior contents.
     */
    private static int runContentsWeightSet(CommandSourceStack source, String rawContents, int value) {
        CarriageContents contents = parseContents(source, rawContents);
        if (contents == null) return 0;
        // If the target is a group member, this weight is dead — picks happen
        // via group resolution, not the top-level weights table. Warn but
        // proceed so the value is still persisted (useful if the user later
        // removes the id from the group).
        if (CarriageContentsGroupStore.allChildIds().contains(contents.id())) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.note_member_contents_group", contents.id()).withStyle(ChatFormatting.YELLOW), false);
        }
        try {
            int stored = CarriageContentsWeights.set(contents.id(), value);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.contents_weight_saved_existing", contents.id(), stored, CarriageContentsWeights.configPath().toString()).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents weight set failed for {}", contents.id(), t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.contents_weight_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * {@code /dt editor contents flip <id> <x|y|z|rooms> <on|off>} — enable or disable one axis of
     * the contents template's random flip (or the {@code rooms} scope flag), persisting to
     * {@code config/dungeontrain/user/contents/weights.json}. Mirrors {@link #runContentsWeightSet}.
     *
     * <p>An enabled axis is permission for a flip, not a flip: each stamp rolls the enabled axes
     * independently, so the template still reads as authored roughly half the time.</p>
     */
    private static int runContentsFlipSet(CommandSourceStack source, String rawContents,
                                          String field, boolean value) {
        CarriageContents contents = parseContents(source, rawContents);
        if (contents == null) return 0;
        if (!FlipOptions.isField(field)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_flip_field_expected", field)
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        try {
            FlipOptions next = CarriageContentsWeights.setFlip(contents.id(),
                CarriageContentsWeights.current().flipFor(contents.id()).with(field, value));
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.contents_flip_now_x", contents.id(), field.toLowerCase(java.util.Locale.ROOT) + axisHint(field), Component.translatable(value ? "chat.dungeontrain.common.on" : "chat.dungeontrain.common.off"), onOff(next.x()), onOff(next.y()), onOff(next.z()), onOff(next.rooms()), CarriageContentsWeights.configPath().toString()).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents flip set failed for {}", contents.id(), t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.contents_flip_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    /**
     * What an axis means in the carriage, appended to the flip command's echo — the letters alone
     * are easy to mix up, and picking the wrong one silently mirrors the interior the other way.
     */
    private static String axisHint(String field) {
        return switch (field == null ? "" : field.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "x" -> " (front\u2194back)";
            case "y" -> " (up\u2194down)";
            case "z" -> " (left\u2194right)";
            default -> "";
        };
    }

    /** Read-modify-write nudge for contents weight. Bounds clamp via {@link CarriageContentsWeights#set}. */
    private static int runContentsWeightAdjust(CommandSourceStack source, String rawContents, int delta) {
        CarriageContents contents = parseContents(source, rawContents);
        if (contents == null) return 0;
        int current = CarriageContentsWeights.current().weightFor(contents.id());
        return runContentsWeightSet(source, rawContents, current + delta);
    }

    // ============================================================
    // Per-template spawn gate — min/max Diff-Level band + worldgen phase set.
    // Read-modify-write over the template's current TemplateGate, persisted via
    // the store's setGate(). Mirrors the weight commands; shared across
    // carriages / contents / track-side variants.
    // ============================================================

    /** Per-category gate application: parse the id, apply {@code op} to its current gate, persist. */
    @FunctionalInterface
    interface SingleGateOp {
        int run(CommandSourceStack source, String id, java.util.function.UnaryOperator<TemplateGate> op);
    }

    private static int applyCarriageGate(CommandSourceStack source, String rawVariant, java.util.function.UnaryOperator<TemplateGate> op) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        try {
            TemplateGate next = op.apply(CarriageWeights.current().gateFor(variant.id()));
            CarriageWeights.setGate(variant.id(), next);
            gateSuccess(source, variant.id(), next, CarriageWeights.configPath().toString(),
                CarriageWeights.current().stageIdFor(variant.id()));
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "carriage", variant.id(), t);
        }
    }

    private static int applyContentsGate(CommandSourceStack source, String rawContents, java.util.function.UnaryOperator<TemplateGate> op) {
        CarriageContents contents = parseContents(source, rawContents);
        if (contents == null) return 0;
        try {
            TemplateGate next = op.apply(CarriageContentsWeights.current().gateFor(contents.id()));
            CarriageContentsWeights.setGate(contents.id(), next);
            gateSuccess(source, contents.id(), next, CarriageContentsWeights.configPath().toString(),
                CarriageContentsWeights.current().stageIdFor(contents.id()));
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "contents", contents.id(), t);
        }
    }

    private static int applyTrackGate(CommandSourceStack source, String rawKind, String name, java.util.function.UnaryOperator<TemplateGate> op) {
        games.brennan.dungeontrain.track.variant.TrackKind kind = parseTrackKind(source, rawKind);
        if (kind == null) return 0;
        if (name == null || name.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.variant_name_required"));
            return 0;
        }
        try {
            TemplateGate next = op.apply(TrackVariantWeights.gateFor(kind, name));
            TrackVariantWeights.setGate(kind, name, next);
            gateSuccess(source, kind.id() + ":" + name, next, TrackVariantWeights.configPath(kind).toString(),
                TrackVariantWeights.stageIdFor(kind, name));
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "track", kind.id() + ":" + name, t);
        }
    }

    /**
     * Report a stored gate. {@code linkedStage} is the Stage the target is linked to, or null when
     * it is Custom — on a linked entry the write lands in the inline detach snapshot and the
     * effective gate still comes from the Stage, so say so rather than reporting a bare success.
     */
    static void gateSuccess(CommandSourceStack source, String id, TemplateGate g, String path,
                                    String linkedStage) {
        String maxStr = g.maxLevel() == TemplateGate.ALL ? "all" : Integer.toString(g.maxLevel());
        StringBuilder phases = new StringBuilder();
        if (g.phases().size() == TrainPhase.values().length) {
            phases.append("all");
        } else {
            for (TrainPhase p : TrainPhase.values()) {
                if (!g.phases().contains(p)) continue;
                if (phases.length() > 0) phases.append(',');
                phases.append(p.token());
            }
        }
        String phaseStr = phases.toString();
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.gate_level_phases_saved", id, g.minLevel(), maxStr, phaseStr, path).withStyle(ChatFormatting.GREEN), true);
        if (linkedStage != null) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.note_linked_stage_inline", id, linkedStage).withStyle(ChatFormatting.YELLOW), false);
        }
    }

    static int gateFail(CommandSourceStack source, String what, String id, Throwable t) {
        LOGGER.error("[DungeonTrain] editor {} gate set failed for {}", what, id, t);
        source.sendFailure(Component.translatable("chat.dungeontrain.editor.gate_failed", what, t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
        return 0;
    }

    /** maxLevel inc cycles ALL → 0 → 1 → … → MAX_LEVEL → ALL (mirrors the mob difficulty-band editor). */
    private static TemplateGate maxLevelInc(TemplateGate g) {
        return g.incMaxLevel();
    }

    /** maxLevel dec cycles the other way: ALL → MAX_LEVEL → … → 0 → ALL. */
    private static TemplateGate maxLevelDec(TemplateGate g) {
        return g.decMaxLevel();
    }

    private static TemplateGate togglePhase(TemplateGate g, String phaseToken, boolean on) {
        TrainPhase p = TrainPhase.byToken(phaseToken);
        return p == null ? g : g.withPhase(p, on);
    }

    /**
     * The {@code others} phase action — "toggle all but that one" (shift-click in every dimension
     * editor). Flips every dimension <em>except</em> {@code phaseToken} via the shared
     * {@link TemplateGate#toggleOtherPhases}, the single source every dimension-toggle UI funnels
     * through (parts menu, template-type menu, keyboard Phases menu, and this slash command).
     */
    private static TemplateGate toggleOtherPhases(TemplateGate g, String phaseToken) {
        TrainPhase p = TrainPhase.byToken(phaseToken);
        return p == null ? g : g.toggleOtherPhases(p);
    }

    // ---- Brigadier subtree builders (single-id categories: carriages, contents) ----

    static LiteralArgumentBuilder<CommandSourceStack> minLevelSingle(
            SuggestionProvider<CommandSourceStack> sug, SingleGateOp run) {
        return Commands.literal("minlevel")
            .then(Commands.argument("id", StringArgumentType.word()).suggests(sug)
                .then(Commands.literal("inc").executes(c -> run.run(c.getSource(), StringArgumentType.getString(c, "id"),
                    g -> g.withMinLevel(g.minLevel() + 1))))
                .then(Commands.literal("dec").executes(c -> run.run(c.getSource(), StringArgumentType.getString(c, "id"),
                    g -> g.withMinLevel(g.minLevel() - 1))))
                .then(Commands.argument("value", IntegerArgumentType.integer(0, TemplateGate.MAX_LEVEL))
                    .executes(c -> run.run(c.getSource(), StringArgumentType.getString(c, "id"),
                        g -> g.withMinLevel(IntegerArgumentType.getInteger(c, "value"))))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> maxLevelSingle(
            SuggestionProvider<CommandSourceStack> sug, SingleGateOp run) {
        return Commands.literal("maxlevel")
            .then(Commands.argument("id", StringArgumentType.word()).suggests(sug)
                .then(Commands.literal("inc").executes(c -> run.run(c.getSource(), StringArgumentType.getString(c, "id"),
                    EditorCommand::maxLevelInc)))
                .then(Commands.literal("dec").executes(c -> run.run(c.getSource(), StringArgumentType.getString(c, "id"),
                    EditorCommand::maxLevelDec)))
                .then(Commands.argument("value", IntegerArgumentType.integer(TemplateGate.ALL, TemplateGate.MAX_LEVEL))
                    .executes(c -> run.run(c.getSource(), StringArgumentType.getString(c, "id"),
                        g -> g.withMaxLevel(IntegerArgumentType.getInteger(c, "value"))))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> phaseSingle(
            SuggestionProvider<CommandSourceStack> sug, SingleGateOp run) {
        return Commands.literal("phase")
            .then(Commands.argument("id", StringArgumentType.word()).suggests(sug)
                .then(Commands.argument("phase", StringArgumentType.word()).suggests(PHASE_SUGGESTIONS)
                    .then(Commands.literal("on").executes(c -> run.run(c.getSource(), StringArgumentType.getString(c, "id"),
                        g -> togglePhase(g, StringArgumentType.getString(c, "phase"), true))))
                    .then(Commands.literal("off").executes(c -> run.run(c.getSource(), StringArgumentType.getString(c, "id"),
                        g -> togglePhase(g, StringArgumentType.getString(c, "phase"), false))))
                    .then(Commands.literal("others").executes(c -> run.run(c.getSource(), StringArgumentType.getString(c, "id"),
                        g -> toggleOtherPhases(g, StringArgumentType.getString(c, "phase")))))));
    }

    // ============================================================
    // Stages — named, reusable gate presets. CRUD + gate-edit + link/detach.
    // The Stages window and the "Stage / Custom" picker dispatch these.
    // ============================================================

    /** {@code /dungeontrain editor stage …} — manage named gate presets and link templates to them. */
    private static LiteralArgumentBuilder<CommandSourceStack> buildStageSubtree() {
        return Commands.literal("stage")
            .then(Commands.literal("new")
                .then(Commands.argument("id", StringArgumentType.word())
                    .executes(c -> runStageNew(c.getSource(), StringArgumentType.getString(c, "id")))))
            .then(Commands.literal("delete")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(STAGE_SUGGESTIONS)
                    .executes(c -> runStageDelete(c.getSource(), StringArgumentType.getString(c, "id")))))
            .then(Commands.literal("builder")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(STAGE_SUGGESTIONS)
                    .then(Commands.argument("uuid", StringArgumentType.word())
                        .executes(c -> EditorBuilderCommands.runStageBuilder(c.getSource(),
                            StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "uuid"), ""))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                            .executes(c -> EditorBuilderCommands.runStageBuilder(c.getSource(),
                                StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "uuid"),
                                StringArgumentType.getString(c, "name")))))))
            .then(Commands.literal("rename")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(STAGE_SUGGESTIONS)
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(c -> runStageRename(c.getSource(), StringArgumentType.getString(c, "id"),
                            StringArgumentType.getString(c, "name"))))))
            .then(Commands.literal("list").executes(c -> runStageList(c.getSource())))
            // Focus a stage: previews every carriage for it (assigned parts stamped, unassigned slots
            // aired out over the kept base shell) and defaults newly-added parts to it. Re-selecting the
            // same id toggles the preview off; `deselect` clears it explicitly.
            .then(Commands.literal("select")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(STAGE_SUGGESTIONS)
                    .executes(c -> runStageSelect(c.getSource(), StringArgumentType.getString(c, "id")))))
            .then(Commands.literal("deselect").executes(c -> runStageDeselect(c.getSource())))
            // Duplicate a stage WITH its content set: part copies + duplicated .parts.json entries.
            .then(Commands.literal("duplicate")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(STAGE_SUGGESTIONS)
                    .executes(c -> runStageDuplicate(c.getSource(), StringArgumentType.getString(c, "id"), null))
                    .then(Commands.argument("newid", StringArgumentType.word())
                        .executes(c -> runStageDuplicate(c.getSource(), StringArgumentType.getString(c, "id"),
                            StringArgumentType.getString(c, "newid"))))))
            // Re-derive the stage placeholder palette (what stage_block_N etc. become) from the
            // stage's parts — `bake all` for every stage. Runs automatically on stage save; this is
            // for after part edits.
            .then(Commands.literal("bake")
                .executes(c -> runStageBake(c.getSource(), "all"))
                .then(Commands.argument("id", StringArgumentType.word()).suggests(STAGE_SUGGESTIONS)
                    .executes(c -> runStageBake(c.getSource(), StringArgumentType.getString(c, "id")))))
            // Chat listing of the stage's parts and their unique blocks (debug / discoverability).
            .then(Commands.literal("blocks")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(STAGE_SUGGESTIONS)
                    .executes(c -> runStageBlocks(c.getSource(), StringArgumentType.getString(c, "id")))))
            // Stage-wide block replacement across every linked part (structural NBT + variant sidecars).
            .then(Commands.literal("replaceblock")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(STAGE_SUGGESTIONS)
                    .then(Commands.argument("from", ResourceLocationArgument.id()).suggests(STAGE_BLOCK_SUGGESTIONS)
                        .then(Commands.argument("to", ResourceLocationArgument.id()).suggests(ALL_BLOCK_SUGGESTIONS)
                            .executes(c -> runStageReplaceBlock(c.getSource(),
                                StringArgumentType.getString(c, "id"),
                                ResourceLocationArgument.getId(c, "from"),
                                ResourceLocationArgument.getId(c, "to")))))))
            // Toggle "hide part plots not used by the focused stage" on the parts grid.
            .then(Commands.literal("filterparts").executes(c -> runStageFilterParts(c.getSource())))
            // Gate editing for a stage reuses the shared min/max/phase builders, keyed on the stage id.
            .then(minLevelSingle(STAGE_SUGGESTIONS, EditorCommand::applyStageGate))
            .then(maxLevelSingle(STAGE_SUGGESTIONS, EditorCommand::applyStageGate))
            .then(phaseSingle(STAGE_SUGGESTIONS, EditorCommand::applyStageGate))
            // Link a template to a stage (or `custom` to detach).
            .then(Commands.literal("apply")
                .then(Commands.literal("carriage")
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(CARRIAGE_VARIANT_SUGGESTIONS)
                        .then(Commands.argument("stage", StringArgumentType.word()).suggests(STAGE_OR_CUSTOM_SUGGESTIONS)
                            .executes(c -> applyCarriageStage(c.getSource(),
                                StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "stage"))))))
                .then(Commands.literal("contents")
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(CONTENTS_SUGGESTIONS)
                        .then(Commands.argument("stage", StringArgumentType.word()).suggests(STAGE_OR_CUSTOM_SUGGESTIONS)
                            .executes(c -> applyContentsStage(c.getSource(),
                                StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "stage"))))))
                .then(WholeEditorCommand.stageApplyNode(games.brennan.dungeontrain.train.WholeKind.ROOM))
                .then(WholeEditorCommand.stageApplyNode(games.brennan.dungeontrain.train.WholeKind.GROUP))
                .then(Commands.literal("contents-group")
                    .then(Commands.argument("parent", StringArgumentType.word()).suggests(CONTENTS_SUGGESTIONS)
                        .then(Commands.argument("child", StringArgumentType.word()).suggests(CONTENTS_SUGGESTIONS)
                            .then(Commands.argument("stage", StringArgumentType.word()).suggests(STAGE_OR_CUSTOM_SUGGESTIONS)
                                .executes(c -> applyGroupMemberStage(c.getSource(),
                                    StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                                    StringArgumentType.getString(c, "stage")))))))
                .then(Commands.literal("tracks")
                    .then(Commands.argument("kind", StringArgumentType.word()).suggests(TRACK_KIND_SUGGESTIONS)
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(TRACK_VARIANT_NAME_SUGGESTIONS)
                            .then(Commands.argument("stage", StringArgumentType.word()).suggests(STAGE_OR_CUSTOM_SUGGESTIONS)
                                .executes(c -> applyTrackStage(c.getSource(),
                                    StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "name"),
                                    StringArgumentType.getString(c, "stage")))))))
                // A track-side sub-variant (today: a portal room's). Kind-qualified like `tracks`
                // above, and a toggle like `contents-group` — a member may carry more than one link.
                .then(Commands.literal("tracks-group")
                    .then(Commands.argument("kind", StringArgumentType.word()).suggests(TRACK_KIND_SUGGESTIONS)
                        .then(Commands.argument("parent", StringArgumentType.word()).suggests(TRACK_VARIANT_NAME_SUGGESTIONS)
                            .then(Commands.argument("child", StringArgumentType.word()).suggests(TRACK_VARIANT_NAME_SUGGESTIONS)
                                .then(Commands.argument("stage", StringArgumentType.word()).suggests(STAGE_OR_CUSTOM_SUGGESTIONS)
                                    .executes(c -> applyTrackGroupMemberStage(c.getSource(),
                                        StringArgumentType.getString(c, "kind"),
                                        StringArgumentType.getString(c, "parent"),
                                        StringArgumentType.getString(c, "child"),
                                        StringArgumentType.getString(c, "stage")))))))));
    }

    /** Gate editor for a stage — read-modify-write its {@link TemplateGate} via {@code StageStore}. */
    private static int applyStageGate(CommandSourceStack source, String stageId,
                                      java.util.function.UnaryOperator<TemplateGate> op) {
        String id = stageId == null ? "" : stageId.toLowerCase(java.util.Locale.ROOT);
        if (id.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.stage_id_required").withStyle(ChatFormatting.RED));
            return 0;
        }
        try {
            TemplateGate current = games.brennan.dungeontrain.editor.StageStore.gateOf(id)
                .orElse(TemplateGate.DEFAULT);
            TemplateGate next = op.apply(current);
            games.brennan.dungeontrain.editor.StageStore.setGate(id, next);
            games.brennan.dungeontrain.editor.StagePaletteBaker.bake(source.getServer().overworld(), id);
            // A Stage's own gate — nothing upstream to be linked to, so no detach note.
            gateSuccess(source, "stage:" + id, next,
                games.brennan.dungeontrain.editor.StageStore.configPath().toString(), null);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "stage", id, t);
        }
    }

    private static int runStageNew(CommandSourceStack source, String rawId) {
        try {
            games.brennan.dungeontrain.template.Stage created =
                games.brennan.dungeontrain.editor.StageStore.add(rawId);
            if (created == null) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.invalid_stage_id", rawId).withStyle(ChatFormatting.RED));
                return 0;
            }
            games.brennan.dungeontrain.editor.StagePaletteBaker.bake(source.getServer().overworld(), created.id());
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.created_stage", created.id())
                .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "stage new", rawId, t);
        }
    }

    private static int runStageDelete(CommandSourceStack source, String rawId) {
        try {
            boolean removed = games.brennan.dungeontrain.editor.StageStore.delete(rawId);
            if (!removed) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_such_stage", rawId).withStyle(ChatFormatting.RED));
                return 0;
            }
            // If the deleted stage was the focused preview, drop the selection and restore normal preview.
            if (games.brennan.dungeontrain.editor.EditorStageSelection.isSelected(rawId)) {
                games.brennan.dungeontrain.editor.EditorStageSelection.clear();
                restampCarriagePlotsForStage(source);
            }
            // Close any Stage Blocks panels showing the deleted stage.
            games.brennan.dungeontrain.editor.StagePanelController.closeForStage(
                source.getServer(), rawId);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.deleted_stage_linked_templates", rawId.toLowerCase(java.util.Locale.ROOT))
                .withStyle(ChatFormatting.YELLOW), true);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "stage delete", rawId, t);
        }
    }

    private static int runStageRename(CommandSourceStack source, String rawId, String name) {
        try {
            games.brennan.dungeontrain.template.Stage renamed =
                games.brennan.dungeontrain.editor.StageStore.rename(rawId, name);
            if (renamed == null) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_such_stage", rawId).withStyle(ChatFormatting.RED));
                return 0;
            }
            games.brennan.dungeontrain.editor.StagePaletteBaker.bake(source.getServer().overworld(), renamed.id());
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.renamed_stage", renamed.id(), renamed.name()).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "stage rename", rawId, t);
        }
    }

    private static int runStageList(CommandSourceStack source) {
        java.util.List<games.brennan.dungeontrain.template.Stage> stages =
            games.brennan.dungeontrain.editor.StageStore.allStages();
        if (stages.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.no_stages_defined_create").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.stage_s", stages.size())
            .withStyle(ChatFormatting.AQUA), false);
        for (games.brennan.dungeontrain.template.Stage s : stages) {
            TemplateGate g = s.gate();
            String maxStr = g.maxLevel() == TemplateGate.ALL ? "all" : Integer.toString(g.maxLevel());
            String phaseStr = g.phases().size() == TrainPhase.values().length ? "all" : phaseTokens(g);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.level_phases", s.id(), g.minLevel(), maxStr, phaseStr).withStyle(ChatFormatting.GRAY), false);
        }
        return stages.size();
    }

    /**
     * Toggle the focused {@link games.brennan.dungeontrain.editor.EditorStageSelection stage}: select
     * {@code rawId} if it isn't already focused, else clear (re-selecting the same row deselects). On
     * change, re-stamp the carriage plots so the per-stage preview refreshes; the client highlight
     * follows automatically via the per-tick type-menu snapshot.
     */
    private static int runStageSelect(CommandSourceStack source, String rawId) {
        String id = rawId == null ? "" : rawId.toLowerCase(java.util.Locale.ROOT);
        if (!games.brennan.dungeontrain.editor.StageStore.exists(id)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_such_stage", rawId).withStyle(ChatFormatting.RED));
            return 0;
        }
        boolean nowSelected = !games.brennan.dungeontrain.editor.EditorStageSelection.isSelected(id);
        if (nowSelected) {
            focusStage(source, id);
        } else {
            games.brennan.dungeontrain.editor.EditorStageSelection.clear();
            restampCarriagePlotsForStage(source);
            games.brennan.dungeontrain.editor.StagePanelController.closeFor(source.getPlayer());
        }
        if (nowSelected) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.previewing_carriages_stage_added", id).withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.stage_preview_off", id)
                .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    /**
     * Focus stage {@code id} without toggling: set the selection, re-stamp the carriage plots for
     * the per-stage preview, and open the Stage Blocks panel for the acting player (replacing any
     * panel already open for them). Parts-grid visibility is decoupled from selection (it's driven
     * by the hide-unused snapshot + per-part checkboxes), so this does not re-filter it. Shared by
     * {@code select} and {@code duplicate} (which lands on the new copy).
     */
    private static void focusStage(CommandSourceStack source, String id) {
        games.brennan.dungeontrain.editor.EditorStageSelection.select(id);
        restampCarriagePlotsForStage(source);
        games.brennan.dungeontrain.editor.StagePanelController.openFor(source.getPlayer(), id);
    }

    /** Clear any focused stage and restore the normal carriage preview. */
    private static int runStageDeselect(CommandSourceStack source) {
        games.brennan.dungeontrain.editor.EditorStageSelection.clear();
        restampCarriagePlotsForStage(source);
        games.brennan.dungeontrain.editor.StagePanelController.closeFor(source.getPlayer());
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.stage_preview_off_2")
            .withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    /**
     * Duplicate stage {@code rawId} (and its parts + assignment entries) as {@code rawNewId}, or an
     * auto-derived {@code <id>_copy} when {@code rawNewId} is null. Full engine in
     * {@link games.brennan.dungeontrain.editor.StageDuplicator}.
     */
    private static int runStageDuplicate(CommandSourceStack source, String rawId, String rawNewId) {
        try {
            games.brennan.dungeontrain.editor.StageDuplicator.Result r =
                games.brennan.dungeontrain.editor.StageDuplicator.duplicate(
                    source.getServer().overworld(), rawId, rawNewId);
            // The copy is what the user wants to edit next — switch the focus (selection, preview,
            // Stage Blocks panel) over to it, replacing the source stage's panel.
            focusStage(source, r.newStageId());
            String skippedNote = r.skippedParts().isEmpty() ? ""
                : ", " + r.skippedParts().size() + " part(s) skipped (missing template)";
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.duplicated_stage_part_s", r.sourceStageId(), r.newStageId(), r.partCopies().size(), r.entriesAdded(), Component.translatable(r.entriesAdded() == 1 ? "chat.dungeontrain.common.noun.entry.singular" : "chat.dungeontrain.common.noun.entry.plural"), r.touchedVariantIds().size(), skippedNote, r.newStageId())
                .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "stage duplicate", rawId, t);
        }
    }

    /** Chat listing of a stage's linked parts and their unique blocks. */
    /** {@code stage bake [<id>|all]} — see {@link games.brennan.dungeontrain.editor.StagePaletteBaker}. */
    private static int runStageBake(CommandSourceStack source, String rawId) {
        String id = rawId == null ? "" : rawId.toLowerCase(java.util.Locale.ROOT);
        try {
            if (id.equals("all")) {
                int n = games.brennan.dungeontrain.editor.StagePaletteBaker.bakeAll(source.getServer().overworld());
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.baked_placeholder_palettes_stage", n, games.brennan.dungeontrain.editor.StageStore.configPath().toString())
                    .withStyle(ChatFormatting.GREEN), true);
                return n;
            }
            java.util.Optional<games.brennan.dungeontrain.template.StagePalette> baked =
                games.brennan.dungeontrain.editor.StagePaletteBaker.bake(source.getServer().overworld(), id);
            if (baked.isEmpty()) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_such_stage", rawId).withStyle(ChatFormatting.RED));
                return 0;
            }
            games.brennan.dungeontrain.template.StagePalette p = baked.get();
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.baked_stage_palette_solid", id, p.solid().toString(), p.stairs().toString(), p.slabs().toString(), p.button(), p.pressurePlate(), p.wood()).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "stage bake", rawId, t);
        }
    }

    private static int runStageBlocks(CommandSourceStack source, String rawId) {
        String id = rawId == null ? "" : rawId.toLowerCase(java.util.Locale.ROOT);
        if (!games.brennan.dungeontrain.editor.StageStore.exists(id)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_such_stage", rawId).withStyle(ChatFormatting.RED));
            return 0;
        }
        games.brennan.dungeontrain.editor.StageBlockIndex.StageBlocks blocks =
            games.brennan.dungeontrain.editor.StageBlockIndex.blocksForStage(
                source.getServer().overworld(), id);
        if (blocks.parts().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.stage_has_no_linked", id).withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.stage_uses_part_s", id, blocks.parts().size(), blocks.aggregatedBlockIds().size()).withStyle(ChatFormatting.AQUA), false);
        for (games.brennan.dungeontrain.editor.StageBlockIndex.PartBlocks pb : blocks.parts()) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.msg", pb.part().kind().id(), pb.part().name(), String.join(", ", pb.blockIds()))
                .withStyle(ChatFormatting.GRAY), false);
        }
        return blocks.parts().size();
    }

    /** Stage-wide block replacement — see {@link games.brennan.dungeontrain.editor.StageBlockReplacer}. */
    private static int runStageReplaceBlock(CommandSourceStack source, String rawId,
                                            net.minecraft.resources.ResourceLocation fromId,
                                            net.minecraft.resources.ResourceLocation toId) {
        try {
            java.util.Optional<net.minecraft.world.level.block.Block> from =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(fromId);
            java.util.Optional<net.minecraft.world.level.block.Block> to =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(toId);
            if (from.isEmpty() || to.isEmpty()) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_block", (from.isEmpty() ? fromId : toId).toString()).withStyle(ChatFormatting.RED));
                return 0;
            }
            // Command path supplies an explicit <to> and no held item → no block-entity payload.
            games.brennan.dungeontrain.editor.StageBlockReplacer.Result r =
                games.brennan.dungeontrain.editor.StageBlockReplacer.replaceAcrossStage(
                    source.getServer().overworld(), rawId.toLowerCase(java.util.Locale.ROOT),
                    from.get(), to.get(), null);
            if (r.isEmpty()) {
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.no_occurrences_stage", fromId.toString(), rawId).withStyle(ChatFormatting.YELLOW), false);
                return 0;
            }
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.replaced_across_part_s", fromId.toString(), toId.toString(), r.partsTouched().size(), r.paletteStatesRewritten(), r.sidecarStatesRewritten())
                .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "stage replaceblock", rawId, t);
        }
    }

    /** Toggle the hide-unused parts filter — bulk-set per-part visibility from the focused stage. */
    private static int runStageFilterParts(CommandSourceStack source) {
        boolean active = games.brennan.dungeontrain.editor.EditorPartsStageFilter.toggle();
        if (active) {
            games.brennan.dungeontrain.editor.EditorPartVisibility.hideUnused(
                games.brennan.dungeontrain.editor.EditorStageSelection.effective());
        } else {
            games.brennan.dungeontrain.editor.EditorPartVisibility.showAll();
        }
        restampPartsGridForStage(source);
        // The filter flag doesn't move the stage-blocks generation — reflect the new button state
        // on any open Stage Blocks panels explicitly (mirrors the panel-op path).
        games.brennan.dungeontrain.editor.StagePanelController.resyncAllOpen(source.getServer());
        if (active) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.parts_grid_showing_only")
                .withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.parts_grid_showing_all")
                .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    /** Toggle / set one part's editor-grid visibility (the part-list ☑/☐ checkbox). */
    private static int runPartDisplay(CommandSourceStack source, String rawKind, String name, String mode) {
        CarriagePartKind kind = CarriagePartKind.fromId(rawKind);
        if (kind == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_part_kind", rawKind).withStyle(ChatFormatting.RED));
            return 0;
        }
        String n = name.toLowerCase(java.util.Locale.ROOT);
        boolean displayed = switch (mode == null ? "toggle" : mode.toLowerCase(java.util.Locale.ROOT)) {
            case "on", "show" -> { games.brennan.dungeontrain.editor.EditorPartVisibility.setHidden(kind, n, false); yield true; }
            case "off", "hide" -> { games.brennan.dungeontrain.editor.EditorPartVisibility.setHidden(kind, n, true); yield false; }
            default -> games.brennan.dungeontrain.editor.EditorPartVisibility.toggle(kind, n);
        };
        restampPartsGridForStage(source);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.part", kind.id(), n, Component.translatable(displayed ? "chat.dungeontrain.editor.part_shown" : "chat.dungeontrain.editor.part_hidden")).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /**
     * Repaint the parts grid so the {@link games.brennan.dungeontrain.editor.EditorPartsStageFilter}
     * state is reflected — same CARRIAGES-stamped guard as {@link #restampCarriagePlotsForStage}.
     */
    private static void restampPartsGridForStage(CommandSourceStack source) {
        if (EditorStampedCategoryState.current().orElse(null) != EditorCategory.CARRIAGES) return;
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        CarriagePartEditor.stampAllPlots(overworld, dims);
    }

    /**
     * Re-stamp every carriage plot so the per-stage preview reflects the current selection — but only
     * when CARRIAGES is the stamped category. In any other category the carriage plots are cleared, so
     * repainting them here would wrongly resurrect carriages over the active category's plots; the
     * selection still applies and the preview appears next time the player enters CARRIAGES.
     */
    private static void restampCarriagePlotsForStage(CommandSourceStack source) {
        if (EditorStampedCategoryState.current().orElse(null) != EditorCategory.CARRIAGES) return;
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        // A category fill still in flight must land first, or it would repaint plots behind this.
        EditorStampQueue.flush();
        for (games.brennan.dungeontrain.train.CarriageVariant v
                : games.brennan.dungeontrain.train.CarriageVariantRegistry.allVariants()) {
            CarriageEditor.stampPlot(overworld, v, dims);
        }
    }

    private static String phaseTokens(TemplateGate g) {
        StringBuilder sb = new StringBuilder();
        for (TrainPhase p : TrainPhase.values()) {
            if (!g.phases().contains(p)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(p.token());
        }
        return sb.toString();
    }

    private static int applyCarriageStage(CommandSourceStack source, String rawVariant, String stageToken) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        String link = resolveStageLink(source, stageToken);
        if (link == INVALID_STAGE) return 0;
        try {
            CarriageWeights.setStage(variant.id(), link);
            stageApplySuccess(source, "carriage", variant.id(), link);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "carriage stage", variant.id(), t);
        }
    }

    private static int applyContentsStage(CommandSourceStack source, String rawContents, String stageToken) {
        CarriageContents contents = parseContents(source, rawContents);
        if (contents == null) return 0;
        String link = resolveStageLink(source, stageToken);
        if (link == INVALID_STAGE) return 0;
        try {
            CarriageContentsWeights.setStage(contents.id(), link);
            stageApplySuccess(source, "contents", contents.id(), link);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "contents stage", contents.id(), t);
        }
    }

    private static int applyTrackStage(CommandSourceStack source, String rawKind, String name, String stageToken) {
        games.brennan.dungeontrain.track.variant.TrackKind kind = parseTrackKind(source, rawKind);
        if (kind == null) return 0;
        if (name == null || name.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.variant_name_required").withStyle(ChatFormatting.RED));
            return 0;
        }
        String link = resolveStageLink(source, stageToken);
        if (link == INVALID_STAGE) return 0;
        try {
            TrackVariantWeights.setStage(kind, name, link);
            stageApplySuccess(source, "track", kind.id() + ":" + name, link);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "track stage", kind.id() + ":" + name, t);
        }
    }

    /** Sentinel distinguishing a reported error from a legitimate {@code null} ("custom"/detach) link. */
    static final String INVALID_STAGE = new String("\0invalid");

    /**
     * Resolve a {@code <stage>} apply token: {@code custom}/blank ⇒ {@code null} (detach); an existing
     * stage id ⇒ that id; an unknown id ⇒ failure reported and {@link #INVALID_STAGE} returned.
     */
    static String resolveStageLink(CommandSourceStack source, String token) {
        if (token == null || token.isBlank() || token.equalsIgnoreCase(STAGE_CUSTOM_TOKEN)) return null;
        String id = token.toLowerCase(java.util.Locale.ROOT);
        if (!games.brennan.dungeontrain.editor.StageStore.exists(id)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_such_stage_use", id).withStyle(ChatFormatting.RED));
            return INVALID_STAGE;
        }
        return id;
    }

    static void stageApplySuccess(CommandSourceStack source, String what, String id, String link) {
        if (link == null) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.detached_custom", what, id).withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.linked_stage", what, id, link).withStyle(ChatFormatting.GREEN), true);
        }
    }

    // ---- Brigadier subtree builders (track-side: kind + name) ----

    private static LiteralArgumentBuilder<CommandSourceStack> minLevelTrack() {
        return Commands.literal("minlevel")
            .then(Commands.argument("kind", StringArgumentType.word()).suggests(TRACK_KIND_SUGGESTIONS)
                .then(Commands.argument("name", StringArgumentType.word()).suggests(TRACK_VARIANT_NAME_SUGGESTIONS)
                    .then(Commands.literal("inc").executes(c -> applyTrackGate(c.getSource(),
                        StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "name"),
                        g -> g.withMinLevel(g.minLevel() + 1))))
                    .then(Commands.literal("dec").executes(c -> applyTrackGate(c.getSource(),
                        StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "name"),
                        g -> g.withMinLevel(g.minLevel() - 1))))
                    .then(Commands.argument("value", IntegerArgumentType.integer(0, TemplateGate.MAX_LEVEL))
                        .executes(c -> applyTrackGate(c.getSource(),
                            StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "name"),
                            g -> g.withMinLevel(IntegerArgumentType.getInteger(c, "value")))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> maxLevelTrack() {
        return Commands.literal("maxlevel")
            .then(Commands.argument("kind", StringArgumentType.word()).suggests(TRACK_KIND_SUGGESTIONS)
                .then(Commands.argument("name", StringArgumentType.word()).suggests(TRACK_VARIANT_NAME_SUGGESTIONS)
                    .then(Commands.literal("inc").executes(c -> applyTrackGate(c.getSource(),
                        StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "name"),
                        EditorCommand::maxLevelInc)))
                    .then(Commands.literal("dec").executes(c -> applyTrackGate(c.getSource(),
                        StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "name"),
                        EditorCommand::maxLevelDec)))
                    .then(Commands.argument("value", IntegerArgumentType.integer(TemplateGate.ALL, TemplateGate.MAX_LEVEL))
                        .executes(c -> applyTrackGate(c.getSource(),
                            StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "name"),
                            g -> g.withMaxLevel(IntegerArgumentType.getInteger(c, "value")))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> phaseTrack() {
        return Commands.literal("phase")
            .then(Commands.argument("kind", StringArgumentType.word()).suggests(TRACK_KIND_SUGGESTIONS)
                .then(Commands.argument("name", StringArgumentType.word()).suggests(TRACK_VARIANT_NAME_SUGGESTIONS)
                    .then(Commands.argument("phase", StringArgumentType.word()).suggests(PHASE_SUGGESTIONS)
                        .then(Commands.literal("on").executes(c -> applyTrackGate(c.getSource(),
                            StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "name"),
                            g -> togglePhase(g, StringArgumentType.getString(c, "phase"), true))))
                        .then(Commands.literal("off").executes(c -> applyTrackGate(c.getSource(),
                            StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "name"),
                            g -> togglePhase(g, StringArgumentType.getString(c, "phase"), false))))
                        .then(Commands.literal("others").executes(c -> applyTrackGate(c.getSource(),
                            StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "name"),
                            g -> toggleOtherPhases(g, StringArgumentType.getString(c, "phase"))))))));
    }

    /**
     * {@code /dt editor contents group add <parent> <child> [weight]} — add
     * or update a member in the parent's group. Validates: both ids are
     * registered, parent isn't a built-in (built-ins have hardcoded NBT
     * fallback that conflicts with group semantics), child isn't itself a
     * group parent (single-hop only), parent isn't a member of another group
     * (no cycles). If the parent has a stored {@code .nbt}, warn that the
     * block layout will be ignored at spawn time.
     */
    private static int runContentsGroupAdd(CommandSourceStack source, String parentRaw, String childRaw, int weight) {
        CarriageContents parent = parseContents(source, parentRaw);
        if (parent == null) return 0;
        CarriageContents child = parseContents(source, childRaw);
        if (child == null) return 0;
        if (parent.isBuiltin()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.built_contents_cannot_be", parent.id()).withStyle(ChatFormatting.RED));
            return 0;
        }
        if (parent.id().equals(child.id())) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.cannot_add_as_member", parent.id()).withStyle(ChatFormatting.RED));
            return 0;
        }
        if (CarriageContentsGroupStore.exists(child.id())) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.itself_contents_group_nested", child.id()).withStyle(ChatFormatting.RED));
            return 0;
        }
        // Cycle guard: parent must not already be a member of another group.
        if (CarriageContentsGroupStore.allChildIds().contains(parent.id())) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.already_member_another_group", parent.id()).withStyle(ChatFormatting.RED));
            return 0;
        }

        CarriageContentsGroup existing = CarriageContentsGroupStore.get(parent.id())
            .orElse(CarriageContentsGroup.EMPTY);
        // Re-adding an existing member only updates its weight — keep its gate + Stage links.
        CarriageContentsGroup.Member member = existing.member(child.id())
            .map(m -> m.withWeight(weight))
            .orElseGet(() -> new CarriageContentsGroup.Member(child.id(), weight));
        CarriageContentsGroup updated = existing.withMember(member);
        try {
            CarriageContentsGroupStore.save(parent.id(), updated);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.group_added_weight_explicit", parent.id(), child.id(), weight, updated.members().size(), Component.translatable(updated.members().size() == 1 ? "chat.dungeontrain.common.noun.member.singular" : "chat.dungeontrain.common.noun.member.plural")).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor contents group add failed", e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.group_add_failed", e.toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * {@code /dt editor contents group set-weight <parent> <child> <value>}
     * — set the weight of an existing group member and persist to the parent's
     * {@code .group.json} sidecar. Mirrors {@link #runContentsWeightSet} but
     * targets the per-member weight pool that group resolution actually reads.
     *
     * <p>When {@code parent == child}, the value is applied to the parent's
     * own {@code selfWeight} (the weight of its synthetic self-entry in the
     * resolution pool) via {@link CarriageContentsGroup#withSelfWeight}.</p>
     */
    private static int runContentsGroupWeightSet(CommandSourceStack source, String parentRaw, String childRaw, int value) {
        CarriageContents parent = parseContents(source, parentRaw);
        if (parent == null) return 0;
        CarriageContents child = parseContents(source, childRaw);
        if (child == null) return 0;
        java.util.Optional<CarriageContentsGroup> existing = CarriageContentsGroupStore.get(parent.id());
        if (existing.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_contents_group_defined", parent.id()).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        boolean isSelf = parent.id().equals(child.id());
        if (!isSelf && existing.get().members().stream().noneMatch(m -> m.id().equals(child.id()))) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_member_group", child.id(), parent.id()).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        CarriageContentsGroup updated;
        final int stored;
        if (isSelf) {
            // Canonical constructor clamps to [MIN_WEIGHT, MAX_WEIGHT].
            updated = existing.get().withSelfWeight(value);
            stored = updated.selfWeight();
        } else {
            // withWeight clamps to [MIN_WEIGHT, MAX_WEIGHT] and keeps the member's gate + Stage
            // links intact (a fresh Member(id, weight) would reset both); withMember replaces in place.
            CarriageContentsGroup.Member updatedMember = existing.get().member(child.id())
                .orElseThrow()
                .withWeight(value);
            updated = existing.get().withMember(updatedMember);
            stored = updatedMember.weight();
        }
        try {
            CarriageContentsGroupStore.save(parent.id(), updated);
            final String label = isSelf ? "selfWeight" : "'" + child.id() + "' weight";
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.group", parent.id(), label, stored).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor contents group set-weight failed", e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.group_set_weight_failed", e.toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /** Read-modify-write nudge for a group member's weight (or the parent's selfWeight when {@code parent == child}). Bounds clamp via {@link CarriageContentsGroup#clampWeight}. */
    private static int runContentsGroupWeightAdjust(CommandSourceStack source, String parentRaw, String childRaw, int delta) {
        CarriageContents parent = parseContents(source, parentRaw);
        if (parent == null) return 0;
        CarriageContents child = parseContents(source, childRaw);
        if (child == null) return 0;
        java.util.Optional<CarriageContentsGroup> existing = CarriageContentsGroupStore.get(parent.id());
        if (existing.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_contents_group_defined", parent.id()).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        int current;
        if (parent.id().equals(child.id())) {
            current = existing.get().selfWeight();
        } else {
            current = existing.get().members().stream()
                .filter(m -> m.id().equals(child.id()))
                .mapToInt(CarriageContentsGroup.Member::weight)
                .findFirst()
                .orElse(-1);
            if (current < 0) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_member_group", child.id(), parent.id()).withStyle(ChatFormatting.YELLOW));
                return 0;
            }
        }
        return runContentsGroupWeightSet(source, parentRaw, childRaw, current + delta);
    }

    /**
     * Read-modify-write a group <em>member</em>'s spawn {@link TemplateGate} in {@code parentRaw}'s
     * {@code .group.json}. Mirrors {@link #applyContentsGate} but keyed on (parent, member); editing
     * the inline gate detaches any Stage link (matches the top-level {@code setGate} contract — the UI
     * routes linked rows to the picker instead of here, so this only fires on Custom members).
     */
    private static int applyGroupMemberGate(CommandSourceStack source, String parentRaw, String memberRaw,
                                            java.util.function.UnaryOperator<TemplateGate> op) {
        CarriageContents parent = parseContents(source, parentRaw);
        if (parent == null) return 0;
        CarriageContents member = parseContents(source, memberRaw);
        if (member == null) return 0;
        java.util.Optional<CarriageContentsGroup> existing = CarriageContentsGroupStore.get(parent.id());
        if (existing.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_contents_group_defined", parent.id())
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        java.util.Optional<CarriageContentsGroup.Member> mOpt = existing.get().member(member.id());
        if (mOpt.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_member_group", member.id(), parent.id()).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        try {
            CarriageContentsGroup.Member m = mOpt.get();
            // Editing the inline gate detaches all Stage links (Custom). Snapshot the first linked
            // Stage's gate as the base so the band survives (single-link members behave exactly as
            // before); a multi-link union has no single gate to snapshot, so the first wins.
            String baseStage = m.stageIds().isEmpty() ? null : m.stageIds().get(0);
            TemplateGate current = games.brennan.dungeontrain.editor.StageStore.effectiveGate(m.gate(), baseStage);
            TemplateGate next = op.apply(current);
            CarriageContentsGroup.Member updated = new CarriageContentsGroup.Member(
                m.id(), m.weight(), next, java.util.List.of());
            CarriageContentsGroupStore.save(parent.id(), existing.get().withMember(updated));
            // The member was just detached to Custom above (by design), so it is unlinked now.
            gateSuccess(source, parent.id() + ":" + member.id(), next,
                CarriageContentsGroupStore.fileForId(parent.id()).toString(), null);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "contents group", parent.id() + ":" + member.id(), t);
        }
    }

    /**
     * Edit a group member's Stage links. Unlike top-level templates and parts, a sub-variant member
     * may link to more than one Stage (the union of their gates applies), so this <b>toggles</b>:
     * a stage id that is already linked is removed, otherwise it is added. The {@code custom}/blank
     * token clears <b>all</b> links; when exactly one Stage was linked its gate is snapshotted inline
     * so the band survives as a Custom gate (matching the pre-multi single-link detach behaviour) —
     * a two-or-more union has no single gate to snapshot, so it simply drops the links and keeps the
     * existing inline gate.
     */
    private static int applyGroupMemberStage(CommandSourceStack source, String parentRaw, String memberRaw, String stageToken) {
        CarriageContents parent = parseContents(source, parentRaw);
        if (parent == null) return 0;
        CarriageContents member = parseContents(source, memberRaw);
        if (member == null) return 0;
        java.util.Optional<CarriageContentsGroup> existing = CarriageContentsGroupStore.get(parent.id());
        if (existing.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_contents_group_defined", parent.id())
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        java.util.Optional<CarriageContentsGroup.Member> mOpt = existing.get().member(member.id());
        if (mOpt.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_member_group", member.id(), parent.id()).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        String link = resolveStageLink(source, stageToken);  // null = custom/clear, id = toggle, INVALID = reported
        if (link == INVALID_STAGE) return 0;
        try {
            CarriageContentsGroup.Member m = mOpt.get();
            CarriageContentsGroup.Member updated;
            if (link == null) {
                TemplateGate inline = m.gate();
                if (m.stageIds().size() == 1) {
                    inline = games.brennan.dungeontrain.editor.StageStore.effectiveGate(inline, m.stageIds().get(0));
                }
                updated = new CarriageContentsGroup.Member(m.id(), m.weight(), inline, java.util.List.of());
            } else {
                updated = m.withStageToggled(link);
            }
            CarriageContentsGroupStore.save(parent.id(), existing.get().withMember(updated));
            groupMemberStageApplySuccess(source, parent.id() + ":" + member.id(), updated.stageIds());
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "contents group stage", parent.id() + ":" + member.id(), t);
        }
    }

    /** Report the resulting Stage-link set after a contents group-member toggle / clear. */
    private static void groupMemberStageApplySuccess(CommandSourceStack source, String id, java.util.List<String> stageIds) {
        groupMemberStageApplySuccess(source, "contents group", id, stageIds);
    }

    /** As above, naming the template layer the member belongs to ({@code "contents group"}, {@code "portal_room group"}, …). */
    private static void groupMemberStageApplySuccess(CommandSourceStack source, String what, String id,
                                                     java.util.List<String> stageIds) {
        if (stageIds.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.detached_custom_no_stage", what, id).withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.stage", what, id, (stageIds.size() == 1 ? " '" + stageIds.get(0) + "'" : "s [" + String.join(", ", stageIds) + "]")).withStyle(ChatFormatting.GREEN), true);
        }
    }

    // ---- Brigadier subtree builders (contents group member: parent + child) ----

    private static LiteralArgumentBuilder<CommandSourceStack> minLevelGroup() {
        return Commands.literal("minlevel")
            .then(Commands.argument("parent", StringArgumentType.word()).suggests(CONTENTS_SUGGESTIONS)
                .then(Commands.argument("child", StringArgumentType.word()).suggests(CONTENTS_SUGGESTIONS)
                    .then(Commands.literal("inc").executes(c -> applyGroupMemberGate(c.getSource(),
                        StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                        g -> g.withMinLevel(g.minLevel() + 1))))
                    .then(Commands.literal("dec").executes(c -> applyGroupMemberGate(c.getSource(),
                        StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                        g -> g.withMinLevel(g.minLevel() - 1))))
                    .then(Commands.argument("value", IntegerArgumentType.integer(0, TemplateGate.MAX_LEVEL))
                        .executes(c -> applyGroupMemberGate(c.getSource(),
                            StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                            g -> g.withMinLevel(IntegerArgumentType.getInteger(c, "value")))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> maxLevelGroup() {
        return Commands.literal("maxlevel")
            .then(Commands.argument("parent", StringArgumentType.word()).suggests(CONTENTS_SUGGESTIONS)
                .then(Commands.argument("child", StringArgumentType.word()).suggests(CONTENTS_SUGGESTIONS)
                    .then(Commands.literal("inc").executes(c -> applyGroupMemberGate(c.getSource(),
                        StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                        EditorCommand::maxLevelInc)))
                    .then(Commands.literal("dec").executes(c -> applyGroupMemberGate(c.getSource(),
                        StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                        EditorCommand::maxLevelDec)))
                    .then(Commands.argument("value", IntegerArgumentType.integer(TemplateGate.ALL, TemplateGate.MAX_LEVEL))
                        .executes(c -> applyGroupMemberGate(c.getSource(),
                            StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                            g -> g.withMaxLevel(IntegerArgumentType.getInteger(c, "value")))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> phaseGroup() {
        return Commands.literal("phase")
            .then(Commands.argument("parent", StringArgumentType.word()).suggests(CONTENTS_SUGGESTIONS)
                .then(Commands.argument("child", StringArgumentType.word()).suggests(CONTENTS_SUGGESTIONS)
                    .then(Commands.argument("phase", StringArgumentType.word()).suggests(PHASE_SUGGESTIONS)
                        .then(Commands.literal("on").executes(c -> applyGroupMemberGate(c.getSource(),
                            StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                            g -> togglePhase(g, StringArgumentType.getString(c, "phase"), true))))
                        .then(Commands.literal("off").executes(c -> applyGroupMemberGate(c.getSource(),
                            StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                            g -> togglePhase(g, StringArgumentType.getString(c, "phase"), false))))
                        .then(Commands.literal("others").executes(c -> applyGroupMemberGate(c.getSource(),
                            StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                            g -> toggleOtherPhases(g, StringArgumentType.getString(c, "phase"))))))));
    }

    /**
     * {@code /dt editor contents group remove <parent> <child>} — remove a
     * member from the parent's group. If the resulting member list is empty,
     * delete the group sidecar so the parent reverts to a normal leaf.
     */
    private static int runContentsGroupRemove(CommandSourceStack source, String parentRaw, String childRaw) {
        String parentId = parentRaw.toLowerCase(Locale.ROOT);
        String childId = childRaw.toLowerCase(Locale.ROOT);
        java.util.Optional<CarriageContentsGroup> existing = CarriageContentsGroupStore.get(parentId);
        if (existing.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_contents_group_defined", parentId).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        CarriageContentsGroup updated = existing.get().withoutMember(childId);
        if (updated.members().size() == existing.get().members().size()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.group_has_no_member", parentId, childId).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        try {
            if (updated.members().isEmpty()) {
                CarriageContentsGroupStore.delete(parentId);
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.group_removed_last_member", parentId, childId).withStyle(ChatFormatting.GREEN), true);
            } else {
                CarriageContentsGroupStore.save(parentId, updated);
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.group_removed_member_remaining", parentId, childId, updated.members().size(), Component.translatable(updated.members().size() == 1 ? "chat.dungeontrain.common.noun.member.singular" : "chat.dungeontrain.common.noun.member.plural")).withStyle(ChatFormatting.GREEN), true);
            }
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor contents group remove failed", e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.group_remove_failed", e.toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * {@code /dt editor contents group move <child> <new_parent>} — re-parent a sub-variant in one
     * step, carrying its weight, gate and Stage links. Refusals are {@code group add}'s, plus the
     * built-in-parent rule that command enforces.
     */
    private static int runContentsGroupMove(CommandSourceStack source, String childRaw, String newParentRaw) {
        CarriageContents child = parseContents(source, childRaw);
        if (child == null) return 0;
        CarriageContents newParent = parseContents(source, newParentRaw);
        if (newParent == null) return 0;
        if (newParent.isBuiltin()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.built_contents_cannot_be", newParent.id()).withStyle(ChatFormatting.RED));
            return 0;
        }
        java.util.Optional<String> currentParent = CarriageContentsGroupStore.findParentOf(child.id());
        if (currentParent.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.top_level_use_group", child.id(), newParent.id(), child.id())
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        String oldParent = currentParent.get();
        games.brennan.dungeontrain.editor.VariantGroupMoves.ContentsMove move =
            games.brennan.dungeontrain.editor.VariantGroupMoves.move(
                oldParent, CarriageContentsGroupStore.get(oldParent).orElse(null),
                newParent.id(), CarriageContentsGroupStore.get(newParent.id()),
                child.id(),
                CarriageContentsGroupStore.allChildIds().contains(newParent.id()),
                CarriageContentsGroupStore.exists(child.id()));
        if (!move.ok()) {
            source.sendFailure(EditorLabelCommands.moveRefusal(
                move.refusal(), child.id(), oldParent, newParent.id(), Component.translatable("chat.dungeontrain.editor.what_contents"))
                .copy().withStyle(ChatFormatting.RED));
            return 0;
        }
        progress(source, Component.translatable("chat.dungeontrain.editor.progress_moving", child.id(), oldParent, newParent.id()));
        try {
            if (move.from().members().isEmpty()) {
                CarriageContentsGroupStore.delete(oldParent);
            } else {
                CarriageContentsGroupStore.save(oldParent, move.from());
            }
            CarriageContentsGroupStore.save(newParent.id(), move.to());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor contents group move failed", e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.group_move_failed", e.toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        Component landed = landInContents(source, child.id());
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.moved_from_group_weight", child.id(), oldParent, newParent.id(), landed).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** {@code /dt editor contents group list <parent>} — print members + weights. */
    private static int runContentsGroupList(CommandSourceStack source, String parentRaw) {
        String parentId = parentRaw.toLowerCase(Locale.ROOT);
        java.util.Optional<CarriageContentsGroup> opt = CarriageContentsGroupStore.get(parentId);
        if (opt.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.not_contents_group_no", parentId), false);
            return 1;
        }
        CarriageContentsGroup group = opt.get();
        if (group.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.group_has_no_members", parentId).withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.group_members", parentId, group.members().size()), false);
        for (CarriageContentsGroup.Member m : group.members()) {
            boolean resolved = CarriageContentsRegistry.find(m.id()).isPresent();
            String suffix = resolved ? "" : " (UNKNOWN — will be skipped)";
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.weight", m.id(), m.weight() + suffix).withStyle(resolved ? ChatFormatting.GRAY : ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    /**
     * {@code /dt editor contents group new <parent> <name> [source]} — atomic
     * create + add-to-group + teleport. Used by the editor's sub-variant
     * menu "+ New" button: one click → one keyboard prompt → fully wired
     * sub-variant ready to author.
     *
     * <p>{@code source} is {@code blank} for an empty template, or the id of a contents to clone.
     * Omitted, it clones {@code parent}: a sub-variant is a variation on its parent, so starting
     * from an empty box is the wrong default — and for something like the portal corridor, whose
     * interior is hundreds of blocks of authored geometry, close to useless.</p>
     */
    private static int runContentsGroupNew(CommandSourceStack source, String parentRaw, String rawName,
                                           String sourceRaw) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        // 1. Validate parent (registered, not built-in, not a member of another group).
        CarriageContents parent = parseContents(source, parentRaw);
        if (parent == null) return 0;
        if (parent.isBuiltin()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.built_contents_cannot_be", parent.id()).withStyle(ChatFormatting.RED));
            return 0;
        }
        if (CarriageContentsGroupStore.allChildIds().contains(parent.id())) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.already_member_another_group", parent.id()).withStyle(ChatFormatting.RED));
            return 0;
        }

        // 2. Validate name.
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!CarriageContents.NAME_PATTERN.matcher(name).matches()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.invalid_name_use_lowercase", rawName));
            return 0;
        }
        if (CarriageContents.isReservedBuiltinName(name)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_reserved_built", name));
            return 0;
        }
        if (CarriageContentsRegistry.find(name).isPresent()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_already_taken", name));
            return 0;
        }

        // 2b. Resolve the source to clone from. Omitted means the parent; "blank" means none.
        boolean blank = "blank".equalsIgnoreCase(sourceRaw);
        CarriageContents cloneFrom = null;
        if (!blank) {
            cloneFrom = (sourceRaw == null) ? parent : parseContents(source, sourceRaw);
            if (cloneFrom == null) return 0;
        }

        try {
            // 3. Create the contents and register it — cloned from the source, or blank.
            //    Either way it is sized as the PARENT: the target is not a group member yet, so
            //    asking for its own box would still answer "carriage" and capture a template the
            //    size gate then rejects on every load.
            CarriageContents.Custom target = (CarriageContents.Custom) CarriageContents.custom(name);
            var origin = blank
                ? CarriageContentsEditor.createBlank(player, target, parent)
                : CarriageContentsEditor.duplicate(player, cloneFrom, target);

            // 4. Append to parent's group (creates the group sidecar if missing).
            CarriageContentsGroup existing = CarriageContentsGroupStore.get(parent.id())
                .orElse(CarriageContentsGroup.EMPTY);
            CarriageContentsGroup updated = existing.withMember(
                new CarriageContentsGroup.Member(target.id(), CarriageContentsGroup.DEFAULT_WEIGHT));
            CarriageContentsGroupStore.save(parent.id(), updated);

            // 5. Teleport into the new plot (now positioned adjacent to parent
            // because the plot layout is flattened-by-group).
            CarriageContentsEditor.enter(player, target, null);

            String from = blank ? "blank" : "cloned from '" + cloneFrom.id() + "'";
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.created_sub_variant_plot", target.id(), parent.id(), from, origin.toShortString(), updated.members().size(), Component.translatable(updated.members().size() == 1 ? "chat.dungeontrain.common.noun.member.singular" : "chat.dungeontrain.common.noun.member.plural")).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents group new failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.group_new_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /** {@code /dt editor contents group clear <parent>} — delete the group sidecar. */
    private static int runContentsGroupClear(CommandSourceStack source, String parentRaw) {
        String parentId = parentRaw.toLowerCase(Locale.ROOT);
        if (!CarriageContentsGroupStore.exists(parentId)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_contents_group_defined", parentId).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        try {
            boolean removed = CarriageContentsGroupStore.delete(parentId);
            if (removed) {
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.cleared_contents_group_parent", parentId).withStyle(ChatFormatting.GREEN), true);
            } else {
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.had_only_bundled_group", parentId).withStyle(ChatFormatting.YELLOW), true);
            }
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor contents group clear failed", e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.group_clear_failed", e.toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Silent variant of {@link #parseTrackKind} for use in suggestion
     * providers (which run mid-typing and shouldn't spam the chat).
     * Accepts both editor-model ids and TrackKind canonical ids; returns
     * null on miss.
     */
    private static games.brennan.dungeontrain.track.variant.TrackKind resolveTrackKindSilent(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String lc = raw.toLowerCase(Locale.ROOT);
        switch (lc) {
            case "track" -> { return games.brennan.dungeontrain.track.variant.TrackKind.TILE; }
            case "pillar_top" -> { return games.brennan.dungeontrain.track.variant.TrackKind.PILLAR_TOP; }
            case "pillar_middle" -> { return games.brennan.dungeontrain.track.variant.TrackKind.PILLAR_MIDDLE; }
            case "pillar_bottom" -> { return games.brennan.dungeontrain.track.variant.TrackKind.PILLAR_BOTTOM; }
            case "adjunct_stairs" -> { return games.brennan.dungeontrain.track.variant.TrackKind.ADJUNCT_STAIRS; }
            case "tunnel_section" -> { return games.brennan.dungeontrain.track.variant.TrackKind.TUNNEL_SECTION; }
            case "tunnel_portal" -> { return games.brennan.dungeontrain.track.variant.TrackKind.TUNNEL_PORTAL; }
            default -> {}
        }
        return games.brennan.dungeontrain.track.variant.TrackKind.fromId(lc);
    }

    /** Silent parse — returns null on miss, used by unified pillar target dispatch. */
    private static PillarSection tryParseSection(String raw) {
        try {
            return PillarSection.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Silent parse — returns null on miss, used by unified pillar target dispatch. */
    private static PillarAdjunct tryParseAdjunct(String raw) {
        try {
            return PillarAdjunct.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Comma-separated list of valid pillar target names for error messages. */
    private static String pillarTargetList() {
        StringBuilder sb = new StringBuilder();
        for (PillarSection s : PillarSection.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.id());
        }
        for (PillarAdjunct a : PillarAdjunct.values()) {
            sb.append(", ").append(a.id());
        }
        return sb.toString();
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
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_plot_use_dungeontrain"));
            return null;
        }
        HitResult hit = player.pick(8.0, 1.0f, false);
        if (!(hit instanceof BlockHitResult bhr) || bhr.getType() == HitResult.Type.MISS) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.look_directly_block_inside"));
            return null;
        }
        BlockPos plotOrigin = CarriageEditor.plotOrigin(plotVariant, dims);
        if (plotOrigin == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.plot_origin_missing", plotVariant.id()));
            return null;
        }
        // Bounds-checked against this variant's own plot box — the portal corridor's is longer than
        // a carriage, and a dims-sized check would refuse every block past x=8 in it.
        CarriageDims box = CarriageEditor.plotDims(plotVariant, dims);
        BlockPos local = bhr.getBlockPos().subtract(plotOrigin);
        if (local.getX() < 0 || local.getX() >= box.length()
            || local.getY() < 0 || local.getY() >= box.height()
            || local.getZ() < 0 || local.getZ() >= box.width()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.target_block_outside_plot", local.toShortString(), box.length(), box.height(), box.width()));
            return null;
        }
        return new VariantTarget(plotVariant, local, box);
    }

    private static int runVariantClear(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ServerLevel level = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        // Part plot first — same priority as the shift-click capture path so
        // an author standing in a part plot can clear the part's own sidecar.
        CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(player.blockPosition(), dims);
        if (partLoc != null) {
            return runVariantClearPart(source, player, dims, partLoc);
        }

        CarriageContents contentsPlot = CarriageContentsEditor.plotContaining(player.blockPosition(), dims);
        if (contentsPlot != null) {
            return runVariantClearContents(source, player, dims, contentsPlot);
        }

        VariantTarget target = resolveTarget(source, player);
        if (target == null) return 0;

        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(target.variant(), target.dims());
        boolean removed = sidecar.remove(target.localPos());
        final String pos = target.localPos().getX() + "," + target.localPos().getY() + "," + target.localPos().getZ();
        if (removed) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.cleared_variant_local_run", pos, target.variant().id()).withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.no_variant_local_clear", pos), false);
        }
        return removed ? 1 : 0;
    }

    private static int runVariantClearPart(CommandSourceStack source, ServerPlayer player,
                                            CarriageDims dims, CarriagePartEditor.PlotLocation partLoc) {
        BlockPos hit = lookedAtBlock(source, player);
        if (hit == null) return 0;
        BlockPos plotOrigin = CarriagePartEditor.plotOrigin(
            new games.brennan.dungeontrain.template.CarriagePartTemplateId(partLoc.kind(), partLoc.name()), dims);
        if (plotOrigin == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.plot_origin_missing_part", partLoc.kind().id(), partLoc.name()));
            return 0;
        }
        Vec3i partSize = partLoc.kind().dims(dims);
        BlockPos local = hit.subtract(plotOrigin);
        if (!inBounds(local, partSize)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.target_block_outside_part", local.toShortString()));
            return 0;
        }
        CarriagePartVariantBlocks sidecar = CarriagePartVariantBlocks.loadFor(
            partLoc.kind(), partLoc.name(), partSize);
        boolean removed = sidecar.remove(local);
        if (removed) {
            try {
                sidecar.save(partLoc.kind(), partLoc.name());
            } catch (IOException e) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.variant_save_failed", e.getMessage()));
                return 0;
            }
        }
        final String pos = local.getX() + "," + local.getY() + "," + local.getZ();
        final String label = partLoc.kind().id() + ":" + partLoc.name();
        if (removed) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.cleared_variant_local_part", pos, label).withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.no_variant_local_clear_2", pos, label), false);
        }
        return removed ? 1 : 0;
    }

    private static int runVariantClearContents(CommandSourceStack source, ServerPlayer player,
                                                 CarriageDims dims, CarriageContents contentsPlot) {
        BlockPos hit = lookedAtBlock(source, player);
        if (hit == null) return 0;
        BlockPos carriageOrigin = CarriageContentsEditor.plotOrigin(contentsPlot, dims);
        if (carriageOrigin == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.plot_origin_missing_contents", contentsPlot.id()));
            return 0;
        }
        BlockPos interiorOrigin = carriageOrigin.offset(1, 1, 1);
        Vec3i interiorSize = CarriageContentsPlacer.interiorSizeFor(contentsPlot, dims);
        BlockPos local = hit.subtract(interiorOrigin);
        if (!inBounds(local, interiorSize)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.target_block_outside_interior", local.toShortString()));
            return 0;
        }
        CarriageContentsVariantBlocks sidecar = CarriageContentsVariantBlocks.loadFor(contentsPlot, interiorSize);
        boolean removed = sidecar.remove(local);
        if (removed) {
            try {
                sidecar.save(contentsPlot);
            } catch (IOException e) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.variant_save_failed", e.getMessage()));
                return 0;
            }
        }
        final String pos = local.getX() + "," + local.getY() + "," + local.getZ();
        if (removed) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.cleared_variant_local_contents", pos, contentsPlot.id()).withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.no_variant_local_clear_3", pos, contentsPlot.id()), false);
        }
        return removed ? 1 : 0;
    }

    private static int runVariantList(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ServerLevel level = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(player.blockPosition(), dims);
        if (partLoc != null) {
            Vec3i partSize = partLoc.kind().dims(dims);
            CarriagePartVariantBlocks sidecar = CarriagePartVariantBlocks.loadFor(
                partLoc.kind(), partLoc.name(), partSize);
            sendVariantsListing(source,
                "part '" + partLoc.kind().id() + ":" + partLoc.name() + "'",
                sidecar.entries(), sidecar.isEmpty(), sidecar.size());
            return 1;
        }

        CarriageContents contentsPlot = CarriageContentsEditor.plotContaining(player.blockPosition(), dims);
        if (contentsPlot != null) {
            Vec3i interiorSize = CarriageContentsPlacer.interiorSizeFor(contentsPlot, dims);
            CarriageContentsVariantBlocks sidecar = CarriageContentsVariantBlocks.loadFor(contentsPlot, interiorSize);
            sendVariantsListing(source,
                "contents '" + contentsPlot.id() + "'",
                sidecar.entries(), sidecar.isEmpty(), sidecar.size());
            return 1;
        }

        CarriageVariant plotVariant = CarriageEditor.plotContaining(player.blockPosition(), dims);
        if (plotVariant == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_plot_use_dungeontrain"));
            return 0;
        }
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(
            plotVariant, CarriageEditor.plotDims(plotVariant, dims));
        sendVariantsListing(source,
            "'" + plotVariant.id() + "'",
            sidecar.entries(), sidecar.isEmpty(), sidecar.size());
        return 1;
    }

    private static void sendVariantsListing(CommandSourceStack source, String label,
                                             List<CarriageVariantBlocks.Entry> entries,
                                             boolean isEmpty, int size) {
        if (isEmpty) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.variants_none", label), false);
            return;
        }
        StringBuilder sb = new StringBuilder("Variants for ").append(label).append(" (")
            .append(size).append(" entries):");
        for (CarriageVariantBlocks.Entry e : entries) {
            sb.append("\n  ").append(e.localPos().getX()).append(",")
                .append(e.localPos().getY()).append(",").append(e.localPos().getZ())
                .append(" → ");
            boolean first = true;
            for (VariantState s : e.states()) {
                if (!first) sb.append(", ");
                sb.append(BuiltInRegistries.BLOCK.getKey(s.state().getBlock()));
                if (s.hasBlockEntityData()) sb.append(" (+nbt)");
                first = false;
            }
        }
        final String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
    }

    /** Raycast helper for the part/contents clear paths — sends a failure and returns null if no hit. */
    private static BlockPos lookedAtBlock(CommandSourceStack source, ServerPlayer player) {
        HitResult hit = player.pick(8.0, 1.0f, false);
        if (!(hit instanceof BlockHitResult bhr) || bhr.getType() == HitResult.Type.MISS) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.look_directly_block_inside"));
            return null;
        }
        return bhr.getBlockPos();
    }

    private static boolean inBounds(BlockPos local, Vec3i size) {
        return local.getX() >= 0 && local.getX() < size.getX()
            && local.getY() >= 0 && local.getY() < size.getY()
            && local.getZ() >= 0 && local.getZ() < size.getZ();
    }

    private static int runStrayGhosts(CommandSourceStack source, boolean on) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        EditorStrayBlocks.setEnabled(player.getUUID(), on);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.out_plot_ghosts", Component.translatable(on ? "chat.dungeontrain.common.on_caps" : "chat.dungeontrain.common.off")), false);
        return 1;
    }

    private static int runDoorGhosts(CommandSourceStack source, boolean on) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        EditorDoorGhosts.setEnabled(player.getUUID(), on);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.door_labels", Component.translatable(on ? "chat.dungeontrain.common.on_caps" : "chat.dungeontrain.common.off")), false);
        return 1;
    }

    private static int runVariantOverlay(CommandSourceStack source, boolean on) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        VariantOverlayRenderer.setEnabled(player, on);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.overlay", Component.translatable(on ? "chat.dungeontrain.common.on_caps" : "chat.dungeontrain.common.off")), false);
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
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_tunnel_variant_valid", raw));
            return null;
        }
    }

    private static CarriageVariant parseVariant(CommandSourceStack source, String raw) {
        String id = raw.toLowerCase(Locale.ROOT);
        return CarriageVariantRegistry.find(id).orElseGet(() -> {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_variant_valid", raw, listIds()));
            return null;
        });
    }

    /** Parse a built-in enum name for commands that only accept built-ins (promote). */
    private static CarriageType parseBuiltin(CommandSourceStack source, String raw) {
        try {
            return CarriageType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_built_valid_standard", raw));
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
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return null;
        }
    }

    /**
     * Fire the "entered editor" advancement trigger and, the first time per play
     * session, show the editor welcome message in chat.
     */
    private static void markEnteredEditor(ServerPlayer player) {
        games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
            .trigger(player, "entered_editor");
        EditorWelcome.showOnEnter(player);
    }

    /**
     * Make {@code category} the resident one before a single-model enter — every category lays out
     * from the same origin, so entering one model of another category on top of the resident one
     * would stamp it into somebody else's plot. A different resident (or none) means the full
     * category entry runs first: landing plot now, the rest queued, the old category erased. The
     * same category is a no-op.
     *
     * @return false when the category entry failed and the caller should stop
     */
    private static boolean ensureCategory(CommandSourceStack source, EditorCategory category) {
        if (EditorStampedCategoryState.isActive(category)) return true;
        return runEnterCategory(source, category) != 0;
    }

    /**
     * Category-level enter: stamp every plot in {@code category} so the player
     * can walk between all of them, then teleport them to the first model.
     * Architecture has no models yet and returns a "coming soon" message.
     */
    /** Package seam for {@link WholeEditorCommand}: the same category entry every bar button runs. */
    static int enterCategory(CommandSourceStack source, EditorCategory category) {
        return runEnterCategory(source, category);
    }

    /** Package seam for {@link WholeEditorCommand}: make {@code category} resident before an enter. */
    static boolean ensureCategoryResident(CommandSourceStack source, EditorCategory category) {
        return ensureCategory(source, category);
    }

    static ServerPlayer playerOrNull(CommandSourceStack source) {
        return requirePlayer(source);
    }

    private static int runEnterCategory(CommandSourceStack source, EditorCategory category) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        markEnteredEditor(player);

        if (category == EditorCategory.ARCHITECTURE) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.architecture_coming_soon_walls").withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }

        java.util.Optional<Template> first = category.firstModel();
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        if (first.isEmpty() && category == EditorCategory.WHOLE) {
            // The Whole pool starts empty on a fresh install; the section still has to open so the
            // player can load a build into it. Erase whatever was resident and land at the row origin.
            List<EditorStampQueue.Job> erases = EditorCategory.clearAllPlotJobs(overworld, dims, category);
            EditorStampedCategoryState.set(overworld, category);
            for (EditorStampQueue.Job job : erases) job.work().run();
            EditorCategory.layerSweepJob(overworld, dims, null).work().run();
            net.minecraft.core.BlockPos origin = games.brennan.dungeontrain.editor.WholeCarriageEditor
                .rowOrigin(games.brennan.dungeontrain.train.WholeKind.ROOM, dims);
            games.brennan.dungeontrain.editor.EditorPlotArrival.land(player, overworld, origin,
                new net.minecraft.core.Vec3i(dims.length(), dims.height(), dims.width()), true,
                games.brennan.dungeontrain.editor.EditorPlotArrival.Inside.FRONT_DOOR, null);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.whole_empty"), true);
            return 1;
        }
        if (first.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.category_has_no_models", Component.translatable("gui.dungeontrain.editor_menu.hud.category." + category.id())));
            return 0;
        }

        // Only the plot the player lands on is stamped here, on this tick. Everything else — the
        // erase of the previous category's plots and the stamp of every other plot in this one — is
        // queued on EditorStampQueue and spread across the ticks that follow. Stamping it all inline
        // held the server thread for as long as the whole category took (nine minutes for Portals
        // on a slow laptop, in a player's log), with the client sat in an empty world and every
        // extra "Editor" press queuing another full pass behind the first.
        //
        // The state half of the clear (labels, strays, undo history, the previous queue) happens
        // now; the entering category's own erases are left out because every stamp erases its own
        // footprint first.
        List<EditorStampQueue.Job> erases = EditorCategory.clearAllPlotJobs(overworld, dims, category);

        Template head = first.get();
        // Remember which category is resident so VariantOverlayRenderer can keep the floating plot
        // labels visible for as long as the structures themselves are present — not just while the
        // player is standing inside a cage. Set before anything is stamped on purpose: every
        // category lays out from the same origin, so no plot answers to a position until its
        // category is the resident one, and the labels are registry-driven, so they appear over
        // every plot at once and show the fill's progress.
        EditorStampedCategoryState.set(overworld, category);

        // Every category shares the origin, so the previous category's plot under the landing spot
        // has to go before the landing plot is stamped — queued behind it, the erase would wipe the
        // plot out from under the player. Those few run now; the rest wait their turn.
        net.minecraft.world.level.levelgen.structure.BoundingBox headBox =
            EditorCategory.plotBoxOf(overworld, head, dims);
        EditorStampQueue.Partition split = EditorStampQueue.partitionOverlapping(erases, headBox);
        List<EditorStampQueue.Job> queued = new ArrayList<>(split.rest());
        try {
            for (EditorStampQueue.Job job : split.overlapping()) {
                LOGGER.info("[DungeonTrain] Editor: '{}' overlaps the landing plot — erasing it first", job.label());
                job.work().run();
            }
            // The landing plot, stamped now so the teleport puts the player on something real.
            stampCategoryModel(overworld, head, dims);
            enterFirstModel(player, head);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor enter-category failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.enter_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
        // Whatever the per-plot erases could not predict, swept once they are done and before the
        // fill — around the landing plot, which is already standing.
        if (!erases.isEmpty()) {
            queued.add(EditorCategory.layerSweepJob(overworld, dims, headBox));
        }

        for (Template model : category.models()) {
            if (model.equals(head) || model instanceof Template.Part) continue;   // parts: below
            queued.add(new EditorStampQueue.Job("stamp " + model.displayName(),
                () -> stampCategoryModel(overworld, model, dims)));
        }
        // CARRIAGES also paints the parts grid — floor / walls / roof / doors
        // templates laid out on new Z rows past the carriage plots so every
        // authorable part is visible at a glance inside the category.
        if (category == EditorCategory.CARRIAGES) {
            queued.addAll(CarriagePartEditor.stampAllPlotJobs(overworld, dims));
        }
        // DIMENSIONS likewise paints the chunk frame plots beside its rooms — what a dimensional
        // carriage can be dressed in.
        if (category == EditorCategory.PORTALS) {
            queued.addAll(games.brennan.dungeontrain.editor.ChunkFrameEditor.stampAllPlotJobs(overworld));
        }
        EditorStampQueue.start(queued, category.id());

        final int pending = queued.size();
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.entered", Component.translatable("gui.dungeontrain.editor_menu.hud.category." + category.id()), head.displayName(), (pending > 0 ? Component.translatable("chat.dungeontrain.editor.entered_pending") : Component.empty())), true);
        return 1;
    }

    /** Teleport onto {@code head}'s plot via its editor's enter path, without restamping it. */
    private static void enterFirstModel(ServerPlayer player, Template head) {
        if (head instanceof Template.WholeCarriage || head instanceof Template.CarriageGroup) {
            games.brennan.dungeontrain.editor.WholeCarriageEditor.enter(player, head, true, false,
                games.brennan.dungeontrain.editor.EditorPlotArrival.Inside.FRONT_DOOR);
        } else if (head instanceof Template.Carriage cm) {
            CarriageEditor.enter(player, cm.variant(), true, false);
        } else if (head instanceof Template.Contents cm) {
            CarriageContentsEditor.enter(player, cm.contents(), null, true, false);
        } else if (head instanceof Template.Pillar pm) {
            PillarEditor.enter(player, pm.section(), true, false);
        } else if (head instanceof Template.Adjunct am) {
            PillarEditor.enter(player, am.adjunct(), true, false);
        } else if (head instanceof Template.Tunnel tm) {
            TunnelEditor.enter(player, tm.variant(), true, false);
        } else if (head instanceof Template.Track) {
            TrackEditor.enter(player, true, false);
        } else if (head instanceof Template.PortalRoom rm) {
            games.brennan.dungeontrain.editor.PortalRoomEditor.enter(player, rm.name(), true, false);
        }
    }

    private static void stampCategoryModel(ServerLevel overworld, Template model, CarriageDims dims) {
        if (model instanceof Template.WholeCarriage || model instanceof Template.CarriageGroup) {
            games.brennan.dungeontrain.editor.WholeCarriageEditor.stampPlot(overworld, model, dims);
        } else if (model instanceof Template.Carriage cm) {
            CarriageEditor.stampPlot(overworld, cm.variant(), dims);
        } else if (model instanceof Template.Contents cm) {
            CarriageContentsEditor.stampPlot(overworld, cm.contents(), dims);
        } else if (model instanceof Template.Pillar pm) {
            PillarEditor.stampPlot(overworld, pm.section(), dims);
        } else if (model instanceof Template.Adjunct am) {
            PillarEditor.stampPlot(overworld, am.adjunct(), dims);
        } else if (model instanceof Template.Tunnel tm) {
            TunnelEditor.stampPlot(overworld, tm.variant());
        } else if (model instanceof Template.Track) {
            TrackEditor.stampPlot(overworld, dims);
        } else if (model instanceof Template.PortalRoom rm) {
            games.brennan.dungeontrain.editor.PortalRoomEditor.stampPlot(overworld, rm.name(), dims);
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

    /**
     * View-only teleport: drop the player into the named plot without
     * re-stamping or remembering a return position. Used by the worldspace
     * menu's unsaved-changes confirmation screen so the View button doesn't
     * destroy the in-world edits the user is being asked about.
     *
     * <p>Unlike {@link CarriageEditor#enter} / {@link CarriageContentsEditor#enter}
     * etc., this does not call {@code rememberReturn} — the player is already
     * inside the editor session ({@code runEnterCategory} sets up plots),
     * so a separate return-position stash isn't appropriate here.</p>
     */
    private static int runEditorView(CommandSourceStack source, String categoryId, String id) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        java.util.Optional<EditorCategory> categoryOpt = EditorCategory.fromId(categoryId);
        if (categoryOpt.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.unknown_category", categoryId));
            return 0;
        }
        EditorCategory category = categoryOpt.get();

        BlockPos origin = null;
        net.minecraft.core.Vec3i size = null;

        // Track-side ids use a "kind.name" format mirroring EditorDirtyCheck:
        //   "track.<name>"        — track tile variant
        //   "pillar_<sec>.<name>" — pillar section variant
        //   "adjunct_<id>.<name>" — pillar adjunct variant
        //   "tunnel_<v>.<name>"   — tunnel variant
        // Carriages and contents use the bare model id. Dot is used (not
        // colon) so the id parses cleanly as a single Brigadier word()
        // argument — colons aren't allowed in unquoted strings.
        if (category == EditorCategory.TRACKS && id.contains(".")) {
            int sep = id.indexOf('.');
            String prefix = id.substring(0, sep);
            String name = id.substring(sep + 1);
            if ("track".equals(prefix)) {
                origin = games.brennan.dungeontrain.editor.TrackSidePlots.plotOrigin(
                    games.brennan.dungeontrain.track.variant.TrackKind.TILE, name, dims);
                size = new net.minecraft.core.Vec3i(
                    games.brennan.dungeontrain.track.TrackPlacer.TILE_LENGTH,
                    games.brennan.dungeontrain.track.TrackPlacer.HEIGHT,
                    dims.width());
            } else if (prefix.startsWith("pillar_")) {
                games.brennan.dungeontrain.track.PillarSection sec = tryParseSection(prefix.substring("pillar_".length()));
                if (sec == null) {
                    source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_pillar_section", id));
                    return 0;
                }
                origin = PillarEditor.plotOrigin(new games.brennan.dungeontrain.template.PillarTemplateId(sec, name), dims);
                size = new net.minecraft.core.Vec3i(1, sec.height(), dims.width());
            } else if (prefix.startsWith("adjunct_")) {
                games.brennan.dungeontrain.track.PillarAdjunct adj = tryParseAdjunct(prefix.substring("adjunct_".length()));
                if (adj == null) {
                    source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_adjunct", id));
                    return 0;
                }
                origin = PillarEditor.plotOriginAdjunct(new games.brennan.dungeontrain.template.PillarAdjunctTemplateId(adj, name), dims);
                size = new net.minecraft.core.Vec3i(adj.xSize(), adj.ySize(), adj.zSize());
            } else if (prefix.startsWith("tunnel_")) {
                TunnelVariant tv;
                try {
                    tv = TunnelVariant.valueOf(prefix.substring("tunnel_".length()).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_tunnel_variant", id));
                    return 0;
                }
                origin = TunnelEditor.plotOrigin(new games.brennan.dungeontrain.template.TunnelTemplateId(tv, name));
                size = new net.minecraft.core.Vec3i(
                    games.brennan.dungeontrain.tunnel.TunnelPlacer.LENGTH,
                    games.brennan.dungeontrain.tunnel.TunnelPlacer.HEIGHT,
                    games.brennan.dungeontrain.tunnel.TunnelPlacer.WIDTH);
            } else {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.unrecognised_track_side_id", id));
                return 0;
            }
        } else if (category == EditorCategory.PORTALS) {
            // Same "kind.name" shape; one kind, so the prefix is always portal_room and a bare id
            // means the default room.
            String prefix = id.contains(".") ? id.substring(0, id.indexOf('.')) : id;
            String name = id.contains(".")
                ? id.substring(id.indexOf('.') + 1)
                : games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME;
            if (!games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM.id().equals(prefix)) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.unrecognised_portal_id", id));
                return 0;
            }
            origin = games.brennan.dungeontrain.editor.PortalRoomEditor.plotOrigin(name, dims);
            size = games.brennan.dungeontrain.editor.PortalRoomEditor.plotSize(name, dims);
        } else {
            // Resolve the model by id within the category and read its plot
            // footprint. The dispatch mirrors stampCategoryModel above —
            // each editor's plotOrigin signature differs.
            Template model = null;
            for (Template m : category.models()) {
                if (m.id().equals(id)) { model = m; break; }
            }
            if (model == null) {
                source.sendFailure(Component.translatable("chat.dungeontrain.save.unknown_model_category", id, Component.translatable("gui.dungeontrain.editor_menu.hud.category." + category.id())));
                return 0;
            }
            if (model instanceof Template.Carriage cm) {
                origin = CarriageEditor.plotOrigin(cm.variant(), dims);
                CarriageDims cmBox = CarriageEditor.plotDims(cm.variant(), dims);
                size = new net.minecraft.core.Vec3i(cmBox.length(), cmBox.height(), cmBox.width());
            } else if (model instanceof Template.Contents cm) {
                origin = CarriageContentsEditor.plotOrigin(cm.contents(), dims);
                size = new net.minecraft.core.Vec3i(dims.length(), dims.height(), dims.width());
            } else if (model instanceof Template.Pillar pm) {
                origin = PillarEditor.plotOrigin(pm.section(), dims);
                size = new net.minecraft.core.Vec3i(1, pm.section().height(), dims.width());
            } else if (model instanceof Template.Track) {
                origin = games.brennan.dungeontrain.editor.TrackSidePlots.plotOrigin(
                    games.brennan.dungeontrain.track.variant.TrackKind.TILE,
                    games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME, dims);
                size = new net.minecraft.core.Vec3i(
                    games.brennan.dungeontrain.track.TrackPlacer.TILE_LENGTH,
                    games.brennan.dungeontrain.track.TrackPlacer.HEIGHT,
                    dims.width());
            } else {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.view_not_supported_model", id));
                return 0;
            }
        }

        if (origin == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_plot_origin", id, Component.translatable("gui.dungeontrain.editor_menu.hud.category." + category.id())));
            return 0;
        }

        double tx = origin.getX() + size.getX() / 2.0;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + size.getZ() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());
        return 1;
    }

    private static int runEnterCarriage(CommandSourceStack source, CarriageVariant variant) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        if (!ensureCategory(source, EditorCategory.CARRIAGES)) return 0;
        markEnteredEditor(player);
        try {
            CarriageEditor.enter(player, variant);
            CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.entered_plot", variant.id(), CarriageEditor.plotOrigin(variant, dims).toShortString()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor enter failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.enter_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runEnterTunnel(CommandSourceStack source, TunnelVariant variant) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        if (!ensureCategory(source, EditorCategory.TRACKS)) return 0;
        markEnteredEditor(player);
        try {
            TunnelEditor.enter(player, variant);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.entered_plot", TUNNEL_PREFIX + variant.name().toLowerCase(Locale.ROOT), TunnelEditor.plotOrigin(variant).toShortString()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor enter (tunnel) failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.enter_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
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
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.tunnel_templates_don_t"));
                return 0;
            }
            try {
                TunnelEditor.save(player, tunnel);
                final TunnelVariant t = tunnel;
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.saved_template", TUNNEL_PREFIX + t.name().toLowerCase(Locale.ROOT)), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor save (tunnel) failed", t);
                source.sendFailure(Component.translatable("chat.dungeontrain.save.save_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();

        // Part plots take priority over the carriage plot — only the
        // per-player session knows which (kind, name) the player is editing,
        // so runPartSave reads it from CarriagePartEditor.currentSession.
        CarriagePartKind partKind = CarriagePartEditor.plotKindContaining(player.blockPosition(), dims);
        if (partKind != null) {
            return runPartSave(source, newName);
        }

        CarriageVariant current = CarriageEditor.plotContaining(player.blockPosition(), dims);
        if (current == null) {
            // Player is outside every plot — but if they've got an active
            // part editor session from a previous `/editor part enter` or
            // from a CARRIAGES-category plot they wandered out of, save the
            // session's part anyway (runPartSave uses plotContaining first
            // then falls back to the session).
            if (CarriagePartEditor.currentSession(player).isPresent()) {
                return runPartSave(source, newName);
            }
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_plot_stand_carriage"));
            return 0;
        }

        if (newName == null) {
            try {
                SaveResult result = CarriageEditor.save(player, current);
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.saved_template_config_dir", current.id()), true);
                if (result.sourceAttempted()) {
                    if (result.sourceWritten()) {
                        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.also_wrote_bundled_copy").withStyle(ChatFormatting.GREEN), true);
                    } else {
                        source.sendFailure(Component.translatable("chat.dungeontrain.editor.source_tree_write_failed", result.sourceError()).withStyle(ChatFormatting.YELLOW));
                    }
                }
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor save failed", t);
                source.sendFailure(Component.translatable("chat.dungeontrain.save.save_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        // Rename path.
        return renameCarriageTo(source, player, current, newName);
    }

    /**
     * Rename the named carriage, from anywhere in the carriages editor.
     *
     * <p>The X menu renames whatever is selected, which is rarely the plot the author happens to be
     * standing in. What it may never do is rename a template whose plots are not stamped: the
     * rename captures blocks from the plot, and a category that is not the stamped one has been
     * cleared, so the capture would write an empty template over a real one and delete the
     * original. Hence the guard — refuse, and say why, rather than quietly destroy the build.</p>
     */
    private static int runRenameCarriageById(CommandSourceStack source, String id, String newName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        if (!requireStamped(source, EditorCategory.CARRIAGES)) return 0;
        CarriageVariant current = CarriageVariantRegistry.find(id).orElse(null);
        if (current == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_carriage", id));
            return 0;
        }
        return renameCarriageTo(source, player, current, newName);
    }

    /** {@link #runRenameCarriageById} for a contents template. */
    private static int runRenameContentsById(CommandSourceStack source, String id, String newName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        if (!requireStamped(source, EditorCategory.CONTENTS)) return 0;
        CarriageContents current = CarriageContentsRegistry.find(id).orElse(null);
        if (current == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_contents", id));
            return 0;
        }
        return renameContentsTo(source, player, current, newName);
    }

    /**
     * True when {@code category}'s plots are the ones currently built into the world.
     *
     * <p>Every id-addressed edit that reads blocks back out of a plot needs this: the plots of any
     * other category were wiped by the last category switch.</p>
     */
    /**
     * Resize the train itself: how long, wide and tall every carriage in this world is.
     *
     * <p><b>This is a world setting, not a template's.</b> A carriage plot is not sized by the
     * build standing in it — every carriage, part and track shares one footprint, chosen when the
     * world was made. Changing it here changes all of them at once, and the plots must be stamped
     * again at the new size or the editor would keep showing cages that no longer match what is
     * inside them.</p>
     *
     * <p>The cost worth knowing about: every store filters what it loads against the world's
     * dimensions, so a template authored at the old size stops loading at the new one. It is still
     * on disk, and setting the size back brings it back — but it will be missing from the roster
     * meanwhile, which is why this says so rather than letting builds quietly disappear.</p>
     *
     * @param arg {@code dec} / {@code inc} to nudge by one, or a number to set outright
     */
    /** {@code editor size <axis> <dec|inc|N>} — one axis of the train's footprint. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> trainSizeNode(String axis) {
        return Commands.literal(axis)
            .then(Commands.argument("amount", StringArgumentType.word())
                .executes(ctx -> runTrainSize(ctx.getSource(), axis,
                    StringArgumentType.getString(ctx, "amount"))));
    }

    private static int runTrainSize(CommandSourceStack source, String axis, String arg) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ServerLevel overworld = source.getServer().overworld();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        CarriageDims dims = data.dims();

        int current = switch (axis) {
            case "length" -> dims.length();
            case "width" -> dims.width();
            default -> dims.height();
        };
        int next;
        if ("dec".equals(arg)) {
            next = current - 1;
        } else if ("inc".equals(arg)) {
            next = current + 1;
        } else {
            try {
                next = Integer.parseInt(arg.trim());
            } catch (NumberFormatException e) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_number", arg));
                return 0;
            }
        }

        CarriageDims updated;
        try {
            updated = switch (axis) {
                case "length" -> CarriageDims.clamp(next, dims.width(), dims.height());
                case "width" -> CarriageDims.clamp(dims.length(), next, dims.height());
                default -> CarriageDims.clamp(dims.length(), dims.width(), next);
            };
        } catch (RuntimeException e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.train_out_range", axis, next));
            return 0;
        }
        if (updated.length() == dims.length() && updated.width() == dims.width()
            && updated.height() == dims.height()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.train_already_it_cannot", axis, current));
            return 0;
        }

        data.apply(data.getTrainY(), data.startsWithTrain(), updated);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.train_now_every_carriage", updated.length(), updated.width(), updated.height()), true);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.templates_authored_old_size").withStyle(ChatFormatting.YELLOW), false);

        // The cages are still the old size until they are stamped again, and nothing else
        // re-measures them — so re-enter whichever category the author is in.
        EditorCategory stamped = EditorStampedCategoryState.current().orElse(null);
        return stamped == null ? 1 : runEnterCategory(source, stamped);
    }

    private static boolean requireStamped(CommandSourceStack source, EditorCategory category) {
        EditorCategory stamped = EditorStampedCategoryState.current().orElse(null);
        if (stamped == category) return true;
        source.sendFailure(Component.translatable("chat.dungeontrain.editor.switch_first_plots_are", Component.translatable("gui.dungeontrain.editor_menu.hud.category." + category.id()), Component.translatable("gui.dungeontrain.editor_menu.hud.category." + category.id())));
        return false;
    }

    /**
     * Rename {@code current} to {@code newName}, capturing its plot as it goes.
     *
     * <p>Split out of {@link #runSave} so the id-addressed rename can reuse it verbatim rather than
     * grow a second copy of these checks — the name rules and the reserved-name list are the sort
     * of thing that drifts the moment there are two of them.</p>
     */
    private static int renameCarriageTo(CommandSourceStack source, ServerPlayer player,
                                        CarriageVariant current, String newName) {
        if (PROTECTED_BUILTINS.contains(current.id())) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.cannot_rename_it_protected", current.id()));
            return 0;
        }
        String newId = newName.toLowerCase(Locale.ROOT);
        if (!CarriageVariant.NAME_PATTERN.matcher(newId).matches()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.invalid_name_use_lowercase", newName));
            return 0;
        }
        if (CarriageVariant.isReservedBuiltinName(newId)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_reserved_built", newId));
            return 0;
        }
        if (CarriageVariantRegistry.find(newId).isPresent()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_already_taken", newId));
            return 0;
        }
        try {
            CarriageVariant.Custom renamed = (CarriageVariant.Custom) CarriageVariant.custom(newId);
            CarriageEditor.saveAs(player, current, renamed);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.saved_and_renamed", current.id(), renamed.id()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor save-rename failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.save.save_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Open the template-blocks world-space menu on the plot the player is
     * standing in — lists every block used with counts and lets the player
     * swap a block (keeping orientation) with whatever is in hand. Primary,
     * discoverable trigger for the menu (a rebindable keybind also exists).
     */
    private static int runBlocks(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        games.brennan.dungeontrain.editor.TemplateBlocksMenuController.toggle(player, true);
        return 1;
    }

    private static int runExit(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        // Three editors keep their own session map — PortalRoomEditor, TunnelEditor and
        // CarriageEditor — and every one of them has to be in this chain or a player who entered
        // through it never gets their position, dimension and game mode back. Portal rooms were
        // missing here, so a direct `/dt editor portals` left the player stuck in the plot, in
        // creative, told there was "no saved editor session".
        //
        // Each session restores its OWN entry point, so stacked sessions unwind one press at a
        // time: a user who entered a carriage plot, then a tunnel or room plot, runs exit twice.
        boolean exited = games.brennan.dungeontrain.editor.PortalRoomEditor.exit(player)
            || TunnelEditor.exit(player)
            || CarriageEditor.exit(player);
        if (!exited) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_saved_session_nothing"));
            return 0;
        }
        // Clear every plot so the sky stays tidy for the next session.
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        EditorCategory.clearAllPlots(overworld, dims);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.exited_returned_previous_location"), true);
        return 1;
    }

    /**
     * The dirty scan as text: every plot the unsaved-changes screen would list, each with its
     * first few differing blocks. The screen is client-only, so this is how a headless test — or
     * an author who wants to know <i>why</i> the Save icon is pulsing — reads the same answer.
     */
    private static int runUnsaved(CommandSourceStack source) {
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        List<games.brennan.dungeontrain.editor.EditorDirtyCheck.DirtyEntry> rows =
            games.brennan.dungeontrain.editor.EditorDirtyCheck.findDirty(overworld, dims);
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.no_unsaved_changes"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("Unsaved editor changes:");
        for (games.brennan.dungeontrain.editor.EditorDirtyCheck.DirtyEntry row : rows) {
            sb.append("\n  ").append(row.categoryId()).append(" / ").append(row.modelId())
                .append(row.isUnsaved() ? " [unsaved]" : "")
                .append(row.isUnpromoted() ? " [unpromoted]" : "");
            if (!row.isUnsaved()) continue;
            List<games.brennan.dungeontrain.editor.EditorDirtyCheck.DiffEntry> diffs =
                games.brennan.dungeontrain.editor.EditorDirtyCheck.findChanges(
                    overworld, dims, row.categoryId(), row.modelId());
            int shown = 0;
            for (games.brennan.dungeontrain.editor.EditorDirtyCheck.DiffEntry d : diffs) {
                if (shown++ == UNSAVED_DIFFS_SHOWN) {
                    sb.append("\n      … ").append(diffs.size() - UNSAVED_DIFFS_SHOWN).append(" more");
                    break;
                }
                sb.append("\n      ").append(d.localPos().toShortString()).append(": ")
                    .append(d.expectedDescription()).append(" -> ").append(d.liveDescription());
            }
        }
        String text = sb.toString();
        LOGGER.info("[DungeonTrain] {}", text);
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /** Diff rows printed per plot by {@code /dt editor unsaved} before it elides the rest. */
    private static final int UNSAVED_DIFFS_SHOWN = 8;

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
                source.sendSuccess(() -> (deleted ? Component.translatable("chat.dungeontrain.editor.deleted_template", TUNNEL_PREFIX + tv.name().toLowerCase(Locale.ROOT)) : Component.translatable("chat.dungeontrain.editor.no_template_delete", TUNNEL_PREFIX + tv.name().toLowerCase(Locale.ROOT))), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor reset (tunnel) failed", t);
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.reset_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        CarriageVariant variant = parseVariant(source, raw);
        if (variant == null) return 0;
        try {
            ServerLevel overworld = source.getServer().overworld();
            CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

            // Capture the +X row state BEFORE registry mutation so the
            // restamp pass knows which positions are dirty (the deleted
            // variant's slot plus every slot to the right that just shifted
            // left by one).
            List<CarriageVariant> rowBefore = CarriageVariantRegistry.allVariants();
            int oldIdx = -1;
            for (int i = 0; i < rowBefore.size(); i++) {
                if (rowBefore.get(i).id().equals(variant.id())) { oldIdx = i; break; }
            }
            int oldCount = rowBefore.size();

            // Plot erase + row restamp are DT's own rewrites — guarded so observers in the
            // touched plots stay quiet (ObserverBlockStampMixin).
            CarriageStampGuard.run(() -> CarriageEditor.clearPlot(overworld, variant, dims));
            boolean deleted = CarriageTemplateStore.delete(variant);
            // Sidecars and weight — and in dev mode the bundled copies of each.
            TemplateDeletes.Report cleanup = TemplateDeletes.carriage(variant);
            boolean wasCustom = !variant.isBuiltin();
            if (wasCustom) {
                CarriageVariantRegistry.unregister(variant.id());
                if (oldIdx >= 0) {
                    final int idx = oldIdx;
                    CarriageStampGuard.run(() ->
                        CarriageEditor.restampRowAfterDeletion(overworld, idx, oldCount, dims));
                }
            }
            source.sendSuccess(() -> (deleted
                    ? Component.translatable("chat.dungeontrain.editor.deleted_template", variant.id(),
                        Component.translatable(wasCustom ? "chat.dungeontrain.editor.and_removed_from_registry" : "chat.dungeontrain.common.full_stop"))
                    : Component.translatable("chat.dungeontrain.editor.no_template_to_delete", variant.id()))
                .append(cleanup.summaryLine()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor reset failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.reset_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * {@code /dungeontrain editor clear} — wipes every interior block of the
     * plot the player is currently standing in to air, and clears every
     * block-variant entry attached to that plot. The barrier-cage outline is
     * preserved (it sits one block outside the footprint, which
     * {@code eraseAt} doesn't touch), so the player keeps editing in place
     * and can re-author from a clean slab.
     *
     * <p>For parts and contents the cleared variants sidecar is persisted
     * immediately — matching {@code /editor variant clear} for those plot
     * kinds. For carriages the variants are cleared in memory only; the
     * carriage's NBT template + sidecar are written together when the
     * player runs {@code /editor save}.</p>
     *
     * <p>Scope matches the editor menu's New / Remove gating: carriages,
     * contents, and parts. Tracks / pillars / tunnels / architecture have no
     * single addressable model id from the menu's perspective, so Clear
     * intentionally doesn't apply there — invoke their {@code reset} commands
     * instead if you want to wipe them.</p>
     */
    private static int runClear(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos pos = player.blockPosition();

        // Parts plots live inside the CARRIAGES view's Z range, while
        // CONTENTS and TRACKS views sit past the parts grid (see
        // EditorLayout) — so a part name resolution can't be confused for
        // a contents or tracks plot. Check first anyway so a part name
        // resolution beats the (unrelated) carriage plot lookup if the
        // player wandered between rows.
        // A chunk frame plot: empty the frame and drop its variant cells, as a part's clear does.
        if (games.brennan.dungeontrain.editor.EditorStampedCategoryState.isActive(EditorCategory.PORTALS)) {
            java.util.Optional<String> frame = games.brennan.dungeontrain.editor.ChunkFrameEditor.plotContaining(pos);
            if (frame.isPresent()) {
                try {
                    games.brennan.dungeontrain.editor.ChunkFrameEditor.clearPlot(overworld, frame.get());
                    games.brennan.dungeontrain.editor.BlockVariantPlot plot =
                        games.brennan.dungeontrain.editor.BlockVariantPlot.resolveByKey(
                            games.brennan.dungeontrain.editor.ChunkFramePlot.KEY_PREFIX + frame.get(), dims);
                    int cleared = 0;
                    if (plot != null) {
                        for (BlockPos local : plot.allFlaggedPositions()) {
                            if (plot.remove(local)) cleared++;
                        }
                        if (cleared > 0) plot.save();
                    }
                    final String id = "frame:" + frame.get();
                    final int n = cleared;
                    source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.cleared_all_blocks", id, (n > 0 ? Component.translatable("chat.dungeontrain.editor.cleared_and_entries", n, Component.translatable(n == 1 ? "chat.dungeontrain.common.noun.entry.singular" : "chat.dungeontrain.common.noun.entry.plural")) : Component.literal("."))).withStyle(ChatFormatting.GREEN), true);
                    return 1;
                } catch (Throwable t) {
                    LOGGER.error("[DungeonTrain] editor clear (chunk frame) failed", t);
                    source.sendFailure(Component.translatable("chat.dungeontrain.editor.clear_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
                    return 0;
                }
            }
        }

        CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(pos, dims);
        if (partLoc != null) {
            try {
                BlockPos origin = CarriagePartEditor.plotOrigin(
                    new games.brennan.dungeontrain.template.CarriagePartTemplateId(partLoc.kind(), partLoc.name()), dims);
                CarriagePartPlacer.eraseAt(overworld, origin, partLoc.kind(), dims);
                Vec3i partSize = partLoc.kind().dims(dims);
                CarriagePartVariantBlocks partSidecar = CarriagePartVariantBlocks.loadFor(
                    partLoc.kind(), partLoc.name(), partSize);
                int cleared = partSidecar.clearAll();
                if (cleared > 0) {
                    try {
                        partSidecar.save(partLoc.kind(), partLoc.name());
                    } catch (IOException e) {
                        source.sendFailure(Component.translatable("chat.dungeontrain.editor.variant_save_failed", e.getMessage()).withStyle(ChatFormatting.RED));
                        return 0;
                    }
                }
                final String id = partLoc.kind().id() + ":" + partLoc.name();
                final int n = cleared;
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.cleared_all_blocks", id, (n > 0 ? Component.translatable("chat.dungeontrain.editor.cleared_and_entries", n, Component.translatable(n == 1 ? "chat.dungeontrain.common.noun.entry.singular" : "chat.dungeontrain.common.noun.entry.plural")) : Component.literal("."))).withStyle(ChatFormatting.GREEN), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor clear (part) failed", t);
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.clear_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        CarriageContents contents = CarriageContentsEditor.plotContaining(pos, dims);
        if (contents != null) {
            try {
                BlockPos origin = CarriageContentsEditor.plotOrigin(contents, dims);
                // Interior-only erase — preserves the carriage shell stamped
                // around it as visual context. CarriageContentsPlacer.eraseAt
                // operates on interiorOrigin/interiorSize, so the floor/walls/
                // ceiling stay put for the author to keep building inside.
                CarriageContentsPlacer.eraseAt(overworld, origin, dims);
                Vec3i interiorSize = CarriageContentsPlacer.interiorSizeFor(contents, dims);
                CarriageContentsVariantBlocks contentsSidecar =
                    CarriageContentsVariantBlocks.loadFor(contents, interiorSize);
                int cleared = contentsSidecar.clearAll();
                if (cleared > 0) {
                    try {
                        contentsSidecar.save(contents);
                    } catch (IOException e) {
                        source.sendFailure(Component.translatable("chat.dungeontrain.editor.variant_save_failed", e.getMessage()).withStyle(ChatFormatting.RED));
                        return 0;
                    }
                }
                final String id = contents.id();
                final int n = cleared;
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.cleared_all_blocks", id, (n > 0 ? Component.translatable("chat.dungeontrain.editor.cleared_and_entries", n, Component.translatable(n == 1 ? "chat.dungeontrain.common.noun.entry.singular" : "chat.dungeontrain.common.noun.entry.plural")) : Component.literal("."))).withStyle(ChatFormatting.GREEN), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor clear (contents) failed", t);
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.clear_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        CarriageVariant carriage = CarriageEditor.plotContaining(pos, dims);
        if (carriage != null) {
            try {
                BlockPos origin = CarriageEditor.plotOrigin(carriage, dims);
                CarriageDims carriageBox = CarriageEditor.plotDims(carriage, dims);
                CarriagePlacer.eraseAt(overworld, origin, carriageBox);
                CarriageVariantBlocks carriageSidecar = CarriageVariantBlocks.loadFor(carriage, carriageBox);
                int cleared = carriageSidecar.clearAll();
                final String id = carriage.id();
                final int n = cleared;
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.cleared_all_blocks", id, (n > 0 ? Component.translatable("chat.dungeontrain.editor.cleared_and_entries_save", n, Component.translatable(n == 1 ? "chat.dungeontrain.common.noun.entry.singular" : "chat.dungeontrain.common.noun.entry.plural")) : Component.literal("."))).withStyle(ChatFormatting.GREEN), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor clear (carriage) failed", t);
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.clear_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        String roomName = PortalRoomEditor.plotContaining(pos, dims);
        if (roomName != null) {
            try {
                int cleared = PortalRoomEditor.clearEverything(overworld, roomName, dims);
                final String id = roomName;
                final int n = cleared;
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.cleared_all_blocks_dimensional", id, (n > 0 ? Component.translatable("chat.dungeontrain.editor.cleared_and_authored", n, Component.translatable(n == 1 ? "chat.dungeontrain.common.noun.entry.singular" : "chat.dungeontrain.common.noun.entry.plural")) : Component.literal("."))).withStyle(ChatFormatting.GREEN), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor clear (portal room) failed", t);
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.clear_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        source.sendFailure(Component.translatable("chat.dungeontrain.editor.clear_stand_inside_carriage"));
        return 0;
    }


    private static int runNew(CommandSourceStack source, String rawName, CarriageVariant sourceVariant) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        String name = rawName.toLowerCase(Locale.ROOT);
        if (!CarriageVariant.NAME_PATTERN.matcher(name).matches()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.invalid_name_use_lowercase", rawName));
            return 0;
        }
        if (CarriageVariant.isReservedBuiltinName(name)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_reserved_built", name));
            return 0;
        }
        if (CarriageVariantRegistry.find(name).isPresent()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_already_taken", name));
            return 0;
        }

        try {
            CarriageVariant.Custom target = (CarriageVariant.Custom) CarriageVariant.custom(name);
            var origin = CarriageEditor.duplicate(player, sourceVariant, target);
            CarriageEditor.enter(player, target);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.created_from_plot", target.id(), sourceVariant.id(), origin.toShortString()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor new failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.new_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Carriage variant of {@code /dt editor new <name> blank} — registers the
     * variant and allocates a plot but stamps no geometry, then teleports the
     * author into the empty plot to build from scratch.
     */
    private static int runNewBlank(CommandSourceStack source, String rawName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        String name = rawName.toLowerCase(Locale.ROOT);
        if (!CarriageVariant.NAME_PATTERN.matcher(name).matches()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.invalid_name_use_lowercase", rawName));
            return 0;
        }
        if (CarriageVariant.isReservedBuiltinName(name)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_reserved_built", name));
            return 0;
        }
        if (CarriageVariantRegistry.find(name).isPresent()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_already_taken", name));
            return 0;
        }

        try {
            CarriageVariant.Custom target = (CarriageVariant.Custom) CarriageVariant.custom(name);
            var origin = CarriageEditor.createBlank(player, target);
            CarriageEditor.enter(player, target);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.created_blank_plot", target.id(), origin.toShortString()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor new blank failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.new_blank_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartMenu(CommandSourceStack source, boolean on) {
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.partmenu_only_players_can"));
            return 0;
        }
        games.brennan.dungeontrain.editor.PartPositionMenuController.setMenuEnabled(player, on);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.part_position_menu", Component.translatable(on ? "chat.dungeontrain.common.on_caps" : "chat.dungeontrain.common.off_caps")).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return 1;
    }

    /**
     * Master mode for all editor world-space menus — {@code on} / {@code auto} / {@code off}.
     * Drives the persistent parts-position auto-open flag (the only menu with persistent state) and,
     * when switching to OFF, also force-closes the two on-demand menus (block-variant
     * tap-Z, container-contents tap-C) if they happen to be open. Those two stay
     * reopenable on demand while OFF — this only closes what's currently up.
     *
     * <p>{@code auto} is the default and differs from {@code on} only in what the client draws:
     * once the player is standing in a plot, the other plots' panels stop rendering. Nothing
     * server-side changes between the two.</p>
     */
    private static int runEditorMenus(CommandSourceStack source,
                                      games.brennan.dungeontrain.editor.EditorMenusMode mode) {
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.menus_only_players_can"));
            return 0;
        }
        boolean on = mode != games.brennan.dungeontrain.editor.EditorMenusMode.OFF;
        games.brennan.dungeontrain.editor.PartPositionMenuController.setMode(player, mode);
        if (!on) {
            // Close any open on-demand world-space menus. Both are no-ops when
            // nothing is open (drop the OPEN entry + send an empty sync packet).
            games.brennan.dungeontrain.editor.BlockVariantMenuController.toggle(player, false);
            games.brennan.dungeontrain.editor.ContainerContentsMenuController.toggle(player, false);
        }
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.menus", mode.name()).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return 1;
    }

    /**
     * Show / hide the editor's world-space Welcome panel for the calling player. Run by the panel's
     * own close (X) button and by the "Welcome Panel" row in the editor (X) menu.
     *
     * <p>The flag is stored per player in the world save
     * ({@link games.brennan.dungeontrain.world.DungeonTrainWorldData}), so a dismissal survives a
     * relog and does not follow the player into other worlds. The client picks the new value up on
     * the next {@code EditorTypeMenusPacket} — its dedup key includes the flag, so the toggle
     * always re-pushes.</p>
     */
    private static int runHelpPanel(CommandSourceStack source, boolean on) {
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.welcome_panel_only_players"));
            return 0;
        }
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (server == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.welcome_panel_no_server"));
            return 0;
        }
        games.brennan.dungeontrain.world.DungeonTrainWorldData.get(server.overworld())
            .setHelpPanelDismissed(player.getUUID(), !on);
        source.sendSuccess(() -> (on ? Component.translatable("chat.dungeontrain.editor.welcome_panel") : Component.translatable("chat.dungeontrain.editor.welcome_panel_off_press")).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        return 1;
    }

    /**
     * Settings → Observers On / Off for this world: while Off, an observer inside an editor plot
     * never pulses, so an author can place and break around a contraption without firing it
     * ({@link games.brennan.dungeontrain.editor.EditorObservers}). World state, so every online
     * player's Settings row is told.
     */
    private static int runObservers(CommandSourceStack source, boolean on) {
        net.minecraft.server.MinecraftServer server = source.getServer();
        games.brennan.dungeontrain.world.DungeonTrainWorldData.get(server.overworld())
            .setEditorObserversOn(on);
        games.brennan.dungeontrain.net.EditorObserversPacket packet =
            new games.brennan.dungeontrain.net.EditorObserversPacket(on);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            games.brennan.dungeontrain.net.DungeonTrainNet.sendTo(online, packet);
        }
        source.sendSuccess(() -> (on ? Component.translatable("chat.dungeontrain.editor.observers_observers_plots_pulse") : Component.translatable("chat.dungeontrain.editor.observers_off_observers_plots")).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        return 1;
    }

    /**
     * Settings → Mobs Blocks / Live for this world. Blocks: a spawn egg used in an editor plot places
     * a frozen, one-hit mob and variant cells with mob entries show a ghost
     * ({@link games.brennan.dungeontrain.editor.FrozenMobs}); Live is vanilla eggs. World state, so
     * every online player's Settings row is told.
     */
    private static int runMobsMode(CommandSourceStack source, boolean live) {
        net.minecraft.server.MinecraftServer server = source.getServer();
        games.brennan.dungeontrain.world.DungeonTrainWorldData.get(server.overworld())
            .setEditorMobsLive(live);
        games.brennan.dungeontrain.net.EditorMobsModePacket packet =
            new games.brennan.dungeontrain.net.EditorMobsModePacket(live);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            games.brennan.dungeontrain.net.DungeonTrainNet.sendTo(online, packet);
        }
        source.sendSuccess(() -> (live ? Component.translatable("chat.dungeontrain.editor.mobs_live_spawn_eggs") : Component.translatable("chat.dungeontrain.editor.mobs_blocks_spawn_eggs")).withStyle(live ? ChatFormatting.YELLOW : ChatFormatting.GREEN), false);
        return 1;
    }

    /**
     * Toggle whether the named contents id may spawn inside the named carriage
     * variant. {@code on} means allowed (removed from the excluded set);
     * {@code off} means disallowed (added to the excluded set). Persists to
     * {@link CarriageVariantContentsAllowStore} so the choice survives restarts.
     * Idempotent — re-toggling to the current state still rewrites the sidecar
     * but doesn't change content.
     */
    private static int runCarriageContentsAllow(CommandSourceStack source, String rawVariant, String rawContents, boolean on) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        CarriageContents contents = parseContents(source, rawContents);
        if (contents == null) return 0;
        // Allow-list operates at the parent level. Sub-variants are reached
        // through their parent's resolution and never directly consulted
        // against the allow-list — toggling a sub-variant here would be a
        // no-op at spawn time, so reject with a clear message.
        if (CarriageContentsGroupStore.allChildIds().contains(contents.id())) {
            java.util.Optional<String> parentId = CarriageContentsGroupStore.findParentOf(contents.id());
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.sub_variant_toggle_parent", contents.id(), parentId.map(p -> " of '" + p + "'").orElse("")).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        try {
            CarriageContentsAllowList current = CarriageVariantContentsAllowStore.get(variant)
                .orElse(CarriageContentsAllowList.EMPTY);
            CarriageContentsAllowList updated = on
                ? current.withAllowed(contents.id())
                : current.withExcluded(contents.id());
            CarriageVariantContentsAllowStore.save(variant, updated);
            String summary = "Carriage '" + variant.id() + "' content '" + contents.id() + "': "
                + (on ? "ALLOWED" : "EXCLUDED");
            source.sendSuccess(() -> Component.literal(summary)
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor carriage-contents save failed for {}/{}",
                variant.id(), contents.id(), e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.failed_update_contents_allow", e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * {@code /dt editor portal-room-contents <room> <contents> on|off} — the portal-room twin of
     * {@link #runCarriageContentsAllow}, writing the room's own sidecar.
     *
     * <p>Deliberately does <b>not</b> check the room's Contents setting. The button that reaches
     * this is only shown while the setting is on, but an author flipping Contents Off and back on
     * should find the toggles they set still there — the allow-list and the on/off switch are
     * separate facts about the room, and the command is also the scripting surface.</p>
     */
    private static int runPortalRoomContentsAllow(CommandSourceStack source, String rawRoom,
                                                  String rawContents, boolean on) {
        String room = rawRoom == null ? "" : rawRoom.trim();
        if (room.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_dimensional_carriage"));
            return 0;
        }
        if (!games.brennan.dungeontrain.track.variant.TrackVariantRegistry
                .namesFor(games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM)
                .contains(room)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_dimensional_carriage", room));
            return 0;
        }
        CarriageContents contents = parseContents(source, rawContents);
        if (contents == null) return 0;
        // Same parent-level rule as the carriage path: a sub-variant is reached through its
        // parent's resolution and is never consulted against the allow-list directly.
        if (CarriageContentsGroupStore.allChildIds().contains(contents.id())) {
            java.util.Optional<String> parentId = CarriageContentsGroupStore.findParentOf(contents.id());
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.sub_variant_toggle_parent", contents.id(), parentId.map(p -> " of '" + p + "'").orElse("")).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        try {
            CarriageContentsAllowList current =
                games.brennan.dungeontrain.editor.PortalRoomContentsAllowStore.getOrEmpty(room);
            CarriageContentsAllowList updated = on
                ? current.withAllowed(contents.id())
                : current.withExcluded(contents.id());
            games.brennan.dungeontrain.editor.PortalRoomContentsAllowStore.save(room, updated);
            String summary = "Dimensional carriage '" + room + "' content '" + contents.id() + "': "
                + (on ? "ALLOWED" : "EXCLUDED");
            source.sendSuccess(() -> Component.literal(summary)
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor portal-room-contents save failed for {}/{}",
                room, contents.id(), e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.failed_update_contents_allow", e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runDevMode(CommandSourceStack source, boolean on) {
        EditorDevMode.set(on);
        // Dev mode decides whether a Train Builder build that goes by a shipped name may be saved
        // in place, and the client only learns that from the bounds packet. Push a fresh one now,
        // so turning dev mode off makes Save behave as it would for a player straight away rather
        // than after the next Open. Outside a builder world the packet is the inert empty form.
        net.minecraft.server.MinecraftServer server = source.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            games.brennan.dungeontrain.net.BuilderBoundsPacket.sendTo(player, server.overworld());
        }
        boolean writable = CarriageTemplateStore.sourceTreeAvailable();
        if (on) {
            if (writable) {
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.dev_mode_save_will").withStyle(ChatFormatting.GREEN), true);
            } else {
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.dev_mode_but_source").withStyle(ChatFormatting.YELLOW), true);
            }
        } else {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.dev_mode_off_save"), true);
        }
        return 1;
    }

    /**
     * Toggle whether the carriage variant the player is standing in is relay-sourced ("shared"). With
     * {@code on == null} it flips the current membership. Persists via {@link SharedCarriageFlags} (and,
     * in dev mode, through to the bundled source file).
     */
    private static int runSharedToggle(CommandSourceStack source, Boolean on) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        CarriageVariant current = CarriageEditor.plotContaining(player.blockPosition(), dims);
        if (current == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_carriage_plot_stand").withStyle(ChatFormatting.RED));
            return 0;
        }
        boolean target = on != null ? on
            : !games.brennan.dungeontrain.train.SharedCarriageFlags.isSharedVariant(current.id());
        try {
            games.brennan.dungeontrain.train.SharedCarriageFlags.set(current.id(), target);
        } catch (Exception e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.failed_save_shared_flag", e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        boolean enabled = games.brennan.dungeontrain.config.DungeonTrainConfig.isSharedCarriagesEnabled();
        // Leasing is the half that PLACES community builds, and it ships off — say so here, or a
        // builder flags a variant shared, never sees a pooled carriage, and reads that as a bug.
        boolean leasing = games.brennan.dungeontrain.event.SharedCarriageGate.canLease();
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.carriage_shared", current.id(), Component.translatable(target ? "chat.dungeontrain.common.on_caps" : "chat.dungeontrain.common.off"), (enabled ? Component.empty() : Component.translatable("chat.dungeontrain.editor.carriage_shared_note_disabled")), (!enabled || leasing ? Component.empty() : Component.translatable("chat.dungeontrain.editor.carriage_shared_note_leasing"))).withStyle(target ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    private static int runPromote(CommandSourceStack source, CarriageType type) {
        try {
            CarriageTemplateStore.promote(type);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.promoted_template_source_tree", type.name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor promote failed for {}", type, t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.promote_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPromoteAll(CommandSourceStack source) {
        if (!CarriageTemplateStore.sourceTreeAvailable()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.promote_all_failed_source").withStyle(ChatFormatting.RED));
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
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.promote_all_promoted_skipped", p, s, (errStr.isEmpty() ? Component.empty() : Component.translatable("chat.dungeontrain.common.errors_suffix", errStr))).withStyle(errStr.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return p > 0 ? 1 : 0;
    }

    private static int runPillarEnter(CommandSourceStack source, PillarSection section) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        if (!ensureCategory(source, EditorCategory.TRACKS)) return 0;
        try {
            PillarEditor.enter(player, section);
            CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.entered_pillar_plot", section.id(), PillarEditor.plotOrigin(section, dims).toShortString()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar enter failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.pillar_enter_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPillarSave(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        PillarEditor.SectionPlot sectionLoc = PillarEditor.plotContaining(player.blockPosition(), dims);
        if (sectionLoc != null) {
            return runPillarSaveSection(source, player, sectionLoc.section());
        }
        PillarEditor.AdjunctPlot adjunctLoc = PillarEditor.plotContainingAdjunct(player.blockPosition(), dims);
        if (adjunctLoc != null) {
            return runPillarSaveAdjunct(source, player, adjunctLoc.adjunct());
        }
        source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_pillar_plot_use", pillarTargetList()));
        return 0;
    }

    private static int runPillarSaveSection(CommandSourceStack source, ServerPlayer player, PillarSection section) {
        try {
            PillarEditor.SaveResult result = PillarEditor.save(player, section);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.saved_pillar_template_config", section.id()), true);
            if (result.sourceAttempted()) {
                if (result.sourceWritten()) {
                    source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.also_wrote_bundled_pillar").withStyle(ChatFormatting.GREEN), true);
                } else {
                    source.sendFailure(Component.translatable("chat.dungeontrain.editor.pillar_source_tree_write", result.sourceError()).withStyle(ChatFormatting.YELLOW));
                }
            }
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar save failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.pillar_save_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPillarSaveAdjunct(CommandSourceStack source, ServerPlayer player, PillarAdjunct adjunct) {
        try {
            PillarEditor.SaveResult result = PillarEditor.save(player, adjunct);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.saved_pillar_adjunct_template", adjunct.id()), true);
            if (result.sourceAttempted()) {
                if (result.sourceWritten()) {
                    source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.also_wrote_bundled_adjunct").withStyle(ChatFormatting.GREEN), true);
                } else {
                    source.sendFailure(Component.translatable("chat.dungeontrain.editor.pillar_adjunct_source_tree", result.sourceError()).withStyle(ChatFormatting.YELLOW));
                }
            }
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar save adjunct failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.pillar_save_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
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
        sb.append("\nPillar adjuncts:");
        for (PillarAdjunct adjunct : PillarAdjunct.values()) {
            boolean config = PillarTemplateStore.existsAdjunct(adjunct);
            boolean bundled = PillarTemplateStore.bundledAdjunct(adjunct);
            String status;
            if (config) status = "config override";
            else if (bundled) status = "bundled default";
            else status = "not placed (no fallback)";
            sb.append("\n  ").append(adjunct.id())
                .append(" (size=").append(adjunct.xSize()).append("x")
                .append(adjunct.ySize()).append("x").append(adjunct.zSize()).append(")")
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
            source.sendSuccess(() -> (deleted ? Component.translatable("chat.dungeontrain.editor.deleted_pillar_template", section.id()) : Component.translatable("chat.dungeontrain.editor.no_pillar_template_delete", section.id())), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar reset failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.pillar_reset_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPillarPromote(CommandSourceStack source, PillarSection section) {
        try {
            PillarTemplateStore.promote(section);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.promoted_pillar_template_source", section.id()).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar promote failed for {}", section, t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.pillar_promote_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static CarriageContents parseContents(CommandSourceStack source, String raw) {
        String id = raw.toLowerCase(Locale.ROOT);
        return CarriageContentsRegistry.find(id).orElseGet(() -> {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_contents_valid", raw, listContentsIds()));
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
        if (!ensureCategory(source, EditorCategory.CONTENTS)) return 0;
        CarriageContents contents = parseContents(source, contentsRaw);
        if (contents == null) return 0;
        // NOTE: group parents are now enterable — the parent's own .nbt is the
        // "default" sub-variant of its group (Phase 2 semantic). The synthetic
        // self entry in CarriageContentsRegistry.resolveGroup keeps the parent
        // in rotation alongside explicit members.
        CarriageVariant shell = shellRaw == null
            ? null
            : CarriageVariantRegistry.find(shellRaw.toLowerCase(Locale.ROOT)).orElse(null);
        if (shellRaw != null && shell == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_shell_variant_valid", shellRaw, listIds()));
            return 0;
        }
        try {
            CarriageContentsEditor.enter(player, contents, shell);
            final CarriageVariant shellUsed = CarriageContentsEditor.resolveShellOrDefault(shellRaw);
            CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
            // Echo the display label when one is set (id in parens) so chat matches the panels.
            final String label = CarriageContentsWeights.current().nameFor(contents.id());
            final String shown = label.equals(contents.id())
                ? "'" + contents.id() + "'"
                : "'" + label + "' (id " + contents.id() + ")";
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.entered_contents_shell_plot", shown, shellUsed.id(), CarriageContentsEditor.plotOrigin(contents, dims).toShortString()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents enter failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.contents_enter_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runContentsSave(CommandSourceStack source, String newName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        CarriageContents current = CarriageContentsEditor.plotContaining(player.blockPosition(), dims);
        if (current == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_contents_plot_use"));
            return 0;
        }

        if (newName == null) {
            try {
                CarriageContentsEditor.SaveResult result = CarriageContentsEditor.save(player, current);
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.saved_contents_template_config", current.id()), true);
                if (result.sourceAttempted()) {
                    if (result.sourceWritten()) {
                        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.also_wrote_bundled_contents").withStyle(ChatFormatting.GREEN), true);
                    } else {
                        source.sendFailure(Component.translatable("chat.dungeontrain.editor.contents_source_tree_write", result.sourceError()).withStyle(ChatFormatting.YELLOW));
                    }
                }
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor contents save failed", t);
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.contents_save_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        // Rename path.
        return renameContentsTo(source, player, current, newName);
    }

    /** {@link #renameCarriageTo} for a contents template — same split, same reason. */
    private static int renameContentsTo(CommandSourceStack source, ServerPlayer player,
                                        CarriageContents current, String newName) {
        if (current.isBuiltin()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.cannot_rename_it_built", current.id()));
            return 0;
        }
        String newId = newName.toLowerCase(Locale.ROOT);
        if (!CarriageContents.NAME_PATTERN.matcher(newId).matches()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.invalid_name_use_lowercase", newName));
            return 0;
        }
        if (CarriageContents.isReservedBuiltinName(newId)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_reserved_built", newId));
            return 0;
        }
        if (CarriageContentsRegistry.find(newId).isPresent()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_already_taken", newId));
            return 0;
        }
        try {
            CarriageContents.Custom renamed = (CarriageContents.Custom) CarriageContents.custom(newId);
            CarriageContentsEditor.saveAs(player, current, renamed);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.saved_and_renamed_contents", current.id(), renamed.id()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents save-rename failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.contents_save_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
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
        return runContentsReset(source, raw, null);
    }

    /**
     * {@code /dt editor contents reset <id> [all|unparent|promote]}. A leaf deletes as it always
     * has. A group <b>parent</b> needs a {@link games.brennan.dungeontrain.editor.ParentDeletes.Mode
     * mode} saying what becomes of its sub-variants, and is refused without one — the group sidecar
     * is the only place their weights, gates and Stage links live, so a bare delete would strand
     * them silently. The editor's Remove asks the author and sends the mode; scripts pass it by hand.
     */
    private static int runContentsReset(CommandSourceStack source, String raw,
                                        games.brennan.dungeontrain.editor.ParentDeletes.Mode mode) {
        CarriageContents contents = parseContents(source, raw);
        if (contents == null) return 0;
        java.util.Optional<CarriageContentsGroup> group = CarriageContentsGroupStore.get(contents.id())
            .filter(g -> !g.members().isEmpty());
        if (group.isPresent() && mode == null) {
            int n = group.get().members().size();
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.has_sub_variant_say", contents.id(), n, Component.translatable(n == 1 ? "chat.dungeontrain.common.noun.sub_variant.singular" : "chat.dungeontrain.common.noun.sub_variant.plural"), contents.id(), games.brennan.dungeontrain.editor.ParentDeletes.Mode.literals()).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        progress(source, Component.translatable("chat.dungeontrain.editor.progress_deleting", contents.id(), (group.isPresent() && mode == games.brennan.dungeontrain.editor.ParentDeletes.Mode.ALL ? Component.translatable("chat.dungeontrain.editor.progress_deleting_and_subs", group.get().members().size()) : Component.empty())));
        try {
            ParentModeOutcome outcome = group.isPresent()
                ? applyContentsParentMode(source, contents, group.get(), mode) : ParentModeOutcome.NONE;
            Component line = deleteContents(source, contents);
            Component landed = landInContents(source, outcome.landing());
            source.sendSuccess(() -> line.copy().append(outcome.line()).append(landed), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents reset failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.contents_reset_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Put the player in {@code id}'s plot after a parent delete — the new parent, or the first
     * member that just became top-level — so the author lands where the work continued rather than
     * in a hole. No-op without a player or a landing. Returns the reply fragment.
     */
    private static Component landInContents(CommandSourceStack source, String id) {
        if (id == null || !(source.getEntity() instanceof ServerPlayer player)) return Component.empty();
        java.util.Optional<CarriageContents> target = CarriageContentsRegistry.find(id);
        if (target.isEmpty()) return Component.empty();
        try {
            CarriageContentsEditor.enter(player, target.get(), null);
            return Component.translatable("chat.dungeontrain.editor.landed_entered", id);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] contents reset: could not enter {} afterwards: {}", id, e.toString());
            return Component.empty();
        }
    }

    /**
     * Delete one contents template: clear its plot, drop the {@code .nbt} and everything
     * {@link TemplateDeletes#contents} takes with it, deregister a custom and close the plot row's
     * gap. Returns the reply line; the caller sends it.
     */
    private static Component deleteContents(CommandSourceStack source, CarriageContents contents) throws java.io.IOException {
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        List<CarriageContents> rowBefore = CarriageContentsRegistry.allContents();
        int oldIdx = -1;
        for (int i = 0; i < rowBefore.size(); i++) {
            if (rowBefore.get(i).id().equals(contents.id())) { oldIdx = i; break; }
        }
        int oldCount = rowBefore.size();

        CarriageStampGuard.run(() -> CarriageContentsEditor.clearPlot(overworld, contents, dims));
        boolean deleted = CarriageContentsStore.delete(contents);
        // Sidecars, weight, group slot — and in dev mode the bundled copies of each.
        TemplateDeletes.Report cleanup = TemplateDeletes.contents(contents);
        boolean wasCustom = !contents.isBuiltin();
        if (wasCustom) {
            CarriageContentsRegistry.unregister(contents.id());
            if (oldIdx >= 0) {
                final int idx = oldIdx;
                CarriageStampGuard.run(() ->
                    CarriageContentsEditor.restampRowAfterDeletion(overworld, idx, oldCount, dims));
            }
        }
        return (deleted
                ? Component.translatable("chat.dungeontrain.editor.deleted_contents_template", contents.id(),
                    Component.translatable(wasCustom ? "chat.dungeontrain.editor.and_removed_from_registry" : "chat.dungeontrain.common.full_stop"))
                : Component.translatable("chat.dungeontrain.editor.no_contents_template_to_delete", contents.id()))
            .copy().append(cleanup.summaryLine());
    }

    /**
     * What a contents parent's sub-variants become, applied before the parent itself goes. Each
     * member step is isolated — a failure is logged and named in the returned line, and the rest
     * (and the parent delete) still run. See {@link games.brennan.dungeontrain.editor.ParentDeletes}.
     */
    private static ParentModeOutcome applyContentsParentMode(CommandSourceStack source, CarriageContents parent,
                                                             CarriageContentsGroup group,
                                                             games.brennan.dungeontrain.editor.ParentDeletes.Mode mode) {
        List<String> done = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        switch (mode) {
            case ALL -> {
                for (CarriageContentsGroup.Member m : group.members()) {
                    java.util.Optional<CarriageContents> member = CarriageContentsRegistry.find(m.id());
                    if (member.isEmpty()) continue;
                    if (member.get().isBuiltin()) {
                        // A built-in can be a member but never deleted — it simply stops being one.
                        done.add(m.id() + " (built-in, kept)");
                        continue;
                    }
                    try {
                        deleteContents(source, member.get());
                        done.add(m.id());
                    } catch (Exception e) {
                        LOGGER.warn("[DungeonTrain] contents reset all: could not delete member {}: {}", m.id(), e.toString());
                        failed.add(m.id());
                    }
                }
                return new ParentModeOutcome(summarise("chat.dungeontrain.editor.sub_variants_deleted", done, failed), null);
            }
            case UNPARENT -> {
                String first = null;
                for (games.brennan.dungeontrain.editor.ParentDeletes.TopLevel t
                        : games.brennan.dungeontrain.editor.ParentDeletes.unparentContents(group)) {
                    try {
                        CarriageContentsWeights.set(t.id(), t.weight());
                        CarriageContentsWeights.setGate(t.id(), t.gate());
                        CarriageContentsWeights.setStage(t.id(), t.stageId());
                        if (first == null) first = t.id();
                        done.add(t.droppedStages() > 0
                            ? t.id() + " (kept 1 of " + (t.droppedStages() + 1) + " Stage links)" : t.id());
                    } catch (Exception e) {
                        LOGGER.warn("[DungeonTrain] contents reset unparent: could not carry {}: {}", t.id(), e.toString());
                        failed.add(t.id());
                    }
                }
                return new ParentModeOutcome(summarise("chat.dungeontrain.editor.now_top_level", done, failed), first);
            }
            case PROMOTE_FIRST -> {
                games.brennan.dungeontrain.editor.ParentDeletes.ContentsPromotion p =
                    games.brennan.dungeontrain.editor.ParentDeletes.promoteContents(group).orElseThrow();
                String heir = p.newParent();
                try {
                    // The family keeps the parent's place in the top-level draw.
                    CarriageContentsWeights w = CarriageContentsWeights.current();
                    CarriageContentsWeights.set(heir, w.weightFor(parent.id()));
                    CarriageContentsWeights.setGate(heir, w.gateFor(parent.id()));
                    CarriageContentsWeights.setStage(heir, w.stageIdFor(parent.id()));
                    if (p.group().isPresent()) CarriageContentsGroupStore.save(heir, p.group().get());
                    int n = p.group().map(g -> g.members().size()).orElse(0);
                    return new ParentModeOutcome(Component.translatable("chat.dungeontrain.editor.now_heads_group", heir, n,
                        Component.translatable(n == 1 ? "chat.dungeontrain.common.noun.sub_variant.singular" : "chat.dungeontrain.common.noun.sub_variant.plural")), heir);
                } catch (Exception e) {
                    LOGGER.warn("[DungeonTrain] contents reset promote: could not promote {}: {}", heir, e.toString());
                    return new ParentModeOutcome(Component.translatable("chat.dungeontrain.editor.could_not_promote", heir, e.getMessage()), null);
                }
            }
        }
        return ParentModeOutcome.NONE;
    }

    /**
     * What a parent-deletion mode did, and where the author should land afterwards: the new parent
     * after a promote, the first newly top-level member after an unparent, nowhere in particular
     * otherwise (null). The landing is a template id the caller enters once the delete has restamped.
     */
    private record ParentModeOutcome(Component line, String landing) {
        static final ParentModeOutcome NONE = new ParentModeOutcome(Component.empty(), null);
    }

    /**
     * A grey "…ing" line sent <b>before</b> a slow editor operation starts — a portal-room delete or
     * re-parent clears and restamps the whole row, and until the result line lands the author has
     * nothing telling them the click took. Handed to the network thread at once, so it shows while
     * the server thread is still working.
     */
    private static void progress(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message.copy().withStyle(ChatFormatting.GRAY), false);
    }

    /** One reply fragment for a per-member pass: what went through and what did not. */
    private static Component summarise(String labelKey, List<String> done, List<String> failed) {
        net.minecraft.network.chat.MutableComponent out = Component.empty();
        if (!done.isEmpty()) out.append(Component.translatable(labelKey, String.join(", ", done)));
        if (!failed.isEmpty()) out.append(Component.translatable("chat.dungeontrain.editor.could_not_list", String.join(", ", failed)));
        return out;
    }

    private static int runContentsNew(CommandSourceStack source, String rawName, CarriageContents sourceContents) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        String name = rawName.toLowerCase(Locale.ROOT);
        if (!CarriageContents.NAME_PATTERN.matcher(name).matches()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.invalid_name_use_lowercase", rawName));
            return 0;
        }
        if (CarriageContents.isReservedBuiltinName(name)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_reserved_built", name));
            return 0;
        }
        if (CarriageContentsRegistry.find(name).isPresent()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_already_taken", name));
            return 0;
        }

        try {
            CarriageContents.Custom target = (CarriageContents.Custom) CarriageContents.custom(name);
            var origin = CarriageContentsEditor.duplicate(player, sourceContents, target);
            CarriageContentsEditor.enter(player, target, null);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.created_contents_from_plot", target.id(), sourceContents.id(), origin.toShortString()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents new failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.contents_new_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Contents variant of {@code /dt editor contents new <name> blank} —
     * registers and allocates the plot with only the default shell stamped,
     * then teleports the author inside to build the interior from scratch.
     */
    private static int runContentsNewBlank(CommandSourceStack source, String rawName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        String name = rawName.toLowerCase(Locale.ROOT);
        if (!CarriageContents.NAME_PATTERN.matcher(name).matches()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.invalid_name_use_lowercase", rawName));
            return 0;
        }
        if (CarriageContents.isReservedBuiltinName(name)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_reserved_built", name));
            return 0;
        }
        if (CarriageContentsRegistry.find(name).isPresent()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_already_taken", name));
            return 0;
        }

        try {
            CarriageContents.Custom target = (CarriageContents.Custom) CarriageContents.custom(name);
            var origin = CarriageContentsEditor.createBlank(player, target);
            CarriageContentsEditor.enter(player, target, null);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.created_blank_contents_plot", target.id(), origin.toShortString()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents new blank failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.contents_new_blank_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPillarPromoteAll(CommandSourceStack source) {
        if (!PillarTemplateStore.sourceTreeAvailable()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.pillar_promote_all_failed").withStyle(ChatFormatting.RED));
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
        for (PillarAdjunct adjunct : PillarAdjunct.values()) {
            if (!PillarTemplateStore.existsAdjunct(adjunct)) {
                skipped++;
                continue;
            }
            try {
                PillarTemplateStore.promoteAdjunct(adjunct);
                promoted++;
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] editor pillar promote-all failed for adjunct {}", adjunct, e);
                errors.append("\n  ").append(adjunct.id())
                    .append(": ").append(e.getMessage());
            }
        }
        final int p = promoted;
        final int s = skipped;
        final String errStr = errors.toString();
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.pillar_promote_all_promoted", p, s, (errStr.isEmpty() ? Component.empty() : Component.translatable("chat.dungeontrain.common.errors_suffix", errStr))).withStyle(errStr.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return p > 0 ? 1 : 0;
    }

    private static int runPillarEnterAdjunct(CommandSourceStack source, PillarAdjunct adjunct) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        if (!ensureCategory(source, EditorCategory.TRACKS)) return 0;
        try {
            PillarEditor.enter(player, adjunct);
            CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.entered_pillar_adjunct_plot", adjunct.id(), PillarEditor.plotOriginAdjunct(adjunct, dims).toShortString()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar enter adjunct failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.pillar_enter_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPillarResetAdjunct(CommandSourceStack source, PillarAdjunct adjunct) {
        try {
            boolean deleted = PillarTemplateStore.deleteAdjunct(adjunct);
            source.sendSuccess(() -> (deleted ? Component.translatable("chat.dungeontrain.editor.deleted_pillar_adjunct_template", adjunct.id()) : Component.translatable("chat.dungeontrain.editor.no_pillar_adjunct_template", adjunct.id())), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar reset adjunct failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.pillar_reset_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPillarPromoteAdjunct(CommandSourceStack source, PillarAdjunct adjunct) {
        try {
            PillarTemplateStore.promoteAdjunct(adjunct);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.promoted_pillar_adjunct_template", adjunct.id()).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar promote adjunct failed for {}", adjunct, t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.pillar_promote_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runTrackEnter(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        if (!ensureCategory(source, EditorCategory.TRACKS)) return 0;
        try {
            TrackEditor.enter(player);
            CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.entered_track_plot", TrackEditor.plotOrigin(dims).toShortString()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor track enter failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.track_enter_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runTrackSave(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        if (!TrackEditor.plotContaining(player.blockPosition(), dims)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_track_plot_use"));
            return 0;
        }
        try {
            TrackEditor.SaveResult result = TrackEditor.save(player);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.saved_track_template_config"), true);
            if (result.sourceAttempted()) {
                if (result.sourceWritten()) {
                    source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.also_wrote_bundled_track").withStyle(ChatFormatting.GREEN), true);
                } else {
                    source.sendFailure(Component.translatable("chat.dungeontrain.editor.track_source_tree_write", result.sourceError()).withStyle(ChatFormatting.YELLOW));
                }
            }
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor track save failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.track_save_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
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
            source.sendSuccess(() -> (deleted ? Component.translatable("chat.dungeontrain.editor.deleted_track_template") : Component.translatable("chat.dungeontrain.editor.no_track_template_delete")), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor track reset failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.track_reset_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runTrackPromote(CommandSourceStack source) {
        try {
            TrackTemplateStore.promote();
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.promoted_track_template_source").withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor track promote failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.track_promote_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    // ---- Part runners -----------------------------------------------------

    private static CarriagePartKind parsePartKind(CommandSourceStack source, String raw) {
        CarriagePartKind kind = CarriagePartKind.fromId(raw);
        if (kind == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_part_kind_valid", raw));
        }
        return kind;
    }

    private static boolean validatePartName(CommandSourceStack source, String raw) {
        String norm = raw.toLowerCase(Locale.ROOT);
        if (!CarriagePartRegistry.NAME_PATTERN.matcher(norm).matches()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.invalid_part_name_use", raw));
            return false;
        }
        if (CarriagePartKind.NONE.equals(norm)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.reserved_it_means_skip", CarriagePartKind.NONE));
            return false;
        }
        return true;
    }

    /** True if {@code raw} is either a valid known part name or the {@code "none"} sentinel. Error already sent on false. */
    private static boolean validateSlotName(CommandSourceStack source, CarriagePartKind kind, String raw) {
        String norm = raw.toLowerCase(Locale.ROOT);
        if (CarriagePartKind.NONE.equals(norm)) return true;
        if (!CarriagePartRegistry.NAME_PATTERN.matcher(norm).matches()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.invalid_part_name_use_2", raw));
            return false;
        }
        if (!CarriagePartRegistry.isKnown(kind, norm)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_part_author_it", kind.id(), norm, kind.id(), norm).withStyle(ChatFormatting.YELLOW));
            return false;
        }
        return true;
    }

    private static int runPartEnter(CommandSourceStack source, String rawKind, String rawName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        if (!ensureCategory(source, EditorCategory.CARRIAGES)) return 0;
        CarriagePartKind kind = parsePartKind(source, rawKind);
        if (kind == null) return 0;
        if (!validatePartName(source, rawName)) return 0;
        String name = rawName.toLowerCase(Locale.ROOT);
        try {
            CarriagePartEditor.enter(player, kind, name);
            CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
            BlockPos plot = CarriagePartEditor.plotOrigin(
                new games.brennan.dungeontrain.template.CarriagePartTemplateId(kind, name), dims);
            if (plot == null) plot = CarriagePartEditor.nextFreePlotOrigin(kind, dims);
            final BlockPos plotFinal = plot;
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.entered_part_plot", kind.id(), name, plotFinal.toShortString()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part enter failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.part_enter_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartNew(CommandSourceStack source, String rawKind, String rawSource, String rawName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriagePartKind kind = parsePartKind(source, rawKind);
        if (kind == null) return 0;
        if (!validatePartName(source, rawName)) return 0;
        String name = rawName.toLowerCase(Locale.ROOT);
        if (CarriagePartRegistry.isKnown(kind, name)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.part_already_registered", kind.id(), name));
            return 0;
        }
        CarriagePartEditor.NewSource srcEnum;
        try {
            srcEnum = CarriagePartEditor.NewSource.valueOf(rawSource.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_source_valid_blank", rawSource));
            return 0;
        }
        try {
            BlockPos origin = CarriagePartEditor.createFrom(player, kind, srcEnum, name);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.created_part_source", kind.id(), name, rawSource.toLowerCase(Locale.ROOT), origin.toShortString()), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part new failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.part_new_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartSave(CommandSourceStack source, String newName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        // Resolve (kind, name) from the player's position first — walking into
        // a plot stamped by `/dt editor carriages` gives the author a source
        // plot without needing an explicit `/editor part enter` session.
        // Session is the fallback for when the player is outside every plot
        // (e.g. right after authoring a fresh name via `part enter`).
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        CarriagePartEditor.PlotLocation loc = CarriagePartEditor.plotContaining(player.blockPosition(), dims);
        CarriagePartKind kind;
        String sourceName;
        if (loc != null) {
            kind = loc.kind();
            sourceName = loc.name();
        } else {
            var session = CarriagePartEditor.currentSession(player);
            if (session.isEmpty()) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_active_part_session"));
                return 0;
            }
            kind = session.get().kind();
            sourceName = session.get().name();
        }
        String targetName;
        if (newName == null) {
            Template.Part part = new Template.Part(kind, sourceName);
            if (games.brennan.dungeontrain.editor.EditorShipped.isProtected(part)) {
                // A shipped part outside dev mode: ask rather than write over it — see EditorSaveAs.
                games.brennan.dungeontrain.editor.EditorSaveAs.prompt(player, part);
                return 1;
            }
            targetName = sourceName;
        } else {
            if (!validatePartName(source, newName)) return 0;
            targetName = newName.toLowerCase(Locale.ROOT);
        }
        try {
            CarriagePartEditor.SaveResult result = CarriagePartEditor.save(player, new games.brennan.dungeontrain.template.CarriagePartTemplateId(kind, targetName));
            final String name = targetName;
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.saved_part_config_dir", kind.id(), name), true);
            if (result.sourceAttempted()) {
                if (result.sourceWritten()) {
                    source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.also_wrote_bundled_copy").withStyle(ChatFormatting.GREEN), true);
                } else {
                    source.sendFailure(Component.translatable("chat.dungeontrain.editor.source_tree_write_failed", result.sourceError()).withStyle(ChatFormatting.YELLOW));
                }
            }
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part save failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.part_save_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartRename(CommandSourceStack source, String newName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        CarriagePartEditor.PlotLocation loc = CarriagePartEditor.plotContaining(player.blockPosition(), dims);
        CarriagePartKind kind;
        String oldName;
        if (loc != null) {
            kind = loc.kind();
            oldName = loc.name();
        } else {
            var session = CarriagePartEditor.currentSession(player);
            if (session.isEmpty()) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_part_plot_stand"));
                return 0;
            }
            kind = session.get().kind();
            oldName = session.get().name();
        }

        if (!validatePartName(source, newName)) return 0;
        String target = newName.toLowerCase(Locale.ROOT);
        if (target.equals(oldName)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.new_name_same_as", target));
            return 0;
        }
        if (CarriagePartRegistry.isKnown(kind, target)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_already_taken_2", kind.id(), target));
            return 0;
        }

        try {
            CarriagePartEditor.SaveResult result = CarriagePartEditor.saveAs(player,
                new games.brennan.dungeontrain.template.CarriagePartTemplateId(kind, oldName),
                new games.brennan.dungeontrain.template.CarriagePartTemplateId(kind, target));
            final String oldRef = oldName;
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.renamed_part", kind.id(), oldRef, kind.id(), target), true);
            if (result.sourceAttempted()) {
                if (result.sourceWritten()) {
                    source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.also_wrote_bundled_copy").withStyle(ChatFormatting.GREEN), true);
                } else {
                    source.sendFailure(Component.translatable("chat.dungeontrain.editor.source_tree_write_failed", result.sourceError()).withStyle(ChatFormatting.YELLOW));
                }
            }
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part rename failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.part_rename_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Iterate every registered part across all four kinds and save each plot's
     * current footprint. Mirrors {@code dungeontrain save all}'s shape — used
     * by the editor menu's Save / All split when the player is standing in
     * the parts grid.
     */
    private static int runPartSaveAll(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        int saved = 0;
        StringBuilder errors = new StringBuilder();
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            for (String name : CarriagePartRegistry.registeredNames(kind)) {
                try {
                    CarriagePartEditor.save(player, new games.brennan.dungeontrain.template.CarriagePartTemplateId(kind, name));
                    saved++;
                } catch (Exception e) {
                    LOGGER.error("[DungeonTrain] editor part save-all failed for {}:{}", kind.id(), name, e);
                    errors.append("\n  ").append(kind.id()).append(":").append(name)
                        .append(": ").append(e.getMessage());
                }
            }
        }
        final int s = saved;
        final String errStr = errors.toString();
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.part_save_all_saved", s, (errStr.isEmpty() ? Component.empty() : Component.translatable("chat.dungeontrain.common.errors_suffix", errStr))).withStyle(errStr.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return s > 0 ? 1 : 0;
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
            ServerLevel overworld = source.getServer().overworld();
            CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

            List<String> rowBefore = CarriagePartRegistry.registeredNames(kind);
            int oldIdx = rowBefore.indexOf(name);
            int oldCount = rowBefore.size();

            CarriageStampGuard.run(() -> CarriagePartEditor.clearPlot(overworld, kind, name, dims));
            boolean deleted = CarriagePartTemplateStore.delete(kind, name);
            // Sidecars — and in dev mode the bundled copies, including the src .nbt. `bundled` below
            // still reads the classpath copy, so the registry entry survives until the next build.
            TemplateDeletes.Report cleanup = TemplateDeletes.part(kind, name);
            boolean srcRemoved = cleanup.removed().contains("bundled template");
            boolean stillBundled = CarriagePartTemplateStore.bundled(kind, name);
            if (!stillBundled) {
                CarriagePartRegistry.unregister(kind, name);
                if (oldIdx >= 0) {
                    CarriageStampGuard.run(() ->
                        CarriagePartEditor.restampRowAfterDeletion(overworld, kind, oldIdx, oldCount, dims));
                }
            }
            final Component msg = (deleted
                ? Component.translatable("chat.dungeontrain.editor.deleted_part", kind.id(), name,
                    Component.translatable(srcRemoved ? "chat.dungeontrain.editor.deleted_part_src_removed"
                        : stillBundled ? "chat.dungeontrain.editor.deleted_part_bundled_remains"
                        : "chat.dungeontrain.editor.deleted_part_no_fallback"))
                : Component.translatable("chat.dungeontrain.editor.no_part_to_delete", kind.id(), name))
                .append(cleanup.summaryLine());
            source.sendSuccess(() -> msg, true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part reset failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.part_reset_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartPromote(CommandSourceStack source, String rawKind, String rawName) {
        CarriagePartKind kind = parsePartKind(source, rawKind);
        if (kind == null) return 0;
        String name = rawName.toLowerCase(Locale.ROOT);
        try {
            CarriagePartTemplateStore.promote(kind, name);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.promoted_part_source_tree", kind.id(), name).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part promote failed for {}:{}", kind.id(), name, t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.part_promote_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartPromoteAll(CommandSourceStack source) {
        if (!CarriagePartTemplateStore.sourceTreeAvailable()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.part_promote_all_failed").withStyle(ChatFormatting.RED));
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
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.part_promote_all_promoted", p, s, (errStr.isEmpty() ? Component.empty() : Component.translatable("chat.dungeontrain.common.errors_suffix", errStr))).withStyle(errStr.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return p > 0 ? 1 : 0;
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
            CarriagePartAssignment updated = existing.withNames(kind, List.of(name));
            CarriageVariantPartsStore.save(variant, updated);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.parts_current", variant.id(), kind.id(), name, formatAssignment(updated)).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part set failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.part_set_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartAdd(CommandSourceStack source, String rawVariant, String rawKind, String rawName, int weight) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        CarriagePartKind kind = parsePartKind(source, rawKind);
        if (kind == null) return 0;
        if (!validateSlotName(source, kind, rawName)) return 0;
        String name = rawName.toLowerCase(Locale.ROOT);
        try {
            CarriagePartAssignment existing = CarriageVariantPartsStore.get(variant).orElse(CarriagePartAssignment.EMPTY);
            CarriagePartAssignment updated = existing.withAppended(kind, name, weight);
            CarriageVariantPartsStore.save(variant, updated);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.parts_appended_weight_current", variant.id(), name, weight, kind.id(), formatAssignment(updated)).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part add failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.part_add_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
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
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.parts_not_current", variant.id(), name, kind.id(), formatSlot(existing.names(kind))));
                return 0;
            }
            CarriagePartAssignment updated = existing.withRemoved(kind, name);
            CarriageVariantPartsStore.save(variant, updated);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.parts_removed_from_current", variant.id(), name, kind.id(), formatAssignment(updated)).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part remove failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.part_remove_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartShow(CommandSourceStack source, String rawVariant) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        var assignment = CarriageVariantPartsStore.get(variant);
        if (assignment.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.has_no_parts_json", variant.id()), false);
            return 1;
        }
        String desc = formatAssignment(assignment.get());
        boolean config = CarriageVariantPartsStore.exists(variant);
        boolean bundled = CarriageVariantPartsStore.bundled(variant);
        String origin = config ? "config override" : (bundled ? "bundled default" : "memory only");
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.parts", variant.id(), origin, desc), false);
        return 1;
    }

    private static int runPartClear(CommandSourceStack source, String rawVariant) {
        CarriageVariant variant = parseVariant(source, rawVariant);
        if (variant == null) return 0;
        try {
            boolean deleted = CarriageVariantPartsStore.delete(variant);
            source.sendSuccess(() -> (deleted ? Component.translatable("chat.dungeontrain.editor.cleared_parts_json_will", variant.id()) : Component.translatable("chat.dungeontrain.editor.no_parts_json_clear", variant.id())), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part clear failed", t);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.part_clear_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static String formatAssignment(CarriagePartAssignment a) {
        return "floor=" + formatSlot(a.names(games.brennan.dungeontrain.train.CarriagePartKind.FLOOR))
            + ", walls=" + formatSlot(a.names(games.brennan.dungeontrain.train.CarriagePartKind.WALLS))
            + ", roof=" + formatSlot(a.names(games.brennan.dungeontrain.train.CarriagePartKind.ROOF))
            + ", doors=" + formatSlot(a.names(games.brennan.dungeontrain.train.CarriagePartKind.DOORS));
    }

    /** Format a slot list for human output — single-element lists unwrap to the bare name. */
    private static String formatSlot(List<String> list) {
        if (list.size() == 1) return list.get(0);
        return list.toString();
    }

    /**
     * Parse the {@code <kind>} arg of {@code /dt editor tracks new/reset}.
     * Accepts both editor-model ids ({@code track}, {@code pillar_top},
     * {@code tunnel_section}) — what {@link games.brennan.dungeontrain.client.menu.EditorMenuScreen}
     * has — and the canonical {@link games.brennan.dungeontrain.track.variant.TrackKind}
     * id. Returns null + sends a failure message if the input matches
     * neither namespace.
     */
    private static games.brennan.dungeontrain.track.variant.TrackKind parseTrackKind(
        CommandSourceStack source, String raw
    ) {
        if (raw == null || raw.isEmpty()) return null;
        String lc = raw.toLowerCase(Locale.ROOT);
        // Editor-model id form (what EditorMenuScreen passes through).
        switch (lc) {
            case "track" -> { return games.brennan.dungeontrain.track.variant.TrackKind.TILE; }
            case "pillar_top" -> { return games.brennan.dungeontrain.track.variant.TrackKind.PILLAR_TOP; }
            case "pillar_middle" -> { return games.brennan.dungeontrain.track.variant.TrackKind.PILLAR_MIDDLE; }
            case "pillar_bottom" -> { return games.brennan.dungeontrain.track.variant.TrackKind.PILLAR_BOTTOM; }
            case "adjunct_stairs" -> { return games.brennan.dungeontrain.track.variant.TrackKind.ADJUNCT_STAIRS; }
            case "tunnel_section" -> { return games.brennan.dungeontrain.track.variant.TrackKind.TUNNEL_SECTION; }
            case "tunnel_portal" -> { return games.brennan.dungeontrain.track.variant.TrackKind.TUNNEL_PORTAL; }
            default -> {}
        }
        games.brennan.dungeontrain.track.variant.TrackKind k =
            games.brennan.dungeontrain.track.variant.TrackKind.fromId(lc);
        if (k != null) return k;
        source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_track_kind_try", raw));
        return null;
    }

    /** {@code /dt editor portals enter <name>} — teleport to one room's plot. */
    private static int runPortalRoomEnter(CommandSourceStack source, String name) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        if (!ensureCategory(source, EditorCategory.PORTALS)) return 0;
        // The registry's spelling, not the argument's: the editor compares plot keys exactly.
        java.util.Optional<String> canonical = games.brennan.dungeontrain.track.variant.TrackVariantRegistry
                .find(games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM, name);
        if (canonical.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_dimensional_carriage", name));
            return 0;
        }
        games.brennan.dungeontrain.editor.PortalRoomEditor.enter(player, canonical.get());
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.entered_dimensional_carriage", canonical.get()), true);
        return 1;
    }

    // ---------- portal room sub-variants ----------

    private static final games.brennan.dungeontrain.track.variant.TrackKind PORTAL_ROOM_KIND =
        games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM;

    /**
     * {@code /dt editor portals group …} — the sub-variant layer for portal rooms: one named room
     * that stands for several room designs, each drawn against the parent's own {@code selfWeight}.
     *
     * <p>Deliberately the same verbs as {@code /dt editor contents group …}, because it is the same
     * idea one level up the template tree.</p>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> portalRoomGroupNode() {
        return Commands.literal("group")
            .then(Commands.literal("new")
                .then(Commands.argument("parent", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                    .then(Commands.argument("name", StringArgumentType.word())
                        // Bare form seeds from the room the player is standing in, falling back to
                        // the parent — same [source] shape as `contents group new` above.
                        .executes(ctx -> runPortalRoomGroupNew(ctx.getSource(),
                            StringArgumentType.getString(ctx, "parent"),
                            StringArgumentType.getString(ctx, "name"),
                            /*sourceRaw*/ null))
                        .then(Commands.argument("source", StringArgumentType.word())
                            .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                            .executes(ctx -> runPortalRoomGroupNew(ctx.getSource(),
                                StringArgumentType.getString(ctx, "parent"),
                                StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "source")))))))
            .then(Commands.literal("add")
                .then(Commands.argument("parent", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                    .then(Commands.argument("child", StringArgumentType.word())
                        .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                        .executes(ctx -> runPortalRoomGroupAdd(ctx.getSource(),
                            StringArgumentType.getString(ctx, "parent"),
                            StringArgumentType.getString(ctx, "child"),
                            games.brennan.dungeontrain.track.variant.TrackVariantGroup.DEFAULT_WEIGHT))
                        .then(Commands.argument("weight", IntegerArgumentType.integer(
                                games.brennan.dungeontrain.track.variant.TrackVariantGroup.MIN_WEIGHT,
                                games.brennan.dungeontrain.track.variant.TrackVariantGroup.MAX_WEIGHT))
                            .executes(ctx -> runPortalRoomGroupAdd(ctx.getSource(),
                                StringArgumentType.getString(ctx, "parent"),
                                StringArgumentType.getString(ctx, "child"),
                                IntegerArgumentType.getInteger(ctx, "weight")))))))
            .then(Commands.literal("set-weight")
                .then(Commands.argument("parent", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                    .then(Commands.argument("child", StringArgumentType.word())
                        .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                        .then(Commands.literal("inc").executes(ctx -> runPortalRoomGroupWeightAdjust(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "parent"),
                            StringArgumentType.getString(ctx, "child"), +1)))
                        .then(Commands.literal("dec").executes(ctx -> runPortalRoomGroupWeightAdjust(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "parent"),
                            StringArgumentType.getString(ctx, "child"), -1)))
                        .then(Commands.argument("value", IntegerArgumentType.integer(
                                games.brennan.dungeontrain.track.variant.TrackVariantGroup.MIN_WEIGHT,
                                games.brennan.dungeontrain.track.variant.TrackVariantGroup.MAX_WEIGHT))
                            .executes(ctx -> runPortalRoomGroupWeightSet(ctx.getSource(),
                                StringArgumentType.getString(ctx, "parent"),
                                StringArgumentType.getString(ctx, "child"),
                                IntegerArgumentType.getInteger(ctx, "value")))))))
            // Per-member spawn gate (min/max Diff-Level + phase) — the Sub-Variants companion's gate
            // cells dispatch these, exactly as they do for a contents group member.
            .then(minLevelTrackGroup(PORTAL_ROOM_KIND, PORTAL_ROOM_NAME_SUGGESTIONS))
            .then(maxLevelTrackGroup(PORTAL_ROOM_KIND, PORTAL_ROOM_NAME_SUGGESTIONS))
            .then(phaseTrackGroup(PORTAL_ROOM_KIND, PORTAL_ROOM_NAME_SUGGESTIONS))
            .then(Commands.literal("remove")
                .then(Commands.argument("parent", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                    .then(Commands.argument("child", StringArgumentType.word())
                        .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                        .executes(ctx -> runPortalRoomGroupRemove(ctx.getSource(),
                            StringArgumentType.getString(ctx, "parent"),
                            StringArgumentType.getString(ctx, "child"))))))
            // `move <child> <new_parent>`: re-parent in one step, carrying the member's weight, gate
            // and Stage links — `remove` then `add` would reroll them to defaults.
            .then(Commands.literal("move")
                .then(Commands.argument("child", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                    .then(Commands.argument("new_parent", StringArgumentType.word())
                        .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                        .executes(ctx -> runPortalRoomGroupMove(ctx.getSource(),
                            StringArgumentType.getString(ctx, "child"),
                            StringArgumentType.getString(ctx, "new_parent"))))))
            .then(Commands.literal("list")
                .then(Commands.argument("parent", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                    .executes(ctx -> runPortalRoomGroupList(ctx.getSource(),
                        StringArgumentType.getString(ctx, "parent")))))
            .then(Commands.literal("clear")
                .then(Commands.argument("parent", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                    .executes(ctx -> runPortalRoomGroupClear(ctx.getSource(),
                        StringArgumentType.getString(ctx, "parent")))));
    }

    /** Registered room name, or null after sending the failure message. */
    private static String parsePortalRoom(CommandSourceStack source, String raw) {
        java.util.Optional<String> found = games.brennan.dungeontrain.track.variant.TrackVariantRegistry
            .find(PORTAL_ROOM_KIND, raw);
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_dimensional_carriage", raw)
                .withStyle(ChatFormatting.RED));
            return null;
        }
        return found.get();
    }

    /**
     * Persist {@code updated} for {@code parent}, restamping whatever the change moves.
     *
     * <p>Membership decides where a plot sits — a member sits beside its parent rather than in the
     * top-level row — so every mutation goes through {@link PortalRoomEditor#relayout}, or the row
     * fills with rooms at positions nothing will clear again.</p>
     */
    private static int savePortalRoomGroup(CommandSourceStack source, String parent,
                                           games.brennan.dungeontrain.track.variant.TrackVariantGroup updated,
                                           Component message) {
        return savePortalRoomGroup(source, parent, updated, message, null);
    }

    /**
     * Put the player in room {@code name}'s plot after a re-parent or a parent delete moved it —
     * the row is re-laid out, so wherever they stood is no longer that room. No-op without a
     * player or a name. Returns the reply fragment.
     */
    private static Component landInPortalRoom(CommandSourceStack source, String name) {
        if (name == null || !(source.getEntity() instanceof ServerPlayer player)) return Component.empty();
        // The registry's spelling, not the caller's: the editor compares plot keys exactly.
        String canonical = games.brennan.dungeontrain.track.variant.TrackVariantRegistry.find(PORTAL_ROOM_KIND, name).orElse(null);
        if (canonical == null) return Component.empty();
        try {
            PortalRoomEditor.enter(player, canonical);
            return Component.translatable("chat.dungeontrain.editor.landed_entered", canonical);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] portals group: could not enter {} afterwards: {}", name, e.toString());
            return Component.empty();
        }
    }

    /** As above; {@code landIn} names the room to enter once the row is re-laid out (null = stay). */
    private static int savePortalRoomGroup(CommandSourceStack source, String parent,
                                           games.brennan.dungeontrain.track.variant.TrackVariantGroup updated,
                                           Component message, String landIn) {
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        IOException[] failure = new IOException[1];
        games.brennan.dungeontrain.editor.PortalRoomEditor.relayout(overworld, dims, () -> {
            try {
                games.brennan.dungeontrain.editor.TrackVariantGroupStore.save(PORTAL_ROOM_KIND, parent, updated);
            } catch (IOException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            LOGGER.error("[DungeonTrain] editor portals group save failed", failure[0]);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.group_save_failed", failure[0].toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        Component landed = landInPortalRoom(source, landIn);
        source.sendSuccess(() -> message.copy().append(landed).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    // ---------- track-side group members: per-member gate + Stage links ----------
    //
    // Keyed on (kind, parent, member) rather than hardcoded to the portal room, because the sidecar,
    // the gate fields and the resolver (TrackVariantRegistry.resolveGroup) are all TrackKind-generic
    // already — the portal room is simply the only kind with a group today. The nodes below are
    // attached under `editor portals group …` with the kind bound; another kind that grows groups
    // attaches the same builders with its own kind and suggestions.

    private static LiteralArgumentBuilder<CommandSourceStack> minLevelTrackGroup(
            games.brennan.dungeontrain.track.variant.TrackKind kind, SuggestionProvider<CommandSourceStack> sug) {
        return Commands.literal("minlevel")
            .then(Commands.argument("parent", StringArgumentType.word()).suggests(sug)
                .then(Commands.argument("child", StringArgumentType.word()).suggests(sug)
                    .then(Commands.literal("inc").executes(c -> applyTrackGroupMemberGate(c.getSource(), kind,
                        StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                        g -> g.withMinLevel(g.minLevel() + 1))))
                    .then(Commands.literal("dec").executes(c -> applyTrackGroupMemberGate(c.getSource(), kind,
                        StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                        g -> g.withMinLevel(g.minLevel() - 1))))
                    .then(Commands.argument("value", IntegerArgumentType.integer(0, TemplateGate.MAX_LEVEL))
                        .executes(c -> applyTrackGroupMemberGate(c.getSource(), kind,
                            StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                            g -> g.withMinLevel(IntegerArgumentType.getInteger(c, "value")))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> maxLevelTrackGroup(
            games.brennan.dungeontrain.track.variant.TrackKind kind, SuggestionProvider<CommandSourceStack> sug) {
        return Commands.literal("maxlevel")
            .then(Commands.argument("parent", StringArgumentType.word()).suggests(sug)
                .then(Commands.argument("child", StringArgumentType.word()).suggests(sug)
                    .then(Commands.literal("inc").executes(c -> applyTrackGroupMemberGate(c.getSource(), kind,
                        StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                        EditorCommand::maxLevelInc)))
                    .then(Commands.literal("dec").executes(c -> applyTrackGroupMemberGate(c.getSource(), kind,
                        StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                        EditorCommand::maxLevelDec)))
                    .then(Commands.argument("value", IntegerArgumentType.integer(TemplateGate.ALL, TemplateGate.MAX_LEVEL))
                        .executes(c -> applyTrackGroupMemberGate(c.getSource(), kind,
                            StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                            g -> g.withMaxLevel(IntegerArgumentType.getInteger(c, "value")))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> phaseTrackGroup(
            games.brennan.dungeontrain.track.variant.TrackKind kind, SuggestionProvider<CommandSourceStack> sug) {
        return Commands.literal("phase")
            .then(Commands.argument("parent", StringArgumentType.word()).suggests(sug)
                .then(Commands.argument("child", StringArgumentType.word()).suggests(sug)
                    .then(Commands.argument("phase", StringArgumentType.word()).suggests(PHASE_SUGGESTIONS)
                        .then(Commands.literal("on").executes(c -> applyTrackGroupMemberGate(c.getSource(), kind,
                            StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                            g -> togglePhase(g, StringArgumentType.getString(c, "phase"), true))))
                        .then(Commands.literal("off").executes(c -> applyTrackGroupMemberGate(c.getSource(), kind,
                            StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                            g -> togglePhase(g, StringArgumentType.getString(c, "phase"), false))))
                        .then(Commands.literal("others").executes(c -> applyTrackGroupMemberGate(c.getSource(), kind,
                            StringArgumentType.getString(c, "parent"), StringArgumentType.getString(c, "child"),
                            g -> toggleOtherPhases(g, StringArgumentType.getString(c, "phase"))))))));
    }

    /** Registered variant name of {@code kind}, or null after sending the failure message. */
    private static String parseTrackVariantName(CommandSourceStack source,
                                                games.brennan.dungeontrain.track.variant.TrackKind kind, String raw) {
        java.util.Optional<String> found = TrackVariantRegistry.find(kind, raw);
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_variant", kind.id(), raw)
                .withStyle(ChatFormatting.RED));
            return null;
        }
        return found.get();
    }

    /**
     * The member {@code memberRaw} of {@code parentRaw}'s group, or {@code null} after sending the
     * failure message. Shared by the gate and Stage editors below, which fail on the same three
     * things: an unregistered name either side, and a name that is not actually in the group.
     */
    private static games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member resolveTrackGroupMember(
            CommandSourceStack source, games.brennan.dungeontrain.track.variant.TrackKind kind,
            String parent, String member) {
        java.util.Optional<games.brennan.dungeontrain.track.variant.TrackVariantGroup> group =
            games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(kind, parent);
        if (group.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.no_group_defined", kind.id(), parent)
                .withStyle(ChatFormatting.YELLOW));
            return null;
        }
        java.util.Optional<games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member> m =
            group.get().member(member);
        if (m.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_member_group", member, parent).withStyle(ChatFormatting.YELLOW));
            return null;
        }
        return m.get();
    }

    /**
     * Read-modify-write a track-side group <em>member</em>'s spawn {@link TemplateGate} in
     * {@code parentRaw}'s {@code .group.json}. The exact contract
     * {@link #applyGroupMemberGate} has for contents: editing the inline gate detaches every Stage
     * link, snapshotting the first linked Stage's gate so the band survives. The UI routes linked
     * rows to the Stage picker rather than here, so this normally only fires on Custom members.
     */
    private static int applyTrackGroupMemberGate(CommandSourceStack source,
                                                 games.brennan.dungeontrain.track.variant.TrackKind kind,
                                                 String parentRaw, String memberRaw,
                                                 java.util.function.UnaryOperator<TemplateGate> op) {
        String parent = parseTrackVariantName(source, kind, parentRaw);
        if (parent == null) return 0;
        String member = parseTrackVariantName(source, kind, memberRaw);
        if (member == null) return 0;
        games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member m =
            resolveTrackGroupMember(source, kind, parent, member);
        if (m == null) return 0;
        try {
            String baseStage = m.stageIds().isEmpty() ? null : m.stageIds().get(0);
            TemplateGate next = op.apply(
                games.brennan.dungeontrain.editor.StageStore.effectiveGate(m.gate(), baseStage));
            saveTrackGroupMember(kind, parent, new games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member(
                m.id(), m.weight(), next, java.util.List.of()));
            // The member was just detached to Custom above (by design), so it is unlinked now.
            gateSuccess(source, parent + ":" + member, next,
                games.brennan.dungeontrain.editor.TrackVariantGroupStore.fileFor(kind, parent).toString(), null);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, kind.id() + " group", parent + ":" + member, t);
        }
    }

    /**
     * Toggle a track-side group member's Stage links — the same union semantics
     * {@link #applyGroupMemberStage} documents for contents: an already-linked id is removed,
     * otherwise added, and {@code custom}/blank clears them all (snapshotting the gate inline when
     * exactly one link was present, so a single-link detach keeps its band).
     */
    private static int applyTrackGroupMemberStage(CommandSourceStack source, String kindRaw,
                                                  String parentRaw, String memberRaw, String stageToken) {
        games.brennan.dungeontrain.track.variant.TrackKind kind = parseTrackKind(source, kindRaw);
        if (kind == null) return 0;
        String parent = parseTrackVariantName(source, kind, parentRaw);
        if (parent == null) return 0;
        String member = parseTrackVariantName(source, kind, memberRaw);
        if (member == null) return 0;
        games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member m =
            resolveTrackGroupMember(source, kind, parent, member);
        if (m == null) return 0;
        String link = resolveStageLink(source, stageToken);  // null = custom/clear, id = toggle, INVALID = reported
        if (link == INVALID_STAGE) return 0;
        try {
            games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member updated;
            if (link == null) {
                TemplateGate inline = m.gate();
                if (m.stageIds().size() == 1) {
                    inline = games.brennan.dungeontrain.editor.StageStore.effectiveGate(inline, m.stageIds().get(0));
                }
                updated = new games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member(
                    m.id(), m.weight(), inline, java.util.List.of());
            } else {
                updated = m.withStageToggled(link);
            }
            saveTrackGroupMember(kind, parent, updated);
            groupMemberStageApplySuccess(source, kind.id() + " group", parent + ":" + member, updated.stageIds());
            return 1;
        } catch (Throwable t) {
            return gateFail(source, kind.id() + " group stage", parent + ":" + member, t);
        }
    }

    /**
     * Write one updated member back into its parent's sidecar. Deliberately NOT the
     * {@link #savePortalRoomGroup} path: that relayouts the plot row because membership decides where
     * a plot sits, and a gate or Stage edit changes neither membership nor plot order.
     */
    private static void saveTrackGroupMember(games.brennan.dungeontrain.track.variant.TrackKind kind, String parent,
                                             games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member updated)
            throws IOException {
        games.brennan.dungeontrain.track.variant.TrackVariantGroup group =
            games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(kind, parent).orElseThrow();
        games.brennan.dungeontrain.editor.TrackVariantGroupStore.save(kind, parent, group.withMember(updated));
    }

    /**
     * {@code /dt editor portals group add <parent> <child> [weight]} — make an existing room a
     * sub-variant of another. Single-hop only, no cycles, and {@code default} may not be a member
     * (it is the synthetic fallback every kind keeps, not an authored room).
     */
    private static int runPortalRoomGroupAdd(CommandSourceStack source, String parentRaw, String childRaw, int weight) {
        String parent = parsePortalRoom(source, parentRaw);
        if (parent == null) return 0;
        String child = parsePortalRoom(source, childRaw);
        if (child == null) return 0;
        if (parent.equals(child)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.cannot_add_as_sub", parent)
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME.equals(child)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.default_cannot_be_sub")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (games.brennan.dungeontrain.editor.TrackVariantGroupStore.exists(PORTAL_ROOM_KIND, child)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.has_sub_variants_its", child)
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        java.util.Optional<String> existingParent = games.brennan.dungeontrain.editor.TrackVariantGroupStore
            .findParentOf(PORTAL_ROOM_KIND, child);
        if (existingParent.isPresent() && !existingParent.get().equals(parent)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.already_sub_variant", child, existingParent.get())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (games.brennan.dungeontrain.editor.TrackVariantGroupStore
                .allChildIds(PORTAL_ROOM_KIND).contains(parent)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.itself_sub_variant_making", parent)
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        games.brennan.dungeontrain.track.variant.TrackVariantGroup updated =
            games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(PORTAL_ROOM_KIND, parent)
                .orElse(games.brennan.dungeontrain.track.variant.TrackVariantGroup.EMPTY)
                .withMember(new games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member(child, weight));
        progress(source, Component.translatable("chat.dungeontrain.editor.progress_parenting", child, parent));
        return savePortalRoomGroup(source, parent, updated,
            Component.translatable("chat.dungeontrain.editor.room_added_sub_variant", parent, child, weight, updated.members().size(),
                Component.translatable(updated.members().size() == 1 ? "chat.dungeontrain.common.noun.sub_variant.singular" : "chat.dungeontrain.common.noun.sub_variant.plural")), child);
    }

    /**
     * {@code /dt editor portals group new <parent> <name> [source]} — create a brand-new
     * sub-variant of {@code parent}, seeded from an existing room in the group so it starts as a
     * variation on it rather than an empty box. Falls back to the built-in room when the seed has
     * nothing saved yet.
     *
     * <p>The seed is the room the author is standing in, not the parent: hitting "+ New" from
     * inside a sibling sub-variant is a request to copy <em>that</em>. {@code source} names it
     * explicitly (what the editor's picker sends); omitted, it is resolved from the player's plot,
     * and only failing that does it fall back to the parent.</p>
     */
    private static int runPortalRoomGroupNew(CommandSourceStack source, String parentRaw, String nameRaw,
                                             String sourceRaw) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        String parent = parsePortalRoom(source, parentRaw);
        if (parent == null) return 0;

        String key = nameRaw == null ? "" : nameRaw.toLowerCase(Locale.ROOT);
        if (!games.brennan.dungeontrain.track.variant.TrackVariantRegistry.NAME_PATTERN.matcher(key).matches()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.invalid_room_name_allowed", nameRaw)
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME.equals(key)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.default_reserved_pick_another")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (games.brennan.dungeontrain.track.variant.TrackVariantRegistry.contains(PORTAL_ROOM_KIND, key)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.dimensional_carriage_already_exists", key)
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (games.brennan.dungeontrain.editor.TrackVariantGroupStore
                .allChildIds(PORTAL_ROOM_KIND).contains(parent)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.itself_sub_variant_nesting", parent)
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        games.brennan.dungeontrain.track.variant.TrackVariantGroup existing =
            games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(PORTAL_ROOM_KIND, parent)
                .orElse(games.brennan.dungeontrain.track.variant.TrackVariantGroup.EMPTY);

        // Which room the new one is seeded from. Explicit argument wins; otherwise the plot the
        // player is standing in, the same way `tracks new` resolves its source — so "+ New" pressed
        // inside a sibling sub-variant copies that sibling rather than the group's parent.
        String seed = parent;
        if (sourceRaw != null && !sourceRaw.isEmpty()) {
            seed = parsePortalRoom(source, sourceRaw);
            if (seed == null) return 0;
        } else {
            games.brennan.dungeontrain.editor.TrackPlotLocator.PlotInfo standing =
                games.brennan.dungeontrain.editor.TrackPlotLocator.locate(player, dims);
            if (standing != null && standing.kind() == PORTAL_ROOM_KIND) seed = standing.name();
        }
        // Only the parent or one of its own sub-variants: seeding across groups would copy a room
        // whose size and variant blocks have nothing to do with this pool.
        if (!seed.equals(parent) && existing.member(seed).isEmpty()) {
            String rejected = seed;
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_part_sub_variant", rejected, parent).withStyle(ChatFormatting.RED));
            return 0;
        }
        final String seedRoom = seed;

        // Membership first: it decides where the new plot lands, so registering the name before the
        // group would stamp it in the top-level row and then move it.
        games.brennan.dungeontrain.track.variant.TrackVariantGroup updated =
            existing.withMember(new games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member(
                key, games.brennan.dungeontrain.track.variant.TrackVariantGroup.DEFAULT_WEIGHT));
        try {
            games.brennan.dungeontrain.editor.TrackVariantGroupStore.save(PORTAL_ROOM_KIND, parent, updated);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor portals group new failed", e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.group_new_failed_2", e.toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        try {
            java.util.Optional<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate> src =
                games.brennan.dungeontrain.editor.PortalRoomTemplateStore.get(overworld, seedRoom, dims);
            if (src.isPresent()) {
                // Save first so the freshly-registered plot stamps the seed's room rather than the
                // built-in one, then register inside a relayout — the new slot shifts the row.
                games.brennan.dungeontrain.editor.PortalRoomTemplateStore.save(key, src.get());
                // The geometry alone isn't the room: without the seed's variant-blocks sidecar
                // the sub-variant stamps as a plain box, because applyRoomVariants early-outs on
                // an empty sidecar. Inside this try so a failure rolls the membership back too.
                copyTrackVariantSidecar(PORTAL_ROOM_KIND, seedRoom, key);
                // Size follows the seed, not the parent: a copy of a smaller sibling that inherited
                // the parent's box would stamp short and read back as an undersized template.
                net.minecraft.core.Vec3i inherited =
                    games.brennan.dungeontrain.portal.PortalRoomSizes.sizeOf(seedRoom, dims);
                games.brennan.dungeontrain.editor.PortalRoomEditor.relayout(overworld, dims, () -> {
                    games.brennan.dungeontrain.portal.PortalRoomSizes.pending(key, inherited);
                    games.brennan.dungeontrain.track.variant.TrackVariantRegistry.register(PORTAL_ROOM_KIND, key);
                });
            } else {
                games.brennan.dungeontrain.editor.PortalRoomEditor.createFromBuiltIn(overworld, seedRoom, key, dims);
            }
        } catch (IOException e) {
            // Roll the membership back rather than leaving a sub-variant that points at nothing.
            try {
                games.brennan.dungeontrain.editor.TrackVariantGroupStore.save(PORTAL_ROOM_KIND, parent,
                    updated.withoutMember(key));
            } catch (IOException rollback) {
                LOGGER.error("[DungeonTrain] editor portals group new: rollback failed", rollback);
            }
            LOGGER.error("[DungeonTrain] editor portals group new failed", e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.group_new_failed_2", e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        games.brennan.dungeontrain.editor.PortalRoomEditor.enter(player, key);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.created_dimensional_carriage_sub", key, parent, seedRoom)
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * {@code /dt editor portals group set-weight <parent> <child> <value>} — the weight the
     * sub-variant is drawn at. {@code parent == child} sets the parent's own share of the draw
     * ({@code selfWeight}), which is how a parent stops being picked as itself.
     */
    private static int runPortalRoomGroupWeightSet(CommandSourceStack source, String parentRaw,
                                                   String childRaw, int value) {
        String parent = parsePortalRoom(source, parentRaw);
        if (parent == null) return 0;
        String child = parsePortalRoom(source, childRaw);
        if (child == null) return 0;
        java.util.Optional<games.brennan.dungeontrain.track.variant.TrackVariantGroup> existing =
            games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(PORTAL_ROOM_KIND, parent);
        if (existing.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.dimensional_carriage_has_no", parent)
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        boolean isSelf = parent.equals(child);
        if (!isSelf && existing.get().member(child).isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_sub_variant", child, parent).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        games.brennan.dungeontrain.track.variant.TrackVariantGroup updated;
        final int stored;
        if (isSelf) {
            updated = existing.get().withSelfWeight(value);
            stored = updated.selfWeight();
        } else {
            games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member m =
                existing.get().member(child).get().withWeight(value);
            updated = existing.get().withMember(m);
            stored = m.weight();
        }
        String label = isSelf ? "the parent's own share" : "'" + child + "'";
        return savePortalRoomGroup(source, parent, updated,
            Component.translatable("chat.dungeontrain.editor.room_weight_set", parent, label, stored));
    }

    /** Read-modify-write nudge for a sub-variant's weight (or the parent's own share). */
    private static int runPortalRoomGroupWeightAdjust(CommandSourceStack source, String parentRaw,
                                                      String childRaw, int delta) {
        String parent = parsePortalRoom(source, parentRaw);
        if (parent == null) return 0;
        String child = parsePortalRoom(source, childRaw);
        if (child == null) return 0;
        java.util.Optional<games.brennan.dungeontrain.track.variant.TrackVariantGroup> existing =
            games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(PORTAL_ROOM_KIND, parent);
        if (existing.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.dimensional_carriage_has_no", parent)
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        int current;
        if (parent.equals(child)) {
            current = existing.get().selfWeight();
        } else {
            java.util.Optional<games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member> m =
                existing.get().member(child);
            if (m.isEmpty()) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_sub_variant", child, parent).withStyle(ChatFormatting.YELLOW));
                return 0;
            }
            current = m.get().weight();
        }
        return runPortalRoomGroupWeightSet(source, parentRaw, childRaw, current + delta);
    }

    /**
     * {@code /dt editor portals group remove <parent> <child>} — detach a sub-variant. The room
     * itself is untouched: it returns to the top-level row as a room in its own right.
     */
    private static int runPortalRoomGroupRemove(CommandSourceStack source, String parentRaw, String childRaw) {
        String parent = parsePortalRoom(source, parentRaw);
        if (parent == null) return 0;
        String child = parsePortalRoom(source, childRaw);
        if (child == null) return 0;
        java.util.Optional<games.brennan.dungeontrain.track.variant.TrackVariantGroup> existing =
            games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(PORTAL_ROOM_KIND, parent);
        if (existing.isEmpty() || existing.get().member(child).isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_sub_variant", child, parent).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        progress(source, Component.translatable("chat.dungeontrain.editor.progress_unparenting", child, parent));
        return savePortalRoomGroup(source, parent, existing.get().withoutMember(child),
            Component.translatable("chat.dungeontrain.editor.room_removed_sub_variant", parent, child), child);
    }

    /**
     * {@code /dt editor portals group move <child> <new_parent>} — re-parent a sub-variant in one
     * step. The member record travels whole (weight, gate, Stage links); both sidecars are saved
     * inside one relayout so the plots follow. The refusals are {@code group add}'s.
     */
    private static int runPortalRoomGroupMove(CommandSourceStack source, String childRaw, String newParentRaw) {
        String child = parsePortalRoom(source, childRaw);
        if (child == null) return 0;
        String newParent = parsePortalRoom(source, newParentRaw);
        if (newParent == null) return 0;
        java.util.Optional<String> currentParent = games.brennan.dungeontrain.editor.TrackVariantGroupStore
            .findParentOf(PORTAL_ROOM_KIND, child);
        if (currentParent.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.top_level_dimensional_carriage", child, newParent, child)
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        String oldParent = currentParent.get();
        games.brennan.dungeontrain.editor.VariantGroupMoves.TrackMove move =
            games.brennan.dungeontrain.editor.VariantGroupMoves.move(
                oldParent,
                games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(PORTAL_ROOM_KIND, oldParent).orElse(null),
                newParent,
                games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(PORTAL_ROOM_KIND, newParent),
                child,
                games.brennan.dungeontrain.editor.TrackVariantGroupStore.allChildIds(PORTAL_ROOM_KIND).contains(newParent),
                games.brennan.dungeontrain.editor.TrackVariantGroupStore.exists(PORTAL_ROOM_KIND, child));
        if (!move.ok()) {
            source.sendFailure(EditorLabelCommands.moveRefusal(
                move.refusal(), child, oldParent, newParent, Component.translatable("chat.dungeontrain.editor.what_dimensional_carriage"))
                .copy().withStyle(ChatFormatting.RED));
            return 0;
        }
        progress(source, Component.translatable("chat.dungeontrain.editor.progress_moving", child, oldParent, newParent));
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        IOException[] failure = new IOException[1];
        games.brennan.dungeontrain.editor.PortalRoomEditor.relayout(overworld, dims, () -> {
            try {
                if (move.from().isEmpty()) {
                    games.brennan.dungeontrain.editor.TrackVariantGroupStore.delete(PORTAL_ROOM_KIND, oldParent);
                } else {
                    games.brennan.dungeontrain.editor.TrackVariantGroupStore.save(PORTAL_ROOM_KIND, oldParent, move.from());
                }
                games.brennan.dungeontrain.editor.TrackVariantGroupStore.save(PORTAL_ROOM_KIND, newParent, move.to());
            } catch (IOException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            LOGGER.error("[DungeonTrain] editor portals group move failed", failure[0]);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.group_move_failed", failure[0].toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        Component landed = landInPortalRoom(source, child);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.moved_sub_variant_from", child, oldParent, newParent, landed).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** {@code /dt editor portals group list <parent>} — what a room's sub-variant pool looks like. */
    private static int runPortalRoomGroupList(CommandSourceStack source, String parentRaw) {
        String parent = parsePortalRoom(source, parentRaw);
        if (parent == null) return 0;
        java.util.Optional<games.brennan.dungeontrain.track.variant.TrackVariantGroup> existing =
            games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(PORTAL_ROOM_KIND, parent);
        if (existing.isEmpty() || existing.get().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.dimensional_carriage_has_no", parent), false);
            return 1;
        }
        games.brennan.dungeontrain.track.variant.TrackVariantGroup group = existing.get();
        StringBuilder sb = new StringBuilder("Dimensional carriage '").append(parent).append("' sub-variants: ")
            .append(parent).append(" (self) = ").append(group.selfWeight());
        for (games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member m : group.members()) {
            sb.append(", ").append(m.id()).append(" = ").append(m.weight());
        }
        String line = sb.toString();
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    /** {@code /dt editor portals group clear <parent>} — drop the whole sidecar. Rooms are kept. */
    private static int runPortalRoomGroupClear(CommandSourceStack source, String parentRaw) {
        String parent = parsePortalRoom(source, parentRaw);
        if (parent == null) return 0;
        if (!games.brennan.dungeontrain.editor.TrackVariantGroupStore.exists(PORTAL_ROOM_KIND, parent)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.dimensional_carriage_has_no", parent)
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        IOException[] failure = new IOException[1];
        games.brennan.dungeontrain.editor.PortalRoomEditor.relayout(overworld, dims, () -> {
            try {
                games.brennan.dungeontrain.editor.TrackVariantGroupStore.delete(PORTAL_ROOM_KIND, parent);
            } catch (IOException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            LOGGER.error("[DungeonTrain] editor portals group clear failed", failure[0]);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.group_clear_failed", failure[0].toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.dimensional_carriage_sub_variants", parent)
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * {@code /dt editor portals mode next} — step the room plot the player is standing in to the
     * next {@link games.brennan.dungeontrain.portal.PortalRoomMode}.
     *
     * <p>What the floating plot panel's Walls button sends. Three modes is few enough that cycling
     * reaches any of them in at most two clicks, and a cycle needs no keyboard.</p>
     */
    private static int runPortalRoomModeCycle(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name, current.withMode(current.mode().next()));
    }

    /** {@code /dt editor portals copies next} — step the Copies sub-mode. */
    private static int runPortalRoomCopiesCycle(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name, current.nextCopies());
    }

    /** {@code /dt editor portals contents next} — step the Contents setting. */
    private static int runPortalRoomContentsCycle(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name, current.withContents(current.contents().next()));
    }

    /** {@code /dt editor portals doorwall next} — step Sealed → Repeated. */
    private static int runPortalRoomDoorWallCycle(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        return applyPortalRoomSettings(source, name,
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name).nextDoorWall());
    }

    /**
     * {@code /dt editor portals doorwall <sealed|repeated>} — set it outright.
     *
     * <p>Compared on the parsed id rather than trusting {@code parse} outright, for the reason the
     * sky and contents setters do the same: parsing is deliberately total, so a typo would otherwise
     * silently set the room to Sealed and report success.</p>
     */
    private static int runPortalRoomDoorWall(CommandContext<CommandSourceStack> ctx, String raw) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomDoorWall wanted =
            games.brennan.dungeontrain.portal.PortalRoomDoorWall.parse(raw);
        if (!wanted.id().equalsIgnoreCase(raw.trim())) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_door_wall_option", raw));
            return 0;
        }
        return applyPortalRoomSettings(source, name,
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name).withDoorWall(wanted));
    }

    /** {@code /dt editor portals sky next} — step Off → Daylight → Day/Night → Nether → End. */
    private static int runPortalRoomSkyCycle(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name, current.withSky(current.sky().next()));
    }

    /** {@code /dt editor portals fog next} — step Auto → On → Off. */
    private static int runPortalRoomFogCycle(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        return applyPortalRoomSettings(source, name,
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name).nextFog());
    }

    /** {@code /dt editor portals fog <auto|on|off>} — set it outright. Rejects a misspelling rather than falling back to Auto. */
    private static int runPortalRoomFog(CommandContext<CommandSourceStack> ctx, String raw) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomFog wanted =
            games.brennan.dungeontrain.portal.PortalRoomFog.parse(raw);
        if (!wanted.id().equalsIgnoreCase(raw.trim())) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_fog_option_try", raw));
            return 0;
        }
        return applyPortalRoomSettings(source, name,
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name).withFog(wanted));
    }

    /** {@code /dt editor portals exits next} — step On → Random → Off, keeping the spacing. */
    private static int runPortalRoomExitsCycle(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name, current.withExits(current.exits().next()));
    }

    /** {@code /dt editor portals exits <on|random|off>} — set it outright, keeping the spacing. */
    private static int runPortalRoomExits(CommandContext<CommandSourceStack> ctx, String raw) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomExits.Kind wanted =
            games.brennan.dungeontrain.portal.PortalRoomExits.Kind.parse(raw);
        if (!wanted.id().equalsIgnoreCase(raw.trim())) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_exits_option_try", raw));
            return 0;
        }
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name,
            current.withExits(current.exits().withKind(wanted)));
    }

    /** Nudge the Exits spacing of the room plot the player is standing in by {@code delta}. */
    private static int runPortalRoomExitEveryStep(CommandContext<CommandSourceStack> ctx, int delta) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return runPortalRoomExitEvery(ctx, current.exits().every() + delta);
    }

    /**
     * {@code /dt editor portals exitevery <tiles>} — set the X both readings of Exits measure in.
     *
     * <p>Clamped rather than rejected, the way the size commands are: a number the author typed and
     * meant should land on the nearest legal one with the reply saying so, not vanish.</p>
     */
    private static int runPortalRoomExitEvery(CommandContext<CommandSourceStack> ctx, int tiles) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name,
            current.withExits(current.exits().withEvery(tiles)));
    }

    /** Nudge how often the room walls off its exit, by {@code delta}. */
    private static int runPortalRoomExitMoveStep(CommandContext<CommandSourceStack> ctx, int delta) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return runPortalRoomExitMove(ctx, current.exits().moveChance() + delta);
    }

    /**
     * {@code /dt editor portals exitmove <0-10>} — how often this room walls off the base pair's
     * exit, so the only ways onward are the scattered copies. 0 never, 10 always.
     *
     * <p>Clamped rather than rejected, like the other numeric portal settings.</p>
     */
    private static int runPortalRoomExitMove(CommandContext<CommandSourceStack> ctx, int chance) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name,
            current.withExits(current.exits().withMoveChance(chance)));
    }

    /** {@code /dt editor portals contents <off|fit|exact|tile>} — set it outright. */
    private static int runPortalRoomContents(CommandContext<CommandSourceStack> ctx, String raw) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomContents wanted =
            games.brennan.dungeontrain.portal.PortalRoomContents.parse(raw);
        if (!wanted.id().equalsIgnoreCase(raw.trim())) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_contents_option_try", raw));
            return 0;
        }
        return applyPortalRoomSettings(source, name,
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name).withContents(wanted));
    }

    /**
     * {@code /dt editor portals sky <none|day|cycle|nether|end>} — set it outright.
     *
     * <p>Compared on the parsed id rather than trusting {@code parse} outright, for the reason the
     * contents setter does the same: parsing is deliberately total, so a typo would otherwise
     * silently set the room to Off and report success.</p>
     */
    private static int runPortalRoomSky(CommandContext<CommandSourceStack> ctx, String raw) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomSky wanted =
            games.brennan.dungeontrain.portal.PortalRoomSky.parse(raw);
        if (!wanted.id().equalsIgnoreCase(raw.trim())) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_sky_option_try", raw));
            return 0;
        }
        return applyPortalRoomSettings(source, name,
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name).withSky(wanted));
    }

    /**
     * {@code /dt editor portals copies <exact|dynamic|single>} — set it outright, keeping the block.
     *
     * <p>Compared on the KIND rather than the whole id, for the same reason the books setter is:
     * {@code single}'s id() carries the block it repeats, and rejecting the bare word would be
     * rejecting the only spelling the suggestions offer.</p>
     */
    private static int runPortalRoomCopies(CommandContext<CommandSourceStack> ctx, String raw) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomCopies.Kind wanted =
            games.brennan.dungeontrain.portal.PortalRoomCopies.Kind.parse(raw);
        if (!wanted.id().equalsIgnoreCase(kindOf(raw))) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_copies_option_try", raw));
            return 0;
        }
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name,
            current.withCopies(current.copies().withKind(wanted)));
    }

    /**
     * One plane's three verbs: {@code held}, {@code edit} and a block id.
     *
     * <p>{@code plane} null means both planes — the {@code block} branch, which is what Single had
     * before the floor and the roof were authored apart and is still the one-material gesture. The
     * three branches are identical bar that argument, so they are built once rather than written out
     * three times over.</p>
     */
    /**
     * Every setting of a dimensional carriage — its box, what its walls do, and what is found
     * inside it — hung off {@code root}.
     *
     * <p>Built twice: once on the bare {@code portals} literal, where each command acts on the plot
     * the player is standing in, and once under {@code portals room <name>}, where it acts on the
     * named room. The handlers tell the two apart through {@link #portalRoomOf}, so a setting that
     * exists in one spelling exists in the other — the editor screen sends the named form for a
     * room the author is previewing rather than standing in.</p>
     */
    private static <T extends com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, T>> T
    portalRoomSettingNodes(T root) {
        return root
            .then(portalSizeNode("length", PortalRoomResize.Axis.LENGTH))
            .then(portalSizeNode("width", PortalRoomResize.Axis.WIDTH))
            .then(portalSizeNode("height", PortalRoomResize.Axis.HEIGHT))
            // All three at once, in the order the menus label them.
            .then(Commands.literal("size")
                .then(Commands.argument("length", IntegerArgumentType.integer(1, 512))
                    .then(Commands.argument("width", IntegerArgumentType.integer(1, 512))
                        .then(Commands.argument("height", IntegerArgumentType.integer(1, 512))
                            .executes(ctx -> runPortalRoomSizeAll(ctx,
                                IntegerArgumentType.getInteger(ctx, "length"),
                                IntegerArgumentType.getInteger(ctx, "width"),
                                IntegerArgumentType.getInteger(ctx, "height")))))))
            // What the room does at its walls. `next` is what the panel's button sends; the
            // named forms are for typing, and for saying which one you want in one go.
            .then(Commands.literal("mode")
                .then(Commands.literal("next")
                    .executes(ctx -> runPortalRoomModeCycle(ctx)))
                .then(Commands.argument("mode", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_MODE_SUGGESTIONS)
                    .executes(ctx -> runPortalRoomMode(ctx,
                        StringArgumentType.getString(ctx, "mode")))))
            // Whether Endless Repetition's copies are the room block for block, or each rolled
            // afresh from its variant sidecar. Means nothing under the other modes.
            .then(Commands.literal("copies")
                .then(Commands.literal("next")
                    .executes(ctx -> runPortalRoomCopiesCycle(ctx)))
                // Which block Single repeats. Its own branch rather than a second argument on
                // the setter above, so the setter stays a bare word and a block id — which
                // carries a colon — never has to survive StringArgumentType.word().
                //
                // `block` sets both planes at once, which is what it has always meant and what
                // "I just want one material" still wants. `floor` and `roof` are the same three
                // verbs aimed at one plane each.
                .then(copiesPlaneNode("block", null))
                .then(copiesPlaneNode("floor",
                    games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane.FLOOR)
                    // How deep the floor is laid — the Floor row's [-] N [+], and a typed depth.
                    .then(Commands.literal("height")
                        .then(Commands.literal("inc")
                            .executes(ctx -> runPortalRoomCopiesFloorHeightStep(ctx, +1)))
                        .then(Commands.literal("dec")
                            .executes(ctx -> runPortalRoomCopiesFloorHeightStep(ctx, -1)))
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(
                                games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.MIN_FLOOR_HEIGHT,
                                games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.MAX_FLOOR_HEIGHT))
                            .executes(ctx -> runPortalRoomCopiesFloorHeight(ctx,
                                IntegerArgumentType.getInteger(ctx, "blocks"))))))
                .then(copiesPlaneNode("roof",
                    games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane.ROOF))
                .then(Commands.argument("copies", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_COPIES_SUGGESTIONS)
                    .executes(ctx -> runPortalRoomCopies(ctx,
                        StringArgumentType.getString(ctx, "copies")))))
            // Which block a sealing room's shell is written in. Means nothing under a mode that
            // seals nothing; bedrock unless the author has picked something else.
            .then(Commands.literal("lock")
                .then(Commands.literal("held")
                    .executes(ctx -> runPortalRoomLockHeld(ctx)))
                .then(Commands.argument("block", StringArgumentType.greedyString())
                    .executes(ctx -> runPortalRoomLockBlock(ctx,
                        StringArgumentType.getString(ctx, "block")))))
            // Whether the room is furnished from the ordinary contents pool, and how a
            // furnishing smaller than the room is fitted into it. Off by default.
            .then(Commands.literal("contents")
                .then(Commands.literal("next")
                    .executes(ctx -> runPortalRoomContentsCycle(ctx)))
                .then(Commands.argument("contents", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_CONTENTS_SUGGESTIONS)
                    .executes(ctx -> runPortalRoomContents(ctx,
                        StringArgumentType.getString(ctx, "contents")))))
            // Whether the copies standing against the portal carriages carry their own end wall
            // through the corridor mouth's plane. Sealed by default — what every room did before
            // the setting existed — and means nothing outside Endless Repetition.
            .then(Commands.literal("doorwall")
                .then(Commands.literal("next")
                    .executes(ctx -> runPortalRoomDoorWallCycle(ctx)))
                .then(Commands.argument("doorwall", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_DOOR_WALL_SUGGESTIONS)
                    .executes(ctx -> runPortalRoomDoorWall(ctx,
                        StringArgumentType.getString(ctx, "doorwall")))))
            // Whether the room is lit as though it stood outdoors, and under which sky. Off by
            // default, which is every room lit only by whatever its own build gives it.
            .then(Commands.literal("sky")
                .then(Commands.literal("next")
                    .executes(ctx -> runPortalRoomSkyCycle(ctx)))
                .then(Commands.argument("sky", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_SKY_SUGGESTIONS)
                    .executes(ctx -> runPortalRoomSky(ctx,
                        StringArgumentType.getString(ctx, "sky")))))
            // Whether the room is fogged. Auto by default — whatever the walls mode says — with
            // On and Off as the author's override either way.
            .then(Commands.literal("fog")
                .then(Commands.literal("next")
                    .executes(ctx -> runPortalRoomFogCycle(ctx)))
                .then(Commands.argument("fog", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_FOG_SUGGESTIONS)
                    .executes(ctx -> runPortalRoomFog(ctx,
                        StringArgumentType.getString(ctx, "fog")))))
            // How many extra ways back to the train an endless room scatters through its copies.
            // Means nothing under the modes that do not repeat.
            .then(Commands.literal("exits")
                .then(Commands.literal("next")
                    .executes(ctx -> runPortalRoomExitsCycle(ctx)))
                .then(Commands.argument("exits", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_EXITS_SUGGESTIONS)
                    .executes(ctx -> runPortalRoomExits(ctx,
                        StringArgumentType.getString(ctx, "exits")))))
            // The X both readings of Exits are measured in — every X tiles, or one tile in X.
            .then(Commands.literal("exitevery")
                .then(Commands.literal("inc")
                    .executes(ctx -> runPortalRoomExitEveryStep(ctx, +1)))
                .then(Commands.literal("dec")
                    .executes(ctx -> runPortalRoomExitEveryStep(ctx, -1)))
                .then(Commands.argument("tiles", IntegerArgumentType.integer(
                        games.brennan.dungeontrain.portal.PortalRoomExits.MIN_EVERY,
                        games.brennan.dungeontrain.portal.PortalRoomExits.MAX_EVERY))
                    .executes(ctx -> runPortalRoomExitEvery(ctx,
                        IntegerArgumentType.getInteger(ctx, "tiles")))))
            // How often a Random room walls off the base pair's exit, so the only ways onward
            // are the copies. 0 never, 10 always.
            .then(Commands.literal("exitmove")
                .then(Commands.literal("inc")
                    .executes(ctx -> runPortalRoomExitMoveStep(ctx, +1)))
                .then(Commands.literal("dec")
                    .executes(ctx -> runPortalRoomExitMoveStep(ctx, -1)))
                .then(Commands.argument("chance", IntegerArgumentType.integer(
                        games.brennan.dungeontrain.portal.PortalRoomExits.MOVE_NEVER,
                        games.brennan.dungeontrain.portal.PortalRoomExits.MOVE_ALWAYS))
                    .executes(ctx -> runPortalRoomExitMove(ctx,
                        IntegerArgumentType.getInteger(ctx, "chance")))))
            // Whether every book found in the room is by one author, and how that author is
            // picked. Off by default — the ordinary mixed community pool.
            .then(Commands.literal("books")
                .then(Commands.literal("next")
                    .executes(ctx -> runPortalRoomBooksCycle(ctx)))
                .then(Commands.argument("books", StringArgumentType.word())
                    .suggests(PORTAL_ROOM_BOOKS_SUGGESTIONS)
                    .executes(ctx -> runPortalRoomBooks(ctx,
                        StringArgumentType.getString(ctx, "books")))))
            // The four shares of the roll — three ways to name an author, plus the tally —
            // and the band of author a room will accept. All six mean nothing while Books is
            // Off, which is why the edit screen is only reachable from a room that stocks at all.
            .then(portalRoomBookWeightNode("booksself",
                games.brennan.dungeontrain.portal.PortalRoomBooks.Share.SELF))
            .then(portalRoomBookWeightNode("booksplayer",
                games.brennan.dungeontrain.portal.PortalRoomBooks.Share.PLAYER))
            .then(portalRoomBookWeightNode("bookssignature",
                games.brennan.dungeontrain.portal.PortalRoomBooks.Share.SIGNATURE))
            .then(portalRoomBookWeightNode("booksstats",
                games.brennan.dungeontrain.portal.PortalRoomBooks.Share.STATS))
            .then(portalRoomBookBoundNode("booksmin", true))
            .then(portalRoomBookBoundNode("booksmax", false));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> copiesPlaneNode(
        String literal,
        @javax.annotation.Nullable games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane plane
    ) {
        return Commands.literal(literal)
            .then(Commands.literal("held")
                .executes(ctx -> runPortalRoomCopiesBlockHeld(ctx, plane)))
            .then(Commands.literal("edit")
                .executes(ctx -> runPortalRoomCopiesBlockEdit(ctx, plane)))
            .then(Commands.argument("block", StringArgumentType.greedyString())
                .executes(ctx -> runPortalRoomCopiesBlock(ctx, plane,
                    StringArgumentType.getString(ctx, "block"))));
    }

    /**
     * {@code /dt editor portals copies block held} — set Single's block to what the author is
     * holding.
     *
     * <p>Four things are worth holding, and one of them is nothing. A plain <b>block</b> is the
     * ordinary case. A <b>variant clipboard</b>, copied from a cell by the Block Variant menu,
     * brings that cell's whole candidate list over in one gesture — the same value the Edit button
     * authors in place. A <b>filled bucket</b> sets the plane to that liquid — a water floor, a lava
     * roof — stored as the fluid's <i>source</i> state, since a flowing state has nothing feeding it
     * and drains to air (see {@link games.brennan.dungeontrain.editor.VariantLiquids}). An
     * <b>empty hand</b> sets the plane to air: a floor of gaps, or a roof that is open sky.</p>
     *
     * <p>Empty-hand-means-air is not invented here — it is what the Block Variant menu's Add does
     * with an empty hand, down to the same command-block sentinel, so the gesture an author already
     * knows from every other cell in the game means the same thing on these two rows.</p>
     *
     * <p>The held item rather than a typed id because this is a picking gesture, and the author is
     * already standing in the plot with their palette in their hotbar. The menu is opened by a key
     * toggle rather than by holding a tool, so the main hand is free.</p>
     */
    private static int runPortalRoomCopiesBlockHeld(
        CommandContext<CommandSourceStack> ctx,
        @javax.annotation.Nullable games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane plane
    ) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.only_player_can_pick"));
            return 0;
        }
        ItemStack held = player.getMainHandItem();

        if (held.isEmpty()) {
            // The empty-placeholder sentinel, stored verbatim: PortalRoomSinglePlanes translates it
            // to a no-cascade air write at stamp time, exactly as applyRoomVariants does for a cell.
            // Air is a candidate like any other here, so this is authoring the plane rather than
            // clearing it — the row keeps showing a value, and the plane is deliberately empty
            // rather than merely unset.
            return savePortalRoomCopiesVariant(source, name, plane, java.util.List.of(
                new games.brennan.dungeontrain.editor.VariantState(
                    games.brennan.dungeontrain.editor.CarriageVariantBlocks.emptyPlaceholder(), null)));
        }

        if (held.getItem() instanceof games.brennan.dungeontrain.item.VariantClipboardItem) {
            java.util.List<games.brennan.dungeontrain.editor.VariantState> states =
                games.brennan.dungeontrain.item.VariantClipboardItem.decodeStates(
                    games.brennan.dungeontrain.item.VariantClipboardItem.readClipboardTag(held));
            if (states.isEmpty()) {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.that_clipboard_empty"));
                return 0;
            }
            return savePortalRoomCopiesVariant(source, name, plane, states);
        }
        // A bucket is a BucketItem, not a BlockItem, so it has to be asked about before the block
        // branch or it falls through to the rejection below — the same gap VariantLiquids closed for
        // the Block Variant menu's Add gesture. Source state only; buckets carry no block-entity NBT.
        net.minecraft.world.level.block.state.BlockState bucketSource =
            games.brennan.dungeontrain.editor.VariantLiquids.sourceStateFrom(held);
        if (bucketSource != null) {
            return savePortalRoomCopiesVariant(source, name, plane, java.util.List.of(
                new games.brennan.dungeontrain.editor.VariantState(bucketSource, null)));
        }
        if (held.getItem() instanceof BlockItem blockItem) {
            return savePortalRoomCopiesVariant(source, name, plane, java.util.List.of(
                new games.brennan.dungeontrain.editor.VariantState(
                    blockItem.getBlock().defaultBlockState(), null)));
        }
        source.sendFailure(Component.translatable("chat.dungeontrain.editor.hold_block_or_variant"));
        return 0;
    }

    /**
     * {@code /dt editor portals lock held} — write this room's shell in whatever the author is
     * holding.
     *
     * <p>A picking gesture rather than a typed id, for the reason the Copies rows are: the value is
     * any block in the registry, the author is already standing in the plot with their palette in
     * their hotbar, and the menu is opened by a key toggle so their main hand is free.</p>
     *
     * <p><b>An empty hand means air</b>, the same as it does on a Copies plane row — and here that
     * genuinely unseals the room: no skin, no corridor shells, no plugs. It is the author saying the
     * shell should not be there, which is why it succeeds rather than failing as a mistake.</p>
     */
    private static int runPortalRoomLockHeld(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.only_player_can_pick"));
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return applyPortalRoomLock(source, name,
                games.brennan.dungeontrain.portal.PortalRoomLock.AIR_BLOCK);
        }
        if (held.getItem() instanceof BlockItem blockItem) {
            return applyPortalRoomLock(source, name,
                net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(blockItem.getBlock()).toString());
        }
        source.sendFailure(Component.translatable("chat.dungeontrain.editor.hold_block_or_nothing"));
        return 0;
    }

    /** {@code /dt editor portals lock <id>} — set the shell's block by name, for a script. */
    private static int runPortalRoomLockBlock(CommandContext<CommandSourceStack> ctx, String raw) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        String id = raw == null ? "" : raw.trim();
        // Air by name as well as by empty hand: stateFor reports air as "no block" on purpose, so it
        // cannot answer this one question, and a script should be able to say what the row can.
        if (!games.brennan.dungeontrain.portal.PortalRoomLock.AIR_BLOCK.equalsIgnoreCase(id)
                && !"air".equalsIgnoreCase(id)
                && games.brennan.dungeontrain.portal.PortalRoomSinglePlanes.stateFor(id).isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_block_world_knows", raw));
            return 0;
        }
        return applyPortalRoomLock(source, name,
            "air".equalsIgnoreCase(id)
                ? games.brennan.dungeontrain.portal.PortalRoomLock.AIR_BLOCK : id);
    }

    /** Save {@code blockId} as this room's shell — the one write both lock verbs share. */
    private static int applyPortalRoomLock(CommandSourceStack source, String name, String blockId) {
        return applyPortalRoomSettings(source, name,
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name).withLockBlock(blockId));
    }

    /** {@code /dt editor portals copies <block|floor|roof> <id>} — set it by name, for a script. */
    private static int runPortalRoomCopiesBlock(
        CommandContext<CommandSourceStack> ctx,
        @javax.annotation.Nullable games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane plane,
        String raw
    ) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        java.util.Optional<net.minecraft.world.level.block.state.BlockState> state =
            games.brennan.dungeontrain.portal.PortalRoomSinglePlanes.stateFor(raw);
        if (state.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_block_world_knows", raw));
            return 0;
        }
        return savePortalRoomCopiesVariant(source, name, plane, java.util.List.of(
            new games.brennan.dungeontrain.editor.VariantState(state.get(), null)));
    }

    /**
     * {@code /dt editor portals copies block edit} — open the Block Variant menu on this room's
     * Copies block.
     *
     * <p>The same menu that authors every other cell in the game, pointed at a one-cell plot
     * ({@code PortalRoomCopiesPlot}). Add, weights, rotation modes and Copy all work there, so
     * turning the one block into a variant needs no authoring surface of its own.</p>
     */
    private static int runPortalRoomCopiesBlockEdit(
        CommandContext<CommandSourceStack> ctx,
        @javax.annotation.Nullable games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane plane
    ) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.only_player_can_open"));
            return 0;
        }
        // Both-planes has no menu of its own — one panel authors one cell, so `block edit` opens
        // the floor and the row's own Edit buttons are how the roof is reached.
        games.brennan.dungeontrain.editor.BlockVariantMenuController.openForCopies(player, name,
            plane == null ? games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane.FLOOR : plane);
        return 1;
    }

    /** {@code /dt editor portals copies floor height inc|dec} — step the floor's depth by one. */
    private static int runPortalRoomCopiesFloorHeightStep(CommandContext<CommandSourceStack> ctx, int delta) {
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        int current = games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.forRoom(
            name, games.brennan.dungeontrain.portal.PortalRoomSettings.of(name).copies()).floorHeight();
        return runPortalRoomCopiesFloorHeight(ctx, current + delta);
    }

    /**
     * {@code /dt editor portals copies floor height <blocks>} — how deep the floor palette is laid,
     * from the tile's bottom up.
     *
     * <p>Clamped to what the room can hold: the roof plane must survive and at least one row must
     * stay open between the two, which is the same bound the stamp applies
     * ({@code PortalRoomSinglePlanes.floorHeightFor}). Clamping here rather than only there keeps
     * the row honest — the number the author sees is the number they will get.</p>
     */
    private static int runPortalRoomCopiesFloorHeight(CommandContext<CommandSourceStack> ctx, int blocks) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        net.minecraft.core.Vec3i size = games.brennan.dungeontrain.portal.PortalRoomSizes.sizeOf(name, dims);

        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant current =
            games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.forRoom(
                name, games.brennan.dungeontrain.portal.PortalRoomSettings.of(name).copies());
        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant wanted = current.withFloorHeight(blocks);
        int applied = games.brennan.dungeontrain.portal.PortalRoomSinglePlanes.floorHeightFor(wanted, size);
        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant variant = current.withFloorHeight(applied);
        try {
            variant.save(name);
            if (EditorDevMode.isEnabled()) variant.saveToSource(name);
        } catch (IOException e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.could_not_save_copies", name, e.getMessage()));
            return 0;
        }
        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.invalidate(name);
        source.sendSuccess(() -> Component.translatable(
            "chat.dungeontrain.editor.dimensional_carriage_floor_height_now", name, applied)
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * Store {@code states} as what one of this room's planes repeats — or both of them, when
     * {@code plane} is null.
     *
     * <p>Read-modify-write on the room's existing palettes rather than a fresh value, so setting the
     * roof leaves the floor exactly as it was. The plane not named keeps whatever it had, which is
     * the whole point of authoring the two apart.</p>
     *
     * <p>Replaces rather than appends within a plane: the value is one block, and the Block Variant
     * menu is what turns it into several. Two writers, one whole-value handoff each.</p>
     *
     * <p>Nothing is rejected on content. An entry that resolves to air is a gap the author asked
     * for, not a mistake to catch here.</p>
     */
    private static int savePortalRoomCopiesVariant(
        CommandSourceStack source, String name,
        @javax.annotation.Nullable games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane plane,
        java.util.List<games.brennan.dungeontrain.editor.VariantState> states
    ) {
        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant current =
            games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.forRoom(
                name, games.brennan.dungeontrain.portal.PortalRoomSettings.of(name).copies());
        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant variant = plane == null
            ? games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.of(states)
            : current.withStates(plane, states);
        try {
            variant.save(name);
            if (EditorDevMode.isEnabled()) variant.saveToSource(name);
        } catch (IOException e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.could_not_save_copies", name, e.getMessage()));
            return 0;
        }
        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.invalidate(name);
        String what = plane == null ? "floor and roof" : plane.displayName().toLowerCase(java.util.Locale.ROOT);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.dimensional_carriage_copies_now", name, what, copiesPaletteText(variant, plane == null ? games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane.FLOOR : plane))
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * One plane's palette as text — with the empty-placeholder sentinel read back as {@code air}.
     *
     * <p>The sentinel is a placeholder block on disk and a gap at stamp time, and the id is what the
     * author would be shown otherwise. Telling them their roof is now
     * {@code dungeontrain:variant_placeholder} describes the storage rather than the choice.</p>
     */
    private static String copiesPaletteText(
        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant variant,
        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane plane
    ) {
        java.util.List<games.brennan.dungeontrain.editor.VariantState> states = variant.states(plane);
        if (states.isEmpty()) return "unset";
        java.util.List<String> out = new java.util.ArrayList<>(states.size());
        for (games.brennan.dungeontrain.editor.VariantState st : states) {
            out.add(games.brennan.dungeontrain.editor.CarriageVariantBlocks.isEmptyPlaceholder(st.state())
                ? "air"
                : net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(st.state().getBlock()).toString());
        }
        return String.join(", ", out);
    }

    /**
     * The two Copies palettes for the status line — {@code "stone"} when both planes agree, and
     * {@code "floor stone, roof glass"} when they do not.
     *
     * <p>Named separately only when they differ: a room whose planes match is the ordinary case, and
     * spelling out "floor X, roof X" for it would put the split in front of every author who never
     * asked for it.</p>
     */
    private static String copiesPalettesText(
        String name, games.brennan.dungeontrain.portal.PortalRoomSettings settings
    ) {
        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant variant =
            games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.forRoom(name, settings.copies());
        String floor = copiesPaletteText(variant,
            games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane.FLOOR);
        String roof = copiesPaletteText(variant,
            games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane.ROOF);
        if (floor.equals(roof)) return floor;
        return "floor " + floor + "; roof " + roof;
    }

    /** {@code /dt editor portals books next} — step the author lock. */
    private static int runPortalRoomBooksCycle(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name, current.withBooks(current.books().next()));
    }

    /** {@code /dt editor portals books <off|self|player|signature>} — set it outright. */
    private static int runPortalRoomBooks(CommandContext<CommandSourceStack> ctx, String raw) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomBooks wanted =
            games.brennan.dungeontrain.portal.PortalRoomBooks.parse(raw);
        // parse is total by design, so a typo would silently set the default rather than complain.
        // Compare on the KIND rather than the whole id: `random:1:1:1` is a legitimate spelling that
        // id() collapses back to the bare `random`, and rejecting it would be rejecting what the
        // weight steppers themselves send.
        if (!wanted.kind().id().equalsIgnoreCase(kindOf(raw))) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_books_option_try", raw));
            return 0;
        }
        return applyPortalRoomSettings(source, name,
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name).withBooks(wanted));
    }

    /** The kind half of a books argument — everything before the first weight separator. */
    private static String kindOf(String raw) {
        String text = raw == null ? "" : raw.trim();
        int colon = text.indexOf(':');
        return colon < 0 ? text : text.substring(0, colon);
    }

    /**
     * {@code /dt editor portals books<self|player|signature> <inc|dec|N>} — one weight of a Random
     * room's roll.
     *
     * <p>One handler for all three: the rows are identical but for which share they name, and three
     * copies of this would be three places for the clamp or the plot lookup to drift apart.</p>
     */
    private static int runPortalRoomBookWeight(CommandContext<CommandSourceStack> ctx,
                                               games.brennan.dungeontrain.portal.PortalRoomBooks.Share which,
                                               int delta, Integer exact) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        games.brennan.dungeontrain.portal.PortalRoomBooks books = current.books();
        int wanted = exact != null ? exact : books.weightFor(which) + delta;
        return applyPortalRoomSettings(source, name,
            current.withBooks(books.withWeightFor(which, wanted)));
    }

    /** {@code /dt editor portals mode <mode>} — set it outright. */
    private static int runPortalRoomMode(CommandContext<CommandSourceStack> ctx, String raw) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomMode wanted =
            games.brennan.dungeontrain.portal.PortalRoomMode.parse(raw);
        // parse is total by design, so a typo would silently set the default rather than complain.
        // Worth complaining about here: the player typed something and meant it.
        if (!wanted.id().equalsIgnoreCase(raw.trim())) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_dimensional_carriage_mode", raw));
            return 0;
        }
        return applyPortalRoomSettings(source, name,
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name).withMode(wanted));
    }

    private static int applyPortalRoomSettings(
        CommandSourceStack source, String name,
        games.brennan.dungeontrain.portal.PortalRoomSettings settings
    ) {
        try {
            games.brennan.dungeontrain.track.variant.TrackVariantWeights.setMode(
                games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM, name,
                settings.toTag());
        } catch (IOException e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.could_not_save_settings", name, e.getMessage()));
            return 0;
        }
        // The Copies and Exits halves are only worth reporting when they mean anything.
        String copies = settings.copiesApply()
            ? ", copies: " + settings.copies().displayName()
                + (settings.effectiveCopies().repeatsOneBlock()
                    ? " (" + copiesPalettesText(name, settings) + ")"
                    : "")
            : "";
        String contents = ", contents: " + settings.contents().displayName();
        String exits = settings.exitsApply()
            ? ", exits: " + settings.exits().displayName()
                + (settings.exits().lays() ? " (every " + settings.exits().every() + ")" : "")
                + (settings.exits().effectiveMoveChance() > 0
                    ? ", moved exit " + settings.exits().moveChance() + "/10" : "")
            : "";
        // Only worth a word when the room actually locks: "books: Off" on every message would be
        // noise on the four rooms out of five that never touch this.
        String books = settings.books().locks()
            ? ", books: " + settings.books().displayName()
                + " (" + settings.books().selfWeight() + "/" + settings.books().playerWeight()
                + "/" + settings.books().signatureWeight() + "/" + settings.books().statsWeight() + ")"
                + ", authors with " + settings.books().minBooks() + "+"
                + (settings.books().maxBooks() == games.brennan.dungeontrain.portal.PortalRoomBooks.NO_MAXIMUM
                    ? "" : "\u2013" + settings.books().maxBooks())
                + " books"
            : "";
        // Same rule as Books: only worth a word when the room actually asks for a sky.
        String sky = settings.sky().lights()
            ? ", sky: " + settings.sky().displayName()
            : "";
        // Same rule again: "door position: centred" on every room would be noise, since centred is
        // what every room did before this setting existed.
        int doorOffsetValue = settings.doorOffset().value();
        String doorOffset = doorOffsetValue != 0
            ? ", door position: " + (doorOffsetValue > 0 ? "+" + doorOffsetValue : doorOffsetValue)
            : "";
        // Same rule again: only worth a word when the room seals AND the author has moved off
        // bedrock, which is what every sealed room was before the block could be chosen.
        String lock = settings.lockApplies()
            && !games.brennan.dungeontrain.portal.PortalRoomLock.DEFAULT.equals(settings.lock())
            ? ", sealed in: " + (settings.lock().isAir() ? "nothing" : settings.lock().blockId())
            : "";
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.dimensional_carriage_walls_portals", name, settings.mode().displayName() + copies + contents + exits + books + sky + doorOffset + lock, subVariantNote(name)).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * A note naming this room's sub-variants, or empty when it has none.
     *
     * <p>A sub-variant is a room in its own right: the draw resolves a group to a <b>member</b> and
     * the settings are read from that member's name, so a member keeps whatever it says at its own
     * walls and a parent's settings never reach it. That is the intended design — each design
     * decides its own boundary — but from inside the editor it looks indistinguishable from the
     * setting not having applied, because the parent's plot is the one you were standing in. Saying
     * so here answers it exactly where the confusion happens.</p>
     */
    private static String subVariantNote(String name) {
        var group = games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(
            games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM, name);
        if (group.isEmpty() || group.get().members().isEmpty()) return "";
        String members = group.get().members().stream()
            .map(games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member::id)
            .collect(java.util.stream.Collectors.joining(", "));
        return " Its sub-variants (" + members + ") keep their own settings — set them in their"
            + " own plots.";
    }

    /** The portal room plot the player is standing in, or null with the reason already reported. */
    /**
     * The room a portal-room setting command acts on.
     *
     * <p>Named when the command was typed under {@code portals room <name>} — the form the editor
     * screen sends for a room the author is only looking at — and the plot the player is standing
     * in otherwise, which is what every one of these commands meant before the named form existed.
     * Both roots share one subtree, so the two spellings cannot drift apart in what they accept.</p>
     */
    private static String portalRoomOf(CommandContext<CommandSourceStack> ctx) {
        String named;
        try {
            named = ctx.getArgument("room", String.class);
        } catch (IllegalArgumentException absent) {
            return portalRoomPlotUnderPlayer(ctx.getSource());
        }
        String room = named == null ? "" : named.trim();
        if (!games.brennan.dungeontrain.track.variant.TrackVariantRegistry
                .namesFor(games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM)
                .contains(room)) {
            ctx.getSource().sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_dimensional_carriage", room));
            return null;
        }
        return room;
    }

    private static String portalRoomPlotUnderPlayer(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return null;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        String name = PortalRoomEditor.plotContaining(player.blockPosition(), dims);
        if (name == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.stand_dimensional_carriage_plot"));
        }
        return name;
    }

    /**
     * One {@code <axis> inc|dec|<blocks>} node, shared by length / width / height.
     *
     * <p>The bare {@code <blocks>} form takes an absolute value; {@code inc} / {@code dec} step by
     * one so the menu steppers can be tapped. All three land on
     * {@link PortalRoomEditor#setSize}, which clamps to what this world's corridor allows.</p>
     */
    /**
     * One share of a Random room's roll — {@code inc}, {@code dec} or an outright number.
     *
     * <p>Three identical nodes built from one place for the same reason
     * {@link #runPortalRoomBookWeight} is one handler: the only thing that differs between Self,
     * Player and Signature here is which share the number lands on.</p>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> portalRoomBookWeightNode(
        String literal, games.brennan.dungeontrain.portal.PortalRoomBooks.Share which
    ) {
        return Commands.literal(literal)
            .then(Commands.literal("inc")
                .executes(ctx -> runPortalRoomBookWeight(ctx, which, +1, null)))
            .then(Commands.literal("dec")
                .executes(ctx -> runPortalRoomBookWeight(ctx, which, -1, null)))
            .then(Commands.argument("weight", IntegerArgumentType.integer(
                    games.brennan.dungeontrain.portal.PortalRoomBooks.MIN_WEIGHT,
                    games.brennan.dungeontrain.portal.PortalRoomBooks.MAX_WEIGHT))
                .executes(ctx -> runPortalRoomBookWeight(ctx, which, 0,
                    IntegerArgumentType.getInteger(ctx, "weight"))));
    }

    /**
     * One end of the band of author a room accepts — {@code inc}, {@code dec} or an outright number.
     *
     * <p>Same shape as the weight node next to it: the only difference is which number it lands on,
     * so both go through one handler rather than four near-copies.</p>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> portalRoomBookBoundNode(
        String literal, boolean minimum
    ) {
        return Commands.literal(literal)
            .then(Commands.literal("inc")
                .executes(ctx -> runPortalRoomBookBound(ctx, minimum, +1, null)))
            .then(Commands.literal("dec")
                .executes(ctx -> runPortalRoomBookBound(ctx, minimum, -1, null)))
            .then(Commands.argument("books", IntegerArgumentType.integer(
                    games.brennan.dungeontrain.portal.PortalRoomBooks.MIN_BOOK_BOUND,
                    games.brennan.dungeontrain.portal.PortalRoomBooks.MAX_BOOK_BOUND))
                .executes(ctx -> runPortalRoomBookBound(ctx, minimum, 0,
                    IntegerArgumentType.getInteger(ctx, "books"))));
    }

    /** {@code /dt editor portals books<min|max> <inc|dec|N>} — the band of author this room accepts. */
    private static int runPortalRoomBookBound(CommandContext<CommandSourceStack> ctx, boolean minimum,
                                              int delta, Integer exact) {
        CommandSourceStack source = ctx.getSource();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        games.brennan.dungeontrain.portal.PortalRoomBooks books = current.books();
        int at = minimum ? books.minBooks() : books.maxBooks();
        int wanted = exact != null ? exact : at + delta;
        return applyPortalRoomSettings(source, name, current.withBooks(
            minimum ? books.withMinBooks(wanted) : books.withMaxBooks(wanted)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> portalSizeNode(
        String literal, PortalRoomResize.Axis axis
    ) {
        return Commands.literal(literal)
            .then(Commands.literal("inc").executes(ctx -> runPortalRoomSizeStep(ctx, axis, +1)))
            .then(Commands.literal("dec").executes(ctx -> runPortalRoomSizeStep(ctx, axis, -1)))
            // Loosest floor AND ceiling of any axis, not the length's — this node is shared by
            // length, width and height, and a parser bound is a silent rejection where clampSize is
            // a visible one. PortalRoomLayout.clampSize stays the single authority on what is legal.
            //
            // The ceiling was MAX_LENGTH (48), which was fine while every axis capped there; height
            // now goes to MAX_HEIGHT (80), and `portals height 70` was refused by the parser with
            // "Integer must not be more than 48" before the clamp could say anything.
            .then(Commands.argument("blocks", IntegerArgumentType.integer(
                    Math.min(PortalRoomLayout.MIN_LENGTH, PortalRoomLayout.MIN_HEIGHT),
                    Math.max(PortalRoomLayout.MAX_LENGTH,
                        Math.max(PortalRoomLayout.MAX_WIDTH, PortalRoomLayout.MAX_HEIGHT))))
                .executes(ctx -> runPortalRoomSize(ctx, axis,
                    IntegerArgumentType.getInteger(ctx, "blocks"))));
    }

    /**
     * {@code /dt editor portals size <length> <width> <height>} — set the whole box at once.
     *
     * <p>Argument bounds are deliberately loose; {@link PortalRoomLayout#clampSize} decides what is
     * legal for this world and the reply says so when it had to pull a number in. A typed size that
     * is silently rejected would be worse than one that is visibly clamped.</p>
     */
    private static int runPortalRoomSizeAll(CommandContext<CommandSourceStack> ctx, int length, int width, int height) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        net.minecraft.core.Vec3i wanted = new net.minecraft.core.Vec3i(length, height, width);
        net.minecraft.core.Vec3i applied = PortalRoomEditor.setSize(overworld, name, wanted, dims);
        boolean clamped = !applied.equals(wanted);
        String note = clamped
            ? " (clamped from " + length + " " + width + " " + height
                + " — the room must still seal the corridor mouth, and fit under the sky)"
            : "";
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.dimensional_carriage_now_long", name, applied.getX(), applied.getZ(), applied.getY(), note).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * Which faces a resize moved, as a sentence fragment — or empty when nothing changed.
     *
     * <p>Worth saying out loud because it is not what the steppers used to do: growth landed on the
     * far face every time, and an author who has built against one wall wants to know which way the
     * room just opened. A shrink also says that the row is recoverable, since it no longer looks
     * like one from the world.</p>
     */
    private static String describeFaces(CarriageDims dims, PortalRoomResize.Axis axis,
                                        int before, int after) {
        java.util.List<PortalRoomResize.Step> steps = PortalRoomResize.plan(
            dims, axis, PortalRoomResize.with(net.minecraft.core.Vec3i.ZERO, axis, before), after);
        if (steps.isEmpty()) return "";
        long min = steps.stream().filter(s -> s.side() == PortalRoomResize.Side.MIN).count();
        long max = steps.size() - min;
        // Height never alternates — its floor is the corridor's, so only the ceiling can move.
        String where = axis == PortalRoomResize.Axis.HEIGHT ? "the ceiling"
            : min > 0 && max > 0 ? "both ends"
            : min > 0 ? "the near end" : "the far end";
        return steps.get(0).grow()
            // Says "empty" out loud because the new space used to arrive floored, walled and lit,
            // and an author who remembers that would read air as a stamp that half-failed.
            ? " Grown at " + where + " — the new space is empty, ready to build in."
            : " Cropped at " + where + " — step back up and those blocks come back.";
    }

    /** Nudge one axis of the room plot the player is standing in by {@code delta}. */
    private static int runPortalRoomSizeStep(CommandContext<CommandSourceStack> ctx, PortalRoomResize.Axis axis, int delta) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        String name = portalRoomOf(ctx);
        if (name == null) return 0;
        int current = PortalRoomEditor.axisOf(PortalRoomEditor.plotSize(name, dims), axis);
        return runPortalRoomSize(ctx, axis, current + delta);
    }

    /**
     * {@code /dt editor portals length|width|height <blocks>} — restamp the room plot the player is
     * standing in with that axis set.
     *
     * <p>Non-destructive: the live room is carried across, length and width alternate which face
     * moves, and a shrink files the row it removes so stepping back up returns it. Nothing is written
     * to disk until the next {@code /dt save}, which is what makes the size permanent.</p>
     */
    private static int runPortalRoomSize(CommandContext<CommandSourceStack> ctx, PortalRoomResize.Axis axis, int blocks) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        String name = portalRoomOf(ctx);
        if (name == null) return 0;

        int before = PortalRoomEditor.axisOf(PortalRoomEditor.plotSize(name, dims), axis);
        net.minecraft.core.Vec3i applied = PortalRoomEditor.setSize(overworld, name, axis, blocks, dims);
        int value = PortalRoomEditor.axisOf(applied, axis);
        String axisName = axis.name().toLowerCase(Locale.ROOT);
        String note = value == blocks ? ""
            : " (clamped from " + blocks + " — the room must still seal the corridor mouth, and fit under the sky)";
        String faces = describeFaces(dims, axis, before, value);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.dimensional_carriage_now_what", name, axisName, value + note, faces).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * Restamp the editor plot for {@code kind} so it picks up the just-set
     * active-variant marker.
     */
    private static void restampPlotForKind(
        ServerLevel overworld, games.brennan.dungeontrain.track.variant.TrackKind kind, CarriageDims dims
    ) {
        switch (kind) {
            case TILE -> games.brennan.dungeontrain.editor.TrackEditor.stampPlot(overworld, dims);
            case PILLAR_TOP -> games.brennan.dungeontrain.editor.PillarEditor.stampPlot(
                overworld, PillarSection.TOP, dims);
            case PILLAR_MIDDLE -> games.brennan.dungeontrain.editor.PillarEditor.stampPlot(
                overworld, PillarSection.MIDDLE, dims);
            case PILLAR_BOTTOM -> games.brennan.dungeontrain.editor.PillarEditor.stampPlot(
                overworld, PillarSection.BOTTOM, dims);
            case TUNNEL_SECTION -> games.brennan.dungeontrain.editor.TunnelEditor.stampPlot(
                overworld, TunnelVariant.SECTION);
            case TUNNEL_PORTAL -> games.brennan.dungeontrain.editor.TunnelEditor.stampPlot(
                overworld, TunnelVariant.PORTAL);
            case ADJUNCT_STAIRS -> games.brennan.dungeontrain.editor.PillarEditor.stampPlot(
                overworld, PillarAdjunct.STAIRS, dims);
            case PORTAL_ROOM -> games.brennan.dungeontrain.editor.PortalRoomEditor.stampAllPlots(
                overworld, dims);
        }
    }

    /**
     * Wipe the in-world blocks of the {@code (kind, name)} plot to air. Called
     * from {@code /dt editor tracks reset <kind>} BEFORE the registry
     * unregister so the just-removed variant doesn't leave an orphaned plot
     * sitting in the world after the player teleports back to default.
     */
    private static void clearPlotForVariant(
        ServerLevel overworld, games.brennan.dungeontrain.track.variant.TrackKind kind, String name, CarriageDims dims
    ) {
        switch (kind) {
            case TILE -> games.brennan.dungeontrain.editor.TrackEditor.clearPlot(overworld, name, dims);
            case PILLAR_TOP -> games.brennan.dungeontrain.editor.PillarEditor.clearPlot(
                overworld, PillarSection.TOP, name, dims);
            case PILLAR_MIDDLE -> games.brennan.dungeontrain.editor.PillarEditor.clearPlot(
                overworld, PillarSection.MIDDLE, name, dims);
            case PILLAR_BOTTOM -> games.brennan.dungeontrain.editor.PillarEditor.clearPlot(
                overworld, PillarSection.BOTTOM, name, dims);
            case TUNNEL_SECTION -> games.brennan.dungeontrain.editor.TunnelEditor.clearPlot(
                overworld, TunnelVariant.SECTION, name);
            case TUNNEL_PORTAL -> games.brennan.dungeontrain.editor.TunnelEditor.clearPlot(
                overworld, TunnelVariant.PORTAL, name);
            case ADJUNCT_STAIRS -> games.brennan.dungeontrain.editor.PillarEditor.clearPlotAdjunct(
                overworld, PillarAdjunct.STAIRS, name, dims);
            case PORTAL_ROOM -> games.brennan.dungeontrain.editor.PortalRoomEditor.clearPlot(
                overworld, name, dims);
        }
    }

    /**
     * Carry every sidecar and the weights entry of {@code (kind, sourceName)} onto
     * {@code targetName}, so a duplicate keeps everything that made the source what it was — the
     * per-cell "pick from these alternatives" authoring data, and for a portal room its
     * contents allow-list, copies palettes, container links and the {@code mode} tag holding its
     * sky, walls and door settings. Same job as {@code CarriageEditor.duplicate} /
     * {@code CarriageContentsEditor.duplicate} do for carriages, through the one enumeration in
     * {@link games.brennan.dungeontrain.editor.TemplateSidecars}. A source with nothing on disk
     * (duplicating the synthetic "default") copies nothing.
     */
    private static void copyTrackVariantSidecar(
        games.brennan.dungeontrain.track.variant.TrackKind kind, String sourceName,
        String targetName
    ) throws IOException {
        games.brennan.dungeontrain.builder.BuilderPhotoPaths.Kind photoKind =
            kind == games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM
                ? games.brennan.dungeontrain.builder.BuilderPhotoPaths.Kind.PORTAL_ROOM
                : games.brennan.dungeontrain.builder.BuilderPhotoPaths.Kind.TRACK;
        games.brennan.dungeontrain.editor.TemplateCopy.copy(photoKind, kind.id(), sourceName, targetName);
    }

    /**
     * {@code /dt editor tracks new <kind> <name>} — duplicate the kind's
     * current active variant under {@code name}, register it, swap the
     * editor's active marker to it, and restamp the plot so the player sees
     * the new (initially identical) variant. Subsequent {@code /dt save}
     * writes through the new name.
     */
    private static int runTrackNewVariant(CommandSourceStack source, String rawKind, String name) {
        games.brennan.dungeontrain.track.variant.TrackKind kind = parseTrackKind(source, rawKind);
        if (kind == null) return 0;
        if (name == null || name.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.variant_name_required"));
            return 0;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (!games.brennan.dungeontrain.track.variant.TrackVariantRegistry.NAME_PATTERN.matcher(key).matches()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.invalid_variant_name_allowed", name));
            return 0;
        }
        if (games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME.equals(key)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.default_reserved_pick_another"));
            return 0;
        }

        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        // Source name = the variant the player is currently standing on, so
        // "New" duplicates whatever you're looking at. Falls back to default
        // when the player isn't in a track-side plot (e.g. command typed via
        // chat).
        games.brennan.dungeontrain.editor.TrackPlotLocator.PlotInfo loc =
            games.brennan.dungeontrain.editor.TrackPlotLocator.locate(player, dims);
        String sourceName = (loc != null && loc.kind() == kind)
            ? loc.name()
            : games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME;

        java.util.Optional<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate> sourceTemplate =
            games.brennan.dungeontrain.track.variant.TrackVariantStore.get(overworld, kind, sourceName, dims);
        if (sourceTemplate.isEmpty()) {
            // A kind whose fallback is code has nothing to copy until somebody has saved one — the
            // portal room ships no bundled nbt on purpose. Seed the new variant from the built-in
            // geometry instead, which is the same starting point 'default' has.
            if (kind.hasBuiltInFallback()) {
                try {
                    games.brennan.dungeontrain.editor.PortalRoomEditor.createFromBuiltIn(
                        overworld, sourceName, key, dims);
                } catch (java.io.IOException e) {
                    source.sendFailure(Component.translatable("chat.dungeontrain.editor.save_failed", e.getMessage()));
                    return 0;
                }
                restampPlotForKind(overworld, kind, dims);
                teleportToPlot(player, overworld, kind, key, dims);
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.created_from_built_room", kind.id(), key).withStyle(ChatFormatting.GREEN), true);
                return 1;
            }
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.cannot_duplicate_no_template", kind.id(), sourceName));
            return 0;
        }

        try {
            games.brennan.dungeontrain.track.variant.TrackVariantStore.save(kind, key, sourceTemplate.get());
        } catch (java.io.IOException e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.save_failed", e.getMessage()));
            return 0;
        }

        try {
            copyTrackVariantSidecar(kind, sourceName, key);
        } catch (java.io.IOException e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.variant_sidecar_copy_failed", e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }

        games.brennan.dungeontrain.track.variant.TrackVariantRegistry.register(kind, key);
        restampPlotForKind(overworld, kind, dims);
        teleportToPlot(player, overworld, kind, key, dims);

        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.created_from_teleported_new", kind.id(), key, sourceName).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * {@code /dt editor <tracks|portals> rename <kind> <name> <new_name>} — give a track-side
     * template, portal rooms included, a new name.
     *
     * <p>The move itself is {@link games.brennan.dungeontrain.editor.TrackVariantRename}, which
     * carries the sidecars, the weight/gate entry and the room's membership of any group with it.
     * This method is the player-facing half: it turns each refusal into a line worth reading, and
     * restamps the row afterwards so the plots follow the names.</p>
     */
    private static int runTrackRenameVariant(CommandSourceStack source, String rawKind,
                                             String name, String newName) {
        games.brennan.dungeontrain.track.variant.TrackKind kind = parseTrackKind(source, rawKind);
        if (kind == null) return 0;
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        games.brennan.dungeontrain.editor.TrackVariantRename.Result result;
        try {
            result = games.brennan.dungeontrain.editor.TrackVariantRename.rename(kind, name, newName);
        } catch (java.io.IOException e) {
            LOGGER.error("[DungeonTrain] editor rename {}:{} -> {} failed", kind.id(), name, newName, e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.rename_failed", e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        String from = name.toLowerCase(Locale.ROOT);
        String to = newName.toLowerCase(Locale.ROOT);
        switch (result) {
            case BAD_NAME -> {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.invalid_name_allowed_lowercase", newName));
                return 0;
            }
            case SAME_NAME -> {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_it_already_has", to));
                return 0;
            }
            case RESERVED -> {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.default_reserved_it_cannot"));
                return 0;
            }
            case UNKNOWN -> {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown", kind.id(), from));
                return 0;
            }
            case TAKEN -> {
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.name_already_taken", to));
                return 0;
            }
            case NO_CONFIG_COPY -> {
                // A bundled template is shadowed by a saved copy, never moved — there is nothing on
                // disk to move, and the bundled original would keep answering to the old name.
                source.sendFailure(Component.translatable("chat.dungeontrain.editor.ships_with_mod_save", from));
                return 0;
            }
            case OK -> { }
        }

        restampPlotForKind(overworld, kind, dims);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.renamed", kind.id(), from, to).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int runTrackResetActiveVariant(CommandSourceStack source, String rawKind) {
        return runTrackResetActiveVariant(source, rawKind, null);
    }

    /**
     * {@code /dt editor tracks reset <kind> [all|unparent|promote]} — delete the variant the
     * player is currently standing on (must not be {@code default}), unregister it, restamp,
     * teleport back to default's plot. See {@link #resetTrackVariant} for the mode rule.
     */
    private static int runTrackResetActiveVariant(CommandSourceStack source, String rawKind,
                                                  games.brennan.dungeontrain.editor.ParentDeletes.Mode mode) {
        games.brennan.dungeontrain.track.variant.TrackKind kind = parseTrackKind(source, rawKind);
        if (kind == null) return 0;
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();

        games.brennan.dungeontrain.editor.TrackPlotLocator.PlotInfo loc =
            games.brennan.dungeontrain.editor.TrackPlotLocator.locate(player, dims);
        if (loc == null || loc.kind() != kind) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.stand_variant_you_want", kind.id()));
            return 0;
        }
        return resetTrackVariant(source, kind, loc.name(), mode);
    }

    /**
     * {@code /dt editor tracks reset <kind> <name> [all|unparent|promote]} — the same delete
     * addressed by name, for the editor screen whose Remove acts on the selected row rather than
     * on the plot the player happens to stand in.
     */
    private static int runTrackResetNamedVariant(CommandSourceStack source, String rawKind, String rawName,
                                                 games.brennan.dungeontrain.editor.ParentDeletes.Mode mode) {
        games.brennan.dungeontrain.track.variant.TrackKind kind = parseTrackKind(source, rawKind);
        if (kind == null) return 0;
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME.equals(name)
                && games.brennan.dungeontrain.track.variant.TrackVariantRegistry.find(kind, name).isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_variant", kind.id(), name));
            return 0;
        }
        return resetTrackVariant(source, kind, name, mode);
    }

    /**
     * Delete {@code (kind, name)}: refuse {@code default}; a variant that heads a group (a
     * dimensional carriage with sub-variants) needs a
     * {@link games.brennan.dungeontrain.editor.ParentDeletes.Mode mode} saying what becomes of its
     * members and is refused without one — same rule as {@code contents reset}. Clears the plot
     * (the whole row for portal rooms), applies the mode, deletes, restamps, and sends a player who
     * was standing in one of this kind's plots back to default's.
     */
    private static int resetTrackVariant(CommandSourceStack source,
                                         games.brennan.dungeontrain.track.variant.TrackKind kind, String name,
                                         games.brennan.dungeontrain.editor.ParentDeletes.Mode mode) {
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        // `default` is a real name too: the registry always lists it and it can never be
        // unregistered, but it may have an authored template (a config-dir save, or — for the
        // portal room — a bundled default.nbt with its own sub-variants). Resetting it drops those
        // files, in dev mode the bundled copies as well, and the kind falls back to its built-in
        // geometry; the row keeps its `default` slot.
        boolean isDefault = games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME.equals(name);
        java.util.Optional<games.brennan.dungeontrain.track.variant.TrackVariantGroup> group =
            games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(kind, name)
                .filter(g -> !g.members().isEmpty());
        if (group.isPresent() && mode == null) {
            int n = group.get().members().size();
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.has_sub_variant_say_2", name, n, Component.translatable(n == 1 ? "chat.dungeontrain.common.noun.sub_variant.singular" : "chat.dungeontrain.common.noun.sub_variant.plural"), kind.id(), name, games.brennan.dungeontrain.editor.ParentDeletes.Mode.literals()).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        ServerPlayer player = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        games.brennan.dungeontrain.editor.TrackPlotLocator.PlotInfo loc = player == null ? null
            : games.brennan.dungeontrain.editor.TrackPlotLocator.locate(player, dims);
        boolean sendHome = loc != null && loc.kind() == kind;
        progress(source, Component.translatable(isDefault ? "chat.dungeontrain.editor.progress_resetting" : "chat.dungeontrain.editor.progress_deleting", name,
            (group.isPresent() && mode == games.brennan.dungeontrain.editor.ParentDeletes.Mode.ALL
                ? Component.translatable("chat.dungeontrain.editor.progress_deleting_and_subs", group.get().members().size()) : Component.empty())));

        // Wipe the variant's plot blocks BEFORE deregistering so the orphaned
        // plot doesn't sit in the world after teleport. restampPlotForKind
        // below only re-stamps registered names, so a leftover plot would
        // otherwise stay visible indefinitely.
        //
        // For a cumulatively-packed row (portal rooms) removing a name also shifts every plot
        // after it, so the whole row has to go, not just this one.
        if (kind.freeSizeAboveFloor()) {
            PortalRoomEditor.clearAllPlots(overworld, dims);
        } else {
            clearPlotForVariant(overworld, kind, name, dims);
        }

        ParentModeOutcome outcome = group.isPresent()
            ? applyTrackParentMode(overworld, dims, kind, name, group.get(), mode) : ParentModeOutcome.NONE;

        TemplateDeletes.Report cleanup;
        try {
            cleanup = deleteTrackVariant(kind, name);
        } catch (java.io.IOException e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.delete_failed", e.getMessage()));
            return 0;
        }
        restampPlotForKind(overworld, kind, dims);
        // Land where the work continued: the new parent, or the first member that just went
        // top-level; otherwise a player who was in this kind's row goes back to default's plot.
        String landing = outcome.landing();
        boolean landed = false;
        if (player != null && landing != null
                && games.brennan.dungeontrain.track.variant.TrackVariantRegistry.find(kind, landing).isPresent()) {
            if (kind == PORTAL_ROOM_KIND) PortalRoomEditor.enter(player, landing);
            else teleportToPlot(player, overworld, kind, landing, dims);
            landed = true;
        } else if (sendHome) {
            teleportToPlot(player, overworld, kind,
                games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME, dims);
        }
        final Component where = landed ? Component.translatable("chat.dungeontrain.editor.landed_entered", landing)
            : (sendHome && !isDefault ? Component.translatable("chat.dungeontrain.editor.teleported_back_default") : Component.empty());
        final boolean dashed = sendHome && !isDefault && !landed;

        source.sendSuccess(() -> (isDefault
                ? Component.translatable("chat.dungeontrain.editor.reset_default_fallback", kind.id())
                : Component.translatable("chat.dungeontrain.editor.removed_variant", kind.id(), name))
                .append(dashed ? Component.empty() : Component.literal("."))
                .append(where)
                .append(cleanup.summaryLine()).append(outcome.line())
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * Delete one named track-side variant's files and registry entry: the {@code .nbt}, everything
     * {@link TemplateDeletes#track} takes with it, the registry row and the cached room size. Plot
     * clearing and restamping are the caller's — they work on the whole row.
     */
    private static TemplateDeletes.Report deleteTrackVariant(
        games.brennan.dungeontrain.track.variant.TrackKind kind, String name
    ) throws java.io.IOException {
        games.brennan.dungeontrain.track.variant.TrackVariantStore.delete(kind, name);
        // Sidecars, weight, group slot — and in dev mode the bundled copies of each. Before
        // unregister, because the group strip finds parents through the registry.
        TemplateDeletes.Report cleanup = TemplateDeletes.track(kind, name);
        games.brennan.dungeontrain.track.variant.TrackVariantRegistry.unregister(kind, name);
        // Drop the removed room's cached size, or re-creating the same name later would silently
        // inherit the dead variant's dimensions.
        if (kind.hasBuiltInFallback()) {
            games.brennan.dungeontrain.portal.PortalRoomSizes.forget(name);
        }
        return cleanup;
    }

    /**
     * Track-side twin of {@link #applyContentsParentMode}: what a group parent's members become,
     * applied between the row clear and the parent's own delete so the one restamp that follows
     * lays the row out for the new membership. Best-effort per member.
     */
    private static ParentModeOutcome applyTrackParentMode(ServerLevel overworld, CarriageDims dims,
                                               games.brennan.dungeontrain.track.variant.TrackKind kind,
                                               String parent,
                                               games.brennan.dungeontrain.track.variant.TrackVariantGroup group,
                                               games.brennan.dungeontrain.editor.ParentDeletes.Mode mode) {
        List<String> done = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        switch (mode) {
            case ALL -> {
                for (games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member m : group.members()) {
                    if (games.brennan.dungeontrain.track.variant.TrackVariantRegistry.find(kind, m.id()).isEmpty()) continue;
                    try {
                        if (!kind.freeSizeAboveFloor()) clearPlotForVariant(overworld, kind, m.id(), dims);
                        deleteTrackVariant(kind, m.id());
                        done.add(m.id());
                    } catch (Exception e) {
                        LOGGER.warn("[DungeonTrain] {} reset all: could not delete member {}: {}", kind.id(), m.id(), e.toString());
                        failed.add(m.id());
                    }
                }
                return new ParentModeOutcome(summarise("chat.dungeontrain.editor.sub_variants_deleted", done, failed), null);
            }
            case UNPARENT -> {
                String first = null;
                for (games.brennan.dungeontrain.editor.ParentDeletes.TopLevel t
                        : games.brennan.dungeontrain.editor.ParentDeletes.unparentTrack(group)) {
                    try {
                        TrackVariantWeights.set(kind, t.id(), t.weight());
                        TrackVariantWeights.setGate(kind, t.id(), t.gate());
                        TrackVariantWeights.setStage(kind, t.id(), t.stageId());
                        if (first == null) first = t.id();
                        done.add(t.droppedStages() > 0
                            ? t.id() + " (kept 1 of " + (t.droppedStages() + 1) + " Stage links)" : t.id());
                    } catch (Exception e) {
                        LOGGER.warn("[DungeonTrain] {} reset unparent: could not carry {}: {}", kind.id(), t.id(), e.toString());
                        failed.add(t.id());
                    }
                }
                return new ParentModeOutcome(summarise("chat.dungeontrain.editor.now_top_level", done, failed), first);
            }
            case PROMOTE_FIRST -> {
                games.brennan.dungeontrain.editor.ParentDeletes.TrackPromotion p =
                    games.brennan.dungeontrain.editor.ParentDeletes.promoteTrack(group).orElseThrow();
                String heir = p.newParent();
                try {
                    // The family keeps the parent's place in the top-level draw.
                    TrackVariantWeights.set(kind, heir, TrackVariantWeights.weightFor(kind, parent));
                    TrackVariantWeights.setGate(kind, heir, TrackVariantWeights.gateFor(kind, parent));
                    TrackVariantWeights.setStage(kind, heir, TrackVariantWeights.stageIdFor(kind, parent));
                    if (p.group().isPresent()) {
                        games.brennan.dungeontrain.editor.TrackVariantGroupStore.save(kind, heir, p.group().get());
                    }
                    int n = p.group().map(g -> g.members().size()).orElse(0);
                    return new ParentModeOutcome(Component.translatable("chat.dungeontrain.editor.now_heads_group", heir, n,
                        Component.translatable(n == 1 ? "chat.dungeontrain.common.noun.sub_variant.singular" : "chat.dungeontrain.common.noun.sub_variant.plural")), heir);
                } catch (Exception e) {
                    LOGGER.warn("[DungeonTrain] {} reset promote: could not promote {}: {}", kind.id(), heir, e.toString());
                    return new ParentModeOutcome(Component.translatable("chat.dungeontrain.editor.could_not_promote", heir, e.getMessage()), null);
                }
            }
        }
        return ParentModeOutcome.NONE;
    }

    /**
     * Teleport {@code player} to the centre of the {@code (kind, name)} plot.
     * No-op for kinds without a single-plot editor (currently nothing — every
     * track-side kind has its plot via {@link games.brennan.dungeontrain.editor.TrackSidePlots}).
     */
    private static void teleportToPlot(
        ServerPlayer player, ServerLevel overworld,
        games.brennan.dungeontrain.track.variant.TrackKind kind, String name,
        CarriageDims dims
    ) {
        BlockPos origin = games.brennan.dungeontrain.editor.TrackSidePlots.plotOrigin(kind, name, dims);
        // Name-aware: a portal room is as long as its author made it, and the kind-level footprint
        // would drop the player short of the centre of a longer one.
        net.minecraft.core.Vec3i fp =
            games.brennan.dungeontrain.editor.TrackSidePlots.footprint(kind, name, dims);
        double tx = origin.getX() + fp.getX() / 2.0;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + fp.getZ() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());
    }

    /**
     * {@code /dungeontrain editor undo|redo} — step the editor history for the
     * plot the player is standing in.
     *
     * <p>Both the Ctrl/Cmd+Z keybinding and the X-menu's Undo | Redo row come
     * through here, so there is one server-side path and the two surfaces
     * cannot drift. Feedback goes to the action bar rather than chat: an author
     * undoing a run of edits should not have to watch their chat fill up.</p>
     */
    /**
     * Rearrange the plot the player is standing in — {@code offset}, {@code rotate}
     * and {@code flip} all land here, differing only in the
     * {@link EditorPlotTransform} they carry.
     *
     * <p>Recorded through {@link EditorRegionDiff} so the whole rearrangement —
     * blocks, variant sidecar and container pools — comes back on one Ctrl+Z.
     * The wrapper is entered only once the transform is known to apply, so a
     * rejected quarter turn does not push an empty step.</p>
     */
    private static int runTransform(CommandSourceStack source, EditorPlotTransform transform) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ServerLevel level = source.getServer().overworld();

        EditorPlotTransformer.Region region =
            EditorPlotTransformer.resolve(player, level).orElse(null);
        if (region == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_plot_stand_inside").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (transform.isIdentity()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.would_leave_plot_exactly", transform.label()).withStyle(ChatFormatting.RED));
            return 0;
        }
        String rejection = transform.rejection(region.size());
        if (rejection != null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.cannot", transform.label().toLowerCase(Locale.ROOT), rejection).withStyle(ChatFormatting.RED));
            return 0;
        }

        // Say so up front rather than letting the author find out at Ctrl+Z: a
        // plot bigger than the history's per-step cell cap runs unrecorded, and
        // EditorRegionDiff drops that plot's history when it happens.
        if (region.volume() > EditorEditHistory.MAX_CELLS_PER_STEP) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.heads_up_plot_too", region.volume()).withStyle(ChatFormatting.YELLOW), false);
        }

        return EditorRegionDiff.recording(source, transform.label(),
            () -> applyTransform(source, level, region, transform));
    }

    /** The recorded half of {@link #runTransform} — see there for the guards. */
    private static int applyTransform(CommandSourceStack source, ServerLevel level,
                                      EditorPlotTransformer.Region region,
                                      EditorPlotTransform transform) {
        EditorPlotTransformer.Result result;
        try {
            result = EditorPlotTransformer.apply(level, region, transform);
        } catch (IOException e) {
            // The blocks have already moved by the time a sidecar write can
            // fail, so this is a partial apply — say which half, and leave the
            // history step to put both back.
            LOGGER.error("[DungeonTrain] editor transform ({}) failed to save a sidecar",
                transform.label(), e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.blocks_moved_but_sidecar", transform.label(), e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }

        net.minecraft.network.chat.MutableComponent message = Component.translatable("chat.dungeontrain.editor.transform_blocks",
            transform.label(), result.cells(),
            Component.translatable(result.cells() == 1 ? "chat.dungeontrain.common.noun.block.singular" : "chat.dungeontrain.common.noun.block.plural"));
        if (result.variantEntries() > 0) {
            message.append(Component.translatable("chat.dungeontrain.editor.transform_variant_entries", result.variantEntries(),
                Component.translatable(result.variantEntries() == 1 ? "chat.dungeontrain.common.noun.entry.singular" : "chat.dungeontrain.common.noun.entry.plural")));
        }
        if (result.pools() > 0) {
            message.append(Component.translatable("chat.dungeontrain.editor.transform_pools", result.pools(),
                Component.translatable(result.pools() == 1 ? "chat.dungeontrain.common.noun.pool.singular" : "chat.dungeontrain.common.noun.pool.plural")));
        }
        message.append(".");
        source.sendSuccess(() -> message.withStyle(ChatFormatting.GREEN), true);

        if (result.entities() > 0) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.entit_plot_stayed_put", result.entities(), Component.translatable(result.entities() == 1 ? "chat.dungeontrain.common.noun.entity.singular" : "chat.dungeontrain.common.noun.entity.plural")).withStyle(ChatFormatting.YELLOW), false);
        }
        if (region.mirrored()) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.plot_has_mirror_axis").withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int runUndoRedo(CommandSourceStack source, boolean redoing) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        EditorEditApplier.Result result = redoing
            ? EditorEditApplier.redo(player)
            : EditorEditApplier.undo(player);
        Component verb = Component.translatable(redoing ? "chat.dungeontrain.editor.redo" : "chat.dungeontrain.editor.undo");

        switch (result.outcome()) {
            case DONE -> {
                // Name the plot: the author may be nowhere near it, so "Undo: Place"
                // alone would not say what just changed.
                actionBar(player, Component.translatable("chat.dungeontrain.editor.undo_done", verb, result.label(), result.plotKey()),
                    ChatFormatting.GREEN);
                return 1;
            }
            case NOTHING -> actionBar(player, Component.translatable(redoing ? "chat.dungeontrain.editor.nothing_to_redo" : "chat.dungeontrain.editor.nothing_to_undo"),
                ChatFormatting.GRAY);
            case STALE -> actionBar(player,
                Component.translatable("chat.dungeontrain.editor.history_out_of_date", result.plotKey()),
                ChatFormatting.YELLOW);
            case FAILED -> actionBar(player, Component.translatable("chat.dungeontrain.editor.undo_partly_failed", verb),
                ChatFormatting.RED);
        }
        return 0;
    }

    /** Overlay text above the hotbar — the editor's usual channel for transient feedback. */
    private static void actionBar(ServerPlayer player, Component text, ChatFormatting colour) {
        player.displayClientMessage(text.copy().withStyle(colour), true);
    }

}
