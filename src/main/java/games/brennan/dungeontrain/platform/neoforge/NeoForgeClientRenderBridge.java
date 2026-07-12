package games.brennan.dungeontrain.platform.neoforge;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtClientChatCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtFogColor;
import games.brennan.dungeontrain.platform.event.DtFogColorCallback;
import games.brennan.dungeontrain.platform.event.DtMusicSelection;
import games.brennan.dungeontrain.platform.event.DtRenderHandCallback;
import games.brennan.dungeontrain.platform.event.DtSelectMusicCallback;
import net.minecraft.sounds.Music;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for client render / audio / chat events
 * (fog colour, music selection, hand render, client chat). Client-only so it never
 * loads on a dedicated server. Mutations are carried through live carriers backed by
 * the event; {@code RenderHandEvent} is cancellable (stop-on-first-true) — pure
 * passthrough, no logic.
 */
@EventBusSubscriber(modid = DtCore.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientRenderBridge {

    private NeoForgeClientRenderBridge() {}

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        DtFogColor color = new DtFogColor() {
            @Override public float getRed() { return event.getRed(); }
            @Override public float getGreen() { return event.getGreen(); }
            @Override public float getBlue() { return event.getBlue(); }
            @Override public void setRed(float red) { event.setRed(red); }
            @Override public void setGreen(float green) { event.setGreen(green); }
            @Override public void setBlue(float blue) { event.setBlue(blue); }
        };
        for (DtFogColorCallback cb : DtEvents.FOG_COLOR.listeners()) {
            cb.onComputeFogColor(event.getCamera(), color);
        }
    }

    @SubscribeEvent
    public static void onSelectMusic(SelectMusicEvent event) {
        DtMusicSelection sel = new DtMusicSelection() {
            @Override public Music getOriginalMusic() { return event.getOriginalMusic(); }
            @Override public void setMusic(Music music) { event.setMusic(music); }
        };
        for (DtSelectMusicCallback cb : DtEvents.SELECT_MUSIC.listeners()) {
            cb.onSelectMusic(sel);
        }
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        for (DtRenderHandCallback cb : DtEvents.RENDER_HAND.listeners()) {
            if (cb.onRenderHand()) {
                event.setCanceled(true);
                return; // first cancel wins — matches NeoForge cancellable-event short-circuit
            }
        }
    }

    @SubscribeEvent
    public static void onClientChat(net.neoforged.neoforge.client.event.ClientChatEvent event) {
        for (DtClientChatCallback cb : DtEvents.CLIENT_CHAT.listeners()) {
            cb.onClientChat();
        }
    }
}
