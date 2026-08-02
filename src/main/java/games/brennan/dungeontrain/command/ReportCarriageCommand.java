package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.List;

/**
 * Player-facing report for a shared (relay-sourced) carriage: standing on one and running the command
 * files a report against it on the relay ({@code /carriages/report}); once enough independent players
 * report the same carriage it auto-flags out of the lease pool (the "serve now, community-moderate"
 * backstop to the automatic text scan). Reason is optional free text.
 *
 * <p>The carriage under the player's feet is resolved with the same recipe the on-enter chat hint uses
 * ({@code SharedCarriageEnterEvents}): {@link CarriageDeck#carriageUnder} finds the carriage, then its
 * ship-local feet position is resolved against {@link SharedCarriageRegistry}. Only a carriage that
 * actually lives on the relay (a pooled lease, or a fresh build already uploaded) has a
 * {@code relayId} to report — a not-yet-uploaded fresh carriage tells the player there's nothing to
 * report yet.</p>
 *
 * <p>Registered two ways (see {@code CommandEvents}): as a subcommand of the op-gated
 * {@code /dungeontrain} root (so it lists alongside the other admin verbs — the single-player host is
 * always op), and as a standalone player-facing {@code /reportcarriage} ({@code requires(s -> true)},
 * like {@code /bug}) so any player on a dedicated server can report.</p>
 */
public final class ReportCarriageCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ReportCarriageCommand() {}

    /** The {@code reportcarriage [reason]} node, for hanging under the {@code /dungeontrain} root. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("reportcarriage")
                .executes(ctx -> run(ctx.getSource(), null))
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "reason"))));
    }

    /** Standalone player-facing {@code /reportcarriage [reason]} — everyone, any game mode. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("reportcarriage")
                .requires(s -> true)
                .executes(ctx -> run(ctx.getSource(), null))
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "reason")))));
    }

    private static int run(CommandSourceStack source, String reason) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("command.dungeontrain.report_carriage.player_only"));
            return 0;
        }

        ServerLevel level = player.serverLevel();
        SharedCarriageRegistry.Instance inst = carriageUnder(level, player);
        if (inst == null) {
            source.sendFailure(Component.translatable("command.dungeontrain.report_carriage.not_shared"));
            return 0;
        }
        Integer relayId = inst.relayId();
        if (relayId == null) {
            source.sendFailure(Component.translatable("command.dungeontrain.report_carriage.not_on_relay"));
            return 0;
        }

        String uuid = player.getUUID().toString().replace("-", "");
        SharedCarriageClient.report(relayId, uuid, reason);
        LOGGER.info("[DungeonTrain] /reportcarriage by {} → relay id={} reason={}",
                player.getName().getString(), relayId, reason == null ? "(none)" : reason);
        source.sendSuccess(() -> Component.translatable("command.dungeontrain.report_carriage.success"), false);
        return 1;
    }

    /** Resolve the shared-carriage instance the player is standing on, or null. */
    private static SharedCarriageRegistry.Instance carriageUnder(ServerLevel level, ServerPlayer player) {
        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        if (carriages.isEmpty()) return null;
        Trains.Carriage c = CarriageDeck.carriageUnder(carriages, player);
        if (c == null) return null;
        ManagedShip ship = c.ship();
        Vector3d local = new Vector3d(player.getX(), player.getY(), player.getZ());
        ship.worldToShip(local);
        return SharedCarriageRegistry.resolve(ship.subLevelId(),
                (int) Math.floor(local.x), (int) Math.floor(local.y), (int) Math.floor(local.z));
    }
}
