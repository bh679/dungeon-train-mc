package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.platform.Window;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientOptionsTab;
import games.brennan.dungeontrain.client.DungeonTrainClientOptionsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

/**
 * A cog button under the streamer's live preview box, shown in the inventory only,
 * that opens Options / Dungeon Train / Stream.
 *
 * <p>Like the box, it is drawn in the streamer's post-frame pass ({@link LiveHeadViewer#onFrameEnd}),
 * after the frame grab, so it never reaches the broadcast. That pass is outside the screen's widget
 * list, so the click is caught in {@link LiveHeadViewer#onMousePressed} rather than by a widget.</p>
 */
final class LiveStreamSettingsButton {

    /** A square vanilla-height button. */
    static final int SIZE = 20;
    static final int ICON = 16;
    /** Space between the preview's bottom border and the button. */
    static final int GAP = 2;

    /** Hover tooltip; the button itself is just the cog. */
    private static final Component LABEL = Component.translatable("gui.dungeontrain.live.stream_settings");
    private static final ResourceLocation SPRITE = ResourceLocation.withDefaultNamespace("widget/button");
    private static final ResourceLocation SPRITE_HOVER = ResourceLocation.withDefaultNamespace("widget/button_highlighted");
    private static final ResourceLocation COG = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/stream_settings");

    private LiveStreamSettingsButton() {}

    /** Under the preview's bottom border, flush with its right edge. */
    static LiveHeadViewer.Box layout(LiveHeadViewer.Box preview) {
        int right = preview.x() + preview.w();
        int top = preview.y() + preview.h() + LiveHeadViewer.BORDER + GAP;
        return new LiveHeadViewer.Box(right - SIZE, top, SIZE, SIZE);
    }

    /** The E menu only — survival or creative inventory. Not chat, not other containers. */
    static boolean showsOn(@Nullable Screen screen) {
        return screen != null && showsOn(screen.getClass());
    }

    static boolean showsOn(Class<?> screen) {
        return InventoryScreen.class.isAssignableFrom(screen)
            || CreativeModeInventoryScreen.class.isAssignableFrom(screen);
    }

    static boolean contains(LiveHeadViewer.Box b, double x, double y) {
        return x >= b.x() && x < b.x() + b.w() && y >= b.y() && y < b.y() + b.h();
    }

    /** Called from the streamer pass, after the box is drawn. */
    static void draw(GuiGraphics g, LiveHeadViewer.Box preview) {
        Minecraft mc = Minecraft.getInstance();
        if (!showsOn(mc.screen)) return;
        LiveHeadViewer.Box b = layout(preview);
        Window window = mc.getWindow();
        double mx = mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
        double my = mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
        boolean hover = contains(b, mx, my);
        g.blitSprite(hover ? SPRITE_HOVER : SPRITE, b.x(), b.y(), b.w(), b.h());
        int inset = (SIZE - ICON) / 2;
        g.blitSprite(COG, b.x() + inset, b.y() + inset, ICON, ICON);
        if (hover) g.renderTooltip(mc.font, LABEL, (int) mx, (int) my);
    }

    /** True (and the settings page opened) if the click landed on the button. */
    static boolean click(Screen screen, LiveHeadViewer.Box preview, double x, double y) {
        if (!showsOn(screen) || !contains(layout(preview), x, y)) return false;
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
        ClientOptionsTab.select(ClientOptionsTab.STREAM);
        mc.setScreen(new DungeonTrainClientOptionsScreen(screen));
        return true;
    }
}
