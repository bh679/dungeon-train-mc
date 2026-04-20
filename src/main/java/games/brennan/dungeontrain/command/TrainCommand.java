package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageTemplate;
import games.brennan.dungeontrain.train.TrainAssembler;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ServerShip;

/**
 * Registers {@code /dungeontrain spawn [count]} — OP-only (permission level 2).
 * Spawns an N-carriage train 10 blocks ahead of the player, moving along +X at
 * a fixed MVP speed. {@code count} defaults to 5 and is clamped to [1, 20].
 */
public final class TrainCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double SPAWN_DISTANCE = 10.0;
    private static final Vector3d TRAIN_VELOCITY = new Vector3d(2.0, 0.0, 0.0);
    private static final int DEFAULT_COUNT = 5;
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 20;

    private TrainCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dungeontrain")
            .requires(s -> s.hasPermission(2))
            .then(Commands.literal("spawn")
                .executes(ctx -> runSpawn(ctx.getSource(), DEFAULT_COUNT))
                .then(Commands.argument("count", IntegerArgumentType.integer(MIN_COUNT, MAX_COUNT))
                    .executes(ctx -> runSpawn(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))));

        dispatcher.register(root);
    }

    private static int runSpawn(CommandSourceStack source, int count) {
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

        LOGGER.info("[DungeonTrain] /dungeontrain spawn {} by {} at origin {}", count, player.getName().getString(), origin);

        try {
            ServerShip ship = TrainAssembler.spawnTrain(level, origin, TRAIN_VELOCITY, count);
            int lengthBlocks = count * CarriageTemplate.LENGTH;
            LOGGER.info("[DungeonTrain] Spawned train ship id={} carriages={} length={} blocks",
                ship.getId(), count, lengthBlocks);
            source.sendSuccess(() -> Component.literal(
                "Spawned " + count + "-carriage train (ship id " + ship.getId() + ", length "
                    + lengthBlocks + " blocks) at " + origin + ", velocity +X 2 m/s"
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] spawnTrain failed", t);
            source.sendFailure(Component.literal(
                "spawnTrain failed: " + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }
}
