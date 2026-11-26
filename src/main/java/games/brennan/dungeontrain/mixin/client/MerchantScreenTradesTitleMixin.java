package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.client.RestockVillagerButton;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Leaves out the villager trade menu's "Trades" heading while both Trader buttons (Restock and Reroll)
 * are showing: they take its place ({@link RestockVillagerButton}). With one button or none, vanilla
 * draws it as usual.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenTradesTitleMixin {

    @WrapOperation(method = "renderLabels",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"))
    private int dungeontrain$hideTradesTitle(GuiGraphics graphics, Font font, Component text, int x, int y, int colour,
                                             boolean shadow, Operation<Integer> original) {
        if (RestockVillagerButton.hidesTradesTitle() && isTradesTitle(text)) return x;
        return original.call(graphics, font, text, x, y, colour, shadow);
    }

    @Unique
    private static boolean isTradesTitle(Component text) {
        return text.getContents() instanceof TranslatableContents t && "merchant.trades".equals(t.getKey());
    }
}
