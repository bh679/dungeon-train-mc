package games.brennan.dungeontrain.compat;

import com.blackgear.vanillabackport.common.level.blocks.CreakingHeartBlock;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Which right-clicks open a creaking heart's settings menu: a Creative player, not sneaking, on a
 * heart. Both sides cancel the click so nothing is placed against the heart; the client also opens
 * the menu ({@code client/CreakingHeartMenuOpener}). Sneak + right-click still builds as usual.
 */
public final class CreakingHeartMenuGate {

    private CreakingHeartMenuGate() {}

    /** Cancels {@code event} and returns true when it is a settings-menu click. */
    public static boolean claim(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.isCreative() || player.isShiftKeyDown()) return false;
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof CreakingHeartBlock)) return false;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        return true;
    }
}
