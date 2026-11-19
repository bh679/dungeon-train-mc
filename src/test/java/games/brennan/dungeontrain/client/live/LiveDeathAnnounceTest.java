package games.brennan.dungeontrain.client.live;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveDeathAnnounceTest {

    @Test
    @DisplayName("shouldAnnounce: in the room through a worn TV, not the streamer, once a life")
    void shouldAnnounce() {
        assertTrue(LiveDeathAnnounce.shouldAnnounce(true, true, false, false, false));
        assertFalse(LiveDeathAnnounce.shouldAnnounce(false, true, false, false, false), "chat not active");
        assertFalse(LiveDeathAnnounce.shouldAnnounce(true, false, false, false, false), "no TV on the head");
        assertFalse(LiveDeathAnnounce.shouldAnnounce(true, true, true, false, false), "the streamer");
        assertFalse(LiveDeathAnnounce.shouldAnnounce(true, true, false, true, false), "Eye waiting on a takeover");
        assertFalse(LiveDeathAnnounce.shouldAnnounce(true, true, false, false, true), "already told this life");
    }

    @Test
    @DisplayName("plain: keys and words travel, styles and events are dropped")
    void plain() {
        MutableComponent victim = Component.literal("Steve")
            .withStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tell Steve ")));
        MutableComponent killer = Component.literal("Zombie").withStyle(ChatFormatting.RED);
        Component death = Component.translatable("death.attack.mob", victim, killer).append(Component.literal("!").withStyle(ChatFormatting.BOLD));

        MutableComponent out = LiveDeathAnnounce.plain(death);
        TranslatableContents t = assertInstanceOf(TranslatableContents.class, out.getContents());
        assertEquals("death.attack.mob", t.getKey());
        assertEquals(2, t.getArgs().length);
        Component arg0 = assertInstanceOf(Component.class, t.getArgs()[0]);
        assertEquals(Style.EMPTY, arg0.getStyle());
        assertEquals("Steve", ((PlainTextContents) arg0.getContents()).text());
        assertEquals(Style.EMPTY, ((Component) t.getArgs()[1]).getStyle());
        assertEquals(1, out.getSiblings().size());
        assertEquals(Style.EMPTY, out.getSiblings().get(0).getStyle());
        assertEquals(Style.EMPTY, out.getStyle());
    }
}
