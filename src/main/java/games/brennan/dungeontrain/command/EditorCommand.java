package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageContentsEditor;
import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
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
import games.brennan.dungeontrain.editor.EditorEditApplier;
import games.brennan.dungeontrain.editor.EditorRegionDiff;
import games.brennan.dungeontrain.editor.PortalRoomEditor;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomResize;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.editor.EditorStampedCategoryState;
import games.brennan.dungeontrain.editor.EditorWelcome;
import games.brennan.dungeontrain.editor.PillarEditor;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TrackEditor;
import games.brennan.dungeontrain.editor.TrackTemplateStore;
import games.brennan.dungeontrain.editor.TunnelEditor;
import games.brennan.dungeontrain.editor.TunnelTemplateStore;
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
    private static final SuggestionProvider<CommandSourceStack> STAGE_OR_CUSTOM_SUGGESTIONS =
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
            .then(Commands.literal("reset")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests(TRACK_KIND_SUGGESTIONS)
                    .executes(ctx -> runTrackResetActiveVariant(
                        ctx.getSource(),
                        StringArgumentType.getString(ctx, "kind")))))
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
            .then(Commands.literal("carriages")
                .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.CARRIAGES)))
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
            .then(attachTrackVariantNodes(Commands.literal("portals")
                .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.PORTALS)))
                .then(Commands.literal("enter")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(PORTAL_ROOM_NAME_SUGGESTIONS)
                        .executes(ctx -> runPortalRoomEnter(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")))))
                .then(portalSizeNode("length", PortalRoomResize.Axis.LENGTH))
                .then(portalSizeNode("width", PortalRoomResize.Axis.WIDTH))
                .then(portalSizeNode("height", PortalRoomResize.Axis.HEIGHT))
                // All three at once, in the order the menus label them.
                .then(Commands.literal("size")
                    .then(Commands.argument("length", IntegerArgumentType.integer(1, 512))
                        .then(Commands.argument("width", IntegerArgumentType.integer(1, 512))
                            .then(Commands.argument("height", IntegerArgumentType.integer(1, 512))
                                .executes(ctx -> runPortalRoomSizeAll(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "length"),
                                    IntegerArgumentType.getInteger(ctx, "width"),
                                    IntegerArgumentType.getInteger(ctx, "height")))))))
                // What the room does at its walls. `next` is what the panel's button sends; the
                // named forms are for typing, and for saying which one you want in one go.
                .then(Commands.literal("mode")
                    .then(Commands.literal("next")
                        .executes(ctx -> runPortalRoomModeCycle(ctx.getSource())))
                    .then(Commands.argument("mode", StringArgumentType.word())
                        .suggests(PORTAL_ROOM_MODE_SUGGESTIONS)
                        .executes(ctx -> runPortalRoomMode(ctx.getSource(),
                            StringArgumentType.getString(ctx, "mode")))))
                // Whether Endless Repetition's copies are the room block for block, or each rolled
                // afresh from its variant sidecar. Means nothing under the other modes.
                .then(Commands.literal("copies")
                    .then(Commands.literal("next")
                        .executes(ctx -> runPortalRoomCopiesCycle(ctx.getSource())))
                    // Which block Single repeats. Its own branch rather than a second argument on
                    // the setter above, so the setter stays a bare word and a block id — which
                    // carries a colon — never has to survive StringArgumentType.word().
                    .then(Commands.literal("block")
                        .then(Commands.literal("held")
                            .executes(ctx -> runPortalRoomCopiesBlockHeld(ctx.getSource())))
                        .then(Commands.literal("edit")
                            .executes(ctx -> runPortalRoomCopiesBlockEdit(ctx.getSource())))
                        .then(Commands.argument("block", StringArgumentType.greedyString())
                            .executes(ctx -> runPortalRoomCopiesBlock(ctx.getSource(),
                                StringArgumentType.getString(ctx, "block")))))
                    .then(Commands.argument("copies", StringArgumentType.word())
                        .suggests(PORTAL_ROOM_COPIES_SUGGESTIONS)
                        .executes(ctx -> runPortalRoomCopies(ctx.getSource(),
                            StringArgumentType.getString(ctx, "copies")))))
                // Whether the room is furnished from the ordinary contents pool, and how a
                // furnishing smaller than the room is fitted into it. Off by default.
                .then(Commands.literal("contents")
                    .then(Commands.literal("next")
                        .executes(ctx -> runPortalRoomContentsCycle(ctx.getSource())))
                    .then(Commands.argument("contents", StringArgumentType.word())
                        .suggests(PORTAL_ROOM_CONTENTS_SUGGESTIONS)
                        .executes(ctx -> runPortalRoomContents(ctx.getSource(),
                            StringArgumentType.getString(ctx, "contents")))))
                // Whether the room is lit as though it stood outdoors, and under which sky. Off by
                // default, which is every room lit only by whatever its own build gives it.
                .then(Commands.literal("sky")
                    .then(Commands.literal("next")
                        .executes(ctx -> runPortalRoomSkyCycle(ctx.getSource())))
                    .then(Commands.argument("sky", StringArgumentType.word())
                        .suggests(PORTAL_ROOM_SKY_SUGGESTIONS)
                        .executes(ctx -> runPortalRoomSky(ctx.getSource(),
                            StringArgumentType.getString(ctx, "sky")))))
                // How many extra ways back to the train an endless room scatters through its copies.
                // Means nothing under the modes that do not repeat.
                .then(Commands.literal("exits")
                    .then(Commands.literal("next")
                        .executes(ctx -> runPortalRoomExitsCycle(ctx.getSource())))
                    .then(Commands.argument("exits", StringArgumentType.word())
                        .suggests(PORTAL_ROOM_EXITS_SUGGESTIONS)
                        .executes(ctx -> runPortalRoomExits(ctx.getSource(),
                            StringArgumentType.getString(ctx, "exits")))))
                // The X both readings of Exits are measured in — every X tiles, or one tile in X.
                .then(Commands.literal("exitevery")
                    .then(Commands.literal("inc")
                        .executes(ctx -> runPortalRoomExitEveryStep(ctx.getSource(), +1)))
                    .then(Commands.literal("dec")
                        .executes(ctx -> runPortalRoomExitEveryStep(ctx.getSource(), -1)))
                    .then(Commands.argument("tiles", IntegerArgumentType.integer(
                            games.brennan.dungeontrain.portal.PortalRoomExits.MIN_EVERY,
                            games.brennan.dungeontrain.portal.PortalRoomExits.MAX_EVERY))
                        .executes(ctx -> runPortalRoomExitEvery(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "tiles")))))
                // How often a Random room walls off the base pair's exit, so the only ways onward
                // are the copies. 0 never, 10 always.
                .then(Commands.literal("exitmove")
                    .then(Commands.literal("inc")
                        .executes(ctx -> runPortalRoomExitMoveStep(ctx.getSource(), +1)))
                    .then(Commands.literal("dec")
                        .executes(ctx -> runPortalRoomExitMoveStep(ctx.getSource(), -1)))
                    .then(Commands.argument("chance", IntegerArgumentType.integer(
                            games.brennan.dungeontrain.portal.PortalRoomExits.MOVE_NEVER,
                            games.brennan.dungeontrain.portal.PortalRoomExits.MOVE_ALWAYS))
                        .executes(ctx -> runPortalRoomExitMove(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "chance")))))
                // Whether every book found in the room is by one author, and how that author is
                // picked. Off by default — the ordinary mixed community pool.
                .then(Commands.literal("books")
                    .then(Commands.literal("next")
                        .executes(ctx -> runPortalRoomBooksCycle(ctx.getSource())))
                    .then(Commands.argument("books", StringArgumentType.word())
                        .suggests(PORTAL_ROOM_BOOKS_SUGGESTIONS)
                        .executes(ctx -> runPortalRoomBooks(ctx.getSource(),
                            StringArgumentType.getString(ctx, "books")))))
                // The three shares of the author roll, and the band of author a room will accept.
                // All five mean nothing while Books is Off, which is why the edit screen is only
                // reachable from a room that stocks an author at all.
                .then(portalRoomBookWeightNode("booksself",
                    games.brennan.dungeontrain.portal.PortalRoomBooks.Share.SELF))
                .then(portalRoomBookWeightNode("booksplayer",
                    games.brennan.dungeontrain.portal.PortalRoomBooks.Share.PLAYER))
                .then(portalRoomBookWeightNode("bookssignature",
                    games.brennan.dungeontrain.portal.PortalRoomBooks.Share.SIGNATURE))
                .then(portalRoomBookBoundNode("booksmin", true))
                .then(portalRoomBookBoundNode("booksmax", false))
                // Sub-variants: one named room standing for several designs, drawn by weight.
                .then(portalRoomGroupNode()))
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
                .then(Commands.literal("on").executes(ctx -> runEditorMenus(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runEditorMenus(ctx.getSource(), false))))
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
                            ctx.getSource().sendFailure(Component.literal(
                                "Unknown pillar target '" + raw + "'. Valid: "
                                    + pillarTargetList()
                            ));
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
                            ctx.getSource().sendFailure(Component.literal(
                                "Unknown pillar target '" + raw + "'. Valid: "
                                    + pillarTargetList()
                            ));
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
                            ctx.getSource().sendFailure(Component.literal(
                                "Unknown pillar target '" + raw + "'. Valid: "
                                    + pillarTargetList()
                            ));
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
            source.sendFailure(Component.literal("Variant name is required."));
            return 0;
        }
        try {
            int stored = TrackVariantWeights.set(kind, name, value);
            source.sendSuccess(() -> Component.literal(
                "Editor: weight " + kind.id() + ":" + name + "=" + stored
                    + " (saved to " + TrackVariantWeights.configPath(kind) + ")."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor tracks weight set failed for {}:{}", kind.id(), name, t);
            source.sendFailure(Component.literal("track weight failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
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
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        CarriageDims dims = DungeonTrainWorldData.get(player.serverLevel()).dims();
        games.brennan.dungeontrain.editor.BlockVariantPlot plot =
            games.brennan.dungeontrain.editor.BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            source.sendFailure(Component.literal("Stand inside an editor plot to rebuild its mirror.")
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
            source.sendFailure(Component.literal(
                "Editor: no mirror axis is on for this plot — turn on X, Y or Z first.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Editor: mirrored " + plot.key() + " from master")
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
            source.sendFailure(Component.literal("Variant name is required."));
            return 0;
        }
        CarriageDims dims = DungeonTrainWorldData.get(source.getLevel()).dims();
        Vec3i footprint = kind.dims(dims);
        games.brennan.dungeontrain.track.variant.TrackVariantBlocks cfg =
            games.brennan.dungeontrain.track.variant.TrackVariantBlocks.loadFor(kind, name, footprint);
        applyMirrorAxis(cfg, axis, on);
        try {
            cfg.save(kind, name);
            if (EditorDevMode.isEnabled()) cfg.saveToSource(kind, name);
            source.sendSuccess(() -> Component.literal(
                "Editor: " + kind.id() + ":" + name + " mirror " + axis.toUpperCase(Locale.ROOT) + " "
                    + (on ? "on" : "off")).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor tracks mirror failed for {}:{}", kind.id(), name, e);
            source.sendFailure(Component.literal("track mirror failed: " + e.getMessage())
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
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        CarriageDims dims = DungeonTrainWorldData.get(player.serverLevel()).dims();
        games.brennan.dungeontrain.editor.BlockVariantPlot plot =
            games.brennan.dungeontrain.editor.BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            source.sendFailure(Component.literal("Stand inside an editor plot to toggle mirror.")
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
            source.sendSuccess(() -> Component.literal(
                "Editor: mirror " + axis.toUpperCase(Locale.ROOT) + " " + (on ? "on" : "off"))
                .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor mirror failed", e);
            source.sendFailure(Component.literal("mirror failed: " + e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /** Read-modify-write nudge for track variant weight. Bounds clamp via {@link TrackVariantWeights#set}. */
    private static int runTrackWeightAdjust(CommandSourceStack source, String rawKind, String name, int delta) {
        games.brennan.dungeontrain.track.variant.TrackKind kind = parseTrackKind(source, rawKind);
        if (kind == null) return 0;
        if (name == null || name.isEmpty()) {
            source.sendFailure(Component.literal("Variant name is required."));
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
            source.sendSuccess(() -> Component.literal(
                "Note: '" + contents.id() + "' is a member of a contents group — top-level weight "
                    + "is IGNORED at spawn time (group resolution uses per-member weights). "
                    + "Value will still be saved in case you ungroup later."
            ).withStyle(ChatFormatting.YELLOW), false);
        }
        try {
            int stored = CarriageContentsWeights.set(contents.id(), value);
            source.sendSuccess(() -> Component.literal(
                "Editor: contents weight " + contents.id() + "=" + stored
                    + " (saved to " + CarriageContentsWeights.configPath() + "). "
                    + "Existing carriages keep their contents until they scroll out."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents weight set failed for {}", contents.id(), t);
            source.sendFailure(Component.literal("contents weight failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
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
    private interface SingleGateOp {
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
            source.sendFailure(Component.literal("Variant name is required."));
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
    private static void gateSuccess(CommandSourceStack source, String id, TemplateGate g, String path,
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
        source.sendSuccess(() -> Component.literal(
            "Editor: gate " + id + " — level " + g.minLevel() + ".." + maxStr
                + ", phases [" + phaseStr + "] (saved to " + path + ")."
        ).withStyle(ChatFormatting.GREEN), true);
        if (linkedStage != null) {
            source.sendSuccess(() -> Component.literal(
                "  Note: '" + id + "' is linked to Stage '" + linkedStage + "' — this inline gate is"
                    + " stored as its detach snapshot and won't take effect until you detach it to"
                    + " Custom (/dungeontrain editor stage …)."
            ).withStyle(ChatFormatting.YELLOW), false);
        }
    }

    private static int gateFail(CommandSourceStack source, String what, String id, Throwable t) {
        LOGGER.error("[DungeonTrain] editor {} gate set failed for {}", what, id, t);
        source.sendFailure(Component.literal(what + " gate failed: "
            + t.getClass().getSimpleName() + ": " + t.getMessage()).withStyle(ChatFormatting.RED));
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

    private static LiteralArgumentBuilder<CommandSourceStack> minLevelSingle(
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

    private static LiteralArgumentBuilder<CommandSourceStack> maxLevelSingle(
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

    private static LiteralArgumentBuilder<CommandSourceStack> phaseSingle(
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
            source.sendFailure(Component.literal("Stage id is required.").withStyle(ChatFormatting.RED));
            return 0;
        }
        try {
            TemplateGate current = games.brennan.dungeontrain.editor.StageStore.gateOf(id)
                .orElse(TemplateGate.DEFAULT);
            TemplateGate next = op.apply(current);
            games.brennan.dungeontrain.editor.StageStore.setGate(id, next);
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
                source.sendFailure(Component.literal("Invalid stage id: " + rawId).withStyle(ChatFormatting.RED));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("Editor: created stage '" + created.id() + "'.")
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
                source.sendFailure(Component.literal("No such stage: " + rawId).withStyle(ChatFormatting.RED));
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
            source.sendSuccess(() -> Component.literal("Editor: deleted stage '"
                + rawId.toLowerCase(java.util.Locale.ROOT) + "'. Linked templates fall back to their inline gate.")
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
                source.sendFailure(Component.literal("No such stage: " + rawId).withStyle(ChatFormatting.RED));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("Editor: renamed stage '" + renamed.id()
                + "' → \"" + renamed.name() + "\".").withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "stage rename", rawId, t);
        }
    }

    private static int runStageList(CommandSourceStack source) {
        java.util.List<games.brennan.dungeontrain.template.Stage> stages =
            games.brennan.dungeontrain.editor.StageStore.allStages();
        if (stages.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Editor: no stages defined. Create one with "
                + "/dt editor stage new <id>.").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Editor: " + stages.size() + " stage(s):")
            .withStyle(ChatFormatting.AQUA), false);
        for (games.brennan.dungeontrain.template.Stage s : stages) {
            TemplateGate g = s.gate();
            String maxStr = g.maxLevel() == TemplateGate.ALL ? "all" : Integer.toString(g.maxLevel());
            String phaseStr = g.phases().size() == TrainPhase.values().length ? "all" : phaseTokens(g);
            source.sendSuccess(() -> Component.literal("  • " + s.id() + " — level " + g.minLevel()
                + ".." + maxStr + ", phases [" + phaseStr + "]").withStyle(ChatFormatting.GRAY), false);
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
            source.sendFailure(Component.literal("No such stage: " + rawId).withStyle(ChatFormatting.RED));
            return 0;
        }
        boolean nowSelected = !games.brennan.dungeontrain.editor.EditorStageSelection.isSelected(id);
        if (nowSelected) {
            games.brennan.dungeontrain.editor.EditorStageSelection.select(id);
        } else {
            games.brennan.dungeontrain.editor.EditorStageSelection.clear();
        }
        restampCarriagePlotsForStage(source);
        // Parts-grid visibility is decoupled from selection now (it's driven by the hide-unused
        // snapshot + per-part checkboxes), so changing the focused stage does not re-filter it.
        // Auto-open the Stage Blocks panel for the selecting player on the focused stage (close on
        // deselect) — the panel follows the selection.
        net.minecraft.server.level.ServerPlayer selPlayer = source.getPlayer();
        if (nowSelected) {
            games.brennan.dungeontrain.editor.StagePanelController.openFor(selPlayer, id);
        } else {
            games.brennan.dungeontrain.editor.StagePanelController.closeFor(selPlayer);
        }
        if (nowSelected) {
            source.sendSuccess(() -> Component.literal("Editor: previewing carriages for stage '" + id
                + "'. Added parts default to this stage.").withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendSuccess(() -> Component.literal("Editor: stage preview off ('" + id + "').")
                .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    /** Clear any focused stage and restore the normal carriage preview. */
    private static int runStageDeselect(CommandSourceStack source) {
        games.brennan.dungeontrain.editor.EditorStageSelection.clear();
        restampCarriagePlotsForStage(source);
        games.brennan.dungeontrain.editor.StagePanelController.closeFor(source.getPlayer());
        source.sendSuccess(() -> Component.literal("Editor: stage preview off.")
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
            String skippedNote = r.skippedParts().isEmpty() ? ""
                : ", " + r.skippedParts().size() + " part(s) skipped (missing template)";
            source.sendSuccess(() -> Component.literal("Editor: duplicated stage '" + r.sourceStageId()
                + "' → '" + r.newStageId() + "' — " + r.partCopies().size() + " part(s) copied, "
                + r.entriesAdded() + " assignment entr" + (r.entriesAdded() == 1 ? "y" : "ies")
                + " added across " + r.touchedVariantIds().size() + " template(s)" + skippedNote + ".")
                .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            return gateFail(source, "stage duplicate", rawId, t);
        }
    }

    /** Chat listing of a stage's linked parts and their unique blocks. */
    private static int runStageBlocks(CommandSourceStack source, String rawId) {
        String id = rawId == null ? "" : rawId.toLowerCase(java.util.Locale.ROOT);
        if (!games.brennan.dungeontrain.editor.StageStore.exists(id)) {
            source.sendFailure(Component.literal("No such stage: " + rawId).withStyle(ChatFormatting.RED));
            return 0;
        }
        games.brennan.dungeontrain.editor.StageBlockIndex.StageBlocks blocks =
            games.brennan.dungeontrain.editor.StageBlockIndex.blocksForStage(
                source.getServer().overworld(), id);
        if (blocks.parts().isEmpty()) {
            source.sendSuccess(() -> Component.literal("Editor: stage '" + id
                + "' has no linked parts.").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Editor: stage '" + id + "' uses "
            + blocks.parts().size() + " part(s), " + blocks.aggregatedBlockIds().size()
            + " unique block(s):").withStyle(ChatFormatting.AQUA), false);
        for (games.brennan.dungeontrain.editor.StageBlockIndex.PartBlocks pb : blocks.parts()) {
            source.sendSuccess(() -> Component.literal("  • " + pb.part().kind().id() + ":"
                + pb.part().name() + " — " + String.join(", ", pb.blockIds()))
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
                source.sendFailure(Component.literal("Unknown block: "
                    + (from.isEmpty() ? fromId : toId)).withStyle(ChatFormatting.RED));
                return 0;
            }
            // Command path supplies an explicit <to> and no held item → no block-entity payload.
            games.brennan.dungeontrain.editor.StageBlockReplacer.Result r =
                games.brennan.dungeontrain.editor.StageBlockReplacer.replaceAcrossStage(
                    source.getServer().overworld(), rawId.toLowerCase(java.util.Locale.ROOT),
                    from.get(), to.get(), null);
            if (r.isEmpty()) {
                source.sendSuccess(() -> Component.literal("Editor: no occurrences of " + fromId
                    + " in stage '" + rawId + "'.").withStyle(ChatFormatting.YELLOW), false);
                return 0;
            }
            source.sendSuccess(() -> Component.literal("Editor: replaced " + fromId + " → " + toId
                + " across " + r.partsTouched().size() + " part(s) ("
                + r.paletteStatesRewritten() + " structural, " + r.sidecarStatesRewritten()
                + " variant state(s)). Plots re-stamped — unsaved plot edits were reset.")
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
            source.sendSuccess(() -> Component.literal(
                "Editor: parts grid showing only the focused stage's parts.")
                .withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendSuccess(() -> Component.literal("Editor: parts grid showing all parts.")
                .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    /** Toggle / set one part's editor-grid visibility (the part-list ☑/☐ checkbox). */
    private static int runPartDisplay(CommandSourceStack source, String rawKind, String name, String mode) {
        CarriagePartKind kind = CarriagePartKind.fromId(rawKind);
        if (kind == null) {
            source.sendFailure(Component.literal("Unknown part kind: " + rawKind).withStyle(ChatFormatting.RED));
            return 0;
        }
        String n = name.toLowerCase(java.util.Locale.ROOT);
        boolean displayed = switch (mode == null ? "toggle" : mode.toLowerCase(java.util.Locale.ROOT)) {
            case "on", "show" -> { games.brennan.dungeontrain.editor.EditorPartVisibility.setHidden(kind, n, false); yield true; }
            case "off", "hide" -> { games.brennan.dungeontrain.editor.EditorPartVisibility.setHidden(kind, n, true); yield false; }
            default -> games.brennan.dungeontrain.editor.EditorPartVisibility.toggle(kind, n);
        };
        restampPartsGridForStage(source);
        source.sendSuccess(() -> Component.literal("Editor: part " + kind.id() + ":" + n
            + (displayed ? " shown." : " hidden.")).withStyle(ChatFormatting.GRAY), false);
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
            source.sendFailure(Component.literal("Variant name is required.").withStyle(ChatFormatting.RED));
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
    private static final String INVALID_STAGE = new String("\0invalid");

    /**
     * Resolve a {@code <stage>} apply token: {@code custom}/blank ⇒ {@code null} (detach); an existing
     * stage id ⇒ that id; an unknown id ⇒ failure reported and {@link #INVALID_STAGE} returned.
     */
    private static String resolveStageLink(CommandSourceStack source, String token) {
        if (token == null || token.isBlank() || token.equalsIgnoreCase(STAGE_CUSTOM_TOKEN)) return null;
        String id = token.toLowerCase(java.util.Locale.ROOT);
        if (!games.brennan.dungeontrain.editor.StageStore.exists(id)) {
            source.sendFailure(Component.literal("No such stage: " + id
                + " (use 'custom' to detach).").withStyle(ChatFormatting.RED));
            return INVALID_STAGE;
        }
        return id;
    }

    private static void stageApplySuccess(CommandSourceStack source, String what, String id, String link) {
        if (link == null) {
            source.sendSuccess(() -> Component.literal("Editor: detached " + what + " " + id
                + " to Custom.").withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendSuccess(() -> Component.literal("Editor: linked " + what + " " + id
                + " to stage '" + link + "'.").withStyle(ChatFormatting.GREEN), true);
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
            source.sendFailure(Component.literal(
                "Built-in contents '" + parent.id() + "' cannot be a group parent — built-ins have hardcoded fallback behaviour."
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
        if (parent.id().equals(child.id())) {
            source.sendFailure(Component.literal(
                "Cannot add '" + parent.id() + "' as a member of itself."
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
        if (CarriageContentsGroupStore.exists(child.id())) {
            source.sendFailure(Component.literal(
                "'" + child.id() + "' is itself a contents group — nested groups are not supported (single-hop only)."
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
        // Cycle guard: parent must not already be a member of another group.
        if (CarriageContentsGroupStore.allChildIds().contains(parent.id())) {
            source.sendFailure(Component.literal(
                "'" + parent.id() + "' is already a member of another group — making it a parent would create a cycle."
            ).withStyle(ChatFormatting.RED));
            return 0;
        }

        CarriageContentsGroup existing = CarriageContentsGroupStore.get(parent.id())
            .orElse(CarriageContentsGroup.EMPTY);
        CarriageContentsGroup updated = existing.withMember(new CarriageContentsGroup.Member(child.id(), weight));
        try {
            CarriageContentsGroupStore.save(parent.id(), updated);
            source.sendSuccess(() -> Component.literal(
                "Editor: group '" + parent.id() + "' → added '" + child.id() + "' (weight=" + weight + ", "
                    + updated.members().size() + " explicit member" + (updated.members().size() == 1 ? "" : "s")
                    + " + parent self as default)."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor contents group add failed", e);
            source.sendFailure(Component.literal("group add failed: " + e.toString())
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
            source.sendFailure(Component.literal(
                "No contents group defined for '" + parent.id() + "'."
            ).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        boolean isSelf = parent.id().equals(child.id());
        if (!isSelf && existing.get().members().stream().noneMatch(m -> m.id().equals(child.id()))) {
            source.sendFailure(Component.literal(
                "'" + child.id() + "' is not a member of group '" + parent.id() + "'."
            ).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        CarriageContentsGroup updated;
        final int stored;
        if (isSelf) {
            // Canonical constructor clamps to [MIN_WEIGHT, MAX_WEIGHT].
            updated = existing.get().withSelfWeight(value);
            stored = updated.selfWeight();
        } else {
            // Member constructor clamps to [MIN_WEIGHT, MAX_WEIGHT]; withMember replaces in place.
            CarriageContentsGroup.Member updatedMember = new CarriageContentsGroup.Member(child.id(), value);
            updated = existing.get().withMember(updatedMember);
            stored = updatedMember.weight();
        }
        try {
            CarriageContentsGroupStore.save(parent.id(), updated);
            final String label = isSelf ? "selfWeight" : "'" + child.id() + "' weight";
            source.sendSuccess(() -> Component.literal(
                "Editor: group '" + parent.id() + "' → " + label + "=" + stored + "."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor contents group set-weight failed", e);
            source.sendFailure(Component.literal("group set-weight failed: " + e.toString())
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
            source.sendFailure(Component.literal(
                "No contents group defined for '" + parent.id() + "'."
            ).withStyle(ChatFormatting.YELLOW));
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
                source.sendFailure(Component.literal(
                    "'" + child.id() + "' is not a member of group '" + parent.id() + "'."
                ).withStyle(ChatFormatting.YELLOW));
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
            source.sendFailure(Component.literal("No contents group defined for '" + parent.id() + "'.")
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        java.util.Optional<CarriageContentsGroup.Member> mOpt = existing.get().member(member.id());
        if (mOpt.isEmpty()) {
            source.sendFailure(Component.literal("'" + member.id() + "' is not a member of group '"
                + parent.id() + "'.").withStyle(ChatFormatting.YELLOW));
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
            source.sendFailure(Component.literal("No contents group defined for '" + parent.id() + "'.")
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        java.util.Optional<CarriageContentsGroup.Member> mOpt = existing.get().member(member.id());
        if (mOpt.isEmpty()) {
            source.sendFailure(Component.literal("'" + member.id() + "' is not a member of group '"
                + parent.id() + "'.").withStyle(ChatFormatting.YELLOW));
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
            source.sendSuccess(() -> Component.literal("Editor: detached " + what + " " + id
                + " to Custom (no Stage links).").withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendSuccess(() -> Component.literal("Editor: " + what + " " + id + " → Stage"
                + (stageIds.size() == 1 ? " '" + stageIds.get(0) + "'"
                    : "s [" + String.join(", ", stageIds) + "]") + ".").withStyle(ChatFormatting.GREEN), true);
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
            source.sendFailure(Component.literal(
                "No contents group defined for '" + parentId + "'."
            ).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        CarriageContentsGroup updated = existing.get().withoutMember(childId);
        if (updated.members().size() == existing.get().members().size()) {
            source.sendFailure(Component.literal(
                "Group '" + parentId + "' has no member '" + childId + "'."
            ).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        try {
            if (updated.members().isEmpty()) {
                CarriageContentsGroupStore.delete(parentId);
                source.sendSuccess(() -> Component.literal(
                    "Editor: group '" + parentId + "' → removed '" + childId + "' (last member; group file deleted, parent reverts to leaf)."
                ).withStyle(ChatFormatting.GREEN), true);
            } else {
                CarriageContentsGroupStore.save(parentId, updated);
                source.sendSuccess(() -> Component.literal(
                    "Editor: group '" + parentId + "' → removed '" + childId + "' (" + updated.members().size() + " member"
                        + (updated.members().size() == 1 ? "" : "s") + " remaining)."
                ).withStyle(ChatFormatting.GREEN), true);
            }
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor contents group remove failed", e);
            source.sendFailure(Component.literal("group remove failed: " + e.toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /** {@code /dt editor contents group list <parent>} — print members + weights. */
    private static int runContentsGroupList(CommandSourceStack source, String parentRaw) {
        String parentId = parentRaw.toLowerCase(Locale.ROOT);
        java.util.Optional<CarriageContentsGroup> opt = CarriageContentsGroupStore.get(parentId);
        if (opt.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "'" + parentId + "' is not a contents group (no .group.json sidecar)."
            ), false);
            return 1;
        }
        CarriageContentsGroup group = opt.get();
        if (group.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "Group '" + parentId + "' has no members."
            ).withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
            "Group '" + parentId + "' members (" + group.members().size() + "):"
        ), false);
        for (CarriageContentsGroup.Member m : group.members()) {
            boolean resolved = CarriageContentsRegistry.find(m.id()).isPresent();
            String suffix = resolved ? "" : " (UNKNOWN — will be skipped)";
            source.sendSuccess(() -> Component.literal(
                "  " + m.id() + " weight=" + m.weight() + suffix
            ).withStyle(resolved ? ChatFormatting.GRAY : ChatFormatting.YELLOW), false);
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
            source.sendFailure(Component.literal(
                "Built-in contents '" + parent.id() + "' cannot be a group parent — built-ins have hardcoded fallback behaviour."
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
        if (CarriageContentsGroupStore.allChildIds().contains(parent.id())) {
            source.sendFailure(Component.literal(
                "'" + parent.id() + "' is already a member of another group — making it a parent would create a cycle."
            ).withStyle(ChatFormatting.RED));
            return 0;
        }

        // 2. Validate name.
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
            source.sendSuccess(() -> Component.literal(
                "Editor: created sub-variant '" + target.id() + "' of '" + parent.id()
                    + "' (" + from + ") at plot " + origin + " (" + updated.members().size()
                    + " member" + (updated.members().size() == 1 ? "" : "s") + " in group)."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents group new failed", t);
            source.sendFailure(Component.literal("group new failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /** {@code /dt editor contents group clear <parent>} — delete the group sidecar. */
    private static int runContentsGroupClear(CommandSourceStack source, String parentRaw) {
        String parentId = parentRaw.toLowerCase(Locale.ROOT);
        if (!CarriageContentsGroupStore.exists(parentId)) {
            source.sendFailure(Component.literal(
                "No contents group defined for '" + parentId + "'."
            ).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        try {
            boolean removed = CarriageContentsGroupStore.delete(parentId);
            if (removed) {
                source.sendSuccess(() -> Component.literal(
                    "Editor: cleared contents group '" + parentId + "' (parent reverts to leaf)."
                ).withStyle(ChatFormatting.GREEN), true);
            } else {
                source.sendSuccess(() -> Component.literal(
                    "Editor: '" + parentId + "' had only a bundled group definition (no per-install override deleted)."
                ).withStyle(ChatFormatting.YELLOW), true);
            }
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor contents group clear failed", e);
            source.sendFailure(Component.literal("group clear failed: " + e.toString())
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
        BlockPos plotOrigin = CarriageEditor.plotOrigin(plotVariant, dims);
        if (plotOrigin == null) {
            source.sendFailure(Component.literal("Plot origin missing for '" + plotVariant.id() + "'."));
            return null;
        }
        // Bounds-checked against this variant's own plot box — the portal corridor's is longer than
        // a carriage, and a dims-sized check would refuse every block past x=8 in it.
        CarriageDims box = CarriageEditor.plotDims(plotVariant, dims);
        BlockPos local = bhr.getBlockPos().subtract(plotOrigin);
        if (local.getX() < 0 || local.getX() >= box.length()
            || local.getY() < 0 || local.getY() >= box.height()
            || local.getZ() < 0 || local.getZ() >= box.width()) {
            source.sendFailure(Component.literal(
                "Target block is outside the plot footprint (local " + local + " vs dims "
                    + box.length() + "x" + box.height() + "x" + box.width() + ")."));
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

    private static int runVariantClearPart(CommandSourceStack source, ServerPlayer player,
                                            CarriageDims dims, CarriagePartEditor.PlotLocation partLoc) {
        BlockPos hit = lookedAtBlock(source, player);
        if (hit == null) return 0;
        BlockPos plotOrigin = CarriagePartEditor.plotOrigin(
            new games.brennan.dungeontrain.template.CarriagePartTemplateId(partLoc.kind(), partLoc.name()), dims);
        if (plotOrigin == null) {
            source.sendFailure(Component.literal("Plot origin missing for part '"
                + partLoc.kind().id() + ":" + partLoc.name() + "'."));
            return 0;
        }
        Vec3i partSize = partLoc.kind().dims(dims);
        BlockPos local = hit.subtract(plotOrigin);
        if (!inBounds(local, partSize)) {
            source.sendFailure(Component.literal(
                "Target block is outside the part footprint (local " + local + ")."));
            return 0;
        }
        CarriagePartVariantBlocks sidecar = CarriagePartVariantBlocks.loadFor(
            partLoc.kind(), partLoc.name(), partSize);
        boolean removed = sidecar.remove(local);
        if (removed) {
            try {
                sidecar.save(partLoc.kind(), partLoc.name());
            } catch (IOException e) {
                source.sendFailure(Component.literal("Variant save failed: " + e.getMessage()));
                return 0;
            }
        }
        final String pos = local.getX() + "," + local.getY() + "," + local.getZ();
        final String label = partLoc.kind().id() + ":" + partLoc.name();
        if (removed) {
            source.sendSuccess(() -> Component.literal(
                "Editor: cleared variant at local " + pos + " on part '" + label + "'."
            ).withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendSuccess(() -> Component.literal(
                "Editor: no variant at local " + pos + " to clear on part '" + label + "'."
            ), false);
        }
        return removed ? 1 : 0;
    }

    private static int runVariantClearContents(CommandSourceStack source, ServerPlayer player,
                                                 CarriageDims dims, CarriageContents contentsPlot) {
        BlockPos hit = lookedAtBlock(source, player);
        if (hit == null) return 0;
        BlockPos carriageOrigin = CarriageContentsEditor.plotOrigin(contentsPlot, dims);
        if (carriageOrigin == null) {
            source.sendFailure(Component.literal("Plot origin missing for contents '" + contentsPlot.id() + "'."));
            return 0;
        }
        BlockPos interiorOrigin = carriageOrigin.offset(1, 1, 1);
        Vec3i interiorSize = CarriageContentsPlacer.interiorSizeFor(contentsPlot, dims);
        BlockPos local = hit.subtract(interiorOrigin);
        if (!inBounds(local, interiorSize)) {
            source.sendFailure(Component.literal(
                "Target block is outside the interior footprint (local " + local + ")."));
            return 0;
        }
        CarriageContentsVariantBlocks sidecar = CarriageContentsVariantBlocks.loadFor(contentsPlot, interiorSize);
        boolean removed = sidecar.remove(local);
        if (removed) {
            try {
                sidecar.save(contentsPlot);
            } catch (IOException e) {
                source.sendFailure(Component.literal("Variant save failed: " + e.getMessage()));
                return 0;
            }
        }
        final String pos = local.getX() + "," + local.getY() + "," + local.getZ();
        if (removed) {
            source.sendSuccess(() -> Component.literal(
                "Editor: cleared variant at local " + pos + " on contents '" + contentsPlot.id() + "'."
            ).withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendSuccess(() -> Component.literal(
                "Editor: no variant at local " + pos + " to clear on contents '" + contentsPlot.id() + "'."
            ), false);
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
            source.sendFailure(Component.literal(
                "Not in an editor plot. Use '/dungeontrain editor enter <variant>' first."));
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
            source.sendSuccess(() -> Component.literal("Variants for " + label + ": (none)"), false);
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
            source.sendFailure(Component.literal(
                "Look directly at a block inside the plot first (8-block reach)."));
            return null;
        }
        return bhr.getBlockPos();
    }

    private static boolean inBounds(BlockPos local, Vec3i size) {
        return local.getX() >= 0 && local.getX() < size.getX()
            && local.getY() >= 0 && local.getY() < size.getY()
            && local.getZ() >= 0 && local.getZ() < size.getZ();
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
                "Unknown built-in '" + raw + "'. Valid: standard, windowed, flatbed"
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
     * Fire the "entered editor" advancement trigger and, the first time per play
     * session, show the editor welcome message in chat.
     */
    private static void markEnteredEditor(ServerPlayer player) {
        games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
            .trigger(player, "entered_editor");
        EditorWelcome.showOnEnter(player);
    }

    /**
     * Category-level enter: stamp every plot in {@code category} so the player
     * can walk between all of them, then teleport them to the first model.
     * Architecture has no models yet and returns a "coming soon" message.
     */
    private static int runEnterCategory(CommandSourceStack source, EditorCategory category) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        markEnteredEditor(player);

        if (category == EditorCategory.ARCHITECTURE) {
            source.sendSuccess(() -> Component.literal(
                "Architecture editor: coming soon. Walls, floor, and roof templates aren't implemented yet."
            ).withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }

        java.util.Optional<Template> first = category.firstModel();
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
        for (Template model : category.models()) {
            stampCategoryModel(overworld, model, dims);
        }

        // CARRIAGES also paints the parts grid — floor / walls / roof / doors
        // templates laid out on new Z rows past the carriage plots so every
        // authorable part is visible at a glance inside the category.
        if (category == EditorCategory.CARRIAGES) {
            CarriagePartEditor.stampAllPlots(overworld, dims);
        }

        // Remember which category is actively stamped so VariantOverlayRenderer
        // can keep the floating plot labels visible for as long as the
        // structures themselves are present — not just while the player is
        // standing inside a cage.
        EditorStampedCategoryState.set(category);

        // Teleport to the first via the existing enter path (also handles session + outline).
        try {
            Template head = first.get();
            if (head instanceof Template.Carriage cm) {
                CarriageEditor.enter(player, cm.variant());
            } else if (head instanceof Template.Contents cm) {
                CarriageContentsEditor.enter(player, cm.contents(), null);
            } else if (head instanceof Template.Pillar pm) {
                PillarEditor.enter(player, pm.section());
            } else if (head instanceof Template.Adjunct am) {
                PillarEditor.enter(player, am.adjunct());
            } else if (head instanceof Template.Tunnel tm) {
                TunnelEditor.enter(player, tm.variant());
            } else if (head instanceof Template.Track) {
                TrackEditor.enter(player);
            } else if (head instanceof Template.PortalRoom rm) {
                games.brennan.dungeontrain.editor.PortalRoomEditor.enter(player, rm.name());
            }
            final Template firstModel = head;
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

    private static void stampCategoryModel(ServerLevel overworld, Template model, CarriageDims dims) {
        if (model instanceof Template.Carriage cm) {
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
            source.sendFailure(Component.literal("Unknown category '" + categoryId + "'."));
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
                    source.sendFailure(Component.literal("Unknown pillar section in '" + id + "'."));
                    return 0;
                }
                origin = PillarEditor.plotOrigin(new games.brennan.dungeontrain.template.PillarTemplateId(sec, name), dims);
                size = new net.minecraft.core.Vec3i(1, sec.height(), dims.width());
            } else if (prefix.startsWith("adjunct_")) {
                games.brennan.dungeontrain.track.PillarAdjunct adj = tryParseAdjunct(prefix.substring("adjunct_".length()));
                if (adj == null) {
                    source.sendFailure(Component.literal("Unknown adjunct in '" + id + "'."));
                    return 0;
                }
                origin = PillarEditor.plotOriginAdjunct(new games.brennan.dungeontrain.template.PillarAdjunctTemplateId(adj, name), dims);
                size = new net.minecraft.core.Vec3i(adj.xSize(), adj.ySize(), adj.zSize());
            } else if (prefix.startsWith("tunnel_")) {
                TunnelVariant tv;
                try {
                    tv = TunnelVariant.valueOf(prefix.substring("tunnel_".length()).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    source.sendFailure(Component.literal("Unknown tunnel variant in '" + id + "'."));
                    return 0;
                }
                origin = TunnelEditor.plotOrigin(new games.brennan.dungeontrain.template.TunnelTemplateId(tv, name));
                size = new net.minecraft.core.Vec3i(
                    games.brennan.dungeontrain.tunnel.TunnelPlacer.LENGTH,
                    games.brennan.dungeontrain.tunnel.TunnelPlacer.HEIGHT,
                    games.brennan.dungeontrain.tunnel.TunnelPlacer.WIDTH);
            } else {
                source.sendFailure(Component.literal("Unrecognised track-side id '" + id + "'."));
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
                source.sendFailure(Component.literal("Unrecognised portal id '" + id + "'."));
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
                source.sendFailure(Component.literal(
                    "Unknown model '" + id + "' in category '" + category.displayName() + "'."));
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
                source.sendFailure(Component.literal(
                    "View not supported for model '" + id + "'."));
                return 0;
            }
        }

        if (origin == null) {
            source.sendFailure(Component.literal(
                "No plot origin for '" + id + "' in '" + category.displayName() + "'."));
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
        markEnteredEditor(player);
        try {
            CarriageEditor.enter(player, variant);
            CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
            source.sendSuccess(() -> Component.literal(
                "Editor: entered '" + variant.id()
                    + "' plot at " + CarriageEditor.plotOrigin(variant, dims)
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
        markEnteredEditor(player);
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
            source.sendFailure(Component.literal(
                "Not in an editor plot. Stand in a carriage plot, a part plot, or run '/dungeontrain editor enter <variant>' first."
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

            CarriageEditor.clearPlot(overworld, variant, dims);
            boolean deleted = CarriageTemplateStore.delete(variant);
            boolean wasCustom = !variant.isBuiltin();
            if (wasCustom) {
                CarriageVariantRegistry.unregister(variant.id());
                if (oldIdx >= 0) {
                    CarriageEditor.restampRowAfterDeletion(overworld, oldIdx, oldCount, dims);
                }
            }
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
                        source.sendFailure(Component.literal(
                            "Variant save failed: " + e.getMessage()
                        ).withStyle(ChatFormatting.RED));
                        return 0;
                    }
                }
                final String id = partLoc.kind().id() + ":" + partLoc.name();
                final int n = cleared;
                source.sendSuccess(() -> Component.literal(
                    "Editor: cleared all blocks in '" + id + "'"
                        + (n > 0 ? " (and " + n + " variant entr" + (n == 1 ? "y" : "ies") + ")." : ".")
                ).withStyle(ChatFormatting.GREEN), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor clear (part) failed", t);
                source.sendFailure(Component.literal("clear failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                ).withStyle(ChatFormatting.RED));
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
                        source.sendFailure(Component.literal(
                            "Variant save failed: " + e.getMessage()
                        ).withStyle(ChatFormatting.RED));
                        return 0;
                    }
                }
                final String id = contents.id();
                final int n = cleared;
                source.sendSuccess(() -> Component.literal(
                    "Editor: cleared all blocks in '" + id + "'"
                        + (n > 0 ? " (and " + n + " variant entr" + (n == 1 ? "y" : "ies") + ")." : ".")
                ).withStyle(ChatFormatting.GREEN), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor clear (contents) failed", t);
                source.sendFailure(Component.literal("clear failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                ).withStyle(ChatFormatting.RED));
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
                source.sendSuccess(() -> Component.literal(
                    "Editor: cleared all blocks in '" + id + "'"
                        + (n > 0
                            ? " (and " + n + " variant entr" + (n == 1 ? "y" : "ies")
                                + "). Run '/dungeontrain editor save' to persist."
                            : ".")
                ).withStyle(ChatFormatting.GREEN), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor clear (carriage) failed", t);
                source.sendFailure(Component.literal("clear failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                ).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        String roomName = PortalRoomEditor.plotContaining(pos, dims);
        if (roomName != null) {
            try {
                int cleared = PortalRoomEditor.clearEverything(overworld, roomName, dims);
                final String id = roomName;
                final int n = cleared;
                source.sendSuccess(() -> Component.literal(
                    "Editor: cleared all blocks in portal room '" + id + "'"
                        + (n > 0 ? " (and " + n + " authored entr" + (n == 1 ? "y" : "ies") + ")." : ".")
                ).withStyle(ChatFormatting.GREEN), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor clear (portal room) failed", t);
                source.sendFailure(Component.literal("clear failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                ).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        source.sendFailure(Component.literal(
            "editor clear: stand inside a carriage / contents / parts / portal room plot first."
        ));
        return 0;
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
            CarriageEditor.enter(player, target);
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
            var origin = CarriageEditor.createBlank(player, target);
            CarriageEditor.enter(player, target);
            source.sendSuccess(() -> Component.literal(
                "Editor: created blank '" + target.id() + "' at plot " + origin
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor new blank failed", t);
            source.sendFailure(Component.literal("new blank failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPartMenu(CommandSourceStack source, boolean on) {
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Editor partmenu: only players can toggle the menu."));
            return 0;
        }
        games.brennan.dungeontrain.editor.PartPositionMenuController.setMenuEnabled(player, on);
        source.sendSuccess(() -> Component.literal(
            "Editor part-position menu: " + (on ? "ON" : "OFF")
        ).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return 1;
    }

    /**
     * Master toggle for all editor world-space menus. Drives the persistent
     * parts-position auto-open flag (the only menu with persistent state) and,
     * when turning OFF, also force-closes the two on-demand menus (block-variant
     * tap-Z, container-contents tap-C) if they happen to be open. Those two stay
     * reopenable on demand while OFF — this only closes what's currently up.
     */
    private static int runEditorMenus(CommandSourceStack source, boolean on) {
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Editor menus: only players can toggle the menu."));
            return 0;
        }
        games.brennan.dungeontrain.editor.PartPositionMenuController.setMenuEnabled(player, on);
        if (!on) {
            // Close any open on-demand world-space menus. Both are no-ops when
            // nothing is open (drop the OPEN entry + send an empty sync packet).
            games.brennan.dungeontrain.editor.BlockVariantMenuController.toggle(player, false);
            games.brennan.dungeontrain.editor.ContainerContentsMenuController.toggle(player, false);
        }
        source.sendSuccess(() -> Component.literal(
            "Editor menus: " + (on ? "ON" : "OFF")
        ).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
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
            source.sendFailure(Component.literal(
                "'" + contents.id() + "' is a sub-variant"
                    + parentId.map(p -> " of '" + p + "'").orElse("")
                    + " — toggle the parent in the allow-list instead. Sub-variants follow their parent's allowance."
            ).withStyle(ChatFormatting.YELLOW));
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
            source.sendFailure(Component.literal(
                "Failed to update contents allow-list: " + e.getMessage()
            ).withStyle(ChatFormatting.RED));
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
            source.sendFailure(Component.literal("Name a portal room."));
            return 0;
        }
        if (!games.brennan.dungeontrain.track.variant.TrackVariantRegistry
                .namesFor(games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM)
                .contains(room)) {
            source.sendFailure(Component.literal("Unknown portal room '" + room + "'."));
            return 0;
        }
        CarriageContents contents = parseContents(source, rawContents);
        if (contents == null) return 0;
        // Same parent-level rule as the carriage path: a sub-variant is reached through its
        // parent's resolution and is never consulted against the allow-list directly.
        if (CarriageContentsGroupStore.allChildIds().contains(contents.id())) {
            java.util.Optional<String> parentId = CarriageContentsGroupStore.findParentOf(contents.id());
            source.sendFailure(Component.literal(
                "'" + contents.id() + "' is a sub-variant"
                    + parentId.map(p -> " of '" + p + "'").orElse("")
                    + " — toggle the parent in the allow-list instead. Sub-variants follow their parent's allowance."
            ).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        try {
            CarriageContentsAllowList current =
                games.brennan.dungeontrain.editor.PortalRoomContentsAllowStore.getOrEmpty(room);
            CarriageContentsAllowList updated = on
                ? current.withAllowed(contents.id())
                : current.withExcluded(contents.id());
            games.brennan.dungeontrain.editor.PortalRoomContentsAllowStore.save(room, updated);
            String summary = "Portal room '" + room + "' content '" + contents.id() + "': "
                + (on ? "ALLOWED" : "EXCLUDED");
            source.sendSuccess(() -> Component.literal(summary)
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor portal-room-contents save failed for {}/{}",
                room, contents.id(), e);
            source.sendFailure(Component.literal(
                "Failed to update contents allow-list: " + e.getMessage()
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
            source.sendFailure(Component.literal(
                "Not in a carriage editor plot — stand in the carriage whose shared flag you want to toggle."
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
        boolean target = on != null ? on
            : !games.brennan.dungeontrain.train.SharedCarriageFlags.isSharedVariant(current.id());
        try {
            games.brennan.dungeontrain.train.SharedCarriageFlags.set(current.id(), target);
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to save shared flag: " + e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        boolean enabled = games.brennan.dungeontrain.config.DungeonTrainConfig.isSharedCarriagesEnabled();
        source.sendSuccess(() -> Component.literal(
            "Carriage '" + current.id() + "' shared: " + (target ? "ON" : "off")
            + (enabled ? "" : " — note: sharedCarriagesEnabled is OFF in dungeontrain-server.toml, so the feature is inactive")
        ).withStyle(target ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
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
            CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
            source.sendSuccess(() -> Component.literal(
                "Editor: entered pillar '" + section.id()
                    + "' plot at " + PillarEditor.plotOrigin(section, dims)
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
        PillarEditor.SectionPlot sectionLoc = PillarEditor.plotContaining(player.blockPosition(), dims);
        if (sectionLoc != null) {
            return runPillarSaveSection(source, player, sectionLoc.section());
        }
        PillarEditor.AdjunctPlot adjunctLoc = PillarEditor.plotContainingAdjunct(player.blockPosition(), dims);
        if (adjunctLoc != null) {
            return runPillarSaveAdjunct(source, player, adjunctLoc.adjunct());
        }
        source.sendFailure(Component.literal(
            "Not in a pillar editor plot. Use '/dungeontrain editor pillar enter <"
                + pillarTargetList() + ">' first."
        ));
        return 0;
    }

    private static int runPillarSaveSection(CommandSourceStack source, ServerPlayer player, PillarSection section) {
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

    private static int runPillarSaveAdjunct(CommandSourceStack source, ServerPlayer player, PillarAdjunct adjunct) {
        try {
            PillarEditor.SaveResult result = PillarEditor.save(player, adjunct);
            source.sendSuccess(() -> Component.literal(
                "Editor: saved pillar adjunct '" + adjunct.id() + "' template (config-dir)."
            ), true);
            if (result.sourceAttempted()) {
                if (result.sourceWritten()) {
                    source.sendSuccess(() -> Component.literal(
                        "Editor: also wrote bundled adjunct copy to source tree (will ship with next build)."
                    ).withStyle(ChatFormatting.GREEN), true);
                } else {
                    source.sendFailure(Component.literal(
                        "Editor: pillar adjunct source-tree write failed: " + result.sourceError()
                    ).withStyle(ChatFormatting.YELLOW));
                }
            }
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar save adjunct failed", t);
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
        // NOTE: group parents are now enterable — the parent's own .nbt is the
        // "default" sub-variant of its group (Phase 2 semantic). The synthetic
        // self entry in CarriageContentsRegistry.resolveGroup keeps the parent
        // in rotation alongside explicit members.
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
            CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
            source.sendSuccess(() -> Component.literal(
                "Editor: entered contents '" + contents.id()
                    + "' (shell=" + shellUsed.id() + ") plot at "
                    + CarriageContentsEditor.plotOrigin(contents, dims)
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
            ServerLevel overworld = source.getServer().overworld();
            CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

            List<CarriageContents> rowBefore = CarriageContentsRegistry.allContents();
            int oldIdx = -1;
            for (int i = 0; i < rowBefore.size(); i++) {
                if (rowBefore.get(i).id().equals(contents.id())) { oldIdx = i; break; }
            }
            int oldCount = rowBefore.size();

            CarriageContentsEditor.clearPlot(overworld, contents, dims);
            boolean deleted = CarriageContentsStore.delete(contents);
            boolean wasCustom = !contents.isBuiltin();
            if (wasCustom) {
                CarriageContentsRegistry.unregister(contents.id());
                if (oldIdx >= 0) {
                    CarriageContentsEditor.restampRowAfterDeletion(overworld, oldIdx, oldCount, dims);
                }
            }
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
            CarriageContentsEditor.enter(player, target, null);
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
            var origin = CarriageContentsEditor.createBlank(player, target);
            CarriageContentsEditor.enter(player, target, null);
            source.sendSuccess(() -> Component.literal(
                "Editor: created blank contents '" + target.id() + "' at plot " + origin
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor contents new blank failed", t);
            source.sendFailure(Component.literal("contents new blank failed: "
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
        source.sendSuccess(() -> Component.literal(
            "Editor: pillar promote all — " + p + " promoted, " + s + " skipped (no config copy)."
                + (errStr.isEmpty() ? "" : "\nErrors:" + errStr)
        ).withStyle(errStr.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return p > 0 ? 1 : 0;
    }

    private static int runPillarEnterAdjunct(CommandSourceStack source, PillarAdjunct adjunct) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        try {
            PillarEditor.enter(player, adjunct);
            CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
            source.sendSuccess(() -> Component.literal(
                "Editor: entered pillar adjunct '" + adjunct.id()
                    + "' plot at " + PillarEditor.plotOriginAdjunct(adjunct, dims)
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar enter adjunct failed", t);
            source.sendFailure(Component.literal("pillar enter failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPillarResetAdjunct(CommandSourceStack source, PillarAdjunct adjunct) {
        try {
            boolean deleted = PillarTemplateStore.deleteAdjunct(adjunct);
            source.sendSuccess(() -> Component.literal(
                deleted
                    ? "Editor: deleted pillar adjunct '" + adjunct.id() + "' template."
                    : "Editor: no pillar adjunct '" + adjunct.id() + "' template to delete."
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar reset adjunct failed", t);
            source.sendFailure(Component.literal("pillar reset failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPillarPromoteAdjunct(CommandSourceStack source, PillarAdjunct adjunct) {
        try {
            PillarTemplateStore.promoteAdjunct(adjunct);
            source.sendSuccess(() -> Component.literal(
                "Editor: promoted pillar adjunct '" + adjunct.id()
                    + "' template to source tree (will ship with next build)."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor pillar promote adjunct failed for {}", adjunct, t);
            source.sendFailure(Component.literal("pillar promote failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runTrackEnter(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        try {
            TrackEditor.enter(player);
            CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
            source.sendSuccess(() -> Component.literal(
                "Editor: entered track plot at " + TrackEditor.plotOrigin(dims)
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

    // ---- Part runners -----------------------------------------------------

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

    private static int runPartEnter(CommandSourceStack source, String rawKind, String rawName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
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
            source.sendSuccess(() -> Component.literal(
                "Editor: entered part '" + kind.id() + ":" + name
                    + "' plot at " + plotFinal
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

    private static int runPartNew(CommandSourceStack source, String rawKind, String rawSource, String rawName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriagePartKind kind = parsePartKind(source, rawKind);
        if (kind == null) return 0;
        if (!validatePartName(source, rawName)) return 0;
        String name = rawName.toLowerCase(Locale.ROOT);
        if (CarriagePartRegistry.isKnown(kind, name)) {
            source.sendFailure(Component.literal(
                "Part '" + kind.id() + ":" + name + "' is already registered."
            ));
            return 0;
        }
        CarriagePartEditor.NewSource srcEnum;
        try {
            srcEnum = CarriagePartEditor.NewSource.valueOf(rawSource.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(
                "Unknown source '" + rawSource + "'. Valid: blank, current, standard"
            ));
            return 0;
        }
        try {
            BlockPos origin = CarriagePartEditor.createFrom(player, kind, srcEnum, name);
            source.sendSuccess(() -> Component.literal(
                "Editor: created part '" + kind.id() + ":" + name
                    + "' (source=" + rawSource.toLowerCase(Locale.ROOT) + ") at " + origin
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor part new failed", t);
            source.sendFailure(Component.literal("part new failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
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
                source.sendFailure(Component.literal(
                    "No active part editor session. Stand in a part plot or run '/dungeontrain editor part enter <kind> <name>' first."
                ));
                return 0;
            }
            kind = session.get().kind();
            sourceName = session.get().name();
        }
        String targetName;
        if (newName == null) {
            targetName = sourceName;
        } else {
            if (!validatePartName(source, newName)) return 0;
            targetName = newName.toLowerCase(Locale.ROOT);
        }
        try {
            CarriagePartEditor.SaveResult result = CarriagePartEditor.save(player, new games.brennan.dungeontrain.template.CarriagePartTemplateId(kind, targetName));
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
                source.sendFailure(Component.literal(
                    "Not in a part editor plot. Stand in a part plot or run '/dungeontrain editor part enter <kind> <name>' first."
                ));
                return 0;
            }
            kind = session.get().kind();
            oldName = session.get().name();
        }

        if (!validatePartName(source, newName)) return 0;
        String target = newName.toLowerCase(Locale.ROOT);
        if (target.equals(oldName)) {
            source.sendFailure(Component.literal(
                "New name '" + target + "' is the same as the current name."
            ));
            return 0;
        }
        if (CarriagePartRegistry.isKnown(kind, target)) {
            source.sendFailure(Component.literal(
                "Name '" + kind.id() + ":" + target + "' is already taken."
            ));
            return 0;
        }

        try {
            CarriagePartEditor.SaveResult result = CarriagePartEditor.saveAs(player,
                new games.brennan.dungeontrain.template.CarriagePartTemplateId(kind, oldName),
                new games.brennan.dungeontrain.template.CarriagePartTemplateId(kind, target));
            final String oldRef = oldName;
            source.sendSuccess(() -> Component.literal(
                "Editor: renamed part '" + kind.id() + ":" + oldRef
                    + "' -> '" + kind.id() + ":" + target + "'."
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
            LOGGER.error("[DungeonTrain] editor part rename failed", t);
            source.sendFailure(Component.literal("part rename failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
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
        source.sendSuccess(() -> Component.literal(
            "Editor: part save all — " + s + " saved"
                + (errStr.isEmpty() ? "" : "\nErrors:" + errStr)
        ).withStyle(errStr.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
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

            CarriagePartEditor.clearPlot(overworld, kind, name, dims);
            boolean deleted = CarriagePartTemplateStore.delete(kind, name);
            boolean stillBundled = CarriagePartTemplateStore.bundled(kind, name);
            if (!stillBundled) {
                CarriagePartRegistry.unregister(kind, name);
                if (oldIdx >= 0) {
                    CarriagePartEditor.restampRowAfterDeletion(overworld, kind, oldIdx, oldCount, dims);
                }
            }
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
            source.sendSuccess(() -> Component.literal(
                "Editor: '" + variant.id() + "' parts — appended '" + name + "' (weight=" + weight + ") to " + kind.id()
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
        source.sendFailure(Component.literal(
            "Unknown track kind '" + raw + "'. Try track, pillar_top/middle/bottom, "
            + "tunnel_section/portal, or adjunct_stairs."));
        return null;
    }

    /** {@code /dt editor portals enter <name>} — teleport to one room's plot. */
    private static int runPortalRoomEnter(CommandSourceStack source, String name) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        if (games.brennan.dungeontrain.track.variant.TrackVariantRegistry
                .find(games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM, name).isEmpty()) {
            source.sendFailure(Component.literal("Unknown portal room '" + name + "'."));
            return 0;
        }
        games.brennan.dungeontrain.editor.PortalRoomEditor.enter(player, name);
        source.sendSuccess(() -> Component.literal("Editor: entered portal room '" + name + "'."), true);
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
            source.sendFailure(Component.literal("Unknown portal room '" + raw + "'.")
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
                                           String message) {
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
            source.sendFailure(Component.literal("group save failed: " + failure[0].toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), true);
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
            source.sendFailure(Component.literal("Unknown " + kind.id() + " variant '" + raw + "'.")
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
            source.sendFailure(Component.literal("No " + kind.id() + " group defined for '" + parent + "'.")
                .withStyle(ChatFormatting.YELLOW));
            return null;
        }
        java.util.Optional<games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member> m =
            group.get().member(member);
        if (m.isEmpty()) {
            source.sendFailure(Component.literal("'" + member + "' is not a member of group '"
                + parent + "'.").withStyle(ChatFormatting.YELLOW));
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
            source.sendFailure(Component.literal("Cannot add '" + parent + "' as a sub-variant of itself.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME.equals(child)) {
            source.sendFailure(Component.literal(
                "'default' cannot be a sub-variant — it is the fallback every portal room falls back to.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (games.brennan.dungeontrain.editor.TrackVariantGroupStore.exists(PORTAL_ROOM_KIND, child)) {
            source.sendFailure(Component.literal(
                "'" + child + "' has sub-variants of its own — nesting is single-hop only.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        java.util.Optional<String> existingParent = games.brennan.dungeontrain.editor.TrackVariantGroupStore
            .findParentOf(PORTAL_ROOM_KIND, child);
        if (existingParent.isPresent() && !existingParent.get().equals(parent)) {
            source.sendFailure(Component.literal(
                "'" + child + "' is already a sub-variant of '" + existingParent.get() + "'.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (games.brennan.dungeontrain.editor.TrackVariantGroupStore
                .allChildIds(PORTAL_ROOM_KIND).contains(parent)) {
            source.sendFailure(Component.literal(
                "'" + parent + "' is itself a sub-variant — making it a parent would create a cycle.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        games.brennan.dungeontrain.track.variant.TrackVariantGroup updated =
            games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(PORTAL_ROOM_KIND, parent)
                .orElse(games.brennan.dungeontrain.track.variant.TrackVariantGroup.EMPTY)
                .withMember(new games.brennan.dungeontrain.track.variant.TrackVariantGroup.Member(child, weight));
        return savePortalRoomGroup(source, parent, updated,
            "Editor: portal room '" + parent + "' → added sub-variant '" + child + "' (weight=" + weight
                + ", " + updated.members().size() + " sub-variant"
                + (updated.members().size() == 1 ? "" : "s") + " + the parent itself).");
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
            source.sendFailure(Component.literal(
                "Invalid room name '" + nameRaw + "'. Allowed: lowercase letters, digits, underscore (1..32 chars).")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME.equals(key)) {
            source.sendFailure(Component.literal("'default' is reserved — pick another name.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (games.brennan.dungeontrain.track.variant.TrackVariantRegistry.contains(PORTAL_ROOM_KIND, key)) {
            source.sendFailure(Component.literal("Portal room '" + key + "' already exists.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (games.brennan.dungeontrain.editor.TrackVariantGroupStore
                .allChildIds(PORTAL_ROOM_KIND).contains(parent)) {
            source.sendFailure(Component.literal(
                "'" + parent + "' is itself a sub-variant — nesting is single-hop only.")
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
            source.sendFailure(Component.literal(
                "'" + rejected + "' is not part of '" + parent + "' — a sub-variant can only be seeded"
                    + " from its parent or a sibling.").withStyle(ChatFormatting.RED));
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
            source.sendFailure(Component.literal("group new failed: " + e.toString())
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
                copyTrackVariantSidecar(PORTAL_ROOM_KIND, seedRoom, key, dims);
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
            source.sendFailure(Component.literal("group new failed: " + e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        games.brennan.dungeontrain.editor.PortalRoomEditor.enter(player, key);
        source.sendSuccess(() -> Component.literal(
            "Editor: created portal room sub-variant '" + key + "' under '" + parent
                + "' (copied from '" + seedRoom + "') — teleported to its plot.")
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
            source.sendFailure(Component.literal("Portal room '" + parent + "' has no sub-variants.")
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        boolean isSelf = parent.equals(child);
        if (!isSelf && existing.get().member(child).isEmpty()) {
            source.sendFailure(Component.literal(
                "'" + child + "' is not a sub-variant of '" + parent + "'.").withStyle(ChatFormatting.YELLOW));
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
            "Editor: portal room '" + parent + "' → " + label + " weight=" + stored + ".");
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
            source.sendFailure(Component.literal("Portal room '" + parent + "' has no sub-variants.")
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
                source.sendFailure(Component.literal(
                    "'" + child + "' is not a sub-variant of '" + parent + "'.").withStyle(ChatFormatting.YELLOW));
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
            source.sendFailure(Component.literal(
                "'" + child + "' is not a sub-variant of '" + parent + "'.").withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        return savePortalRoomGroup(source, parent, existing.get().withoutMember(child),
            "Editor: portal room '" + parent + "' → removed sub-variant '" + child
                + "' (it is a top-level room again).");
    }

    /** {@code /dt editor portals group list <parent>} — what a room's sub-variant pool looks like. */
    private static int runPortalRoomGroupList(CommandSourceStack source, String parentRaw) {
        String parent = parsePortalRoom(source, parentRaw);
        if (parent == null) return 0;
        java.util.Optional<games.brennan.dungeontrain.track.variant.TrackVariantGroup> existing =
            games.brennan.dungeontrain.editor.TrackVariantGroupStore.get(PORTAL_ROOM_KIND, parent);
        if (existing.isEmpty() || existing.get().isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "Portal room '" + parent + "' has no sub-variants."), false);
            return 1;
        }
        games.brennan.dungeontrain.track.variant.TrackVariantGroup group = existing.get();
        StringBuilder sb = new StringBuilder("Portal room '").append(parent).append("' sub-variants: ")
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
            source.sendFailure(Component.literal("Portal room '" + parent + "' has no sub-variants.")
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
            source.sendFailure(Component.literal("group clear failed: " + failure[0].toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "Editor: portal room '" + parent + "' → sub-variants cleared (the rooms themselves are kept).")
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
    private static int runPortalRoomModeCycle(CommandSourceStack source) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name, current.withMode(current.mode().next()));
    }

    /** {@code /dt editor portals copies next} — step the Copies sub-mode. */
    private static int runPortalRoomCopiesCycle(CommandSourceStack source) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name, current.nextCopies());
    }

    /** {@code /dt editor portals contents next} — step the Contents setting. */
    private static int runPortalRoomContentsCycle(CommandSourceStack source) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name, current.withContents(current.contents().next()));
    }

    /** {@code /dt editor portals sky next} — step Off → Daylight → Day/Night → Nether → End. */
    private static int runPortalRoomSkyCycle(CommandSourceStack source) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name, current.withSky(current.sky().next()));
    }

    /** {@code /dt editor portals exits next} — step On → Random → Off, keeping the spacing. */
    private static int runPortalRoomExitsCycle(CommandSourceStack source) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name, current.withExits(current.exits().next()));
    }

    /** {@code /dt editor portals exits <on|random|off>} — set it outright, keeping the spacing. */
    private static int runPortalRoomExits(CommandSourceStack source, String raw) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomExits.Kind wanted =
            games.brennan.dungeontrain.portal.PortalRoomExits.Kind.parse(raw);
        if (!wanted.id().equalsIgnoreCase(raw.trim())) {
            source.sendFailure(Component.literal(
                "Unknown exits option '" + raw + "'. Try on, random or off."));
            return 0;
        }
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name,
            current.withExits(current.exits().withKind(wanted)));
    }

    /** Nudge the Exits spacing of the room plot the player is standing in by {@code delta}. */
    private static int runPortalRoomExitEveryStep(CommandSourceStack source, int delta) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return runPortalRoomExitEvery(source, current.exits().every() + delta);
    }

    /**
     * {@code /dt editor portals exitevery <tiles>} — set the X both readings of Exits measure in.
     *
     * <p>Clamped rather than rejected, the way the size commands are: a number the author typed and
     * meant should land on the nearest legal one with the reply saying so, not vanish.</p>
     */
    private static int runPortalRoomExitEvery(CommandSourceStack source, int tiles) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name,
            current.withExits(current.exits().withEvery(tiles)));
    }

    /** Nudge how often the room walls off its exit, by {@code delta}. */
    private static int runPortalRoomExitMoveStep(CommandSourceStack source, int delta) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return runPortalRoomExitMove(source, current.exits().moveChance() + delta);
    }

    /**
     * {@code /dt editor portals exitmove <0-10>} — how often this room walls off the base pair's
     * exit, so the only ways onward are the scattered copies. 0 never, 10 always.
     *
     * <p>Clamped rather than rejected, like the other numeric portal settings.</p>
     */
    private static int runPortalRoomExitMove(CommandSourceStack source, int chance) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name,
            current.withExits(current.exits().withMoveChance(chance)));
    }

    /** {@code /dt editor portals contents <off|fit|exact|tile>} — set it outright. */
    private static int runPortalRoomContents(CommandSourceStack source, String raw) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomContents wanted =
            games.brennan.dungeontrain.portal.PortalRoomContents.parse(raw);
        if (!wanted.id().equalsIgnoreCase(raw.trim())) {
            source.sendFailure(Component.literal(
                "Unknown contents option '" + raw + "'. Try off, fit, exact or tile."));
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
    private static int runPortalRoomSky(CommandSourceStack source, String raw) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomSky wanted =
            games.brennan.dungeontrain.portal.PortalRoomSky.parse(raw);
        if (!wanted.id().equalsIgnoreCase(raw.trim())) {
            source.sendFailure(Component.literal(
                "Unknown sky option '" + raw + "'. Try none, day, cycle, nether or end."));
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
    private static int runPortalRoomCopies(CommandSourceStack source, String raw) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomCopies.Kind wanted =
            games.brennan.dungeontrain.portal.PortalRoomCopies.Kind.parse(raw);
        if (!wanted.id().equalsIgnoreCase(kindOf(raw))) {
            source.sendFailure(Component.literal(
                "Unknown copies option '" + raw + "'. Try exact, dynamic or single."));
            return 0;
        }
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name,
            current.withCopies(current.copies().withKind(wanted)));
    }

    /**
     * {@code /dt editor portals copies block held} — set Single's block to what the author is
     * holding.
     *
     * <p>Two things are worth holding. A plain <b>block</b> is the ordinary case. A <b>variant
     * clipboard</b>, copied from a cell by the Block Variant menu, brings that cell's whole
     * candidate list over in one gesture — the same value the Edit button authors in place.</p>
     *
     * <p>The held item rather than a typed id because this is a picking gesture, and the author is
     * already standing in the plot with their palette in their hotbar. The menu is opened by a key
     * toggle rather than by holding a tool, so the main hand is free.</p>
     */
    private static int runPortalRoomCopiesBlockHeld(CommandSourceStack source) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only a player can pick a block from their hand."));
            return 0;
        }
        ItemStack held = player.getMainHandItem();

        if (held.getItem() instanceof games.brennan.dungeontrain.item.VariantClipboardItem) {
            java.util.List<games.brennan.dungeontrain.editor.VariantState> states =
                games.brennan.dungeontrain.item.VariantClipboardItem.decodeStates(
                    games.brennan.dungeontrain.item.VariantClipboardItem.readClipboardTag(held));
            if (states.isEmpty()) {
                source.sendFailure(Component.literal("That clipboard is empty."));
                return 0;
            }
            return savePortalRoomCopiesVariant(source, name,
                games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.of(states));
        }
        if (held.getItem() instanceof BlockItem blockItem) {
            return savePortalRoomCopiesVariant(source, name,
                games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.of(java.util.List.of(
                    new games.brennan.dungeontrain.editor.VariantState(
                        blockItem.getBlock().defaultBlockState(), null))));
        }
        source.sendFailure(Component.literal(
            "Hold a block, or a variant copied from a cell, then press this again."));
        return 0;
    }

    /** {@code /dt editor portals copies block <id>} — set the block by name, for a script. */
    private static int runPortalRoomCopiesBlock(CommandSourceStack source, String raw) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;

        java.util.Optional<net.minecraft.world.level.block.state.BlockState> state =
            games.brennan.dungeontrain.portal.PortalRoomSinglePlanes.stateFor(raw);
        if (state.isEmpty()) {
            source.sendFailure(Component.literal(
                "'" + raw + "' is not a block this world knows about."));
            return 0;
        }
        return savePortalRoomCopiesVariant(source, name,
            games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.of(java.util.List.of(
                new games.brennan.dungeontrain.editor.VariantState(state.get(), null))));
    }

    /**
     * {@code /dt editor portals copies block edit} — open the Block Variant menu on this room's
     * Copies block.
     *
     * <p>The same menu that authors every other cell in the game, pointed at a one-cell plot
     * ({@code PortalRoomCopiesPlot}). Add, weights, rotation modes and Copy all work there, so
     * turning the one block into a variant needs no authoring surface of its own.</p>
     */
    private static int runPortalRoomCopiesBlockEdit(CommandSourceStack source) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only a player can open the variant menu."));
            return 0;
        }
        games.brennan.dungeontrain.editor.BlockVariantMenuController.openForCopies(player, name);
        return 1;
    }

    /**
     * Store {@code variant} as what this room's copies repeat.
     *
     * <p>Replaces rather than appends: the value is one block, and the Block Variant menu is what
     * turns it into several. Two writers, one whole-value handoff each.</p>
     *
     * <p>Nothing is rejected on content. An entry that resolves to air is a gap the author asked
     * for, not a mistake to catch here.</p>
     */
    private static int savePortalRoomCopiesVariant(
        CommandSourceStack source, String name,
        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant variant
    ) {
        try {
            variant.save(name);
            if (EditorDevMode.isEnabled()) variant.saveToSource(name);
        } catch (IOException e) {
            source.sendFailure(Component.literal(
                "Could not save the copies block for portal room '" + name + "': " + e.getMessage()));
            return 0;
        }
        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.invalidate(name);
        source.sendSuccess(() -> Component.literal(
            "Editor: portal room '" + name + "' copies block is now "
                + String.join(", ", variant.blockIds())).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** {@code /dt editor portals books next} — step the author lock. */
    private static int runPortalRoomBooksCycle(CommandSourceStack source) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        return applyPortalRoomSettings(source, name, current.withBooks(current.books().next()));
    }

    /** {@code /dt editor portals books <off|self|player|signature>} — set it outright. */
    private static int runPortalRoomBooks(CommandSourceStack source, String raw) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomBooks wanted =
            games.brennan.dungeontrain.portal.PortalRoomBooks.parse(raw);
        // parse is total by design, so a typo would silently set the default rather than complain.
        // Compare on the KIND rather than the whole id: `random:1:1:1` is a legitimate spelling that
        // id() collapses back to the bare `random`, and rejecting it would be rejecting what the
        // weight steppers themselves send.
        if (!wanted.kind().id().equalsIgnoreCase(kindOf(raw))) {
            source.sendFailure(Component.literal(
                "Unknown books option '" + raw + "'. Try off, mix, "
                + "mix:<self>:<player>:<signature>, or mix:<self>:<player>:<signature>:<min>:<max>."));
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
    private static int runPortalRoomBookWeight(CommandSourceStack source,
                                               games.brennan.dungeontrain.portal.PortalRoomBooks.Share which,
                                               int delta, Integer exact) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;
        games.brennan.dungeontrain.portal.PortalRoomSettings current =
            games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
        games.brennan.dungeontrain.portal.PortalRoomBooks books = current.books();
        int wanted = exact != null ? exact : books.weightFor(which) + delta;
        return applyPortalRoomSettings(source, name,
            current.withBooks(books.withWeightFor(which, wanted)));
    }

    /** {@code /dt editor portals mode <mode>} — set it outright. */
    private static int runPortalRoomMode(CommandSourceStack source, String raw) {
        String name = portalRoomPlotUnderPlayer(source);
        if (name == null) return 0;

        games.brennan.dungeontrain.portal.PortalRoomMode wanted =
            games.brennan.dungeontrain.portal.PortalRoomMode.parse(raw);
        // parse is total by design, so a typo would silently set the default rather than complain.
        // Worth complaining about here: the player typed something and meant it.
        if (!wanted.id().equalsIgnoreCase(raw.trim())) {
            source.sendFailure(Component.literal(
                "Unknown portal room mode '" + raw + "'. Try bedrock_lock, endless_repetition or endless_open."));
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
            source.sendFailure(Component.literal(
                "Could not save the settings for portal room '" + name + "': " + e.getMessage()));
            return 0;
        }
        // The Copies and Exits halves are only worth reporting when they mean anything.
        String copies = settings.copiesApply()
            ? ", copies: " + settings.copies().displayName()
                + (settings.effectiveCopies().repeatsOneBlock()
                    ? " (" + String.join(", ",
                        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant
                            .forRoom(name, settings.copies()).blockIds()) + ")"
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
                + "/" + settings.books().signatureWeight() + ")"
                + ", authors with " + settings.books().minBooks() + "+"
                + (settings.books().maxBooks() == games.brennan.dungeontrain.portal.PortalRoomBooks.NO_MAXIMUM
                    ? "" : "\u2013" + settings.books().maxBooks())
                + " books"
            : "";
        // Same rule as Books: only worth a word when the room actually asks for a sky.
        String sky = settings.sky().lights()
            ? ", sky: " + settings.sky().displayName()
            : "";
        source.sendSuccess(() -> Component.literal(
            "Portal room '" + name + "' walls: " + settings.mode().displayName() + copies + contents
            + exits + books + sky
            + ". Portals already standing keep the settings they were built with — this takes effect "
            + "on the next one the train reaches." + subVariantNote(name)
        ).withStyle(ChatFormatting.GREEN), true);
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
    private static String portalRoomPlotUnderPlayer(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return null;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        String name = PortalRoomEditor.plotContaining(player.blockPosition(), dims);
        if (name == null) {
            source.sendFailure(Component.literal(
                "Stand in a portal room plot first — /dt editor portals."));
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
                .executes(ctx -> runPortalRoomBookWeight(ctx.getSource(), which, +1, null)))
            .then(Commands.literal("dec")
                .executes(ctx -> runPortalRoomBookWeight(ctx.getSource(), which, -1, null)))
            .then(Commands.argument("weight", IntegerArgumentType.integer(
                    games.brennan.dungeontrain.portal.PortalRoomBooks.MIN_WEIGHT,
                    games.brennan.dungeontrain.portal.PortalRoomBooks.MAX_WEIGHT))
                .executes(ctx -> runPortalRoomBookWeight(ctx.getSource(), which, 0,
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
                .executes(ctx -> runPortalRoomBookBound(ctx.getSource(), minimum, +1, null)))
            .then(Commands.literal("dec")
                .executes(ctx -> runPortalRoomBookBound(ctx.getSource(), minimum, -1, null)))
            .then(Commands.argument("books", IntegerArgumentType.integer(
                    games.brennan.dungeontrain.portal.PortalRoomBooks.MIN_BOOK_BOUND,
                    games.brennan.dungeontrain.portal.PortalRoomBooks.MAX_BOOK_BOUND))
                .executes(ctx -> runPortalRoomBookBound(ctx.getSource(), minimum, 0,
                    IntegerArgumentType.getInteger(ctx, "books"))));
    }

    /** {@code /dt editor portals books<min|max> <inc|dec|N>} — the band of author this room accepts. */
    private static int runPortalRoomBookBound(CommandSourceStack source, boolean minimum,
                                              int delta, Integer exact) {
        String name = portalRoomPlotUnderPlayer(source);
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
            .then(Commands.literal("inc").executes(ctx -> runPortalRoomSizeStep(ctx.getSource(), axis, +1)))
            .then(Commands.literal("dec").executes(ctx -> runPortalRoomSizeStep(ctx.getSource(), axis, -1)))
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
                .executes(ctx -> runPortalRoomSize(ctx.getSource(), axis,
                    IntegerArgumentType.getInteger(ctx, "blocks"))));
    }

    /**
     * {@code /dt editor portals size <length> <width> <height>} — set the whole box at once.
     *
     * <p>Argument bounds are deliberately loose; {@link PortalRoomLayout#clampSize} decides what is
     * legal for this world and the reply says so when it had to pull a number in. A typed size that
     * is silently rejected would be worse than one that is visibly clamped.</p>
     */
    private static int runPortalRoomSizeAll(CommandSourceStack source, int length, int width, int height) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        String name = PortalRoomEditor.plotContaining(player.blockPosition(), dims);
        if (name == null) {
            source.sendFailure(Component.literal(
                "Stand in a portal room plot first — /dt editor portals."));
            return 0;
        }

        net.minecraft.core.Vec3i wanted = new net.minecraft.core.Vec3i(length, height, width);
        net.minecraft.core.Vec3i applied = PortalRoomEditor.setSize(overworld, name, wanted, dims);
        boolean clamped = !applied.equals(wanted);
        String note = clamped
            ? " (clamped from " + length + " " + width + " " + height
                + " — the room must still seal the corridor mouth, and fit under the sky)"
            : "";
        source.sendSuccess(() -> Component.literal(
            "Portal room '" + name + "' is now " + applied.getX() + " long, " + applied.getZ()
            + " wide, " + applied.getY() + " tall" + note
            + ". What you built is still there — /dt save to keep the new size."
        ).withStyle(ChatFormatting.GREEN), true);
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
            ? " Grown at " + where + "."
            : " Cropped at " + where + " — step back up and those blocks come back.";
    }

    /** Nudge one axis of the room plot the player is standing in by {@code delta}. */
    private static int runPortalRoomSizeStep(CommandSourceStack source, PortalRoomResize.Axis axis, int delta) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        String name = PortalRoomEditor.plotContaining(player.blockPosition(), dims);
        if (name == null) {
            source.sendFailure(Component.literal(
                "Stand in a portal room plot first — /dt editor portals."));
            return 0;
        }
        int current = PortalRoomEditor.axisOf(PortalRoomEditor.plotSize(name, dims), axis);
        return runPortalRoomSize(source, axis, current + delta);
    }

    /**
     * {@code /dt editor portals length|width|height <blocks>} — restamp the room plot the player is
     * standing in with that axis set.
     *
     * <p>Non-destructive: the live room is carried across, length and width alternate which face
     * moves, and a shrink files the row it removes so stepping back up returns it. Nothing is written
     * to disk until the next {@code /dt save}, which is what makes the size permanent.</p>
     */
    private static int runPortalRoomSize(CommandSourceStack source, PortalRoomResize.Axis axis, int blocks) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        String name = PortalRoomEditor.plotContaining(player.blockPosition(), dims);
        if (name == null) {
            source.sendFailure(Component.literal(
                "Stand in a portal room plot first — /dt editor portals."));
            return 0;
        }

        int before = PortalRoomEditor.axisOf(PortalRoomEditor.plotSize(name, dims), axis);
        net.minecraft.core.Vec3i applied = PortalRoomEditor.setSize(overworld, name, axis, blocks, dims);
        int value = PortalRoomEditor.axisOf(applied, axis);
        String axisName = axis.name().toLowerCase(Locale.ROOT);
        String note = value == blocks ? ""
            : " (clamped from " + blocks + " — the room must still seal the corridor mouth, and fit under the sky)";
        String faces = describeFaces(dims, axis, before, value);
        source.sendSuccess(() -> Component.literal(
            "Portal room '" + name + "' " + axisName + " is now " + value + note + "." + faces
            + " What you built is still there — /dt save to keep the new size."
        ).withStyle(ChatFormatting.GREEN), true);
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
     * Mirror {@code (kind, sourceName)}'s variant-blocks sidecar onto {@code targetName} so a
     * duplicate keeps the per-cell "pick from these alternatives" authoring data — the cells,
     * their lock-group ids, and the mirror flags. Same job as
     * {@code CarriageEditor.duplicate} / {@code CarriageContentsEditor.duplicate} do for
     * carriages. No-op when the source has nothing worth persisting (e.g. duplicating the
     * synthetic "default"); a mirror-only sidecar still copies, since axis toggles are authoring
     * data too.
     *
     * <p>The footprint comes from {@link games.brennan.dungeontrain.editor.TrackSidePlots#footprint(
     * games.brennan.dungeontrain.track.variant.TrackKind, String, CarriageDims)} rather than
     * {@code kind.dims(dims)}: a portal room is whatever size its author made it, and the
     * kind-level box would send every cell above that floor through
     * {@code TrackVariantBlocks.parse}'s bounds check and out of the copy.</p>
     */
    private static void copyTrackVariantSidecar(
        games.brennan.dungeontrain.track.variant.TrackKind kind, String sourceName,
        String targetName, CarriageDims dims
    ) throws IOException {
        net.minecraft.core.Vec3i footprint =
            games.brennan.dungeontrain.editor.TrackSidePlots.footprint(kind, sourceName, dims);
        games.brennan.dungeontrain.track.variant.TrackVariantBlocks sourceSidecar =
            games.brennan.dungeontrain.track.variant.TrackVariantBlocks.loadFor(kind, sourceName, footprint);
        if (sourceSidecar.isEmpty() && sourceSidecar.isDefaultMirror()) return;
        games.brennan.dungeontrain.track.variant.TrackVariantBlocks.copyOf(sourceSidecar)
            .save(kind, targetName);
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
            source.sendFailure(Component.literal("Variant name is required."));
            return 0;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (!games.brennan.dungeontrain.track.variant.TrackVariantRegistry.NAME_PATTERN.matcher(key).matches()) {
            source.sendFailure(Component.literal(
                "Invalid variant name '" + name + "'. Allowed: lowercase letters, digits, underscore (1..32 chars)."));
            return 0;
        }
        if (games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME.equals(key)) {
            source.sendFailure(Component.literal("'default' is reserved — pick another name."));
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
                    source.sendFailure(Component.literal("Save failed: " + e.getMessage()));
                    return 0;
                }
                restampPlotForKind(overworld, kind, dims);
                teleportToPlot(player, overworld, kind, key, dims);
                source.sendSuccess(() -> Component.literal(
                    "Created " + kind.id() + ":" + key + " from the built-in room"
                    + " — teleported to the new plot."
                ).withStyle(ChatFormatting.GREEN), true);
                return 1;
            }
            source.sendFailure(Component.literal(
                "Cannot duplicate " + kind.id() + ":" + sourceName + " — no template found at expected size."));
            return 0;
        }

        try {
            games.brennan.dungeontrain.track.variant.TrackVariantStore.save(kind, key, sourceTemplate.get());
        } catch (java.io.IOException e) {
            source.sendFailure(Component.literal("Save failed: " + e.getMessage()));
            return 0;
        }

        try {
            copyTrackVariantSidecar(kind, sourceName, key, dims);
        } catch (java.io.IOException e) {
            source.sendFailure(Component.literal(
                "Variant sidecar copy failed: " + e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }

        games.brennan.dungeontrain.track.variant.TrackVariantRegistry.register(kind, key);
        restampPlotForKind(overworld, kind, dims);
        teleportToPlot(player, overworld, kind, key, dims);

        source.sendSuccess(() -> Component.literal(
            "Created " + kind.id() + ":" + key + " from " + sourceName + " — teleported to the new plot."
        ).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * {@code /dt editor tracks reset <kind>} — delete the variant the
     * player is currently standing on (must not be {@code default}),
     * unregister it, restamp, teleport back to default's plot.
     */
    private static int runTrackResetActiveVariant(CommandSourceStack source, String rawKind) {
        games.brennan.dungeontrain.track.variant.TrackKind kind = parseTrackKind(source, rawKind);
        if (kind == null) return 0;

        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        games.brennan.dungeontrain.editor.TrackPlotLocator.PlotInfo loc =
            games.brennan.dungeontrain.editor.TrackPlotLocator.locate(player, dims);
        if (loc == null || loc.kind() != kind) {
            source.sendFailure(Component.literal(
                "Stand on the " + kind.id() + " variant you want to remove first."));
            return 0;
        }
        String name = loc.name();
        if (games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME.equals(name)) {
            source.sendFailure(Component.literal(
                "Standing on the synthetic 'default' for " + kind.id() + " — nothing to remove. "
                + "Stand on a custom variant first."));
            return 0;
        }

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

        try {
            games.brennan.dungeontrain.track.variant.TrackVariantStore.delete(kind, name);
        } catch (java.io.IOException e) {
            source.sendFailure(Component.literal("Delete failed: " + e.getMessage()));
            return 0;
        }
        games.brennan.dungeontrain.track.variant.TrackVariantRegistry.unregister(kind, name);
        // Drop the removed room's cached size, or re-creating the same name later would silently
        // inherit the dead variant's dimensions.
        if (kind.hasBuiltInFallback()) {
            games.brennan.dungeontrain.portal.PortalRoomSizes.forget(name);
        }
        restampPlotForKind(overworld, kind, dims);
        teleportToPlot(player, overworld, kind,
            games.brennan.dungeontrain.track.variant.TrackKind.DEFAULT_NAME, dims);

        source.sendSuccess(() -> Component.literal(
            "Removed " + kind.id() + ":" + name + " — teleported back to default."
        ).withStyle(ChatFormatting.GREEN), true);
        return 1;
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
    private static int runUndoRedo(CommandSourceStack source, boolean redoing) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        EditorEditApplier.Result result = redoing
            ? EditorEditApplier.redo(player)
            : EditorEditApplier.undo(player);
        String verb = redoing ? "Redo" : "Undo";

        switch (result.outcome()) {
            case DONE -> {
                // Name the plot: the author may be nowhere near it, so "Undo: Place"
                // alone would not say what just changed.
                actionBar(player, verb + ": " + result.label() + " — " + result.plotKey(),
                    ChatFormatting.GREEN);
                return 1;
            }
            case NOTHING -> actionBar(player, "Nothing to " + verb.toLowerCase(Locale.ROOT),
                ChatFormatting.GRAY);
            case STALE -> actionBar(player,
                "Editor history is out of date — " + result.plotKey() + " was rebuilt",
                ChatFormatting.YELLOW);
            case FAILED -> actionBar(player, verb + " partly failed — see the log",
                ChatFormatting.RED);
        }
        return 0;
    }

    /** Overlay text above the hotbar — the editor's usual channel for transient feedback. */
    private static void actionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(Component.literal(text).withStyle(colour), true);
    }

}
