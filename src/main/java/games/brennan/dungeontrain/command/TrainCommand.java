package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.CarriageTemplate;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ServerShip;

import java.util.List;

/**
 * Registers OP-only (permission level 2) commands:
 *   - {@code /dungeontrain spawn [count]} — spawns a train at the configured
 *     speed with {@code count} carriages (default from config).
 *   - {@code /dungeontrain speed <value>} — sets train speed in blocks/second,
 *     persists to config, and updates any active train live.
 */
public final class TrainCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double SPAWN_DISTANCE = 10.0;

    private TrainCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dungeontrain")
            .requires(s -> s.hasPermission(2))
            .then(Commands.literal("spawn")
                .executes(ctx -> runSpawn(ctx.getSource(), DungeonTrainConfig.getNumCarriages()))
                .then(Commands.argument("count",
                        IntegerArgumentType.integer(DungeonTrainConfig.MIN_CARRIAGES, DungeonTrainConfig.MAX_CARRIAGES))
                    .executes(ctx -> runSpawn(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
            .then(Commands.literal("speed")
                .then(Commands.argument("value",
                        DoubleArgumentType.doubleArg(DungeonTrainConfig.MIN_SPEED, DungeonTrainConfig.MAX_SPEED))
                    .executes(ctx -> runSpeed(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "value")))))
            .then(EditorCommand.build())
            .then(DebugCommand.build());

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
        Vec3 pp = player.position();
        Vector3d spawnerWorldPos = new Vector3d(pp.x, pp.y, pp.z);

        double speed = DungeonTrainConfig.getSpeed();
        Vector3d velocity = new Vector3d(speed, 0.0, 0.0);

        LOGGER.info("[DungeonTrain] /dungeontrain spawn {} by {} at origin {} speed {}",
            count, player.getName().getString(), origin, speed);

        try {
            ServerShip ship = TrainAssembler.spawnTrain(level, origin, velocity, count, spawnerWorldPos);
            int lengthBlocks = count * CarriageTemplate.LENGTH;
            LOGGER.info("[DungeonTrain] Spawned train ship id={} carriages={} length={} blocks",
                ship.getId(), count, lengthBlocks);
            source.sendSuccess(() -> Component.literal(
                "Spawned " + count + "-carriage train (ship id " + ship.getId() + ", length "
                    + lengthBlocks + " blocks) at " + origin + ", velocity +X " + speed + " m/s"
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

    private static int runSpeed(CommandSourceStack source, double value) {
        DungeonTrainConfig.setSpeed(value);

        MinecraftServer server = source.getServer();
        int updated = 0;
        for (ServerLevel level : server.getAllLevels()) {
            List<TrainTransformProvider> providers = TrainAssembler.getActiveTrainProviders(level);
            for (TrainTransformProvider p : providers) {
                p.setTargetVelocity(new Vector3d(value, 0.0, 0.0));
                updated++;
            }
        }

        final int count = updated;
        LOGGER.info("[DungeonTrain] /dungeontrain speed {} — updated {} active trains", value, count);
        source.sendSuccess(() -> Component.literal(
            "Train speed set to " + value + " m/s (" + count + " active train"
                + (count == 1 ? "" : "s") + " updated)"
        ), true);
        return 1;
    }
}
