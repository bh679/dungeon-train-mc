package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Keeps a Discord or Twitch link code off the Live Eye stream. {@code /discord} and {@code /twitch}
 * print the code into chat; a viewer who reads it off the stream could spend it first and link their
 * own account to the streamer's. So while a code line can still be on the HUD, the stream treats the
 * chat switch as off ({@link LiveStreamController#streamChatShown}) and the chat layer never reaches a
 * frame — the streamer still sees the code, viewers don't.
 *
 * <p>Timed on the gui tick clock the HUD chat itself fades by: a line shows for 200 ticks after it is
 * added, so the guard holds a little longer. It does not ask whether the Live Eye is on, so putting
 * it on after running the command is covered too. The chat box, where the line can be scrolled back
 * to, is never broadcast ({@link StreamableScreens}); nor is the Credits page's link screen.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LinkCodeGuard {

    /** Vanilla's 200-tick HUD chat lifetime plus two seconds' margin. */
    static final long HUD_TICKS = 240;
    private static final long NEVER = Long.MIN_VALUE;

    private static volatile long shownAt = NEVER;

    private LinkCodeGuard() {}

    /** A link code has just been printed to chat. Render thread. */
    public static void markShown() {
        shownAt = Minecraft.getInstance().gui.getGuiTicks();
    }

    /** A link code may still be on the HUD chat. */
    public static boolean onHud() {
        return onHud(Minecraft.getInstance().gui.getGuiTicks(), shownAt);
    }

    static boolean onHud(long now, long shownAt) {
        if (shownAt == NEVER) return false;
        long age = now - shownAt;
        return age >= 0 && age < HUD_TICKS;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        shownAt = NEVER;
    }
}
