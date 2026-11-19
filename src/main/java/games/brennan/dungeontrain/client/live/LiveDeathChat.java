package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.ChatComponentAccessor;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.util.StringUtil;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The stream's chat on the death screens, for a streamer still on air after dying ({@link LivePostMortem}).
 *
 * <p>The death screens paint over the HUD, and vanilla opens chat only with no screen up, so a post-mortem
 * streamer could neither read the room nor answer it. While {@link LiveStreamController#postMortem} holds
 * and {@link LiveChatBridge} is connected, this shows the newest chat message as a dim ticker under the death
 * page's footer (the whole chat only while typing, so the page stays readable) and lets the chat key (or the command key) type a line
 * without leaving the page: Enter sends it the way vanilla's chat box does (so {@link LiveChatBridge#onClientChat} and the text filter see it), Esc puts the box away.</p>
 *
 * <p>Everything here draws on {@link RenderFrameEvent.Post} after the stream grabs the frame, so neither the
 * chat lines nor what is being typed go out on the broadcast (pinned by {@code LiveFrameOrderTest}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveDeathChat {

    private static final int MAX_LENGTH = 256;
    /** The ticker: left margin, rise from the screen's bottom edge, the gap kept from the centre column. */
    private static final int TICKER_LEFT = 4;
    private static final int TICKER_BOTTOM = 10;
    private static final int TICKER_CENTRE_GAP = 70;
    private static final int TICKER_COLOUR = 0xFF808080;
    private static final String ELLIPSIS = "\u2026";

    /** The line being typed, or null when the box is away. */
    @Nullable private static EditBox input;
    /** The key that opened the box also arrives as a character; it is not part of the line. */
    private static boolean swallowNextChar;
    private static int historyPos;

    private LiveDeathChat() {}

    /** The chat shows on this screen: a death screen, the stream still this client's, the bridge in the room. */
    static boolean active(boolean postMortem, boolean connected, boolean deathScreen) {
        return postMortem && connected && deathScreen;
    }

    /** The chat key opens the box unless the page's own text field (a survey comment) has the keyboard. */
    static boolean mayOpen(boolean active, boolean typing, boolean pageFieldFocused) {
        return active && !typing && !pageFieldFocused;
    }

    private static boolean activeOn(@Nullable Screen screen) {
        return active(LiveStreamController.get().postMortem(), LiveChatBridge.connected(),
            screen != null && LivePostMortem.isDeathScreen(screen.getClass().getName()));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (input != null && !activeOn(Minecraft.getInstance().screen)) close();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!activeOn(screen)) return;
        int key = event.getKeyCode();
        int scan = event.getScanCode();
        Minecraft mc = Minecraft.getInstance();
        if (input == null) {
            if (!mayOpen(true, false, screen.getFocused() instanceof EditBox)) return;
            if (mc.options.keyChat.matches(key, scan)) {
                open(mc, "");
            } else if (mc.options.keyCommand.matches(key, scan)) {
                open(mc, "/");
            } else {
                return;
            }
            event.setCanceled(true);
            return;
        }
        // The box has the keyboard: no key reaches the page underneath.
        event.setCanceled(true);
        switch (key) {
            case InputConstants.KEY_ESCAPE -> close();
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                send(mc, input.getValue());
                close();
            }
            case InputConstants.KEY_UP -> moveInHistory(mc, -1);
            case InputConstants.KEY_DOWN -> moveInHistory(mc, 1);
            case InputConstants.KEY_PAGEUP -> mc.gui.getChat().scrollChat(mc.gui.getChat().getLinesPerPage() - 1);
            case InputConstants.KEY_PAGEDOWN -> mc.gui.getChat().scrollChat(-mc.gui.getChat().getLinesPerPage() + 1);
            default -> {
                input.keyPressed(key, scan, event.getModifiers());
                LiveTextCensorClient.checkTyped(input);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (input == null || !activeOn(event.getScreen())) return;
        event.setCanceled(true);
        if (swallowNextChar) {
            swallowNextChar = false;
            return;
        }
        input.charTyped(event.getCodePoint(), event.getModifiers());
        LiveTextCensorClient.checkTyped(input);
    }

    /**
     * The HUD's own chat would show through a see-through death page as a stack of fading lines; this
     * overlay is the death page's chat instead. After the stream's own chat-layer hook (HIGHEST).
     */
    @SubscribeEvent
    public static void onGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (VanillaGuiLayers.CHAT.equals(event.getName()) && activeOn(Minecraft.getInstance().screen)) {
            event.setCanceled(true);
        }
    }

    /** Drawn after the stream's grab: the streamer's screen only. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFrameEnd(RenderFrameEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !activeOn(mc.screen)) return;
        LiveRecOverlay.guiPass(LiveDeathChat::draw);
    }

    private static void draw(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        EditBox box = input;
        if (box == null) {
            drawLatest(g, mc);
            return;
        }
        mc.gui.getChat().render(g, mc.gui.getGuiTicks(), -1, -1, true);
        int w = g.guiWidth();
        int h = g.guiHeight();
        if (box.getWidth() != w - 4) box.setWidth(w - 4);
        box.setY(h - 12);
        g.fill(2, h - 14, w - 2, h - 2, mc.options.getBackgroundColor(Integer.MIN_VALUE));
        box.render(g, -1, -1, 0f);
    }

    /**
     * The newest chat message as a dim one-line ticker in the strip under the death page's footer, left of
     * its centre column: no backing, no {@code [Live]} prefix or source icon, cut short with an ellipsis.
     * The whole chat is one key away.
     */
    private static void drawLatest(GuiGraphics g, Minecraft mc) {
        if (mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) return;
        List<GuiMessage> all = ((ChatComponentAccessor) mc.gui.getChat()).dungeontrain$getAllMessages();
        if (all.isEmpty()) return;
        String text = tickerText(all.get(0).content().getString(),
            Component.translatable(LiveChatRules.PREFIX_KEY).getString());
        int maxWidth = g.guiWidth() / 2 - TICKER_CENTRE_GAP - TICKER_LEFT;
        if (text.isEmpty() || maxWidth < mc.font.width(ELLIPSIS) * 4) return;
        if (mc.font.width(text) > maxWidth) {
            text = mc.font.plainSubstrByWidth(text, maxWidth - mc.font.width(ELLIPSIS)).stripTrailing() + ELLIPSIS;
        }
        g.drawString(mc.font, text, TICKER_LEFT, g.guiHeight() - TICKER_BOTTOM, TICKER_COLOUR, false);
    }

    /** A chat line as the ticker shows it: the live prefix and its source icon taken off, plain text. */
    static String tickerText(String line, String prefix) {
        String s = line.strip();
        if (!prefix.isEmpty() && s.startsWith(prefix)) s = s.substring(prefix.length()).strip();
        int i = 0;
        while (i < s.length() && (Character.getType(s.charAt(i)) == Character.PRIVATE_USE || s.charAt(i) == ' ')) i++;
        return s.substring(i);
    }

    private static void open(Minecraft mc, String initial) {
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        EditBox box = new EditBox(mc.fontFilterFishy, 4, h - 12, w - 4, 12, Component.translatable("chat.editBox"));
        box.setMaxLength(MAX_LENGTH);
        box.setBordered(false);
        box.setValue(initial);
        box.setCanLoseFocus(false);
        box.setFocused(true);
        input = box;
        swallowNextChar = true;
        historyPos = mc.gui.getChat().getRecentChat().size();
    }

    private static void close() {
        input = null;
        swallowNextChar = false;
        Minecraft.getInstance().gui.getChat().resetChatScroll();
    }

    /** As vanilla's {@code ChatScreen#handleChatInput}: the client hook and the filter run inside sendChat. */
    private static void send(Minecraft mc, String raw) {
        String message = StringUtil.trimChatMessage(StringUtils.normalizeSpace(raw.trim()));
        if (message.isEmpty() || mc.player == null) return;
        mc.gui.getChat().addRecentChat(message);
        if (message.startsWith("/")) {
            mc.player.connection.sendCommand(message.substring(1));
        } else {
            mc.player.connection.sendChat(message);
        }
    }

    private static void moveInHistory(Minecraft mc, int dir) {
        EditBox box = input;
        if (box == null) return;
        var recent = mc.gui.getChat().getRecentChat();
        int pos = Math.max(0, Math.min(historyPos + dir, recent.size()));
        if (pos == historyPos) return;
        historyPos = pos;
        box.setValue(pos == recent.size() ? "" : recent.get(pos));
    }
}
