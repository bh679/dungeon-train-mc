package games.brennan.dungeontrain.client.chat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.UUID;

/**
 * Puts a small envelope button on the title screen — directly above the vanilla accessibility button,
 * same 20×20 size — that opens the dev conversation in its own {@link MenuChatScreen}. Replaces the old
 * docked right-gutter chat panel.
 *
 * <p>The button only exists once someone on Discord (the dev) has actually written in the player's
 * thread ({@link MenuChatFilter#hasDevHistory}, verdict cached for the session) — a player who was never
 * messaged sees no chat affordance at all. When the relay inbox holds <b>unread</b> dev messages, a
 * callout pops up beside the button — <i>The Dev sent you a message: "…"</i> quoting the first
 * characters of the least-recent unread — and disappears once the window is opened (opening drains the
 * inbox). {@link MenuChatLivePoll} keeps the popup live: a reply sent while the player sits on the menu
 * reveals the button and popup within a few seconds. Everything is gated on the DiscordPresence
 * network-access consent, like the panel before it.</p>
 */
public final class MenuChatButtonHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int BUTTON_SIZE = 20; // matches the vanilla accessibility SpriteIconButton
    private static final int GAP = 4;
    private static final ResourceLocation ENVELOPE_SPRITE = ResourceLocation.withDefaultNamespace("icon/invite");
    private static final int ENVELOPE_W = 14;
    private static final int ENVELOPE_H = 14;
    private static final Component ACCESSIBILITY_KEY = Component.translatable("options.accessibility");

    private static final int PREVIEW_CHARS = 10;
    private static final int POPUP_MAX_TEXT_WIDTH = 150;
    private static final int POPUP_PAD = 4;
    private static final int POPUP_BG = 0xF0100010;     // vanilla-tooltip dark
    private static final int POPUP_TEXT = 0xFFFFFFFF;

    // Unread pulse — the same wall-clock sine as PulsingDiscordButton, so the two title-screen
    // affordances breathe alike. The popup border holds the pulse hue at full alpha while the button
    // border pulses, visually tying the callout to the envelope it's talking about.
    private static final long PULSE_PERIOD_MS = 1500L;
    private static final int PULSE_PEAK_ALPHA = 200;
    private static final int PULSE_RGB = 0x60_C0_FF;
    private static final int POPUP_BORDER = 0xFF000000 | PULSE_RGB;

    // Session cache: once we know the dev has messaged, the button shows instantly on every title-screen
    // visit instead of popping in after each async history fetch. Never un-reveals mid-session.
    private static boolean knownDevHistory;

    // Unread state for the popup, fed by peeks (never drains — opening MenuChatScreen does the draining).
    private static int unread;
    private static String preview;

    private static WeakReference<Screen> screenRef = new WeakReference<>(null);
    private static WeakReference<SpriteIconButton> buttonRef = new WeakReference<>(null);

    private MenuChatButtonHandler() {}

    public static void onScreenInitPost(games.brennan.dungeontrain.platform.event.DtScreenInit event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }
        if (!RelayChatClient.canConnect()) {
            return; // no network consent — the existing Discord welcome flow handles opt-in
        }
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null) {
            LOGGER.debug("Menu chat: no local profile id at title screen; skipping button.");
            return;
        }

        // Anchor to the vanilla accessibility button (a 20×20 SpriteIconButton). If another mod rewrote
        // the menu and it's gone, skip quietly rather than guessing a position.
        AbstractWidget accessibility = findWidget(event, ACCESSIBILITY_KEY);
        if (accessibility == null) {
            LOGGER.debug("Menu chat: accessibility button not found; skipping the chat button.");
            return;
        }

        SpriteIconButton button = SpriteIconButton.builder(
                        Component.translatable("gui.dungeontrain.menu_chat.button"),
                        b -> {
                            unread = 0; // opening reads them; MenuChatScreen's drain advances the cursor
                            preview = null;
                            mc.setScreen(new MenuChatScreen(titleScreen));
                        },
                        true)
                .width(BUTTON_SIZE)
                .sprite(ENVELOPE_SPRITE, ENVELOPE_W, ENVELOPE_H)
                .build();
        button.setPosition(accessibility.getX(), accessibility.getY() - BUTTON_SIZE - GAP);
        button.visible = knownDevHistory; // hidden until the dev has messaged (revealed async below)
        event.addListener(button);
        screenRef = new WeakReference<>(titleScreen);
        buttonRef = new WeakReference<>(button);

        // Deliver anything queued from a prior offline session while we're at the menu anyway.
        ChatOutbox.get().flush();

        // Fresh visibility verdict: the button appears only once a real person has written in the thread.
        RelayChatClient.fetchHistory(uuid).thenAcceptAsync(history -> {
            if (history != null) {
                // Reaching the menu means the client has these — ✅ them (even with the envelope hidden).
                ChatReceipts.markLoaded(uuid, history.threadId(), history.messages());
                if (MenuChatFilter.hasDevHistory(history.messages())) {
                    reveal();
                }
            }
        }, mc);

        // Prime the unread popup without consuming anything (peek leaves the drain cursor alone).
        RelayChatClient.peekInbox(uuid).thenAcceptAsync(MenuChatButtonHandler::applyInbox, mc);
    }

    /**
     * Title-screen heartbeat: keep the popup live (a Discord reply sent while the player sits on the
     * menu reveals button + popup within one poll) and draw the callout when there's something unread.
     */
    public static void onRenderPost(net.minecraft.client.gui.screens.Screen screen,
                                    net.minecraft.client.gui.GuiGraphics graphics) {
        if (!(screen instanceof TitleScreen) || screen != screenRef.get()) {
            return;
        }
        SpriteIconButton button = buttonRef.get();
        if (button == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc.getUser() != null ? mc.getUser().getProfileId() : null;
        MenuChatLivePoll.poll(uuid, false, MenuChatButtonHandler::applyInbox); // peek only — never consumes

        if (button.visible && unread > 0 && preview != null) {
            drawPulse(graphics, button);
            drawPopup(graphics, button);
        }
    }

    /**
     * Whether the envelope is currently pulsing over unread messages — other title-screen pulses (the
     * opted-out Discord button) stand down while this one is active, so only one thing breathes at a time.
     */
    public static boolean hasUnreadPulse() {
        SpriteIconButton button = buttonRef.get();
        return button != null && button.visible && unread > 0;
    }

    /** The same sine-pulsing 1-pixel border as {@code PulsingDiscordButton}, hugging the envelope. */
    private static void drawPulse(GuiGraphics g, SpriteIconButton button) {
        long now = net.minecraft.Util.getMillis();
        float phase = (float) (now % PULSE_PERIOD_MS) / (float) PULSE_PERIOD_MS;
        float wave = (net.minecraft.util.Mth.sin(phase * 2.0F * (float) Math.PI) + 1.0F) * 0.5F;
        int alpha = (int) (wave * PULSE_PEAK_ALPHA);
        if (alpha <= 0) {
            return;
        }
        int colour = (alpha << 24) | PULSE_RGB;
        int x = button.getX();
        int y = button.getY();
        int w = button.getWidth();
        int h = button.getHeight();
        g.fill(x - 1, y - 1, x + w + 1, y, colour);
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, colour);
        g.fill(x - 1, y, x, y + h, colour);
        g.fill(x + w, y, x + w + 1, y + h, colour);
    }

    /** Apply a peeked inbox: inbox rows are always a real person, so any row also reveals the button. */
    private static void applyInbox(ChatInbox inbox) {
        if (inbox == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc.getUser() != null ? mc.getUser().getProfileId() : null;
        ChatReceipts.markLoaded(uuid, inbox.threadId(), inbox.messages());
        List<ChatHistory.Message> messages = inbox.messages();
        boolean hasRows = messages != null && !messages.isEmpty();
        if (hasRows) {
            reveal();
        }
        unread = inbox.unread();
        if (unread > 0 && hasRows) {
            // Least-recent unread first (the relay returns oldest→newest).
            preview = MenuChatFilter.preview(messages.get(0).content(), PREVIEW_CHARS);
        } else {
            preview = null;
        }
    }

    private static void reveal() {
        knownDevHistory = true;
        SpriteIconButton button = buttonRef.get();
        if (button != null) {
            button.visible = true;
        }
    }

    /** A tooltip-style callout beside the envelope: “The Dev sent you a message: "…"”. */
    private static void drawPopup(GuiGraphics g, SpriteIconButton button) {
        Font font = Minecraft.getInstance().font;
        Component text = Component.translatable("gui.dungeontrain.menu_chat.popup", preview);
        // Keep the callout in the right gutter: its left edge shares the envelope's x (which already
        // clears the centered button column), growing rightward/upward — never over the menu buttons.
        int x = button.getX();
        int avail = g.guiWidth() - x - 6;
        int wrapWidth = Math.max(40, Math.min(POPUP_MAX_TEXT_WIDTH, avail - POPUP_PAD * 2));
        List<FormattedCharSequence> lines = font.split(text, wrapWidth);
        int textW = 0;
        for (FormattedCharSequence line : lines) {
            textW = Math.max(textW, font.width(line));
        }
        int w = textW + POPUP_PAD * 2;
        int h = lines.size() * font.lineHeight + POPUP_PAD * 2;
        int y = Math.max(4, button.getY() - h - GAP);

        g.fill(x, y, x + w, y + h, POPUP_BG);
        g.fill(x, y, x + w, y + 1, POPUP_BORDER);
        g.fill(x, y + h - 1, x + w, y + h, POPUP_BORDER);
        g.fill(x, y, x + 1, y + h, POPUP_BORDER);
        g.fill(x + w - 1, y, x + w, y + h, POPUP_BORDER);
        int ty = y + POPUP_PAD;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, x + POPUP_PAD, ty, POPUP_TEXT, false);
            ty += font.lineHeight;
        }
    }

    private static AbstractWidget findWidget(games.brennan.dungeontrain.platform.event.DtScreenInit event, Component message) {
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget widget && message.equals(widget.getMessage())) {
                return widget;
            }
        }
        return null;
    }
}
