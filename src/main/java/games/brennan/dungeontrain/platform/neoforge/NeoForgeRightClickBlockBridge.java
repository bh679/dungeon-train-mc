package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DtCore;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtPriority;
import games.brennan.dungeontrain.platform.event.DtRightClickBlock;
import games.brennan.dungeontrain.platform.event.DtRightClickBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * NeoForge → {@code DtEvents.RIGHT_CLICK_BLOCK} bridge for the cancellable
 * {@code PlayerInteractEvent.RightClickBlock} sub-event. Auto-registered via
 * {@link EventBusSubscriber}. Registers one {@code @SubscribeEvent} per DT priority
 * tier (HIGHEST / HIGH / NORMAL) so NeoForge's own priority + non-{@code receiveCanceled}
 * dispatch skips lower tiers once a higher tier cancels — exactly the behavior the
 * former per-handler {@code @SubscribeEvent}s had. Within a tier the loop re-checks
 * {@code isCanceled()} before each handler so a same-tier consume also suppresses the rest.
 *
 * <p>Pure adapter: the {@link DtRightClickBlock} carrier reads and writes straight
 * through to the live event, so getters see current state and cancellation +
 * cancellation-result take effect immediately.</p>
 */
@EventBusSubscriber(modid = DtCore.MOD_ID)
public final class NeoForgeRightClickBlockBridge {

    private NeoForgeRightClickBlockBridge() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onHighest(PlayerInteractEvent.RightClickBlock event) {
        dispatch(event, DtPriority.HIGHEST);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onHigh(PlayerInteractEvent.RightClickBlock event) {
        dispatch(event, DtPriority.HIGH);
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onNormal(PlayerInteractEvent.RightClickBlock event) {
        dispatch(event, DtPriority.NORMAL);
    }

    private static void dispatch(PlayerInteractEvent.RightClickBlock event, DtPriority tier) {
        DtRightClickBlock ctx = new DtRightClickBlock() {
            @Override public Player player() { return event.getEntity(); }
            @Override public Level level() { return event.getLevel(); }
            @Override public BlockPos pos() { return event.getPos(); }
            @Override public InteractionHand hand() { return event.getHand(); }
            @Override public ItemStack itemStack() { return event.getItemStack(); }
            @Override public Direction face() { return event.getFace(); }
            @Override public BlockHitResult hitResult() { return event.getHitVec(); }
            @Override public boolean isCanceled() { return event.isCanceled(); }
            @Override public void setCanceled(boolean canceled) { event.setCanceled(canceled); }
            @Override public void setCancellationResult(InteractionResult result) {
                event.setCancellationResult(result);
            }
            @Override public void denyUseBlock() { event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE); }
            @Override public void denyUseItem() { event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE); }
        };
        for (DtRightClickBlockCallback cb : DtEvents.RIGHT_CLICK_BLOCK.listeners(tier)) {
            if (event.isCanceled()) {
                return; // non-receiveCanceled: a same-tier consume suppresses the rest
            }
            cb.onRightClickBlock(ctx);
        }
    }
}
