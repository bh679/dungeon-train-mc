package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import games.brennan.dungeontrain.event.CinematographerService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class CinematographerCommand {

    private CinematographerCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("cinematographer")
            .executes(ctx -> runToggle(ctx.getSource()))
            .then(Commands.argument("distance", DoubleArgumentType.doubleArg(1.0, 64.0))
                .executes(ctx -> runEnterWithDistance(ctx.getSource(),
                    DoubleArgumentType.getDouble(ctx, "distance"))));
    }

    private static int runToggle(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        if (CinematographerService.isActive(player.getUUID())) {
            CinematographerService.exit(player);
            source.sendSuccess(() -> Component.literal("Cinematographer mode OFF."), true);
        } else {
            double d = CinematographerService.DEFAULT_DISTANCE;
            CinematographerService.enter(player, d);
            source.sendSuccess(() -> Component.literal(
                "Cinematographer mode ON (distance: " + d + ")."
            ), true);
        }
        return 1;
    }

    private static int runEnterWithDistance(CommandSourceStack source, double distance) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        if (CinematographerService.isActive(player.getUUID())) {
            CinematographerService.updateDistance(player.getUUID(), distance);
            source.sendSuccess(() -> Component.literal(
                "Cinematographer distance updated to " + distance + "."
            ), true);
        } else {
            CinematographerService.enter(player, distance);
            source.sendSuccess(() -> Component.literal(
                "Cinematographer mode ON (distance: " + distance + ")."
            ), true);
        }
        return 1;
    }
}
