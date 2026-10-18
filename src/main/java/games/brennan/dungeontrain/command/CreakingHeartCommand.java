package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import games.brennan.dungeontrain.compat.CreakingHeartSettings;
import games.brennan.dungeontrain.compat.CreakingHeartSettingsHolder;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.EditorPlotSnapshots;
import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.function.UnaryOperator;

/**
 * {@code /dungeontrain editor creakingheart <pos> …}: edits one creaking heart's
 * {@link CreakingHeartSettings}. Run by the rows of the heart's Creative right-click menu
 * ({@code client/menu/creakingheart/CreakingHeartPanel}).
 *
 * <pre>
 * day|night on|off
 * count inc|dec|&lt;1..8&gt;
 * minlevel inc|dec|&lt;n&gt;     maxlevel inc|dec|&lt;n|-1&gt;
 * phase &lt;band&gt; on|off|others
 * stage custom|&lt;stage id&gt;
 * </pre>
 */
public final class CreakingHeartCommand {

    private CreakingHeartCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("creakingheart")
            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(toggle("day", CreakingHeartSettings::withDay))
                .then(toggle("night", CreakingHeartSettings::withNight))
                .then(Commands.literal("count")
                    .then(Commands.literal("inc").executes(c -> edit(c, s -> s.withCount(s.count() + 1))))
                    .then(Commands.literal("dec").executes(c -> edit(c, s -> s.withCount(s.count() - 1))))
                    .then(Commands.argument("value", IntegerArgumentType.integer(
                            CreakingHeartSettings.MIN_COUNT, CreakingHeartSettings.MAX_COUNT))
                        .executes(c -> edit(c, s -> s.withCount(IntegerArgumentType.getInteger(c, "value"))))))
                .then(Commands.literal("minlevel")
                    .then(Commands.literal("inc").executes(c -> gate(c, g -> g.withMinLevel(g.minLevel() + 1))))
                    .then(Commands.literal("dec").executes(c -> gate(c, g -> g.withMinLevel(g.minLevel() - 1))))
                    .then(Commands.argument("value", IntegerArgumentType.integer(0, TemplateGate.MAX_LEVEL))
                        .executes(c -> gate(c, g -> g.withMinLevel(IntegerArgumentType.getInteger(c, "value"))))))
                .then(Commands.literal("maxlevel")
                    .then(Commands.literal("inc").executes(c -> gate(c, TemplateGate::incMaxLevel)))
                    .then(Commands.literal("dec").executes(c -> gate(c, TemplateGate::decMaxLevel)))
                    .then(Commands.argument("value", IntegerArgumentType.integer(TemplateGate.ALL, TemplateGate.MAX_LEVEL))
                        .executes(c -> gate(c, g -> g.withMaxLevel(IntegerArgumentType.getInteger(c, "value"))))))
                .then(Commands.literal("phase")
                    .then(Commands.argument("phase", StringArgumentType.word())
                        .then(Commands.literal("on").executes(c -> phase(c, (g, p) -> g.withPhase(p, true))))
                        .then(Commands.literal("off").executes(c -> phase(c, (g, p) -> g.withPhase(p, false))))
                        .then(Commands.literal("others").executes(c -> phase(c, TemplateGate::toggleOtherPhases)))))
                .then(Commands.literal("stage")
                    .then(Commands.argument("stage", StringArgumentType.word())
                        .executes(CreakingHeartCommand::stage))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> toggle(
            String name, java.util.function.BiFunction<CreakingHeartSettings, Boolean, CreakingHeartSettings> with) {
        return Commands.literal(name)
            .then(Commands.literal("on").executes(c -> edit(c, s -> with.apply(s, true))))
            .then(Commands.literal("off").executes(c -> edit(c, s -> with.apply(s, false))));
    }

    private static int gate(CommandContext<CommandSourceStack> c, UnaryOperator<TemplateGate> op)
            throws CommandSyntaxException {
        return edit(c, s -> s.withGate(op.apply(s.gate())));
    }

    private static int phase(CommandContext<CommandSourceStack> c,
                             java.util.function.BiFunction<TemplateGate, TrainPhase, TemplateGate> op)
            throws CommandSyntaxException {
        String token = StringArgumentType.getString(c, "phase");
        TrainPhase p = TrainPhase.byToken(token);
        if (p == null) {
            c.getSource().sendFailure(Component.literal("Unknown band '" + token + "'."));
            return 0;
        }
        return gate(c, g -> op.apply(g, p));
    }

    private static int stage(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String token = StringArgumentType.getString(c, "stage");
        if ("custom".equals(token)) return edit(c, s -> s.withStage(""));
        if (StageStore.get(token).isEmpty()) {
            c.getSource().sendFailure(Component.literal("Unknown stage '" + token + "'."));
            return 0;
        }
        return edit(c, s -> s.withStage(token));
    }

    private static int edit(CommandContext<CommandSourceStack> c, UnaryOperator<CreakingHeartSettings> op)
            throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
        if (!(c.getSource().getLevel().getBlockEntity(pos) instanceof CreakingHeartSettingsHolder holder)) {
            c.getSource().sendFailure(Component.literal("No creaking heart at " + pos.toShortString() + "."));
            return 0;
        }
        holder.dungeontrain$setSettings(op.apply(holder.dungeontrain$settings()));
        markPlotEdited(c.getSource(), pos);
        return 1;
    }

    /**
     * A heart's settings live on its block entity, which the editor's block-state dirty scan cannot
     * see, so the plot holding it is marked edited explicitly: the Save icon, Save All and the
     * unsaved-check then treat it like any other change until the plot is saved or re-stamped.
     */
    private static void markPlotEdited(CommandSourceStack source, BlockPos pos) {
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAtPos(source.getLevel(), pos, dims);
        if (plot == null) return;
        EditorPlotSnapshots.markBlockSettingsEdit(plot.dirtySnapshotKey(), pos.subtract(plot.origin()), "creaking heart");
    }
}
