package games.brennan.dungeontrain.echo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Turns a finished Death Note {@link EchoEncounter} into the compact journal the relay stores against
 * the curse ({@code POST /deathnotes/outcome} → {@code encounter}), so the curse's AUTHOR — who was
 * never there, and whose game never saw any of it — can be told the story in their cursed welcome
 * book.
 *
 * <p>Structured, not prose: the beats are enum names and the gear is already-rendered descriptors, so
 * the book can be written in the reader's own voice ({@code CursedStoryNarrative}) rather than
 * shipping the third-person Discord sentence across the wire.</p>
 *
 * <p><b>What is deliberately absent:</b> the target's chat line. The Discord story may quote it to the
 * player who was in the room; the author of the curse is a stranger to that conversation, so it never
 * enters this payload.</p>
 *
 * <p>Pure — no Minecraft types, no network — so the shape is unit-testable.</p>
 */
public final class CursedEncounterPayload {

    /** Beats carried at most; a fight with more distinct moments than this is already a long story. */
    static final int MAX_BEATS = 12;
    /** Gear descriptors carried per list. */
    static final int MAX_ITEMS = 4;
    /** Character cap per descriptor — matches the Discord story's own item rendering budget. */
    static final int MAX_DESCRIPTOR_CHARS = 120;

    private CursedEncounterPayload() {}

    /**
     * Build the journal object for {@code enc} ending in {@code reason}.
     *
     * @param beats    the ordered beat names ({@link EchoEvent#name()})
     * @param items    descriptors of the gear the echo carried when it arrived
     * @param acquired descriptors of the gear it took along the way
     * @param reason   how the encounter ended
     * @param seconds  how long it lasted, in whole seconds (clamped at 0)
     */
    public static JsonObject build(List<String> beats, List<String> items, List<String> acquired,
                                   EndReason reason, long seconds) {
        JsonObject out = new JsonObject();
        out.add("beats", strings(beats, MAX_BEATS));
        out.add("items", strings(items, MAX_ITEMS));
        out.add("acquired", strings(acquired, MAX_ITEMS));
        out.addProperty("end", reason == null ? "" : reason.name());
        out.addProperty("sec", Math.max(0L, seconds));
        return out;
    }

    /**
     * The reported ending for {@code reason}, or {@code null} when the curse has no verdict: the
     * target died to something else, or the train simply rolled on and left the echo behind. Those
     * still ship a journal — the story of a curse that went nowhere is worth telling — they just
     * don't claim an outcome.
     */
    public static String outcomeFor(EndReason reason) {
        if (reason == null) return null;
        return switch (reason) {
            case ECHO_SLAIN_BY_YOU, ECHO_SLAIN -> "target_killed_echo";
            case YOU_SLAIN_BY_ECHO -> "echo_killed_target";
            case YOU_DIED, LEFT_BEHIND -> null;
        };
    }

    /** Clamp a descriptor list to {@code max} entries, each trimmed to {@link #MAX_DESCRIPTOR_CHARS}. */
    private static JsonArray strings(List<String> values, int max) {
        JsonArray arr = new JsonArray();
        if (values == null) return arr;
        for (String value : values) {
            if (arr.size() >= max) break;
            if (value == null || value.isBlank()) continue;
            String s = value.strip();
            arr.add(s.length() <= MAX_DESCRIPTOR_CHARS ? s : s.substring(0, MAX_DESCRIPTOR_CHARS));
        }
        return arr;
    }
}
