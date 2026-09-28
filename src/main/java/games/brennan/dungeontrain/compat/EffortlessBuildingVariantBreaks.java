package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.VariantHoverPacket;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Clears the block-variant pools of the cells an Effortless Building break empties.
 *
 * <p>A hand break inside a block-variant plot drops that cell's pool through
 * {@link games.brennan.dungeontrain.editor.VariantBlockBreakHandler}, which listens on
 * {@code BlockEvent.BreakEvent}. Effortless Building's shape break writes with raw
 * {@code level.setBlock} from its own packet handler and fires no block events (see
 * {@link EffortlessBuildingHistory}), so without this the blocks went but every pool stayed —
 * still flagged by the overlay, still stamped back in.</p>
 *
 * <p><b>How.</b> A before/after diff, so Effortless Building's shape and modifier maths never
 * need reproducing: {@link #begin} notes the plot's flagged cells that hold a block, and
 * {@link #end} drops the pool of each one that is now air. Driven from the
 * {@code handleBreakBuildMode} injector pair in
 * {@code mixin.effortlessbuilding.EffortlessBuildingPacketHandlerMixin}; {@link #end} runs before
 * {@link EffortlessBuildingHistory#end}, whose capture was opened with this plot's sidecar key, so
 * one Ctrl+Z brings back both the blocks and their pools.</p>
 *
 * <p>Server thread only (Effortless Building enqueues its packet work), hence a plain
 * {@link HashMap}. Fails open: a failure leaves the pools where they were, never costs the
 * break.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EffortlessBuildingVariantBreaks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The plot and its occupied flagged cells (local) as they stood before the break. */
    private record Pending(String plotKey, List<BlockPos> occupied) {}

    /** player → the break currently running. Server-thread only. */
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private EffortlessBuildingVariantBreaks() {}

    /**
     * Note the occupied flagged cells of the plot the player stands in.
     *
     * @return that plot's key, for the undo capture's sidecar half; null when the player is
     *         outside every block-variant plot (nothing to clean up).
     */
    public static @Nullable String begin(ServerPlayer player) {
        if (player == null) return null;
        PENDING.remove(player.getUUID());
        try {
            ServerLevel level = player.serverLevel();
            CarriageDims dims = DungeonTrainWorldData.get(level).dims();
            BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
            if (plot == null) return null;
            BlockPos origin = plot.origin();
            List<BlockPos> occupied = plot.allFlaggedPositions().stream()
                .filter(local -> !level.getBlockState(origin.offset(local)).isAir())
                .map(BlockPos::immutable)
                .toList();
            if (!occupied.isEmpty()) PENDING.put(player.getUUID(), new Pending(plot.key(), occupied));
            return plot.key();
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Could not note variant cells ahead of an Effortless Building"
                + " break — their pools will stay: {}", t.toString());
            return null;
        }
    }

    /** Drop the pool of every noted cell the break left as air. Nothing noted is a no-op. */
    public static void end(ServerPlayer player) {
        if (player == null) return;
        Pending pending = PENDING.remove(player.getUUID());
        if (pending == null) return;
        try {
            ServerLevel level = player.serverLevel();
            BlockVariantPlot plot = BlockVariantPlot.resolveByKey(
                pending.plotKey(), DungeonTrainWorldData.get(level).dims());
            if (plot == null) return;
            BlockPos origin = plot.origin();
            int removed = 0;
            for (BlockPos local : pending.occupied()) {
                if (!level.getBlockState(origin.offset(local)).isAir()) continue;
                if (plot.remove(local)) removed++;
            }
            if (removed == 0) return;
            save(player, plot, removed);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Could not clear variant cells after an Effortless Building"
                + " break over {}: {}", pending.plotKey(), t.toString());
        }
    }

    private static void save(ServerPlayer player, BlockVariantPlot plot, int removed) {
        try {
            plot.save();
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Variant save after Effortless Building break failed for {}: {}",
                plot.key(), e.toString());
            player.displayClientMessage(Component.literal("Variant save failed: " + e.getMessage())
                .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        DungeonTrainNet.sendTo(player, VariantHoverPacket.empty());
        player.displayClientMessage(Component.literal("- removed variants from " + removed + " cells")
            .withStyle(ChatFormatting.GOLD), true);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }
}
