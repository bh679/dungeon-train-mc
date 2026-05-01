package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import games.brennan.dungeontrain.ship.ManagedShip;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Registers OP-only (permission level 2) commands:
 *   - {@code /dungeontrain spawn [count]} — spawns a train at the configured
 *     speed with {@code count} carriages (default from config).
 *   - {@code /dungeontrain speed <value>} — sets train speed in blocks/second,
 *     persists to config, and updates any active train live.
 *   - {@code /dungeontrain carriages <count>} — sets the default carriage
 *     count, persists to config, and resizes any active train live (rolling
 *     window grows or shrinks on the next tick).
 */
public final class TrainCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double SPAWN_DISTANCE = 10.0;

    private TrainCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
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
            .then(Commands.literal("carriages")
                .then(Commands.argument("count",
                        IntegerArgumentType.integer(DungeonTrainConfig.MIN_CARRIAGES, DungeonTrainConfig.MAX_CARRIAGES))
                    .executes(ctx -> runCarriages(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
            .then(Commands.literal("tracks")
                .then(Commands.literal("on").executes(ctx -> runTracks(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runTracks(ctx.getSource(), false))))
            .then(EditorCommand.build(buildContext))
            .then(SaveCommand.build())
            .then(ResetCommand.build())
            .then(DebugCommand.build());

        LiteralCommandNode<CommandSourceStack> registered = dispatcher.register(root);

        // `/dt` is a short alias for `/dungeontrain`. Brigadier's redirect
        // forwards every subcommand under the full name so tab-completion and
        // execution both work against the same tree — no duplicated wiring.
        dispatcher.register(Commands.literal("dt")
            .requires(s -> s.hasPermission(2))
            .redirect(registered));
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
            CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
            ManagedShip ship = TrainAssembler.spawnTrain(level, origin, velocity, count, spawnerWorldPos, dims);
            int lengthBlocks = count * dims.length();
            LOGGER.info("[DungeonTrain] Spawned train ship id={} carriages={} length={} blocks",
                ship.id(), count, lengthBlocks);
            source.sendSuccess(() -> Component.literal(
                "Spawned " + count + "-carriage train (ship id " + ship.id() + ", length "
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
        Set<UUID> updatedTrainIds = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            List<TrainTransformProvider> providers = TrainAssembler.getActiveTrainProviders(level);
            for (TrainTransformProvider p : providers) {
                p.setTargetVelocity(new Vector3d(value, 0.0, 0.0));
                updatedTrainIds.add(p.getTrainId());
            }
        }

        final int trainCount = updatedTrainIds.size();
        LOGGER.info("[DungeonTrain] /dungeontrain speed {} — updated {} active train(s)", value, trainCount);
        source.sendSuccess(() -> Component.literal(
            "Train speed set to " + value + " m/s (" + trainCount + " active train"
                + (trainCount == 1 ? "" : "s") + " updated)"
        ), true);
        return 1;
    }

    private static int runCarriages(CommandSourceStack source, int count) {
        // Per-carriage architecture: count is a config-only knob that
        // controls the appender's halfBack / halfFront window. New
        // carriages spawn on demand as the player advances; existing
        // carriages keep their pIdx and stay in place.
        DungeonTrainConfig.setNumCarriages(count);

        LOGGER.info("[DungeonTrain] /dungeontrain carriages {} — updated config; appender will respect new window on next tick", count);
        source.sendSuccess(() -> Component.literal(
            "Default carriages set to " + count
                + ". The appender will extend or trim its needed window on the next tick "
                + "as the player crosses pIdx boundaries."
        ), true);
        return 1;
    }

    private static int runTracks(CommandSourceStack source, boolean enabled) {
        DungeonTrainConfig.setGenerateTracks(enabled);
        LOGGER.info("[DungeonTrain] /dungeontrain tracks {} — generation flag flipped", enabled ? "on" : "off");
        source.sendSuccess(() -> Component.literal(
            "Track generation is now " + (enabled ? "ON" : "OFF")
                + ". Existing tracks are preserved; this only affects future chunk loads and per-tick scans."
        ), true);
        return 1;
    }
}
