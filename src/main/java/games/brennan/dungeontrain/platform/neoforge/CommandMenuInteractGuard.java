package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DtCore;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Root residue split out of {@code CommandMenuInputHandler}: the three
 * worldspace-menu interaction guards that remain bound to a loader-specific
 * NeoForge event and so could not move onto a {@code DtEvents} seam this stage.
 *
 * <ul>
 *   <li>{@code RightClickItem} — cancelled while the menu is open. Unlike the
 *       non-cancelling {@code DtEvents.RIGHT_CLICK_ITEM} seam (whose five other
 *       handlers are pure observers), this HIGHEST-priority handler consumes the
 *       event, so keeping it on the bus avoids widening that seam's contract.</li>
 *   <li>{@code LeftClickEmpty} / {@code RightClickEmpty} — HIGHEST no-op markers
 *       preserved verbatim (not cancellable; kept for parity with the original).</li>
 * </ul>
 *
 * <p>Client-only ({@code Dist.CLIENT}) exactly as the former
 * {@code @EventBusSubscriber(value = Dist.CLIENT)} on {@code CommandMenuInputHandler}
 * — priorities and bodies are unchanged.</p>
 */
@EventBusSubscriber(modid = DtCore.MOD_ID, value = Dist.CLIENT)
public final class CommandMenuInteractGuard {

    private CommandMenuInteractGuard() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        // Not cancellable in 1.20.1, but we still want to note the menu ate the input.
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        // Not cancellable; equivalent to empty hand right-click.
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (CommandMenuState.isOpen()) event.setCanceled(true);
    }
}
