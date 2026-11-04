package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvBlocks;
import games.brennan.dungeontrain.portal.home.HomeRoomVisitSessions;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * What a home room visit does not let its owner do: take the TV down. The TV is the room's anchor —
 * where they stand on arrival and what plays their story — and a room without one cannot be saved
 * ({@code HomeRoomVisitService#captureAndSave}), so the block is simply not breakable while visiting.
 *
 * <p>The look-only rule for a home room standing on the live train (nobody breaks or takes anything,
 * owner included) is the next piece, and lives here when it comes.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class HomeRoomGuard {

    private HomeRoomGuard() {}

    /** Whether breaking {@code pos}, a TV block or not, is refused inside a visit. Pure. */
    static boolean deniesBreak(boolean inVisit, boolean isTv) {
        return inVisit && isTv;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (HomeRoomVisitSessions.isEmpty() || !(event.getPlayer() instanceof ServerPlayer player)) return;
        BlockPos pos = event.getPos();
        boolean inVisit = HomeRoomVisitSessions.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (!deniesBreak(inVisit, TvBlocks.isTelevision(event.getState()))) return;
        event.setCanceled(true);
        player.displayClientMessage(Component.translatable("chat.dungeontrain.home_room.tv_stays").withStyle(ChatFormatting.GRAY), true);
    }
}
