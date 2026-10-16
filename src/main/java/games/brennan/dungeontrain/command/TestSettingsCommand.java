package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.editor.TestOverrides;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PortalTestSessionPacket;
import games.brennan.dungeontrain.portal.PortalTestSession;
import games.brennan.dungeontrain.template.TestGate;
import games.brennan.dungeontrain.train.CarriageTestSession;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * {@code portal test set stage|level|band <value|auto>} — the editor's test settings rows.
 *
 * <p>They change where a test pretends to stand, never the template: see {@link TestGate}. Inside a
 * test the copy is stood up again on the same rolls, so the setting is the one thing that differs;
 * outside one the setting waits for the next test.</p>
 */
public final class TestSettingsCommand {

    /** The highest Diff-Level a test can be set to — well past the last bundled stage band. */
    public static final int MAX_LEVEL = 999;

    private static final String AUTO = "auto";

    private TestSettingsCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("set")
            .then(Commands.literal("stage")
                .then(Commands.literal(AUTO).executes(ctx -> apply(ctx.getSource(), g -> g.withStage(null))))
                .then(Commands.argument("stage", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(StageStore.allIds(), b))
                    .executes(ctx -> setStage(ctx.getSource(), StringArgumentType.getString(ctx, "stage")))))
            .then(Commands.literal("level")
                .then(Commands.literal(AUTO).executes(ctx -> apply(ctx.getSource(), g -> g.withLevel(TestGate.AUTO_LEVEL))))
                .then(Commands.argument("level", IntegerArgumentType.integer(0, MAX_LEVEL))
                    .executes(ctx -> apply(ctx.getSource(),
                        g -> g.withLevel(IntegerArgumentType.getInteger(ctx, "level"))))))
            .then(Commands.literal("band")
                .then(Commands.literal(AUTO).executes(ctx -> apply(ctx.getSource(), g -> g.withPhase(null))))
                .then(Commands.argument("band", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                        Arrays.stream(TrainPhase.values()).map(TestSettingsCommand::bandArg), b))
                    .executes(ctx -> setBand(ctx.getSource(), StringArgumentType.getString(ctx, "band")))));
    }

    /** A band as the command spells it: its constant, lower-cased. */
    public static String bandArg(TrainPhase phase) {
        return phase.name().toLowerCase(Locale.ROOT);
    }

    private static int setStage(CommandSourceStack source, String raw) {
        String id = raw.toLowerCase(Locale.ROOT);
        if (!StageStore.exists(id)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.test_settings.unknown_stage", raw)
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        return apply(source, g -> g.withStage(id));
    }

    private static int setBand(CommandSourceStack source, String raw) {
        Optional<TrainPhase> phase = Arrays.stream(TrainPhase.values())
            .filter(p -> bandArg(p).equals(raw.toLowerCase(Locale.ROOT))).findFirst();
        if (phase.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.test_settings.unknown_band", raw)
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        return apply(source, g -> g.withPhase(phase.get()));
    }

    /** Store the changed settings, tell the client, and re-stand the running test on its own rolls. */
    private static int apply(CommandSourceStack source, UnaryOperator<TestGate> change) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return 0;
        }
        TestOverrides.set(player.getUUID(), change.apply(TestOverrides.get(player.getUUID())));
        DungeonTrainNet.sendTo(player, PortalTestSessionPacket.of(player));
        if (PortalTestSession.has(player.getUUID()) || CarriageTestSession.has(player.getUUID())) {
            return PortalTestCommand.runRestamp(source, TestReroll.NONE);
        }
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.test_settings.saved")
            .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
