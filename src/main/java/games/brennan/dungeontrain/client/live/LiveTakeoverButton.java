package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

/**
 * "Take over" under a viewer's TV box while a dead streamer's stream is on offer
 * ({@link LiveTakeoverOffer}). Drawn with the box in the HUD layer, so it shows over play; the HUD
 * takes no clicks, so it is clicked from chat or the inventory ({@link LiveHeadViewer#onMousePressed}),
 * and its tooltip says so. The chat line's button does the same.
 */
final class LiveTakeoverButton {

    static final int H = 20;
    static final int PAD_X = 8;
    /** Space between the box's bottom border and the button. */
    static final int GAP = 3;

    private static final Component LABEL = Component.translatable("gui.dungeontrain.live.takeover");
    private static final Component TIP = Component.translatable("gui.dungeontrain.live.takeover.tip");
    private static final ResourceLocation SPRITE = ResourceLocation.withDefaultNamespace("widget/button");
    private static final ResourceLocation SPRITE_HOVER = ResourceLocation.withDefaultNamespace("widget/button_highlighted");
    private static final int TEXT = 0xFF55FF55;

    private LiveTakeoverButton() {}

    /** Under the box, flush with its right edge. */
    static LiveHeadViewer.Box layout(LiveHeadViewer.Box box, Font font) {
        int w = font.width(LABEL) + 2 * PAD_X;
        int top = box.y() + box.h() + LiveHeadViewer.BORDER + GAP;
        return new LiveHeadViewer.Box(box.x() + box.w() - w, top, w, H);
    }

    static void draw(GuiGraphics g, LiveHeadViewer.Box box) {
        if (LiveTakeoverOffer.current() == null) return;
        Minecraft mc = Minecraft.getInstance();
        LiveHeadViewer.Box b = layout(box, mc.font);
        Window window = mc.getWindow();
        double mx = mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
        double my = mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
        boolean hover = mc.screen != null && LiveStreamSettingsButton.contains(b, mx, my);
        g.blitSprite(hover ? SPRITE_HOVER : SPRITE, b.x(), b.y(), b.w(), b.h());
        g.drawString(mc.font, LABEL, b.x() + PAD_X, b.y() + (b.h() - mc.font.lineHeight) / 2 + 1, TEXT, true);
        if (hover) g.renderTooltip(mc.font, TIP, (int) mx, (int) my);
    }

    /** True (and the offer taken) if the click landed on the button. */
    static boolean click(LiveHeadViewer.Box box, double x, double y) {
        if (LiveTakeoverOffer.current() == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (!LiveStreamSettingsButton.contains(layout(box, mc.font), x, y)) return false;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
        LiveTakeoverOffer.accept();
        return true;
    }
}
