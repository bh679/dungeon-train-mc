package games.brennan.dungeontrain.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.debug.CarriageDebug;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
            .then(Commands.literal("scan").executes(ctx -> runScan(ctx.getSource())));
    }

    private static int runScan(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int totalStrays = 0;
        int trainsScanned = 0;

        for (ManagedShip loaded : Shipyards.of(level).findAll()) {
            if (!(loaded.getKinematicDriver() instanceof TrainTransformProvider provider)) continue;
            trainsScanned++;

            BlockPos origin = provider.getShipyardOrigin();
            int originX = origin.getX();
            int originY = origin.getY();
            int originZ = origin.getZ();
            CarriageDims dims = provider.dims();
            int shipStrays = 0;
            for (Integer i : provider.getActiveIndices()) {
                BlockPos carriageOrigin = new BlockPos(originX + i * dims.length(), originY, originZ);
                shipStrays += CarriageDebug.scanForStrays(level, carriageOrigin,
                    "debug-cmd shipId=" + loaded.id() + " idx=" + i, dims);
            }
            final int fShipStrays = shipStrays;
            final long fShipId = loaded.id();
            source.sendSuccess(() -> Component.literal(
                "Ship " + fShipId + " — " + fShipStrays + " stray(s) across "
                    + provider.getActiveIndices().size() + " active carriage(s)"
            ), false);
            totalStrays += shipStrays;
        }

        if (trainsScanned == 0) {
            source.sendFailure(Component.literal("No Dungeon Train ships loaded in this level."));
            return 0;
        }
        final int fTotal = totalStrays;
        final int fTrains = trainsScanned;
        source.sendSuccess(() -> Component.literal(
            "Debug scan complete: " + fTotal + " total stray(s) across " + fTrains + " train(s). "
                + "See server log for offsets."
        ).withStyle(fTotal == 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        LOGGER.info("[DungeonTrain][DEBUG] /dungeontrain debug scan: {} strays across {} train(s)",
            totalStrays, trainsScanned);
        return 1;
    }
}
