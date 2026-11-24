package games.brennan.dungeontrain.compat.photo;

import java.util.List;

/**
 * The reasons a player can give when reporting a found photo, as the wire strings the relay's
 * {@code POST /photos/report} accepts. {@link #HATE_SYMBOL} stands alone; the other two first ask a
 * topic, sent as {@code text:<topic>} or {@code other:<topic>}. The topics are the relay's canonical
 * moderation keys (dp-relay {@code moderation-categories.js}), minus the ones a photo reporter never
 * picks ({@code csam}, {@code grooming}, {@code political}, {@code spam}).
 *
 * <p>Photos only: book reports carry no reason and are untouched by this list.</p>
 */
public final class PhotoReportReason {

    public static final String HATE_SYMBOL = "hate_symbol";
    public static final String TEXT_PREFIX = "text:";
    public static final String OTHER_PREFIX = "other:";

    /** Picker order. Each topic's label is {@code gui.dungeontrain.photo_report.topic.<topic>}. */
    public static final List<String> TOPICS = List.of(
        "sexual", "self_harm", "pii", "violence", "hate", "harassment", "illicit", "profanity");

    /** Longest wire reason; bounds the packet string. */
    public static final int MAX_CHARS = 32;

    private PhotoReportReason() {}

    /** Whether {@code reason} is one the relay accepts. Anything else is dropped server-side. */
    public static boolean isValid(String reason) {
        if (reason == null) return false;
        if (HATE_SYMBOL.equals(reason)) return true;
        String topic = reason.startsWith(TEXT_PREFIX) ? reason.substring(TEXT_PREFIX.length())
            : reason.startsWith(OTHER_PREFIX) ? reason.substring(OTHER_PREFIX.length())
            : null;
        return topic != null && TOPICS.contains(topic);
    }
}
