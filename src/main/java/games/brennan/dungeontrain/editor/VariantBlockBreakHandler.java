package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.VariantHoverPacket;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.util.List;

/**
 * Removes block-variant sidecar entries when the underlying block is broken
 * inside an editor plot, so {@link VariantOverlayRenderer} no longer flags
 * the now-empty position when the player hovers it.
 *
 * <p>Symmetric counterpart to {@link VariantBlockInteractions#onRightClickBlock} —
 * the add path appends to the candidate list at a flagged position; this break
 * path clears the entire candidate list at that position. Resolution uses the
 * same {@link BlockVariantPlot#resolveAt} cascade so all four plot types
 * (carriage / contents / part / track-side) share one handler.</p>
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VariantBlockBreakHandler {

    private VariantBlockBreakHandler() {}

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) return;

        BlockPos worldPos = event.getPos();
        BlockPos local = worldPos.subtract(plot.origin());
        if (!plot.inBounds(local)) return;

        List<VariantState> existing = plot.statesAt(local);
        if (existing == null) return;

        int removedCount = existing.size();
        plot.remove(local);
        try {
            plot.save();
        } catch (IOException e) {
            player.displayClientMessage(
                Component.literal("Variant save failed: " + e.getMessage())
                    .withStyle(ChatFormatting.YELLOW), true);
            return;
        }

        DungeonTrainNet.sendTo(player, VariantHoverPacket.empty());
        final int lx = local.getX();
        final int ly = local.getY();
        final int lz = local.getZ();
        player.displayClientMessage(
            Component.literal("- removed " + removedCount + " variants @ " + lx + "," + ly + "," + lz)
                .withStyle(ChatFormatting.GOLD), true);
    }
}
