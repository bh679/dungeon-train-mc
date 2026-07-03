package games.brennan.dungeontrain.client.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Pure classification rules that turn a player's full Discord thread backscroll into the
 * conversation-only view the title-screen panel shows. No Minecraft classes — unit-testable.
 *
 * <p>The thread is a firehose: DiscordPresence relays every in-game chat line, join line, death /
 * disconnect / Free Play / difficulty report, advancement embed, and survey answer into it. The menu
 * panel is a <em>conversation</em> surface, so only these survive (see {@link #filterHistory}):</p>
 *
 * <ul>
 *   <li><b>Human messages</b> (a real Discord-side person — not the bot, not a webhook echo). These are
 *       also the panel's reason to exist: no human message ⇒ no panel ({@link #hasDevHistory}).</li>
 *   <li><b>Player chat lines</b> (webhook, content-only) — but only when they tagged the dev (DP's
 *       {@code MentionTrigger.applyPings} bakes a {@code <@id>} mention into the relayed content), were
 *       sent from this menu (the outbox remembers delivered ids), or sit inside the
 *       {@link #CONVERSATION_WINDOW} around a human message (they're part of that exchange).</li>
 *   <li><b>Genuine survey answers</b> ({@code 📋 Feedback — …} embeds), rendered compactly by the list.</li>
 * </ul>
 *
 * <p>Everything else is hidden, <b>failing closed</b>: bot posts (advancements + state lines +
 * autoresponder flavor), any webhook embed that is not a survey answer (death / disconnect / Free Play /
 * difficulty — and whatever report DP grows next), join lines (recognised by DP's {@code 🎮 } join
 * templates — checked before the mention rule, since milestone-ping join lines contain a mention too),
 * and attachment posts (bug-report log bundles; the bug <em>survey answer</em> already shows).</p>
 */
public final class MenuChatFilter {

    /** How close (either side) a plain chat line must be to a human message to count as conversation. */
    static final Duration CONVERSATION_WINDOW = Duration.ofMinutes(10);

    /** A raw Discord user mention, as baked into relayed content by DP's {@code MentionTrigger}. */
    private static final Pattern MENTION = Pattern.compile("<@!?\\d+>");

    /** DP join/first-join templates start with this marker ({@code DEFAULT_JOIN_TEMPLATE}). */
    private static final String JOIN_MARKER = "🎮"; // 🎮

    private MenuChatFilter() {}

    /** A real Discord-side person — not the bot, not a webhook echo of the player. */
    public static boolean isHuman(ChatHistory.Message m) {
        return m != null && !m.isBot() && !m.isWebhook();
    }

    /** Whether anyone (dev / community) has ever written in this thread — the panel-visibility gate. */
    public static boolean hasDevHistory(List<ChatHistory.Message> messages) {
        if (messages == null) {
            return false;
        }
        for (ChatHistory.Message m : messages) {
            if (isHuman(m)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reduce a full thread backscroll to the conversation-only view, oldest→newest order preserved.
     *
     * @param isSentByMe whether a message id was a menu send delivered by this client's outbox — those
     *                   always show (they are, by definition, addressed to the dev)
     */
    public static List<ChatHistory.Message> filterHistory(List<ChatHistory.Message> messages,
                                                          Predicate<String> isSentByMe) {
        List<ChatHistory.Message> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        List<Instant> humanTimes = new ArrayList<>();
        for (ChatHistory.Message m : messages) {
            if (isHuman(m)) {
                Instant t = parseTimestamp(m.timestamp());
                if (t != null) {
                    humanTimes.add(t);
                }
            }
        }
        for (ChatHistory.Message m : messages) {
            if (shouldShow(m, humanTimes, isSentByMe)) {
                out.add(m);
            }
        }
        return out;
    }

    /** The full table of rules — see the class doc. */
    static boolean shouldShow(ChatHistory.Message m, List<Instant> humanTimes,
                              Predicate<String> isSentByMe) {
        if (m == null) {
            return false;
        }
        if (isHuman(m)) {
            return true;
        }
        if (isAutomatedReport(m)) {
            return false;
        }
        if (surveyEmbed(m) != null) {
            return true; // genuine survey answer — the list renders it compactly
        }
        if (m.hasEmbeds() || m.hasAttachments()) {
            // Fail closed: any non-survey webhook embed is some report (death/disconnect/…, or a future
            // one without a known title marker); attachment posts are bug-report log bundles.
            return false;
        }
        // Plain webhook content — an in-game chat line or a menu send.
        if (isJoinLine(m)) {
            return false; // before the mention rule: milestone-ping join lines carry a mention too
        }
        if (isSentByMe != null && m.id() != null && isSentByMe.test(m.id())) {
            return true;
        }
        if (m.content() != null && MENTION.matcher(m.content()).find()) {
            return true; // tagged the dev
        }
        Instant t = parseTimestamp(m.timestamp());
        if (t != null) {
            for (Instant h : humanTimes) {
                if (Duration.between(t, h).abs().compareTo(CONVERSATION_WINDOW) <= 0) {
                    return true; // part of the exchange around a human message
                }
            }
        }
        return false;
    }

    /**
     * Whether a thread message is an automated game report (advancement / difficulty / death /
     * join-leave) rather than conversation.
     *
     * <p>Two signals: DiscordPresence <b>bot</b> posts ({@code isBot} but not a webhook — advancement
     * embeds + the "Carriage +N · Difficulty Level M" state lines), and report <b>title markers</b>.
     * Colour can't be used: the difficulty report reuses the survey embed colour (both go through
     * {@code postSurveyResponse}), so only the {@code ⚔}/{@code 💀}/{@code 👋} title markers separate a
     * difficulty/death/leave report from a genuine survey answer (which carries a question title).</p>
     */
    public static boolean isAutomatedReport(ChatHistory.Message m) {
        if (m == null) {
            return false;
        }
        if (m.isBot() && !m.isWebhook()) {
            return true; // DP bot: advancement embeds + "Carriage +N · Difficulty Level M" lines
        }
        if (m.hasEmbeds()) {
            for (ChatHistory.Embed e : m.embeds()) {
                if (e != null && isReportTitle(e.title())) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isReportTitle(String title) {
        if (title == null) {
            return false;
        }
        String t = title.strip();
        return t.startsWith("⚔") || t.startsWith("💀") || t.startsWith("👋") || t.startsWith(JOIN_MARKER)
                || t.contains("reached Difficulty Level") || t.contains("left the game")
                || t.contains("entered Free Play");
    }

    /**
     * A relayed join / first-join line: plain webhook content built from DP's {@code 🎮 }-prefixed join
     * templates ("🎮 **Name** started the game …"). These are activity markers, not conversation, and a
     * version-milestone join line even pings the dev — so this is checked before the mention rule.
     */
    public static boolean isJoinLine(ChatHistory.Message m) {
        if (m == null || !m.isWebhook() || m.hasEmbeds() || m.hasAttachments() || m.content() == null) {
            return false;
        }
        String c = m.content().strip();
        return c.startsWith(JOIN_MARKER)
                || c.contains("started the game") || c.contains("joined the game for the first time");
    }

    /** The survey-answer embed on a message ("📋 Feedback — …"), or null when it isn't one. */
    public static ChatHistory.Embed surveyEmbed(ChatHistory.Message m) {
        if (m == null || !m.hasEmbeds()) {
            return null;
        }
        for (ChatHistory.Embed e : m.embeds()) {
            String t = e == null ? null : e.title();
            if (t != null && (t.strip().startsWith("📋") || t.contains("Feedback —"))) {
                return e;
            }
        }
        return null;
    }

    /**
     * Replace raw Discord user mentions ({@code <@123456789>}) with a readable {@code @dev} for panel
     * display — the id means nothing to the player, and the only mention DP bakes into relayed game
     * chat is the configured dev tag.
     */
    public static String prettifyMentions(String content) {
        if (content == null || content.indexOf('<') < 0) {
            return content;
        }
        return MENTION.matcher(content).replaceAll("@dev");
    }

    /** Discord ISO-8601 timestamp → {@link Instant}, or null when absent/unparseable. */
    static Instant parseTimestamp(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
