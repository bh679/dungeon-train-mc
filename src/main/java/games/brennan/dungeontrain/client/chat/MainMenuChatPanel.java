package games.brennan.dungeontrain.client.chat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Docks a read-only Discord chat panel into the right gutter of the title screen. The panel loads the
 * player's per-player Discord thread from the relay (keyed by the launcher's Minecraft UUID — there's
 * no Minecraft server at the menu) and marks messages 👀 as the player sees them.
 *
 * <p>Reads + 👀-marks the thread, and on open drains the relay's offline inbox to badge how many
 * real-person replies arrived while the player was away (see {@link RelayChatClient#drainInbox}). Shown only when the
 * DiscordPresence network-access consent is granted ({@link RelayChatClient#canConnect()}) <b>and</b>
 * someone has actually written in the player's thread ({@link MenuChatFilter#hasDevHistory}) — a player
 * who declined networking is prompted through the existing Discord welcome flow, not here, and one who
 * was never messaged gets no empty chat dock. Like
 * {@link games.brennan.dungeontrain.client.TitleScreenLayoutHandler}, it bails quietly when there isn't
 * room (a small window, or another mod rewrote the menu) rather than overlapping the centered buttons.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class MainMenuChatPanel {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PANEL_WIDTH = 140;   // preferred width; narrows to fit on small windows
    private static final int MIN_PANEL_WIDTH = 64; // floor; the gutter is width/2-100, so this shows down
                                                   // to ~344 GUI-wide (covers fullscreen's higher GUI scale)
    private static final int MARGIN = 6;
    private static final int TOP = 30;
    private static final int BOTTOM_MARGIN = 30;
    private static final int MIN_HEIGHT = 80;
    private static final int CENTER_BUTTON_HALF = 100; // vanilla title buttons are 200px wide, centered
    private static final int CLEARANCE = 4;

    // Draining the inbox advances a server-side cursor, so it must run ONCE per title-screen visit — not
    // on every Init.Post (the screen re-inits on resize, firing it repeatedly on the same instance). We
    // remember which screen we've drained for and cache the count, so re-inits reuse it (a fresh widget
    // starts at 0) instead of re-draining to an empty result and losing the badge.
    private static WeakReference<Screen> drainedScreen;
    private static int cachedUnread;

    // The panel exists only once someone on Discord (the dev) has actually written in the player's
    // thread — a player who was never messaged shouldn't see an empty chat dock on the title screen.
    // The verdict comes from the filtered history (or a live/drained inbox arrival, which is always a
    // real person) and is cached for the session, so revisits render instantly instead of popping in
    // after each async fetch.
    private static boolean knownDevHistory;

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
        int height = screenH - TOP - BOTTOM_MARGIN;

        // Dock in the right gutter, narrowing the panel to whatever space clears the centered button
        // column. Skip only when even a minimal panel would overlap the buttons or be too short.
        int rightEdge = screenW - MARGIN;
        int leftLimit = screenW / 2 + CENTER_BUTTON_HALF + CLEARANCE;
        int available = rightEdge - leftLimit;
        if (available < MIN_PANEL_WIDTH || height < MIN_HEIGHT) {
            LOGGER.debug("Menu chat: not enough room for the chat panel (w={}, h={}, avail={}); skipping.",
                    screenW, screenH, available);
            return;
        }
        int panelWidth = Math.min(PANEL_WIDTH, available);
        int x = rightEdge - panelWidth;

        ChatMessageList list = new ChatMessageList(x, TOP, panelWidth, height);
        list.visible = knownDevHistory; // hidden until the dev has messaged (revealed by history/inbox below)
        AtomicReference<String> threadId = new AtomicReference<>();
        list.setOnSeen(m -> {
            String tid = threadId.get();
            if (tid != null) {
                RelayChatClient.markSeen(uuid, tid, m.id());
            }
        });
        // Submit → echo it locally now and hand it to the outbox, which delivers immediately when online
        // and keeps it queued (flushing on the next open/launch) when the relay is unreachable.
        list.setOnSubmit(text -> {
            String name = mc.getUser() != null ? mc.getUser().getName() : "Me";
            list.appendOutgoing(name, text);
            ChatOutbox.get().submit(uuid, text);
        });
        event.addListener(list);

        // Flush anything queued from a prior offline session as soon as the panel opens.
        ChatOutbox.get().flush();

        RelayChatClient.fetchHistory(uuid).thenAcceptAsync(history -> {
            if (history == null) {
                list.setStatus(Component.translatable("gui.dungeontrain.menu_chat.offline"));
                return; // couldn't reach the relay — visibility verdict unchanged (stays hidden or cached)
            }
            threadId.set(history.threadId());
            // Fresh verdict each load: the panel appears only once a real person has written in the
            // thread. (Never un-reveals mid-session — a live arrival may have revealed it already, and
            // Discord can lag behind the gateway-fed inbox by a moment.)
            if (MenuChatFilter.hasDevHistory(history.messages())) {
                knownDevHistory = true;
                list.visible = true;
            }
            list.setHistory(history);
        }, mc);

        // Drain the offline inbox once per title-screen visit — badge how many real-person replies arrived
        // while away. Draining marks them delivered (advances the cursor), so re-inits of this same screen
        // must NOT re-drain; they reuse the cached count instead.
        Screen screen = event.getScreen();
        if (drainedScreen == null || drainedScreen.get() != screen) {
            drainedScreen = new WeakReference<>(screen);
            cachedUnread = 0;
            RelayChatClient.drainInbox(uuid).thenAcceptAsync(inbox -> {
                if (inbox != null) {
                    cachedUnread = inbox.unread();
                    // Apply to whatever list is current now (a resize may have rebuilt it since the drain).
                    ChatMessageList current = find(screen);
                    if (current != null) {
                        current.setUnread(cachedUnread);
                        if (inbox.messages() != null && !inbox.messages().isEmpty()) {
                            reveal(current); // inbox rows are always a real person → the dev has messaged
                        }
                    }
                }
            }, mc);
        } else {
            list.setUnread(cachedUnread); // re-init of an already-drained screen → reuse the count
        }
    }

    /**
     * After the whole screen (incl. the Dungeon Train logo + splash) has drawn, paint the panel on top
     * when it's click-selected — so it sits in front rather than behind the title. When not selected the
     * panel draws in the normal widget pass (behind the logo, 10% faded); see {@link ChatMessageList}.
     */
    // Live-receive poll: while the panel is open, peek the relay inbox every few seconds and stream in
    // new Discord replies (the gateway tap already captured them, so peek is cheap + Discord-API-free).
    private static final long POLL_INTERVAL_MS = 3500;
    private static long lastPollMs;
    private static final AtomicBoolean polling = new AtomicBoolean(false);

    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        ChatMessageList list = find(event.getScreen());
        if (list == null) {
            return; // not the title screen (or the panel was skipped) → nothing to draw or poll
        }
        if (list.isSelected()) {
            list.renderRaised(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
        pollLive(event.getScreen());
    }

    /**
     * The live half of 2-way chat: while the title-screen panel is shown, poll the relay inbox with a
     * non-destructive {@code peek} every {@link #POLL_INTERVAL_MS} and append any new replies to the list.
     * Cheap — the relay serves from its in-memory, gateway-fed inbox (no Discord API call). Draining stays
     * owned by the once-per-open path; peek never advances the cursor, so we dedupe by message id. Polling
     * stops on its own when the player leaves the menu (this only runs while a TitleScreen panel exists).
     */
    private static void pollLive(Screen screen) {
        if (!RelayChatClient.canConnect()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPollMs < POLL_INTERVAL_MS || !polling.compareAndSet(false, true)) {
            return; // throttled, or a poll is already in flight
        }
        lastPollMs = now;
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null) {
            polling.set(false);
            return;
        }
        RelayChatClient.peekInbox(uuid).thenAcceptAsync(inbox -> {
            polling.set(false);
            if (inbox == null || inbox.messages() == null) {
                return;
            }
            ChatMessageList current = find(screen);
            if (current == null) {
                return; // screen changed while the peek was in flight
            }
            int added = 0;
            for (ChatHistory.Message m : inbox.messages()) {
                if (current.appendInbound(m)) {
                    added++;
                }
            }
            if (added > 0) {
                reveal(current); // a live reply is always a real person → the dev has messaged
                // Only badge when the player isn't actively watching (panel raised) — they've seen it otherwise.
                if (!current.isSelected()) {
                    current.addUnread(added);
                }
            }
        }, mc).exceptionally(t -> {
            polling.set(false);
            return null;
        });
    }

    /** Reveal the panel and remember the verdict for the rest of the session. */
    private static void reveal(ChatMessageList list) {
        knownDevHistory = true;
        list.visible = true;
    }

    private static ChatMessageList find(Screen screen) {
        if (!(screen instanceof TitleScreen)) {
            return null;
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof ChatMessageList list) {
                return list;
            }
        }
        return null;
    }
}
