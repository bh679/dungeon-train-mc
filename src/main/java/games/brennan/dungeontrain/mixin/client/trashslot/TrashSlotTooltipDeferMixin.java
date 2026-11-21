package games.brennan.dungeontrain.mixin.client.trashslot;

import games.brennan.dungeontrain.client.DeferredTrashTooltip;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * TrashSlot draws its bin's tooltip ("Trash Item", "Destroy Item", or the parked item's own tooltip) in the
 * container <em>background</em> pass, before vanilla draws any slot. Vanilla fills a durability bar with
 * {@code RenderType.guiOverlay()}, which skips the depth test, so the bar of any slot under the tooltip
 * painted straight over its text. These redirects hand the tooltip to {@link DeferredTrashTooltip}, which
 * draws it after the whole screen.
 *
 * <p>{@code require = 0}: a TrashSlot build that moves these calls keeps drawing its tooltip as shipped.
 * Gated on TrashSlot being installed by {@code TrashSlotMixinPlugin}.</p>
 */
@Pseudo
@Mixin(targets = "net.blay09.mods.trashslot.client.TrashSlotGuiHandler", remap = false)
public abstract class TrashSlotTooltipDeferMixin {

    private static final String ITEM_TOOLTIP =
            "Lnet/minecraft/client/gui/GuiGraphics;renderTooltip(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V";
    private static final String TEXT_TOOLTIP =
            "Lnet/minecraft/client/gui/GuiGraphics;renderTooltip(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;II)V";

    @Redirect(method = {"onBackgroundDrawn", "onScreenDrawn"},
            at = @At(value = "INVOKE", target = ITEM_TOOLTIP), require = 0, remap = false)
    private static void dungeontrain$deferItemTooltip(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        DeferredTrashTooltip.deferItem(stack, x, y);
    }

    @Redirect(method = "onBackgroundDrawn",
            at = @At(value = "INVOKE", target = TEXT_TOOLTIP), require = 0, remap = false)
    private static void dungeontrain$deferTextTooltip(GuiGraphics graphics, Font font, Component text, int x, int y) {
        DeferredTrashTooltip.deferText(text, x, y);
    }
}
