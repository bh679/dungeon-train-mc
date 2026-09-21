package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorPlotArrival;
import games.brennan.dungeontrain.editor.WholeCarriageEditor;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.template.BuilderCredit;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupRegistry;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import games.brennan.dungeontrain.train.WholeGroupSettings;
import games.brennan.dungeontrain.train.WholeKind;
import games.brennan.dungeontrain.train.WholeWeights;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /dungeontrain editor whole …} — the Whole section's command tree, attached under
 * {@link EditorCommand#build}.
 *
 * <pre>
 *   whole                                  enter the WHOLE category
 *   whole enter &lt;room&gt;                     teleport to a room's plot
 *   whole weight &lt;room&gt; &lt;0..100&gt;|inc|dec   room pick weight
 *   whole label &lt;room&gt; [name…]             display label
 *   whole builder &lt;room&gt; &lt;uuid|none&gt; [name…]
 *   whole reset &lt;room&gt;                     delete the user copy
 *   whole every &lt;0..64&gt;|inc|dec|off        one carriage group in every N is a whole group
 *   whole group enter|weight|label|builder|reset …   the same verbs for groups
 * </pre>
 *
 * Kept out of {@link EditorCommand}, which is long past the point where another category's verbs
 * belong inline.
 */
public final class WholeEditorCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final SuggestionProvider<CommandSourceStack> ROOM_SUGGESTIONS =
        (ctx, builder) -> SharedSuggestionProvider.suggest(WholeCarriageRegistry.ids(), builder);
    private static final SuggestionProvider<CommandSourceStack> GROUP_SUGGESTIONS =
        (ctx, builder) -> SharedSuggestionProvider.suggest(CarriageGroupRegistry.ids(), builder);

    private WholeEditorCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> whole = Commands.literal("whole")
            .executes(ctx -> EditorCommand.enterCategory(ctx.getSource(), EditorCategory.WHOLE))
            .then(Commands.literal("every")
                .then(Commands.literal("off").executes(ctx -> runEvery(ctx.getSource(), WholeGroupSettings.OFF)))
                .then(Commands.literal("inc").executes(ctx -> runEvery(ctx.getSource(), WholeGroupSettings.every() + 1)))
                .then(Commands.literal("dec").executes(ctx -> runEvery(ctx.getSource(), WholeGroupSettings.every() - 1)))
                .then(Commands.argument("n", IntegerArgumentType.integer(WholeGroupSettings.OFF, WholeGroupSettings.MAX_EVERY))
                    .executes(ctx -> runEvery(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))));
        attachVerbs(whole, WholeKind.ROOM, ROOM_SUGGESTIONS);
        LiteralArgumentBuilder<CommandSourceStack> group = Commands.literal("group");
        attachVerbs(group, WholeKind.GROUP, GROUP_SUGGESTIONS);
        return whole.then(group);
    }

    private static void attachVerbs(LiteralArgumentBuilder<CommandSourceStack> node, WholeKind kind,
                                    SuggestionProvider<CommandSourceStack> suggestions) {
        node.then(EditorCommand.minLevelSingle(suggestions, (src, id, op) -> applyGate(src, kind, id, op)))
            .then(EditorCommand.maxLevelSingle(suggestions, (src, id, op) -> applyGate(src, kind, id, op)))
            .then(EditorCommand.phaseSingle(suggestions, (src, id, op) -> applyGate(src, kind, id, op)))
            .then(Commands.literal("enter")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(suggestions)
                    .executes(ctx -> runEnter(ctx.getSource(), kind, StringArgumentType.getString(ctx, "id")))))
            .then(Commands.literal("weight")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(suggestions)
                    .then(Commands.literal("inc").executes(ctx -> runWeightAdjust(ctx.getSource(), kind, StringArgumentType.getString(ctx, "id"), +1)))
                    .then(Commands.literal("dec").executes(ctx -> runWeightAdjust(ctx.getSource(), kind, StringArgumentType.getString(ctx, "id"), -1)))
                    .then(Commands.argument("value", IntegerArgumentType.integer(WholeWeights.MIN, WholeWeights.MAX))
                        .executes(ctx -> runWeightSet(ctx.getSource(), kind, StringArgumentType.getString(ctx, "id"),
                            IntegerArgumentType.getInteger(ctx, "value"))))))
            .then(Commands.literal("label")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(suggestions)
                    .executes(ctx -> runLabel(ctx.getSource(), kind, StringArgumentType.getString(ctx, "id"), ""))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> runLabel(ctx.getSource(), kind, StringArgumentType.getString(ctx, "id"),
                            StringArgumentType.getString(ctx, "name"))))))
            .then(Commands.literal("builder")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(suggestions)
                    .then(Commands.argument("uuid", StringArgumentType.word())
                        .executes(ctx -> runBuilder(ctx.getSource(), kind, StringArgumentType.getString(ctx, "id"),
                            StringArgumentType.getString(ctx, "uuid"), ""))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                            .executes(ctx -> runBuilder(ctx.getSource(), kind, StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "uuid"), StringArgumentType.getString(ctx, "name")))))))
            .then(Commands.literal("reset")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(suggestions)
                    .executes(ctx -> runReset(ctx.getSource(), kind, StringArgumentType.getString(ctx, "id")))));
    }

    // ---- gates + stages -------------------------------------------------------------------------

    /** {@code /dt editor stage apply whole|whole_group <id> <stage|custom>} — the picker's route. */
    static LiteralArgumentBuilder<CommandSourceStack> stageApplyNode(WholeKind kind) {
        SuggestionProvider<CommandSourceStack> ids = kind == WholeKind.GROUP ? GROUP_SUGGESTIONS : ROOM_SUGGESTIONS;
        return Commands.literal(kind == WholeKind.GROUP ? "whole_group" : "whole")
            .then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
                .then(Commands.argument("stage", StringArgumentType.word()).suggests(EditorCommand.STAGE_OR_CUSTOM_SUGGESTIONS)
                    .executes(c -> applyStage(c.getSource(), kind, StringArgumentType.getString(c, "id"),
                        StringArgumentType.getString(c, "stage")))));
    }

    private static int applyStage(CommandSourceStack source, WholeKind kind, String rawId, String stageToken) {
        Template model = resolve(source, kind, rawId);
        if (model == null) return 0;
        String link = EditorCommand.resolveStageLink(source, stageToken);
        if (link == EditorCommand.INVALID_STAGE) return 0;
        try {
            WholeWeights.setStage(kind, model.id(), link);
            EditorCommand.stageApplySuccess(source, "whole " + kind.id(), model.id(), link);
            return 1;
        } catch (Throwable t) {
            return EditorCommand.gateFail(source, "whole " + kind.id() + " stage", model.id(), t);
        }
    }

    private static int applyGate(CommandSourceStack source, WholeKind kind, String rawId,
                                 java.util.function.UnaryOperator<TemplateGate> op) {
        Template model = resolve(source, kind, rawId);
        if (model == null) return 0;
        try {
            TemplateGate next = op.apply(WholeWeights.gateFor(kind, model.id()));
            WholeWeights.setGate(kind, model.id(), next);
            EditorCommand.gateSuccess(source, model.id(), next, WholeWeights.configPath(kind).toString(),
                WholeWeights.stageIdFor(kind, model.id()));
            return 1;
        } catch (Throwable t) {
            return EditorCommand.gateFail(source, "whole " + kind.id(), model.id(), t);
        }
    }

    // ---- handlers -------------------------------------------------------------------------------

    private static int runEnter(CommandSourceStack source, WholeKind kind, String rawId) {
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        Template model = resolve(source, kind, rawId);
        if (model == null) return 0;
        if (!EditorCommand.ensureCategoryResident(source, EditorCategory.WHOLE)) return 0;
        WholeCarriageEditor.enter(player, model, true, true, EditorPlotArrival.Inside.FRONT_DOOR);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.whole_entered", model.displayName()), true);
        return 1;
    }

    private static int runWeightSet(CommandSourceStack source, WholeKind kind, String rawId, int value) {
        Template model = resolve(source, kind, rawId);
        if (model == null) return 0;
        try {
            int stored = WholeWeights.set(kind, model.id(), value);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.whole_weight_saved",
                kind.id(), model.id(), stored, WholeWeights.configPath(kind).toString()).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            return failure(source, model.id(), e);
        }
    }

    private static int runWeightAdjust(CommandSourceStack source, WholeKind kind, String rawId, int delta) {
        Template model = resolve(source, kind, rawId);
        if (model == null) return 0;
        return runWeightSet(source, kind, rawId, WholeWeights.weightFor(kind, model.id()) + delta);
    }

    private static int runLabel(CommandSourceStack source, WholeKind kind, String rawId, String rawName) {
        Template model = resolve(source, kind, rawId);
        if (model == null) return 0;
        try {
            WholeWeights.setName(kind, model.id(), rawName);
            String shown = WholeWeights.nameFor(kind, model.id());
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.whole_label_saved",
                kind.id(), model.id(), shown).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            return failure(source, model.id(), e);
        }
    }

    private static int runBuilder(CommandSourceStack source, WholeKind kind, String rawId, String rawUuid, String rawName) {
        Template model = resolve(source, kind, rawId);
        if (model == null) return 0;
        BuilderCredit credit = EditorBuilderCommands.parse(rawUuid, rawName);
        try {
            BuilderCredit stored = WholeWeights.setBuilder(kind, model.id(), credit);
            String who = stored == null ? "-" : stored.display();
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.whole_builder_saved",
                kind.id(), model.id(), who).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            return failure(source, model.id(), e);
        }
    }

    /** Delete the user copy. A bundled template drops back to its shipped copy; a user-only one is gone. */
    private static int runReset(CommandSourceStack source, WholeKind kind, String rawId) {
        Template model = resolve(source, kind, rawId);
        if (model == null) return 0;
        try {
            boolean existed = kind == WholeKind.GROUP
                ? CarriageGroupTemplateStore.delete(new CarriageGroup(model.id()))
                : WholeCarriageTemplateStore.delete(new WholeCarriage(model.id()));
            WholeWeights.unset(kind, model.id());
            games.brennan.dungeontrain.editor.WholeVariantBlocks.delete(kind, model.id());
            boolean bundled = model.isBuiltin();
            ServerLevel overworld = source.getServer().overworld();
            if (!bundled) {
                if (kind == WholeKind.GROUP) CarriageGroupRegistry.unregister(model.id());
                else WholeCarriageRegistry.unregister(model.id());
                // The row past it shifts one slot left; restamp what is left standing.
                for (Template m : EditorCategory.WHOLE.models()) {
                    WholeCarriageEditor.clearPlot(overworld, m, DungeonTrainWorldData.get(overworld).dims());
                }
                WholeCarriageEditor.clearPlot(overworld, model, DungeonTrainWorldData.get(overworld).dims());
                for (Template m : EditorCategory.WHOLE.models()) {
                    WholeCarriageEditor.stampPlot(overworld, m, DungeonTrainWorldData.get(overworld).dims());
                }
            } else {
                WholeCarriageEditor.stampPlot(overworld, model, DungeonTrainWorldData.get(overworld).dims());
            }
            source.sendSuccess(() -> existed
                ? Component.translatable("chat.dungeontrain.editor.deleted_template", model.displayName())
                : Component.translatable("chat.dungeontrain.editor.no_template_delete", model.displayName()), true);
            return 1;
        } catch (IOException e) {
            return failure(source, model.id(), e);
        }
    }

    private static int runEvery(CommandSourceStack source, int n) {
        try {
            int stored = WholeGroupSettings.set(n);
            source.sendSuccess(() -> stored == WholeGroupSettings.OFF
                ? Component.translatable("chat.dungeontrain.editor.whole_every_off").withStyle(ChatFormatting.GREEN)
                : Component.translatable("chat.dungeontrain.editor.whole_every_set", stored).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            return failure(source, "every", e);
        }
    }

    // ---- shared ---------------------------------------------------------------------------------

    private static Template resolve(CommandSourceStack source, WholeKind kind, String rawId) {
        String id = rawId == null ? "" : rawId.toLowerCase(Locale.ROOT);
        Optional<? extends Template> found = kind == WholeKind.GROUP
            ? CarriageGroupRegistry.find(id).map(Template.CarriageGroup::new)
            : WholeCarriageRegistry.find(id).map(Template.WholeCarriage::new);
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable(kind == WholeKind.GROUP
                ? "chat.dungeontrain.editor.unknown_whole_group" : "chat.dungeontrain.editor.unknown_whole", rawId));
            return null;
        }
        return found.get();
    }

    private static int failure(CommandSourceStack source, String id, IOException e) {
        LOGGER.warn("[DungeonTrain] whole editor command failed for {}: {}", id, e.toString());
        source.sendFailure(Component.translatable("chat.dungeontrain.editor.whole_failed", id, e.getMessage()).withStyle(ChatFormatting.RED));
        return 0;
    }
}
