package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.train.CarriageDims;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;

/**
 * {@code /dungeontrain whole …} — operator knobs for the Whole pool at spawn time, the counterpart of
 * {@code /dungeontrain portal carriage}.
 *
 * <pre>
 *   whole status                 every, forced cadence, pool sizes
 *   whole group every &lt;n&gt;|off    persist N (same as the editor's header cell)
 *   whole group force &lt;n&gt;|off    session-only: exactly every Nth group, seed ignored (testing)
 *   whole room list              rooms with tier, weight and whether they fit this world's dims
 *   whole group list             groups with tier, weight and carriage count
 * </pre>
 */
public final class WholeCarriageCommand {

    private WholeCarriageCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("whole")
            .requires(s -> s.hasPermission(2))
            .executes(ctx -> runStatus(ctx.getSource()))
            .then(Commands.literal("status").executes(ctx -> runStatus(ctx.getSource())))
            .then(Commands.literal("room")
                .then(Commands.literal("list").executes(ctx -> runRoomList(ctx.getSource()))))
            .then(Commands.literal("group")
                .then(Commands.literal("list").executes(ctx -> runGroupList(ctx.getSource())))
                .then(Commands.literal("every")
                    .then(Commands.literal("off").executes(ctx -> runEvery(ctx.getSource(), WholeGroupSettings.OFF)))
                    .then(Commands.argument("n", IntegerArgumentType.integer(1, WholeGroupSettings.MAX_EVERY))
                        .executes(ctx -> runEvery(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("force")
                    .then(Commands.literal("off").executes(ctx -> runForce(ctx.getSource(), WholeGroupSettings.OFF)))
                    .then(Commands.argument("n", IntegerArgumentType.integer(1, WholeGroupSettings.MAX_EVERY))
                        .executes(ctx -> runForce(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n"))))));
    }

    private static int runStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(String.format(
            "Whole: group every=%d forced=%d rooms=%d groups=%d",
            WholeGroupSettings.every(), WholeGroupSettings.forced(),
            WholeCarriageRegistry.ids().size(), CarriageGroupRegistry.ids().size())), false);
        return 1;
    }

    private static int runEvery(CommandSourceStack source, int n) {
        try {
            int stored = WholeGroupSettings.set(n);
            source.sendSuccess(() -> Component.literal("Whole group every=" + stored + (stored == 0 ? " (off)" : ""))
                .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not save whole-group settings: " + e.getMessage()));
            return 0;
        }
    }

    private static int runForce(CommandSourceStack source, int n) {
        WholeGroupSettings.force(n);
        source.sendSuccess(() -> Component.literal("Whole group forced cadence=" + n
            + (n == 0 ? " (off — seeded lottery)" : " (session only)")).withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int runRoomList(CommandSourceStack source) {
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        for (String id : WholeCarriageRegistry.ids()) {
            boolean fits = WholeCarriageTemplateStore.get(overworld, new WholeCarriage(id), dims).isPresent();
            String line = id + "  " + (WholeCarriageRegistry.isBundled(id) ? "bundled" : "user")
                + "  weight=" + WholeWeights.weightFor(WholeKind.ROOM, id) + (fits ? "" : "  (does not fit dims)");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        int n = WholeCarriageRegistry.ids().size();
        source.sendSuccess(() -> Component.literal(n + " whole room(s)"), false);
        return 1;
    }

    private static int runGroupList(CommandSourceStack source) {
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        for (String id : CarriageGroupRegistry.ids()) {
            int held = CarriageGroupTemplateStore.carriagesIn(overworld, new CarriageGroup(id), dims);
            String line = id + "  " + (CarriageGroupRegistry.isBundled(id) ? "bundled" : "user")
                + "  weight=" + WholeWeights.weightFor(WholeKind.GROUP, id) + "  carriages=" + held;
            source.sendSuccess(() -> Component.literal(line), false);
        }
        int n = CarriageGroupRegistry.ids().size();
        source.sendSuccess(() -> Component.literal(n + " whole group(s)"), false);
        return 1;
    }
}
