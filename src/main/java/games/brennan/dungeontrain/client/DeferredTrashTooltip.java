package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Draws the TrashSlot bin's tooltip after the whole screen, so slot overlays (durability bars) can't paint
 * over it. {@code mixin.client.trashslot.TrashSlotTooltipDeferMixin} captures the tooltip TrashSlot would
 * have drawn in the background pass; {@link ScreenEvent.Render.Post} draws it, with the pose untranslated,
 * so TrashSlot's raw mouse coordinates still line up. One tooltip per frame: the last capture wins.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeferredTrashTooltip {

    /** Exactly one of {@code stack} / {@code text} is set. */
    private record Pending(Screen screen, ItemStack stack, Component text, int x, int y) {}

    private static Pending pending;

    private DeferredTrashTooltip() {}

    public static void deferItem(ItemStack stack, int x, int y) {
        pending = new Pending(Minecraft.getInstance().screen, stack.copy(), null, x, y);
    }

    public static void deferText(Component text, int x, int y) {
        pending = new Pending(Minecraft.getInstance().screen, null, text, x, y);
    }

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        Pending tooltip = pending;
        pending = null;
        if (tooltip == null || tooltip.screen() != event.getScreen()) return;
        GuiGraphics graphics = event.getGuiGraphics();
        var font = Minecraft.getInstance().font;
        if (tooltip.stack() != null) {
            graphics.renderTooltip(font, tooltip.stack(), tooltip.x(), tooltip.y());
        } else {
            graphics.renderTooltip(font, tooltip.text(), tooltip.x(), tooltip.y());
        }
    }
}
