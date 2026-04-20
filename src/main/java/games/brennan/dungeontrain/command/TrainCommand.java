package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import games.brennan.dungeontrain.train.TrainAssembler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.ServerShip;

/**
 * Registers {@code /dungeontrain spawn} — OP-only (permission level 2).
 * Spawns a carriage 10 blocks ahead of the player, moving along +X at
 * a fixed MVP speed.
 */
public final class TrainCommand {

    private static final double SPAWN_DISTANCE = 10.0;
    private static final Vector3d TRAIN_VELOCITY = new Vector3d(2.0, 0.0, 0.0);

    private TrainCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dungeontrain")
            .requires(s -> s.hasPermission(2))
            .then(Commands.literal("spawn").executes(ctx -> runSpawn(ctx.getSource())));

        dispatcher.register(root);
    }

    private static int runSpawn(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        Vec3 look = player.getLookAngle();
        Vec3 spawn = player.position().add(look.x * SPAWN_DISTANCE, 0.0, look.z * SPAWN_DISTANCE);
        BlockPos origin = BlockPos.containing(spawn.x, spawn.y, spawn.z);

        ServerShip ship = TrainAssembler.spawnCarriage(level, origin, TRAIN_VELOCITY);

        source.sendSuccess(() -> Component.literal(
            "Spawned carriage (ship id " + ship.getId() + ") at " + origin + ", velocity +X 2 m/s"
        ), true);
        return 1;
    }
}
