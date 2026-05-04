package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageContentsEditor;
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
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.editor.PillarEditor;
import games.brennan.dungeontrain.template.Template;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

    public static LiteralArgumentBuilder<CommandSourceStack> build(CommandBuildContext buildContext) {
        return Commands.literal("editor")
            .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.CARRIAGES))
            .then(Commands.literal("carriages")
                .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.CARRIAGES)))
            .then(Commands.literal("tracks")
                .executes(ctx -> runEnterCategory(ctx.getSource(), EditorCategory.TRACKS))
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
                                    IntegerArgumentType.getInteger(ctx, "value"))))))))
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
            .then(Commands.literal("clear")
                .executes(ctx -> runClear(ctx.getSource())))
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
            .then(Commands.literal("partmenu")
                .then(Commands.literal("on").executes(ctx -> runPartMenu(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runPartMenu(ctx.getSource(), false))))
            .then(Commands.literal("carriage-contents")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(CARRIAGE_VARIANT_SUGGESTIONS)
                    .then(Commands.argument("contents", StringArgumentType.word())
                        .suggests(CONTENTS_SUGGESTIONS)
                        .then(Commands.literal("on").executes(ctx -> runCarriageContentsAllow(ctx.getSource(),
                            StringArgumentType.getString(ctx, "variant"),
                            StringArgumentType.getString(ctx, "contents"), true)))
                        .then(Commands.literal("off").executes(ctx -> runCarriageContentsAllow(ctx.getSource(),
                            StringArgumentType.getString(ctx, "variant"),
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
                                IntegerArgumentType.getInteger(ctx, "value")))))))
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
     * for the contents id and persist to {@code config/dungeontrain/contents/weights.json}.
     * Mirrors {@link #runWeightSet} but for carriage-interior contents.
     */
    private static int runContentsWeightSet(CommandSourceStack source, String rawContents, int value) {
        CarriageContents contents = parseContents(source, rawContents);
        if (contents == null) return 0;
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
        Vec3i interiorSize = CarriageContentsPlacer.interiorSize(dims);
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
            Vec3i interiorSize = CarriageContentsPlacer.interiorSize(dims);
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
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(plotVariant, dims);
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
                size = new net.minecraft.core.Vec3i(dims.length(), dims.height(), dims.width());
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
            CarriageEditor.clearPlot(overworld, variant, dims);
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

    /**
     * {@code /dungeontrain editor clear} — wipes every interior block of the
     * plot the player is currently standing in to air. The barrier-cage
     * outline is preserved (it sits one block outside the footprint, which
     * {@code eraseAt} doesn't touch), so the player keeps editing in place
     * and can re-author from a clean slab. Disk template is unchanged until
     * the player explicitly hits {@code save}.
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
                final String id = partLoc.kind().id() + ":" + partLoc.name();
                source.sendSuccess(() -> Component.literal(
                    "Editor: cleared all blocks in '" + id + "'."
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
                final String id = contents.id();
                source.sendSuccess(() -> Component.literal(
                    "Editor: cleared all blocks in '" + id + "'."
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
                CarriagePlacer.eraseAt(overworld, origin, dims);
                final String id = carriage.id();
                source.sendSuccess(() -> Component.literal(
                    "Editor: cleared all blocks in '" + id + "'."
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

        source.sendFailure(Component.literal(
            "editor clear: stand inside a carriage / contents / parts plot first."
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
            CarriageContentsEditor.clearPlot(overworld, contents, dims);
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
            CarriagePartEditor.clearPlot(overworld, kind, name, dims);
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
        }
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
        clearPlotForVariant(overworld, kind, name, dims);

        try {
            games.brennan.dungeontrain.track.variant.TrackVariantStore.delete(kind, name);
        } catch (java.io.IOException e) {
            source.sendFailure(Component.literal("Delete failed: " + e.getMessage()));
            return 0;
        }
        games.brennan.dungeontrain.track.variant.TrackVariantRegistry.unregister(kind, name);
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
        net.minecraft.core.Vec3i fp =
            games.brennan.dungeontrain.editor.TrackSidePlots.footprint(kind, dims);
        double tx = origin.getX() + fp.getX() / 2.0;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + fp.getZ() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());
    }
}
