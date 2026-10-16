package games.brennan.dungeontrain.client.twitch;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatEvent;

import java.util.Optional;

/**
 * "Drop your Twitch username or URL in chat for a free shout-out": after that hint is said at stream
 * start ({@code LiveStreamController}), this watches what the player types for {@link #WINDOW_MS}.
 * A Twitch URL or @-handle anywhere in a line, or a lone bare name, gets a one-click confirmation —
 * "Save twitch.tv/x for your shout-out? [Yes] [No]" — never an automatic save, because a bare word
 * ("lol") can be a channel too. Yes runs {@code /twitch save <login>} ({@link TwitchLinkCommand}). The
 * chat line itself still goes out: they meant to share it.
 *
 * <p>Client only, like {@code LiveChatBridge}. The window closes on save, on {@link #close()} (the
 * stream ending), or by itself after ten minutes; outside it nothing is read.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TwitchHandleCapture {

    static final long WINDOW_MS = 10 * 60 * 1000;
    private static volatile long openUntilMs = 0;
    private static volatile String lastOffered = null;

    private TwitchHandleCapture() {}

    /** The hint was just said: read chat for a while. */
    public static void open() {
        openUntilMs = System.currentTimeMillis() + WINDOW_MS;
        lastOffered = null;
    }

    /** The stream ended, or a handle was saved. */
    public static void close() {
        openUntilMs = 0;
    }

    public static boolean isOpen() {
        return System.currentTimeMillis() < openUntilMs;
    }

    @SubscribeEvent
    public static void onClientChat(ClientChatEvent event) {
        String message = event.getMessage();
        if (message == null || message.startsWith("/")) return;
        boolean open = isOpen();
        // A URL or @handle is unmistakable at any time while streaming; a bare name only inside the window.
        Optional<String> found = TwitchHandle.find(message, open);
        if (found.isEmpty() || !open && !looksExplicit(message)) return;
        String login = found.get();
        if (login.equals(lastOffered)) return;
        lastOffered = login;
        Minecraft.getInstance().execute(() -> offer(login));
    }

    private static boolean looksExplicit(String message) {
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("twitch.tv/") || m.contains("@");
    }

    /** "Save twitch.tv/x for your shout-out? [Yes] [No]" */
    static Component offerLine(String login) {
        MutableComponent yes = Component.literal("[").append(Component.translatable("chat.dungeontrain.twitch.save_yes")).append("]")
                .withStyle(s -> s.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/twitch save " + login))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/twitch save " + login))));
        MutableComponent no = Component.literal("[").append(Component.translatable("chat.dungeontrain.twitch.save_no")).append("]")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/twitch hint off")));
        return Component.translatable("chat.dungeontrain.twitch.save_offer", Component.literal("twitch.tv/" + login).withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.WHITE).append(" ").append(yes).append(" ").append(no);
    }

    private static void offer(String login) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(offerLine(login), false);
    }
}
