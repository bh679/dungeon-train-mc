package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtScreenClosingCallback;
import games.brennan.dungeontrain.platform.event.DtScreenInit;
import games.brennan.dungeontrain.platform.event.DtScreenInitCallback;
import games.brennan.dungeontrain.platform.event.DtScreenOpening;
import games.brennan.dungeontrain.platform.event.DtScreenOpeningCallback;
import games.brennan.dungeontrain.platform.event.DtScreenRenderPostCallback;
import games.brennan.dungeontrain.platform.event.DtScreenRenderPreCallback;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for the {@code ScreenEvent} sub-events
 * (Opening, Init.Post, Render.Pre, Render.Post, Closing). Client-only so it never
 * loads on a dedicated server. Opening carries cancel + screen-replacement through a
 * live carrier with a skip-if-canceled loop; the rest are direct passthroughs.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientScreenBridge {

    private NeoForgeClientScreenBridge() {}

    @SubscribeEvent
    public static void onOpening(ScreenEvent.Opening event) {
        DtScreenOpening carrier = new DtScreenOpening() {
            @Override public Screen getScreen() { return event.getScreen(); }
            @Override public Screen getCurrentScreen() { return event.getCurrentScreen(); }
            @Override public Screen getNewScreen() { return event.getNewScreen(); }
            @Override public void setNewScreen(Screen newScreen) { event.setNewScreen(newScreen); }
            @Override public void setCanceled(boolean canceled) { event.setCanceled(canceled); }
        };
        for (DtScreenOpeningCallback cb : DtEvents.SCREEN_OPENING.listeners()) {
            // Replicate NeoForge's dispatch: once canceled, non-receiveCanceled listeners skip.
            if (event.isCanceled()) {
                return;
            }
            cb.onScreenOpening(carrier);
        }
    }

    @SubscribeEvent
    public static void onInitPost(ScreenEvent.Init.Post event) {
        DtScreenInit carrier = new DtScreenInit() {
            @Override public Screen getScreen() { return event.getScreen(); }
            @Override public java.util.List<GuiEventListener> getListenersList() { return event.getListenersList(); }
            @Override public <T extends GuiEventListener & Renderable & NarratableEntry> void addListener(T listener) {
                event.addListener(listener);
            }
        };
        for (DtScreenInitCallback cb : DtEvents.SCREEN_INIT_POST.listeners()) {
            cb.onScreenInit(carrier);
        }
    }

    @SubscribeEvent
    public static void onRenderPre(ScreenEvent.Render.Pre event) {
        for (DtScreenRenderPreCallback cb : DtEvents.SCREEN_RENDER_PRE.listeners()) {
            cb.onScreenRenderPre(event.getScreen());
        }
    }

    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        for (DtScreenRenderPostCallback cb : DtEvents.SCREEN_RENDER_POST.listeners()) {
            cb.onScreenRenderPost(event.getScreen(), event.getGuiGraphics());
        }
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) {
        for (DtScreenClosingCallback cb : DtEvents.SCREEN_CLOSING.listeners()) {
            cb.onScreenClosing(event.getScreen());
        }
    }
}
