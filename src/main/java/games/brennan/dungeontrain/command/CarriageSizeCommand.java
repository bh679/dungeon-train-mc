package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.ContentsSize;
import games.brennan.dungeontrain.train.HalfCarriageSettings;
import games.brennan.dungeontrain.train.HalfJoinMode;
import games.brennan.dungeontrain.train.CarriageLayout;
import games.brennan.dungeontrain.train.LayoutWeights;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * {@code /dungeontrain editor shell-size <variant> room|half|full} — change a carriage template's size
 * ({@link CarriageEditor#resize}); the X menu's Room · Half · Group cells run it.
 *
 * <p>{@code /dungeontrain editor half-join wall|bridge|short|random} — how a Half pair closes the gap
 * between its halves ({@link HalfCarriageSettings}).</p>
 */
final class CarriageSizeCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CarriageSizeCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> shellSize() {
        LiteralArgumentBuilder<CommandSourceStack> sizeNode = Commands.literal("shell-size");
        var variantArg = Commands.argument("variant", StringArgumentType.word())
            .suggests((ctx, builder) -> {
                for (CarriageVariant v : CarriageVariantRegistry.allVariants()) builder.suggest(v.id());
                return builder.buildFuture();
            });
        for (ContentsSize size : ContentsSize.values()) {
            variantArg.then(Commands.literal(size.key()).executes(ctx ->
                runShellSize(ctx.getSource(), StringArgumentType.getString(ctx, "variant"), size)));
        }
        return sizeNode.then(variantArg);
    }

    /**
     * {@code layout} — how often each way of filling a group is drawn: {@code layout rooms|halves|group
     * <n>|inc|dec}, and bare {@code layout} to read all three.
     */
    static LiteralArgumentBuilder<CommandSourceStack> layout() {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("layout")
            .executes(ctx -> {
                LayoutWeights w = LayoutWeights.current();
                ctx.getSource().sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.layout_is",
                    w.rooms(), w.halves(), w.group()), false);
                return 1;
            });
        for (CarriageLayout layout : CarriageLayout.values()) {
            node.then(Commands.literal(layout.key())
                .then(Commands.literal("inc").executes(ctx -> runLayout(ctx.getSource(), layout,
                    LayoutWeights.current().weightOf(layout) + 1)))
                .then(Commands.literal("dec").executes(ctx -> runLayout(ctx.getSource(), layout,
                    LayoutWeights.current().weightOf(layout) - 1)))
                .then(Commands.argument("weight", IntegerArgumentType.integer(LayoutWeights.MIN, LayoutWeights.MAX))
                    .executes(ctx -> runLayout(ctx.getSource(), layout,
                        IntegerArgumentType.getInteger(ctx, "weight")))));
        }
        return node;
    }

    private static int runLayout(CommandSourceStack source, CarriageLayout layout, int weight) {
        try {
            LayoutWeights w = LayoutWeights.set(layout, weight);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.layout_set",
                layout.key(), w.weightOf(layout), w.rooms(), w.halves(), w.group())
                .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor layout failed", e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.shell_size_failed", e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    static LiteralArgumentBuilder<CommandSourceStack> halfJoin() {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("half-join")
            .executes(ctx -> {
                HalfJoinMode now = HalfCarriageSettings.join();
                ctx.getSource().sendSuccess(() -> Component.translatable(
                    "chat.dungeontrain.editor.half_join_is", now.key()), false);
                return 1;
            });
        for (HalfJoinMode mode : HalfJoinMode.values()) {
            node.then(Commands.literal(mode.key()).executes(ctx -> runHalfJoin(ctx.getSource(), mode)));
        }
        return node;
    }

    private static int runShellSize(CommandSourceStack source, String rawVariant, ContentsSize size) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return 0;
        }
        CarriageVariant variant = CarriageVariantRegistry.find(rawVariant).orElse(null);
        if (variant == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.shell_size_unknown", rawVariant)
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        try {
            CarriageDims box = CarriageEditor.resize(player, variant, size);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.shell_size_set",
                variant.id(), Component.translatable("gui.dungeontrain.editor_menu.size." + size.key()), box.length())
                .withStyle(ChatFormatting.GREEN), false);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.shell_size_failed", e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        } catch (RuntimeException e) {
            LOGGER.error("[DungeonTrain] editor shell-size failed for {}", variant.id(), e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.shell_size_failed", e.toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runHalfJoin(CommandSourceStack source, HalfJoinMode mode) {
        try {
            HalfJoinMode stored = HalfCarriageSettings.set(mode);
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.half_join_set", stored.key())
                .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor half-join failed", e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.shell_size_failed", e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
    }
}
