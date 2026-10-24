package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.perks.effects.crafter.CrafterAnvilMenu;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.world.entity.player.Abilities;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Crafter Enchant Cost on the anvil screen: vanilla labels any price of 40+ "Too Expensive!" unless
 * the player is in creative. For an anvil the server marked uncapped ({@code AnvilMenuCrafterMixin},
 * synced through a data slot) that creative check reads true, so the price shows instead, red when
 * the player lacks the levels as for any other unaffordable price.
 */
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenCrafterCapMixin {

    @Redirect(method = "renderLabels",
              at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;instabuild:Z",
                       opcode = Opcodes.GETFIELD, ordinal = 0),
              require = 1)
    private boolean dungeontrain$showPrice(Abilities abilities) {
        if (abilities.instabuild) return true;
        return ((AbstractContainerScreen<?>) (Object) this).getMenu() instanceof CrafterAnvilMenu menu
            && menu.dungeontrain$uncapped();
    }
}
