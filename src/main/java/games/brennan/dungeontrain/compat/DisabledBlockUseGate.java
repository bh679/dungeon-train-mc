package games.brennan.dungeontrain.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Right-clicks on a {@link DisabledModContent#DISABLED_BLOCKS disabled block} do nothing outside Creative. Hiding
 * the item can't reach the copies the mods' own structures place — Respawner Statues in BetterNether cities,
 * Respawn Obelisks in BetterEnd villages, smelters in its houses — so the click itself is swallowed: no spawn
 * point, no ritual, no menu. Creative players keep the click so builders can still work with the blocks.
 */
public final class DisabledBlockUseGate {

    private DisabledBlockUseGate() {}

    /** Cancels {@code event} and returns true when it targets a disabled block. */
    public static boolean veto(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isCreative()) return false;
        Block block = event.getLevel().getBlockState(event.getPos()).getBlock();
        if (!DisabledModContent.isDisabledBlock(BuiltInRegistries.BLOCK.getKey(block))) return false;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        return true;
    }
}
