package games.brennan.dungeontrain.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.vault.VaultServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Whether a vault has already rewarded a player (package-private), for Chest Sense's vault check. */
@Mixin(VaultServerData.class)
public interface VaultServerDataInvoker {

    @Invoker("hasRewardedPlayer")
    boolean dungeontrain$hasRewardedPlayer(Player player);
}
