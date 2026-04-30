package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.debug.CarriageDebug;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

/**
 * {@code /dungeontrain debug scan} — walks every active carriage index of
 * every loaded Dungeon Train and reports sliver candidates (non-air blocks
 * outside the canonical footprint) via {@link CarriageDebug}.
 */
public final class DebugCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DebugCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("debug")
            .then(Commands.literal("scan").executes(ctx -> runScan(ctx.getSource())))
            .then(Commands.literal("pair")
                .executes(ctx -> runPair(ctx.getSource(), 0.0))
                .then(Commands.argument("velocity", DoubleArgumentType.doubleArg())
                    .executes(ctx -> runPair(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "velocity")))));
    }

    private static int runScan(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int totalStrays = 0;
        int carriagesScanned = 0;

        // Per-carriage architecture: each carriage is its own sub-level
        // with its own shipyardOrigin. Scan each one individually for
        // sliver candidates outside its canonical footprint.
        java.util.Map<java.util.UUID, java.util.List<games.brennan.dungeontrain.train.Trains.Carriage>> trainsById =
            games.brennan.dungeontrain.train.Trains.byTrainId(level);

        for (java.util.Map.Entry<java.util.UUID, java.util.List<games.brennan.dungeontrain.train.Trains.Carriage>> entry : trainsById.entrySet()) {
            java.util.UUID trainId = entry.getKey();
            java.util.List<games.brennan.dungeontrain.train.Trains.Carriage> train = entry.getValue();
            int trainStrays = 0;
            for (games.brennan.dungeontrain.train.Trains.Carriage c : train) {
                CarriageDims dims = c.provider().dims();
                BlockPos carriageOrigin = c.provider().getShipyardOrigin();
                int strays = CarriageDebug.scanForStrays(level, carriageOrigin,
                    "debug-cmd shipId=" + c.ship().id() + " trainId=" + trainId + " pIdx=" + c.provider().getPIdx(),
                    dims);
                trainStrays += strays;
                carriagesScanned++;
            }
            final int fTrainStrays = trainStrays;
            final java.util.UUID fTrainId = trainId;
            final int fCarriageCount = train.size();
            source.sendSuccess(() -> Component.literal(
                "Train " + fTrainId + " — " + fTrainStrays + " stray(s) across "
                    + fCarriageCount + " carriage(s)"
            ), false);
            totalStrays += trainStrays;
        }

        if (carriagesScanned == 0) {
            source.sendFailure(Component.literal("No Dungeon Train carriages loaded in this level."));
            return 0;
        }
        final int fTotal = totalStrays;
        final int fTrains = trainsById.size();
        final int fCarriages = carriagesScanned;
        source.sendSuccess(() -> Component.literal(
            "Debug scan complete: " + fTotal + " total stray(s) across "
                + fCarriages + " carriage(s) in " + fTrains + " train(s). "
                + "See server log for offsets."
        ).withStyle(fTotal == 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        LOGGER.info("[DungeonTrain][DEBUG] /dungeontrain debug scan: {} strays across {} carriage(s) in {} train(s)",
            totalStrays, carriagesScanned, trainsById.size());
        return 1;
    }

    /**
     * Probe entry point — see {@code plans/wild-leaping-taco.md} (Gate A).
     *
     * <p>Spawns two single-carriage Sable sub-levels side by side along +X at
     * the player's current world position, both attached to the kinematic
     * driver with identical {@code velocity} (0 = static probe; positive =
     * moving probe). Both providers get {@code appenderDisabled = true} so
     * the rolling appender does NOT extend the probe ships — they stay
     * exactly the size they were assembled at, which keeps the seam
     * geometry visible.</p>
     *
     * <p>The user walks across the seam between A and B and observes
     * Sable's player↔sub-level handoff. Outcome decides whether to commit
     * to the per-group sub-level refactor.</p>
     */
    private static int runPair(CommandSourceStack source, double velocity) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        Vec3 pp = player.position();
        // Spawn the carriage with its floor at the player's feet block. The
        // floor is at carriage.dy=0, so origin.y == player feet => player
        // visibly steps onto the carriage by 1 block.
        BlockPos shipAOrigin = BlockPos.containing(pp.x, pp.y, pp.z);
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        BlockPos shipBOrigin = shipAOrigin.offset(dims.length(), 0, 0);

        Vector3d vel = new Vector3d(velocity, 0.0, 0.0);

        LOGGER.info("[DungeonTrain][probe] /dt debug pair velocity={} dims={}x{}x{} player={} → shipA origin={} shipB origin={}",
            velocity, dims.length(), dims.height(), dims.width(), pp, shipAOrigin, shipBOrigin);

        try {
            // The probe spawns 2 separate single-carriage sub-levels — bypass
            // the production spawnTrain path (which would create a single
            // train with groupSize-many carriages) and call spawnGroup
            // directly with groupSize=1. Each ship gets its own trainId so
            // they're truly independent for the handoff test.
            TrainAssembler.deleteAllTrains(level);
            java.util.UUID trainIdA = java.util.UUID.randomUUID();
            java.util.UUID trainIdB = java.util.UUID.randomUUID();
            ManagedShip shipA = TrainAssembler.spawnGroup(level, shipAOrigin, vel, 0, 1, dims, trainIdA);
            ManagedShip shipB = TrainAssembler.spawnGroup(level, shipBOrigin, vel, 0, 1, dims, trainIdB);

            // Disable appender on both — the probe must stay exactly 1+1
            // carriages so the seam geometry remains observable.
            if (shipA.getKinematicDriver() instanceof TrainTransformProvider providerA) {
                providerA.setAppenderDisabled(true);
            }
            if (shipB.getKinematicDriver() instanceof TrainTransformProvider providerB) {
                providerB.setAppenderDisabled(true);
            }

            long shipAId = shipA.id();
            long shipBId = shipB.id();
            AABBdc aabbA = shipA.worldAABB();
            AABBdc aabbB = shipB.worldAABB();
            int seamWorldX = shipBOrigin.getX();

            LOGGER.info("[DungeonTrain][probe] shipA id={} aabb=[{}, {}, {} -> {}, {}, {}]",
                shipAId,
                String.format("%.2f", aabbA.minX()), String.format("%.2f", aabbA.minY()), String.format("%.2f", aabbA.minZ()),
                String.format("%.2f", aabbA.maxX()), String.format("%.2f", aabbA.maxY()), String.format("%.2f", aabbA.maxZ()));
            LOGGER.info("[DungeonTrain][probe] shipB id={} aabb=[{}, {}, {} -> {}, {}, {}]",
                shipBId,
                String.format("%.2f", aabbB.minX()), String.format("%.2f", aabbB.minY()), String.format("%.2f", aabbB.minZ()),
                String.format("%.2f", aabbB.maxX()), String.format("%.2f", aabbB.maxY()), String.format("%.2f", aabbB.maxZ()));
            LOGGER.info("[DungeonTrain][probe] seam world x={} — walk across this boundary to test handoff", seamWorldX);

            source.sendSuccess(() -> Component.literal(
                "Probe pair spawned: shipA=" + shipAId + " at " + shipAOrigin
                    + ", shipB=" + shipBId + " at " + shipBOrigin
                    + ", velocity=" + velocity + " m/s. "
                    + "Seam at world x=" + seamWorldX + ". Walk across and observe."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain][probe] /dt debug pair failed", t);
            source.sendFailure(Component.literal(
                "pair probe failed: " + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }
}
