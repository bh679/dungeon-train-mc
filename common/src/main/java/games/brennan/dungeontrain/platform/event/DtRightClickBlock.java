package games.brennan.dungeontrain.platform.event;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Mutable, loader-neutral carrier for {@link DtRightClickBlockCallback}, mirroring
 * NeoForge's {@code PlayerInteractEvent.RightClickBlock}. CANCELLABLE with a result:
 * a handler that consumes the interaction calls {@link #setCanceled(boolean)} +
 * {@link #setCancellationResult(InteractionResult)} exactly as it did on the bus
 * (former {@code event.setCanceled(true); event.setCancellationResult(...)}).
 *
 * <p>The bridge ({@code NeoForgeRightClickBlockBridge}) backs every getter/setter
 * with the live NeoForge event, so reads see current state and cancellation takes
 * effect immediately — the bridge also skips any remaining handler once the event
 * is canceled, matching NeoForge's non-{@code receiveCanceled} dispatch. Every type
 * here is vanilla Minecraft, so a Fabric bridge can populate the same shape.</p>
 */
public interface DtRightClickBlock {

    /** The interacting player (former {@code event.getEntity()}). */
    Player player();

    /** The level the interaction happened in (former {@code event.getLevel()}). */
    Level level();

    /** The clicked block position (former {@code event.getPos()}). */
    BlockPos pos();

    /** The hand used (former {@code event.getHand()}). */
    InteractionHand hand();

    /** The held stack (former {@code event.getItemStack()}). */
    ItemStack itemStack();

    /** The clicked block face (former {@code event.getFace()}). */
    Direction face();

    /** The precise hit result (former {@code event.getHitVec()}). */
    BlockHitResult hitResult();

    /** Whether the interaction is already canceled (former {@code event.isCanceled()}). */
    boolean isCanceled();

    /** Cancel / un-cancel the interaction (former {@code event.setCanceled(...)}). */
    void setCanceled(boolean canceled);

    /** Set the interaction result returned to vanilla (former {@code event.setCancellationResult(...)}). */
    void setCancellationResult(InteractionResult result);

    /** Deny the block's use side of the interaction (former {@code event.setUseBlock(TriState.FALSE)}). */
    void denyUseBlock();

    /** Deny the item's use side of the interaction (former {@code event.setUseItem(TriState.FALSE)}). */
    void denyUseItem();
}
