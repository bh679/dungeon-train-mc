package games.brennan.dungeontrain.client.chat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Docks a read-only Discord chat panel into the right gutter of the title screen. The panel loads the
 * player's per-player Discord thread from the relay (keyed by the launcher's Minecraft UUID — there's
 * no Minecraft server at the menu) and marks messages 👀 as the player sees them.
 *
 * <p>Phase 1 is read + seen only; sending and the offline inbox are later features. Shown only when the
 * DiscordPresence network-access consent is granted ({@link RelayChatClient#canConnect()}) — a player
 * who declined networking is prompted through the existing Discord welcome flow, not here. Like
 * {@link games.brennan.dungeontrain.client.TitleScreenLayoutHandler}, it bails quietly when there isn't
 * room (a small window, or another mod rewrote the menu) rather than overlapping the centered buttons.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class MainMenuChatPanel {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PANEL_WIDTH = 140;
    private static final int MARGIN = 6;
    private static final int TOP = 30;
    private static final int BOTTOM_MARGIN = 30;
    private static final int MIN_HEIGHT = 80;
    private static final int CENTER_BUTTON_HALF = 100; // vanilla title buttons are 200px wide, centered
    private static final int CLEARANCE = 8;

    private MainMenuChatPanel() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        if (!RelayChatClient.canConnect()) {
            return; // no network consent — the existing Discord welcome flow handles opt-in
        }

        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null) {
            LOGGER.debug("Menu chat: no local profile id at title screen; skipping panel.");
            return;
        }

        int screenW = event.getScreen().width;
        int screenH = event.getScreen().height;
        int x = screenW - PANEL_WIDTH - MARGIN;
        int height = screenH - TOP - BOTTOM_MARGIN;

        // Don't overlap the centered button column or render a uselessly short panel.
        if (x < screenW / 2 + CENTER_BUTTON_HALF + CLEARANCE || height < MIN_HEIGHT) {
            LOGGER.debug("Menu chat: not enough room for the chat panel (w={}, h={}); skipping.", screenW, screenH);
            return;
        }

        ChatMessageList list = new ChatMessageList(x, TOP, PANEL_WIDTH, height);
        AtomicReference<String> threadId = new AtomicReference<>();
        list.setOnSeen(m -> {
            String tid = threadId.get();
            if (tid != null) {
                RelayChatClient.markSeen(uuid, tid, m.id());
            }
        });
        event.addListener(list);

        RelayChatClient.fetchHistory(uuid).thenAcceptAsync(history -> {
            if (history == null) {
                list.setStatus(Component.translatable("gui.dungeontrain.menu_chat.offline"));
                return;
            }
            threadId.set(history.threadId());
            list.setHistory(history);
        }, mc);
    }
}
