package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickTokens;
import games.brennan.dungeontrain.client.ChatClickValues;
import net.minecraft.network.chat.ClickEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveGiftClientTest {

    @Test
    @DisplayName("the gift prompt shows at most once every 12 minutes")
    void throttle() {
        assertEquals(12L * 60L * 1000L, LiveGiftClient.PROMPT_GAP_MS);
        assertTrue(LiveGiftClient.shouldPrompt(-1, 1_000), "never prompted");
        long last = 10_000_000L;
        assertFalse(LiveGiftClient.shouldPrompt(last, last));
        assertFalse(LiveGiftClient.shouldPrompt(last, last + LiveGiftClient.PROMPT_GAP_MS - 1));
        assertTrue(LiveGiftClient.shouldPrompt(last, last + LiveGiftClient.PROMPT_GAP_MS));
        assertTrue(LiveGiftClient.shouldPrompt(last, last - 1), "a clock that ran backwards does not lock it out");
    }

    @Test
    @DisplayName("only a player who can take a Live Eye joins as eligible, sees or accepts an offer")
    void canReceive() {
        assertTrue(LiveGiftClient.canReceive(false, true, false, false, true));
        assertFalse(LiveGiftClient.canReceive(true, true, false, false, true), "Kid mode");
        assertFalse(LiveGiftClient.canReceive(false, false, false, false, true), "Livestreaming off");
        assertFalse(LiveGiftClient.canReceive(false, true, true, false, true), "already carries one");
        assertFalse(LiveGiftClient.canReceive(false, true, false, true, true), "Free Play: it would burn on the way on");
        assertFalse(LiveGiftClient.canReceive(false, true, false, false, false), "no ffmpeg to stream with");
    }

    @Test
    @DisplayName("/offereye refusals: 409 already offered, 404 not watching, bare 404 = older relay")
    void offerFailureKeys() {
        String k = LiveGiftClient.OFFER_KEY;
        assertEquals(k + "already_offered", LiveGiftClient.offerFailureKey(409, "already_offered"));
        assertEquals(k + "not_watching", LiveGiftClient.offerFailureKey(404, "not_watching"));
        assertEquals(k + "unavailable", LiveGiftClient.offerFailureKey(404, ""));
        assertEquals(k + "not_live", LiveGiftClient.offerFailureKey(403, "forbidden"));
        assertEquals("chat.dungeontrain.live_gift.give_failed", LiveGiftClient.offerFailureKey(0, ""));
    }

    @Test
    @DisplayName("/offereye all refusals never name a viewer; already given wins over already offered")
    void offerAllFailureKeys() {
        String k = LiveGiftClient.OFFER_KEY;
        assertEquals(k + "none", LiveGiftClient.offerAllFailureKey(404, "not_watching"));
        assertEquals(k + "all_already_offered", LiveGiftClient.offerAllFailureKey(409, "already_offered"));
        assertEquals(k + "already_given", LiveGiftClient.offerAllFailureKey(409, "already_given"));
        assertEquals(k + "already_given", LiveGiftClient.offerFailureKey(409, "already_given"));
        assertEquals(k + "unavailable", LiveGiftClient.offerAllFailureKey(404, ""));
        assertEquals(k + "list_failed", LiveGiftClient.offerAllFailureKey(500, ""));
    }

    // ---- the buttons' tokens ---------------------------------------------------------------------

    private final AtomicLong now = new AtomicLong(1_000_000);

    @BeforeEach
    void clock() {
        ChatClickTokens.useClock(now::get);
        LiveGiftClient.startOfferList();
    }

    @AfterEach
    void wallClock() {
        LiveGiftClient.startOfferList();
        ChatClickTokens.useClock(null);
    }

    /** The click's {@code [step, button, token]}, as the handler gets it. */
    private static List<String> args(ClickEvent e) {
        List<String> parts = ChatClick.parse(e.getValue());
        assertNotNull(parts);
        assertEquals(LiveGiftClient.CLICK_ACTION, parts.get(0));
        return parts.subList(1, parts.size());
    }

    private void age() {
        now.addAndGet(ChatClickTokens.MIN_AGE_MS);
    }

    @Test
    @DisplayName("an [Offer] button carries a button number and a token, never the name")
    void offerButtonCarriesToken() {
        List<String> a = ChatClickValues.of(LiveGiftClient.offerButton("Steve")).get(0).subList(1, 4);
        assertEquals(LiveGiftClient.OFFER, a.get(0));
        assertFalse(String.join("/", a).contains("Steve"));
        long b = LiveGiftClient.buttonOf(a);
        assertTrue(b > 0);
        assertEquals("Steve", LiveGiftClient.offerTarget(b));
    }

    @Test
    @DisplayName("the printed token offers to its name once; a forged one offers nothing")
    void offerRedeem() {
        List<String> a = args(LiveGiftClient.offerClick(LiveGiftClient.OFFER, "Steve"));
        long b = LiveGiftClient.buttonOf(a);
        age();
        assertNull(LiveGiftClient.redeemOffer(b, "forged"));
        // The guess used the real token up; the entry stays but nothing can redeem it now.
        assertNull(LiveGiftClient.redeemOffer(b, a.get(2)));

        List<String> c = args(LiveGiftClient.offerClick(LiveGiftClient.OFFER_ALL, LiveGiftClient.ALL));
        age();
        assertEquals(LiveGiftClient.ALL, LiveGiftClient.redeemOffer(LiveGiftClient.buttonOf(c), c.get(2)));
        assertNull(LiveGiftClient.redeemOffer(LiveGiftClient.buttonOf(c), c.get(2)), "used up");
    }

    @Test
    @DisplayName("old click shapes (a name, or a join id, with no token) are not acted on")
    void oldShapesRefused() {
        assertEquals(-1, LiveGiftClient.buttonOf(List.of("offer", "Steve")));
        assertEquals(-1, LiveGiftClient.buttonOf(List.of("offer-all", "all")));
        assertEquals(-1, LiveGiftClient.buttonOf(List.of("give", "j-abc")));
        assertEquals(-1, LiveGiftClient.buttonOf(List.of("give", "j-abc", "tok")));
        assertEquals(-1, LiveGiftClient.buttonOf(List.of("give", "5", "")));
    }

    @Test
    @DisplayName("a new /offereye list stops the last list's buttons")
    void newListClearsOld() {
        List<String> a = args(LiveGiftClient.offerClick(LiveGiftClient.OFFER, "Steve"));
        long b = LiveGiftClient.buttonOf(a);
        LiveGiftClient.startOfferList();
        age();
        assertNull(LiveGiftClient.offerTarget(b));
        assertNull(LiveGiftClient.redeemOffer(b, a.get(2)));
    }

    @Test
    @DisplayName("the list's buttons last five minutes, past the default two")
    void offerTtl() {
        List<String> a = args(LiveGiftClient.offerClick(LiveGiftClient.OFFER, "Steve"));
        now.addAndGet(LiveGiftClient.TOKEN_TTL_MS - 1);
        assertEquals("Steve", LiveGiftClient.redeemOffer(LiveGiftClient.buttonOf(a), a.get(2)));
    }

    @Test
    @DisplayName("[Gift it to them] gives its join only with its token; a refused click keeps the join")
    void giveRedeem() {
        List<String> a = args(LiveGiftClient.giveClick("j-abc"));
        assertEquals(LiveGiftClient.GIVE, a.get(0));
        assertFalse(String.join("/", a).contains("j-abc"), "the join id stays on this client");
        long b = LiveGiftClient.buttonOf(a);
        age();
        Map<Long, LiveGiftClient.Button> buttons = new HashMap<>();
        buttons.put(b, LiveGiftClient.buttonFor(b));
        assertNull(LiveGiftClient.redeem(buttons, LiveGiftClient.GIFT_SCOPE, b, LiveGiftClient.ACCEPT, a.get(2)),
            "a give button is not an accept button");
        assertNull(LiveGiftClient.redeem(buttons, LiveGiftClient.GIFT_SCOPE, b, LiveGiftClient.GIVE, "forged"));
        assertTrue(buttons.containsKey(b), "the join is still there");
    }

    @Test
    @DisplayName("the right token redeems a give button once")
    void giveOnce() {
        List<String> a = args(LiveGiftClient.giveClick("j-abc"));
        long b = LiveGiftClient.buttonOf(a);
        age();
        Map<Long, LiveGiftClient.Button> buttons = new HashMap<>();
        buttons.put(b, LiveGiftClient.buttonFor(b));
        assertEquals("j-abc", LiveGiftClient.redeem(buttons, LiveGiftClient.GIFT_SCOPE, b, LiveGiftClient.GIVE, a.get(2)));
        assertNull(LiveGiftClient.redeem(buttons, LiveGiftClient.GIFT_SCOPE, b, LiveGiftClient.GIVE, a.get(2)));
    }
}
