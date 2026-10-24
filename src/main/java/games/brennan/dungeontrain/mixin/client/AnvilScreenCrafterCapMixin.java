package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterAnvilMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.world.entity.player.Abilities;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Crafter Enchant Cost on the anvil screen: vanilla labels any price of 40+ "Too Expensive!" unless
 * the player is in creative. For an anvil the server marked uncapped ({@code AnvilMenuCrafterMixin},
 * synced through a data slot) that creative check reads true, so the price shows instead, red when
 * the player lacks the levels as for any other unaffordable price.
 *
 * <p>When Enchant Cost lowered the price, the label reads "Enchantment Cost: <s>51</s> 49": the
 * pre-discount price (second synced data slot) struck through at half the label's brightness, then
 * the price paid. The label colour is vanilla's: green, or red when the result can't be taken.</p>
 */
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenCrafterCapMixin {

    /** {@code AnvilScreen#renderLabels}'s price colours. */
    @Unique private static final int VANILLA_GREEN = 8453920;
    @Unique private static final int VANILLA_RED = 16736352;

    @Redirect(method = "renderLabels",
              at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;instabuild:Z",
                       opcode = Opcodes.GETFIELD, ordinal = 0),
              require = 1)
    private boolean dungeontrain$showPrice(Abilities abilities) {
        if (abilities.instabuild) return true;
        return ((AbstractContainerScreen<?>) (Object) this).getMenu() instanceof CrafterAnvilMenu menu
            && menu.dungeontrain$uncapped();
    }

    @WrapOperation(method = "renderLabels",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"),
        require = 1)
    private MutableComponent dungeontrain$struckPrice(String key, Object[] args, Operation<MutableComponent> original) {
        AbstractContainerMenu menu = ((AbstractContainerScreen<?>) (Object) this).getMenu();
        if (!(menu instanceof CrafterAnvilMenu crafter) || args.length != 1) return original.call(key, args);
        int before = crafter.dungeontrain$originalCost();
        if (!(args[0] instanceof Integer cost) || before <= cost) return original.call(key, args);
        boolean affordable = Minecraft.getInstance().player != null
            && menu.getSlot(2).mayPickup(Minecraft.getInstance().player);
        int color = affordable ? VANILLA_GREEN : VANILLA_RED;
        MutableComponent struck = Component.literal(Integer.toString(before))
            .withStyle(style -> style.withStrikethrough(true).withColor((color & 0xFEFEFE) >> 1));
        return original.call(key, new Object[] {Component.empty().append(struck).append(" " + cost)});
    }
}
