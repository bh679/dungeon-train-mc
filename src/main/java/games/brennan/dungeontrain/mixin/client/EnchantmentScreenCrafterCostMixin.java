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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crafter Enchant Cost and Re-enchant on the enchanting screen: a row whose price the perks moved
 * shows the fresh vanilla level struck through before the price actually needed ("<s>30</s> 28" for
 * Enchant Cost, "<s>8</s> 16" for a re-enchant). The originals arrive through the menu's extra data
 * slots ({@code EnchantmentMenuCrafterMixin}); a row with none renders as vanilla. A Re-enchant row
 * with nothing left to add ({@link CrafterEnchantMenu#NOTHING_NEW}) is vanilla's disabled slot with
 * "Nothing new" written across it.
 *
 * <p>{@code renderBg} measures each enabled row's label once ({@code Font.width(String)}, first call
 * site) to size the enchantment-name text beside it, then draws it ({@code drawString}). The first
 * call site visits enabled rows in order, so a per-frame cursor names the row; the measurement is
 * widened to the full label so the name wraps clear of it, and the draw is swapped for the label,
 * right-aligned where vanilla's number ended.</p>
 */
@Mixin(EnchantmentScreen.class)
public abstract class EnchantmentScreenCrafterCostMixin extends AbstractContainerScreen<EnchantmentMenu> {

    /** Vanilla's row geometry and disabled-text colour (EnchantmentScreen#renderBg). */
    @Unique private static final int ROW_X = 60;
    @Unique private static final int ROW_Y = 14;
    @Unique private static final int ROW_W = 108;
    @Unique private static final int ROW_H = 19;
    @Unique private static final int DISABLED_TEXT = 0x685E4A;

    private EnchantmentScreenCrafterCostMixin(EnchantmentMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Unique
    private int dungeontrain$row = -1;

    @Inject(method = "renderBg", at = @At("HEAD"))
    private void dungeontrain$resetRow(GuiGraphics graphics, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
        dungeontrain$row = -1;
    }

    /** Re-enchant: "Nothing new" across each row whose rolls were all filtered out. */
    @Inject(method = "renderBg", at = @At("RETURN"))
    private void dungeontrain$drawNothingNew(GuiGraphics graphics, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
        if (!(this.menu instanceof CrafterEnchantMenu crafter)) return;
        int[] marks = crafter.dungeontrain$rollPower();
        Component text = null;
        for (int row = 0; row < marks.length && row < this.menu.costs.length; row++) {
            if (marks[row] != CrafterEnchantMenu.NOTHING_NEW || this.menu.costs[row] != 0) continue;
            if (text == null) text = Component.translatable("gui.dungeontrain.enchant.nothing_new");
            int x = this.leftPos + ROW_X + (ROW_W - this.font.width(text)) / 2;
            int y = this.topPos + ROW_Y + ROW_H * row + (ROW_H - this.font.lineHeight) / 2 + 1;
            graphics.drawString(this.font, text, x, y, DISABLED_TEXT, false);
        }
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
     * "<s>original</s> cost" for a row whose price a perk moved (down or up), else null (vanilla label). The struck number is
     * drawn at half the row colour's brightness so it reads as the old price.
     */
    @Unique
    private Component dungeontrain$label(String cost, int row, int color) {
        if (row < 0 || !(dungeontrain$menu() instanceof CrafterEnchantMenu crafter)) return null;
        int[] originals = crafter.dungeontrain$rollPower();
        if (row >= originals.length) return null;
        int before = originals[row];
        if (before <= 0 || before == dungeontrain$menu().costs[row]) return null;
        MutableComponent struck = Component.literal(Integer.toString(before)).withStyle(style -> style.withStrikethrough(true).withColor((color & 0xFEFEFE) >> 1));
        return Component.empty().append(struck).append(" " + cost);
    }

    @Unique
    private EnchantmentMenu dungeontrain$menu() {
        return this.menu;
    }
}
