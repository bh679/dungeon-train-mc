package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import games.brennan.dungeontrain.event.CinematographerClearView;
import games.brennan.dungeontrain.event.CinematographerService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.OptionalDouble;
import java.util.UUID;

public final class CinematographerCommand {

    private CinematographerCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("cinematographer")
            .executes(ctx -> runToggle(ctx.getSource()))
            .then(Commands.argument("distance", DoubleArgumentType.doubleArg(1.0, 64.0))
                .executes(ctx -> runEnterWithDistance(ctx.getSource(),
                    DoubleArgumentType.getDouble(ctx, "distance"))))
            .then(Commands.literal("clearview")
                .then(Commands.literal("on")
                    .executes(ctx -> runClearView(ctx.getSource(), true, OptionalDouble.empty()))
                    .then(Commands.argument("distance", DoubleArgumentType.doubleArg(1.0, 256.0))
                        .executes(ctx -> runClearView(ctx.getSource(), true,
                            OptionalDouble.of(DoubleArgumentType.getDouble(ctx, "distance"))))))
                .then(Commands.literal("off")
                    .executes(ctx -> runClearView(ctx.getSource(), false, OptionalDouble.empty()))));
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
            CinematographerClearView.restoreAll(player);
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

    /**
     * Toggle the clear-view sub-mode, which removes solid blocks (not doors) at
     * head height in front of the camera. Enabling auto-enters cinematographer
     * mode (at the default distance) if the player is not already in it, so a
     * single command gets them filming. When no distance is given the reach
     * defaults to the door distance + {@link CinematographerService#CLEARVIEW_DISTANCE_OFFSET}.
     */
    private static int runClearView(CommandSourceStack source, boolean enable, OptionalDouble distance) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        UUID id = player.getUUID();
        if (enable) {
            if (!CinematographerService.isActive(id)) {
                CinematographerService.enter(player, CinematographerService.DEFAULT_DISTANCE);
            }
            double reach = distance.orElseGet(() -> CinematographerService.getDefaultClearViewDistance(id));
            CinematographerService.setClearView(id, true, reach);
            source.sendSuccess(() -> Component.literal(
                "Cinematographer clear-view ON (reach: " + reach + ")."
            ), true);
        } else {
            CinematographerClearView.restoreAll(player);
            if (CinematographerService.isActive(id)) {
                CinematographerService.setClearView(id, false);
            }
            source.sendSuccess(() -> Component.literal("Cinematographer clear-view OFF."), true);
        }
        return 1;
    }
}
