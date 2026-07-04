package games.brennan.dungeontrain.client.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The conversation-only rules of the title-screen chat panel (see {@link MenuChatFilter}): humans always
 * show, automated reports never do, player chat lines only when dev-tagged / menu-sent / inside the
 * conversation window, join lines never (even when they ping the dev), and only genuine survey-answer
 * embeds survive of all the webhook embeds.
 */
class MenuChatFilterTest {

    private static final String T0 = "2026-07-01T12:00:00.000000+00:00";       // the human reply's time
    private static final String NEAR = "2026-07-01T12:05:00.000000+00:00";     // inside the ±10 min window
    private static final String FAR = "2026-07-01T13:00:00.000000+00:00";      // way outside it
    private static final String BEFORE_NEAR = "2026-07-01T11:52:00.000000+00:00"; // window works both ways

    // --- fixtures -------------------------------------------------------------

    private static ChatHistory.Message human(String id, String ts) {
        return new ChatHistory.Message(id, "dev1", "Brennan", false, false, "hey! saw your run", List.of(), List.of(), ts, false);
    }

    private static ChatHistory.Message playerChat(String id, String content, String ts) {
        return new ChatHistory.Message(id, "wh", "Steve", false, true, content, List.of(), List.of(), ts, true);
    }

    private static ChatHistory.Message botPost(String id) {
        return new ChatHistory.Message(id, "bot1", "Dungeon Train", true, false, "Carriage +3 · Difficulty Level 2",
                List.of(), List.of(), T0, false);
    }

    private static ChatHistory.Message webhookEmbed(String id, String title) {
        return new ChatHistory.Message(id, "wh", "Steve", false, true, null,
                List.of(new ChatHistory.Embed(title, "desc", null, List.of())), List.of(), T0, true);
    }

    private static ChatHistory.Message surveyAnswer(String id, String prompt, ChatHistory.Field... fields) {
        return new ChatHistory.Message(id, "wh", "Steve", false, true, null,
                List.of(new ChatHistory.Embed("📋 Feedback — Steve", prompt, null, List.of(fields))),
                List.of(), T0, true);
    }

    private static ChatHistory.Message attachmentPost(String id) {
        return new ChatHistory.Message(id, "wh", "Steve", false, true, "bug logs attached", List.of(),
                List.of(new ChatHistory.Attachment("latest.log.gz", 1234L, "application/gzip", "http://x")),
                T0, true);
    }

    private static Set<String> shownIds(List<ChatHistory.Message> messages) {
        return MenuChatFilter.filterHistory(messages, id -> false).stream()
                .map(ChatHistory.Message::id)
                .collect(Collectors.toSet());
    }

    // --- who shows ------------------------------------------------------------

    @Test
    void humanMessagesAlwaysShowAndGateThePanel() {
        List<ChatHistory.Message> thread = List.of(human("h1", T0));
        assertTrue(MenuChatFilter.hasDevHistory(thread));
        assertEquals(Set.of("h1"), shownIds(thread));
    }

    @Test
    void threadWithoutHumansHasNoDevHistory() {
        List<ChatHistory.Message> thread = List.of(
                playerChat("c1", "hello?", T0), botPost("b1"), webhookEmbed("d1", "💀 Steve"));
        assertFalse(MenuChatFilter.hasDevHistory(thread));
    }

    @Test
    void automatedReportsNeverShow() {
        List<ChatHistory.Message> thread = List.of(
                human("h1", T0),
                botPost("adv"),                                              // advancement/state line (bot)
                webhookEmbed("death", "💀 Steve"),                           // death report
                webhookEmbed("leave", "Steve left the game"),                // disconnect report
                webhookEmbed("free", "🎮 Steve entered Free Play"),          // Free Play notice
                webhookEmbed("diff", "⚔ Steve reached Difficulty Level 3"), // difficulty notice
                webhookEmbed("mystery", "Some future report"),               // unknown embed → fail closed
                attachmentPost("logs"));                                     // bug-report log bundle
        assertEquals(Set.of("h1"), shownIds(thread));
    }

    // --- player chat lines ----------------------------------------------------

    @Test
    void taggedChatShowsEvenWithNoHumanReply() {
        List<ChatHistory.Message> thread = List.of(playerChat("c1", "hey <@123456789> the train vanished!", FAR));
        assertEquals(Set.of("c1"), shownIds(thread));
    }

    @Test
    void untaggedChatShowsOnlyInsideTheConversationWindow() {
        List<ChatHistory.Message> thread = List.of(
                playerChat("early", "anyone there?", BEFORE_NEAR), // 8 min before the reply → part of it
                human("h1", T0),
                playerChat("reply", "oh hi!", NEAR),               // 5 min after → part of it
                playerChat("later", "talking to myself", FAR));    // an hour later → firehose, hidden
        assertEquals(Set.of("early", "h1", "reply"), shownIds(thread));
    }

    @Test
    void chatWithoutTimestampNeedsATagOrSentId() {
        List<ChatHistory.Message> thread = List.of(human("h1", T0), playerChat("c1", "hello", null));
        assertEquals(Set.of("h1"), shownIds(thread));
    }

    @Test
    void ownMenuSendsAlwaysShow() {
        List<ChatHistory.Message> thread = List.of(playerChat("mine", "hi dev, from the menu", FAR));
        List<ChatHistory.Message> shown = MenuChatFilter.filterHistory(thread, "mine"::equals);
        assertEquals(1, shown.size());
        assertEquals("mine", shown.get(0).id());
    }

    @Test
    void joinLinesNeverShowEvenWithAMilestonePing() {
        List<ChatHistory.Message> thread = List.of(
                playerChat("join", "🎮 **Steve** started the game (v0.380.0) <@123456789>", NEAR),
                playerChat("first", "🎮 **Steve** joined the game for the first time", NEAR),
                human("h1", T0));
        assertEquals(Set.of("h1"), shownIds(thread));
    }

    // --- surveys ---------------------------------------------------------------

    @Test
    void surveyAnswersShowAndAreDetected() {
        ChatHistory.Message answer = surveyAnswer("s1", "Did you face any bugs in this run?",
                new ChatHistory.Field("Answer", "Other"), new ChatHistory.Field("Details", "fell through the floor"));
        assertNotNull(MenuChatFilter.surveyEmbed(answer));
        assertEquals(Set.of("s1"), shownIds(List.of(answer)));
    }

    @Test
    void reportEmbedsAreNotSurveys() {
        assertNull(MenuChatFilter.surveyEmbed(webhookEmbed("free", "🎮 Steve entered Free Play")));
        assertNull(MenuChatFilter.surveyEmbed(webhookEmbed("death", "💀 Steve")));
    }

    // --- display helpers --------------------------------------------------------

    @Test
    void mentionsPrettifyToDevTag() {
        assertEquals("hey @dev look at this", MenuChatFilter.prettifyMentions("hey <@123456789> look at this"));
        assertEquals("hey @dev!", MenuChatFilter.prettifyMentions("hey <@!42>!"));
        assertEquals("no mentions here", MenuChatFilter.prettifyMentions("no mentions here"));
        assertNull(MenuChatFilter.prettifyMentions(null));
    }

    @Test
    void previewTruncatesForTheUnreadCallout() {
        assertEquals("short", MenuChatFilter.preview("short", 10));
        assertEquals("exactly10!", MenuChatFilter.preview("exactly10!", 10));
        assertEquals("the third…", MenuChatFilter.preview("the third one, right after the tunnel", 10));
        assertEquals("hey @dev t…", MenuChatFilter.preview("hey <@123456789> the train vanished!", 10));
        assertEquals("…", MenuChatFilter.preview("   ", 10));
        assertEquals("…", MenuChatFilter.preview(null, 10));
    }

    @Test
    void timestampParsingIsLenient() {
        assertNotNull(MenuChatFilter.parseTimestamp(T0));
        assertNull(MenuChatFilter.parseTimestamp("not-a-time"));
        assertNull(MenuChatFilter.parseTimestamp(null));
    }
}
