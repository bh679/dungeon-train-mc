package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.ChatClickTokens;
import games.brennan.dungeontrain.client.live.LiveBotReport.Flagged;
import games.brennan.dungeontrain.client.live.LiveBotReport.Outcome;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Source;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Live chat bot report's pure parts: who gets a flag, its click values, and what the relay's answer means. */
final class LiveBotReportTest {

    @AfterEach
    void tearDown() {
        LiveBotReport.forget();
        ChatClickTokens.useClock(null);
    }

    /** Every click value on {@code line} and its siblings, in order. */
    private static List<String> clickValues(Component line) {
        List<String> out = new ArrayList<>();
        line.visit((style, text) -> {
            ClickEvent e = style.getClickEvent();
            if (e != null && !text.isEmpty()) out.add(e.getValue());
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return out;
    }

    @Test
    @DisplayName("the confirm's [Report] and [Cancel] carry the seq and a fresh secret token")
    void confirmCarriesToken() {
        Flagged f = new Flagged("bot", Source.KICK, null);
        Component a = LiveBotReport.confirmLine(42, f);
        Component b = LiveBotReport.confirmLine(42, f);
        String goA = clickValues(a).stream().filter(v -> v.contains("/go/")).findFirst().orElseThrow();
        String goB = clickValues(b).stream().filter(v -> v.contains("/go/")).findFirst().orElseThrow();
        assertTrue(goA.matches("dungeontrain:live-bot-report/go/42/[A-Za-z0-9_-]{22}"), goA);
        assertFalse(goA.equals(goB), "each confirm mints its own token");
        assertTrue(clickValues(b).stream().anyMatch(v -> v.matches("dungeontrain:live-bot-report/cancel/42/[A-Za-z0-9_-]{22}")));
    }

    @Test
    @DisplayName("a [Report] click without this client's confirm token sends nothing")
    void forgedGoSendsNothing() {
        AtomicLong now = new AtomicLong(1_000_000);
        ChatClickTokens.useClock(now::get);
        LiveBotReport.flagForTest(42, new Flagged("bot", Source.TWITCH, null));

        LiveBotReport.go(42, "A".repeat(22));
        assertFalse(LiveBotReport.sent(42), "no confirm was shown");

        LiveBotReport.confirmLine(42, new Flagged("bot", Source.TWITCH, null));
        now.addAndGet(1_000);
        LiveBotReport.go(42, "B".repeat(22));
        assertFalse(LiveBotReport.sent(42), "a guessed token");
    }

    @Test
    @DisplayName("go and cancel need the token segment; the old two-segment go means nothing")
    void goNeedsToken() {
        assertEquals(-1, LiveBotReport.seqOf(List.of("go", "42"), LiveBotReport.GO, 3));
        assertEquals(42, LiveBotReport.seqOf(List.of("go", "42", "t"), LiveBotReport.GO, 3));
        assertEquals(42, LiveBotReport.seqOf(List.of("cancel", "42", "t"), LiveBotReport.CANCEL, 3));
        assertEquals(-1, LiveBotReport.seqOf(List.of("go", "42", "t"), LiveBotReport.ASK));
    }

    @Test
    @DisplayName("only Kick and Twitch lines can be reported")
    void reportable() {
        assertTrue(LiveBotReport.reportable(Source.KICK));
        assertTrue(LiveBotReport.reportable(Source.TWITCH));
        for (Source s : Source.values()) {
            if (s != Source.KICK && s != Source.TWITCH) assertFalse(LiveBotReport.reportable(s), s.name());
        }
    }

    @Test
    @DisplayName("the flag and its confirm are chat actions, never commands, and carry the line's seq")
    void clicks() {
        ClickEvent ask = LiveBotReport.click(LiveBotReport.ASK, 42);
        assertEquals(ClickEvent.Action.RUN_COMMAND, ask.getAction());
        assertEquals("dungeontrain:live-bot-report/ask/42", ask.getValue());
        assertFalse(ask.getValue().startsWith("/"));
        assertEquals("dungeontrain:live-bot-report/go/7", LiveBotReport.click(LiveBotReport.GO, 7).getValue());
        assertEquals(ask, LiveBotReport.chipComponent(42).getStyle().getClickEvent());
    }

    @Test
    @DisplayName("its click action is its own: the [Report streamer] button's must not be replaced")
    void ownAction() {
        assertFalse(LiveBotReport.ACTION.equals(LiveReportButton.CLICK_ACTION));
    }

    @Test
    @DisplayName("click args: the right step and a positive seq, nothing else")
    void seqOf() {
        assertEquals(42, LiveBotReport.seqOf(List.of("ask", "42"), LiveBotReport.ASK));
        assertEquals(-1, LiveBotReport.seqOf(List.of("go", "42"), LiveBotReport.ASK));
        assertEquals(-1, LiveBotReport.seqOf(List.of("ask", "0"), LiveBotReport.ASK));
        assertEquals(-1, LiveBotReport.seqOf(List.of("ask", "x"), LiveBotReport.ASK));
        assertEquals(-1, LiveBotReport.seqOf(List.of("ask"), LiveBotReport.ASK));
        assertEquals(-1, LiveBotReport.seqOf(List.of("ask", "1", "2"), LiveBotReport.ASK));
    }

    @Test
    @DisplayName("the relay's answer: done, gone, link again (after trying the second key), limit, else failed")
    void outcome() {
        assertEquals(Outcome.DONE, LiveBotReport.outcome(200, "", false));
        assertEquals(Outcome.RETRY_PENDING, LiveBotReport.outcome(403, "bad_key", true));
        assertEquals(Outcome.RELINK, LiveBotReport.outcome(403, "bad_key", false));
        assertEquals(Outcome.RETRY_PENDING, LiveBotReport.outcome(403, "relink_needed", true));
        assertEquals(Outcome.RELINK, LiveBotReport.outcome(403, "relink_needed", false));
        assertEquals(Outcome.RELINK, LiveBotReport.outcome(403, "not_linked", true));
        assertEquals(Outcome.FAILED, LiveBotReport.outcome(403, "banned", true));
        assertEquals(Outcome.GONE, LiveBotReport.outcome(404, "no_line", false));
        assertEquals(Outcome.GONE, LiveBotReport.outcome(409, "already_reported", false));
        assertEquals(Outcome.LIMIT, LiveBotReport.outcome(429, "rate_limited", false));
        assertEquals(Outcome.FAILED, LiveBotReport.outcome(429, "hook", false));
        assertEquals(Outcome.FAILED, LiveBotReport.outcome(404, "", false), "an older relay without the route");
        assertEquals(Outcome.FAILED, LiveBotReport.outcome(0, "", false), "no answer");
    }
}
