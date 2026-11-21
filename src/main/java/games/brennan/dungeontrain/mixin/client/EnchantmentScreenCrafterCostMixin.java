package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterEnchantMenu;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crafter Enchant Cost on the enchanting screen: a discounted row shows its pre-discount level
 * struck through before the price actually paid ("<s>30</s> 28"). The originals arrive through the
 * menu's extra data slots ({@code EnchantmentMenuCrafterMixin}); a row with none renders as vanilla.
 *
 * <p>{@code renderBg} measures each enabled row's label once ({@code Font.width(String)}, first call
 * site) to size the enchantment-name text beside it, then draws it ({@code drawString}). The first
 * call site visits enabled rows in order, so a per-frame cursor names the row; the measurement is
 * widened to the full label so the name wraps clear of it, and the draw is swapped for the label,
 * right-aligned where vanilla's number ended.</p>
 */
@Mixin(EnchantmentScreen.class)
public abstract class EnchantmentScreenCrafterCostMixin {

    @Unique
    private int dungeontrain$row = -1;

    @Inject(method = "renderBg", at = @At("HEAD"))
    private void dungeontrain$resetRow(GuiGraphics graphics, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
        dungeontrain$row = -1;
    }

    @WrapOperation(method = "renderBg",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;width(Ljava/lang/String;)I", ordinal = 0))
    private int dungeontrain$measureLabel(Font font, String cost, Operation<Integer> original) {
        dungeontrain$row = dungeontrain$nextEnabledRow(dungeontrain$row);
        Component label = dungeontrain$label(cost, dungeontrain$row, 0);
        return label == null ? original.call(font, cost) : font.width(label);
    }

    @WrapOperation(method = "renderBg",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"))
    private int dungeontrain$drawLabel(GuiGraphics graphics, Font font, String cost, int x, int y, int color,
                                       Operation<Integer> original) {
        Component label = dungeontrain$label(cost, dungeontrain$row, color);
        if (label == null) return original.call(graphics, font, cost, x, y, color);
        int right = x + font.width(cost);
        return graphics.drawString(font, label, right - font.width(label), y, color);
    }

    /** The row after {@code after} that vanilla draws a label for (cost above 0), or -1. */
    @Unique
    private int dungeontrain$nextEnabledRow(int after) {
        EnchantmentMenu menu = dungeontrain$menu();
        if (menu == null) return -1;
        int[] costs = menu.costs;
        for (int row = after + 1; row < costs.length; row++) {
            if (costs[row] > 0) return row;
        }
        return -1;
    }

    /**
     * "<s>original</s> cost" for a discounted row, else null (vanilla label). The struck number is
     * drawn at half the row colour's brightness so it reads as the old price.
     */
    @Unique
    private Component dungeontrain$label(String cost, int row, int color) {
        if (row < 0 || !(dungeontrain$menu() instanceof CrafterEnchantMenu crafter)) return null;
        int[] originals = crafter.dungeontrain$rollPower();
        if (row >= originals.length) return null;
        int before = originals[row];
        if (before <= dungeontrain$menu().costs[row]) return null;
        MutableComponent struck = Component.literal(Integer.toString(before)).withStyle(style -> style.withStrikethrough(true).withColor((color & 0xFEFEFE) >> 1));
        return Component.empty().append(struck).append(" " + cost);
    }

    @Unique
    private EnchantmentMenu dungeontrain$menu() {
        return ((AbstractContainerScreen<?>) (Object) this).getMenu() instanceof EnchantmentMenu menu ? menu : null;
    }
}
