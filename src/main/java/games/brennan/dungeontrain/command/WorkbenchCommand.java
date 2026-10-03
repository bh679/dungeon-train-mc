package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.builder.relay.WorkbenchCommit;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorPlotArrival;
import games.brennan.dungeontrain.editor.WorkbenchEditor;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagedBuild;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagingStore;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * {@code /dungeontrain editor workbench …} — the Workbench category and its staged builds.
 *
 * <ul>
 *   <li>{@code workbench} — enter the category (every staged build stamped at its recorded slot).</li>
 *   <li>{@code workbench list} — what is on the shelf.</li>
 *   <li>{@code workbench goto <id> [centre]} — walk to a staged build without restamping it.</li>
 *   <li>{@code workbench save <id>} — re-capture its plot into the staged snapshot.</li>
 *   <li>{@code workbench remove <id>} — erase it and delete it from the shelf.</li>
 *   <li>{@code workbench commit <id> <kind> [<subKind>] [<name>] [<parent>]} — the X menu's commit as a
 *       command, for RCON and for anyone who prefers typing; a taken name is refused here rather than
 *       asked about, and {@code replace} may be given as the first word to write over it.</li>
 * </ul>
 */
final class WorkbenchCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final SuggestionProvider<CommandSourceStack> STAGED = (ctx, b) ->
        SharedSuggestionProvider.suggest(WorkbenchStagingStore.list().stream().map(WorkbenchStagedBuild::stagedId), b);

    private static final SuggestionProvider<CommandSourceStack> KINDS = (ctx, b) ->
        SharedSuggestionProvider.suggest(java.util.Arrays.stream(BuilderPhotoPaths.Kind.values())
            .filter(WorkbenchCommit::commitsAs).map(BuilderRelayKinds::idOf), b);

    private WorkbenchCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("workbench")
            .executes(ctx -> EditorCommand.enterCategory(ctx.getSource(), EditorCategory.WORKBENCH))
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
            .then(Commands.literal("goto")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(STAGED)
                    .executes(ctx -> walkTo(ctx.getSource(), StringArgumentType.getString(ctx, "id"), false))
                    .then(Commands.literal("centre")
                        .executes(ctx -> walkTo(ctx.getSource(), StringArgumentType.getString(ctx, "id"), true)))))
            .then(Commands.literal("save")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(STAGED)
                    .executes(ctx -> save(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
            .then(Commands.literal("remove")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(STAGED)
                    .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
            .then(Commands.literal("commit")
                .then(commitNode(false))
                .then(Commands.literal("replace").then(commitNode(true))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> commitNode(boolean replace) {
        return Commands.argument("id", StringArgumentType.word()).suggests(STAGED)
            .then(Commands.argument("kind", StringArgumentType.word()).suggests(KINDS)
                .executes(ctx -> commit(ctx.getSource(), replace, StringArgumentType.getString(ctx, "id"),
                    StringArgumentType.getString(ctx, "kind"), "", "", ""))
                .then(Commands.argument("subKind", StringArgumentType.word())
                    .executes(ctx -> commit(ctx.getSource(), replace, StringArgumentType.getString(ctx, "id"),
                        StringArgumentType.getString(ctx, "kind"), StringArgumentType.getString(ctx, "subKind"), "", ""))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> commit(ctx.getSource(), replace, StringArgumentType.getString(ctx, "id"),
                            StringArgumentType.getString(ctx, "kind"), StringArgumentType.getString(ctx, "subKind"),
                            StringArgumentType.getString(ctx, "name"), ""))
                        .then(Commands.argument("parent", StringArgumentType.word())
                            .executes(ctx -> commit(ctx.getSource(), replace, StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "kind"), StringArgumentType.getString(ctx, "subKind"),
                                StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "parent")))))));
    }

    private static int list(CommandSourceStack source) {
        var staged = WorkbenchStagingStore.list();
        if (staged.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.workbench.list_empty"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.workbench.list_header", staged.size()), false);
        for (WorkbenchStagedBuild b : staged) {
            source.sendSuccess(() -> Component.literal("  " + b.stagedId() + "  ·  " + b.relayKind()
                + (b.subKind().isEmpty() ? "" : "/" + b.subKind()) + "  ·  " + b.buildName()
                + "  ·  " + b.size().getX() + "x" + b.size().getY() + "x" + b.size().getZ()).withStyle(ChatFormatting.GRAY), false);
        }
        return staged.size();
    }

    private static int walkTo(CommandSourceStack source, String id, boolean centre) {
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        if (WorkbenchStagingStore.find(id).isEmpty()) return unknown(source, id);
        if (!EditorCommand.ensureCategoryResident(source, EditorCategory.WORKBENCH)) return 0;
        ServerLevel overworld = source.getServer().overworld();
        if (WorkbenchEditor.plotOrigin(overworld, id) == null) WorkbenchEditor.stampPlot(overworld, id);
        WorkbenchEditor.enter(player, overworld, id, !centre,
            centre ? EditorPlotArrival.Inside.CENTRE : EditorPlotArrival.Inside.FRONT_DOOR);
        return 1;
    }

    private static int save(CommandSourceStack source, String id) {
        if (WorkbenchStagingStore.find(id).isEmpty()) return unknown(source, id);
        try {
            boolean saved = WorkbenchEditor.save(source.getServer().overworld(), id);
            if (!saved) {
                source.sendFailure(Component.translatable("chat.dungeontrain.workbench.not_standing", id));
                return 0;
            }
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.workbench.saved", id).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Workbench save failed for {}", id, e);
            source.sendFailure(Component.translatable("chat.dungeontrain.workbench.failed", e.getMessage()));
            return 0;
        }
    }

    private static int remove(CommandSourceStack source, String id) {
        if (WorkbenchStagingStore.find(id).isEmpty()) return unknown(source, id);
        try {
            WorkbenchEditor.remove(source.getServer().overworld(), id);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.workbench.removed", id).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Workbench remove failed for {}", id, e);
            source.sendFailure(Component.translatable("chat.dungeontrain.workbench.failed", e.getMessage()));
            return 0;
        }
    }

    private static int commit(CommandSourceStack source, boolean replace, String id, String kindId,
                              String subKind, String name, String parent) {
        WorkbenchStagedBuild staged = WorkbenchStagingStore.find(id).orElse(null);
        if (staged == null) return unknown(source, id);
        BuilderPhotoPaths.Kind kind = BuilderRelayKinds.kindOf(kindId);
        if (kind == null || !WorkbenchCommit.commitsAs(kind)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.workbench.bad_kind", kindId));
            return 0;
        }
        String sub = "-".equals(subKind) ? "" : subKind;
        String landsOn = name.isEmpty() || "-".equals(name) ? staged.buildName() : name;
        WorkbenchCommit.Request request = new WorkbenchCommit.Request(id, kind, sub, landsOn, parent,
            replace ? BuilderRelayInstall.Resolution.REPLACE : BuilderRelayInstall.Resolution.AS_IS,
            // The command has no prefab-conflict screen: this install's prefabs stay as they are.
            BuilderRelayDownload.PrefabAnswer.resolved(java.util.List.of(), java.util.Map.of()));
        BuilderRelayDownload.Result result = WorkbenchCommit.commit(source.getServer().overworld(), request);
        switch (result.outcome()) {
            case INSTALLED -> {
                source.sendSuccess(() -> Component.translatable("chat.dungeontrain.workbench.committed",
                    id, kind.id(), landsOn).withStyle(ChatFormatting.GREEN), true);
                if (!result.conflicts().isEmpty()) {
                    String kept = String.join(", ", result.conflicts().stream().map(c -> c.id()).toList());
                    source.sendSuccess(() -> Component.translatable("chat.dungeontrain.workbench.prefabs_kept_local", kept)
                        .withStyle(ChatFormatting.YELLOW), false);
                }
            }
            case ALREADY_HERE, NAME_TAKEN -> source.sendFailure(
                Component.translatable("chat.dungeontrain.workbench.name_taken", landsOn, kind.id()));
            default -> source.sendFailure(Component.translatable("chat.dungeontrain.workbench.failed", result.outcome().name()));
        }
        return result.outcome() == BuilderRelayDownload.Outcome.INSTALLED ? 1 : 0;
    }

    private static int unknown(CommandSourceStack source, String id) {
        source.sendFailure(Component.translatable("chat.dungeontrain.workbench.unknown", id));
        return 0;
    }
}
