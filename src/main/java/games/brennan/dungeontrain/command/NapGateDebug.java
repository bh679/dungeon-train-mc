package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.nap.NapGateData;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * {@code /dungeontrain debug nap-gate <player> [show|set <x> <dir>|wake <dir>|clear]} — read or stand in
 * for the nap gate a woken nap sets ({@link PlayerRunState#setNapGate}), so the ahead/behind counter rule
 * can be tested without taking a real nap. {@code wake} gates as a nap taken where the player stands
 * would (at their current X, as at a bed). {@code show} also prints the counters it moves.
 */
final class NapGateDebug {

    private static final Logger LOGGER = LogUtils.getLogger();

    private NapGateDebug() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("nap-gate")
            .then(Commands.argument("player", EntityArgument.player())
                .then(Commands.literal("show").executes(ctx -> show(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"))))
                .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"))))
                .then(Commands.literal("wake")
                    .then(Commands.argument("dir", IntegerArgumentType.integer(-1, 1))
                        .executes(ctx -> {
                            ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                            int dir = IntegerArgumentType.getInteger(ctx, "dir");
                            return set(ctx.getSource(), p, p.getX(), dir);
                        })))
                .then(Commands.literal("set")
                    .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("dir", IntegerArgumentType.integer(-1, 1))
                            .executes(ctx -> set(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                DoubleArgumentType.getDouble(ctx, "x"), IntegerArgumentType.getInteger(ctx, "dir")))))));
    }

    private static PlayerRunState run(ServerPlayer player) {
        return player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
    }

    private static int set(CommandSourceStack src, ServerPlayer player, double x, int dir) throws CommandSyntaxException {
        if (dir == 0) throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
            Component.literal("dir must be 1 or -1")).create();
        PlayerRunState run = run(player);
        run.setNapGate(x, dir);
        NapGateData.remember(player, run);
        return show(src, player);
    }

    private static int clear(CommandSourceStack src, ServerPlayer player) {
        PlayerRunState run = run(player);
        run.clearNapGate();
        NapGateData.remember(player, run);
        return show(src, player);
    }

    private static int show(CommandSourceStack src, ServerPlayer player) {
        PlayerRunState r = run(player);
        String line = String.format(
            "NAPGATE %s gated=%s gateX=%.1f dir=%d playerX=%.1f onTrain=%s lastTrainX=%.1f step=%d | mobKills=%d playerKills=%d echoes=%d "
                + "weapons=%s chestStreak=%d containers=%d booksRead=%d photos=%d shots=%d tamed=%d encountered=%d",
            player.getGameProfile().getName(), r.napGated(), r.napGateX(), r.napGateDir(), player.getX(),
            NapGateData.onTrainLevel(player), NapGateData.lastTrainX(player),
            NapGateData.step(player), r.mobKills(), r.playerKills(), r.echoesKilled(), r.weaponKillsSnapshot(),
            r.chestStreak(), r.containersOpened(), r.booksReadCount(), r.photosTaken(), r.photoShots(),
            r.tamedCount(), r.encounteredCount());
        LOGGER.info("[DungeonTrain] {}", line);
        src.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }
}
