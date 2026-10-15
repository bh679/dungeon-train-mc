package games.brennan.dungeontrain.client.chat;

import games.brennan.dungeontrain.mixin.client.ChatComponentAccessor;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.ListIterator;

/**
 * Re-draws one chat line in place. Lines are found by the identity of the {@link Component} they were
 * printed with — whoever printed it keeps that instance — and swapped for a new one keeping the line's
 * time, signature and tag, so it stays where it was and fades on the same clock. Client thread only.
 */
public final class ChatLineEditor {

    /** Lines fade from the closed chat this many GUI ticks after they were added (vanilla's 200). */
    public static final int FADE_TICKS = 200;

    private ChatLineEditor() {}

    /**
     * Swaps the line printed as {@code current} for {@code next}. Returns how many GUI ticks ago the line
     * was added, or -1 when it is no longer in the chat history (pushed out, or cleared).
     */
    public static int replace(Component current, Component next) {
        Minecraft mc = Minecraft.getInstance();
        ChatComponentAccessor chat = (ChatComponentAccessor) mc.gui.getChat();
        List<GuiMessage> all = chat.dungeontrain$getAllMessages();
        for (ListIterator<GuiMessage> it = all.listIterator(); it.hasNext(); ) {
            GuiMessage m = it.next();
            if (m.content() != current) continue;
            it.set(new GuiMessage(m.addedTime(), next, m.signature(), m.tag()));
            chat.dungeontrain$refreshTrimmedMessages();
            return mc.gui.getGuiTicks() - m.addedTime();
        }
        return -1;
    }
}
