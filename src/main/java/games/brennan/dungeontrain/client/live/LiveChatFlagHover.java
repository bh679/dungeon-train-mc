package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.chat.ChatLineEditor;
import games.brennan.dungeontrain.client.chat.ChatReactionTracker;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Hovering a translated line's language flag shows the original text in place of the translation, until the mouse
 * moves away. Chat itself only offers tooltips, so the line is redrawn ({@link ChatLineEditor}). A flag is told apart
 * by the identity of its hover text ({@link LanguageFlags#flag} makes a new one each time), which survives the style
 * copies a printed line goes through. Client thread only.
 */
final class LiveChatFlagHover {

    /** Flags remembered, newest kept: the relay's ring holds 200 lines. */
    static final int MAX = 256;

    private record Swap(Runnable original, Runnable translated) {}

    private final Map<Component, Swap> watched = new IdentityHashMap<>();
    private final Deque<Component> order = new ArrayDeque<>();
    @Nullable private Component active;

    /** Watch a line replaced by its translation: {@code flag}'s line, as {@link LiveChatTranslations#replacedLine}. */
    void watchReplaced(@Nullable MutableComponent flag, ChatLine line, String translation) {
        watch(flag,
            () -> ChatReactionTracker.replaceLive(line.seq(), LiveChatTranslations.replacedLine(line, line.content(), flag)),
            () -> ChatReactionTracker.replaceLive(line.seq(), LiveChatTranslations.replacedLine(line, translation, flag)));
    }

    /** Watch a translation printed under its line as {@code printed}. */
    void watchUnder(@Nullable MutableComponent flag, ChatLine line, boolean adjacent, Component printed) {
        Component[] current = {printed};
        Runnable translated = () -> {
            Component next = printed.copy();
            ChatLineEditor.replace(current[0], next);
            current[0] = next;
        };
        Runnable original = () -> {
            Component next = ChatReactionTracker.onOtherChat(LiveChatTranslations.underLine(line, line.content(), adjacent, flag));
            ChatLineEditor.replace(current[0], next);
            current[0] = next;
        };
        watch(flag, original, translated);
    }

    void watch(@Nullable Component flag, Runnable original, Runnable translated) {
        Component key = keyOf(flag == null ? null : flag.getStyle());
        if (key == null) return;
        if (watched.put(key, new Swap(original, translated)) == null) order.addLast(key);
        while (order.size() > MAX) {
            Component eldest = order.removeFirst();
            watched.remove(eldest);
            if (eldest == active) active = null;
        }
    }

    /** Once a tick: what is under the mouse while chat is open. */
    void tick(Minecraft mc) {
        if (active == null && !(mc.screen instanceof ChatScreen)) return;
        hover(mc.screen instanceof ChatScreen ? keyOf(styleUnderMouse(mc)) : null);
    }

    /** The mouse is now over the flag with hover text {@code key}, or none: the lines change only when that does. */
    void hover(@Nullable Component key) {
        Component next = key != null && watched.containsKey(key) ? key : null;
        if (next == active) return;
        if (active != null) watched.get(active).translated().run();
        active = next;
        if (active != null) watched.get(active).original().run();
    }

    /** Whether the mouse is on a flag now. */
    boolean hovering() {
        return active != null;
    }

    int size() {
        return watched.size();
    }

    @Nullable
    private static Style styleUnderMouse(Minecraft mc) {
        double x = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double y = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
        return mc.gui.getChat().getClickedComponentStyleAt(x, y);
    }

    /** A style's flag identity: its hover text, when it is a flag's. */
    @Nullable
    static Component keyOf(@Nullable Style style) {
        if (style == null || !LanguageFlags.FONT.equals(style.getFont()) || style.getHoverEvent() == null) return null;
        return style.getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT);
    }
}
