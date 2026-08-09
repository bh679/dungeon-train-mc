package games.brennan.dungeontrain.narrative;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a landed curse's encounter journal into the prose the cursed welcome book reads —
 * <b>second person, addressed to the curser</b>.
 *
 * <p>The same beats become a third-person bulletin in the Discord feed
 * ({@code EchoEncounterFormat}: "A remote echo of Brennan stepped aboard Dev's train"). Here the
 * reader is the one who wrote the name, and was not there: the echo is "your echo", the other player
 * is named, and every sentence is something that happened in a world the reader will never see.</p>
 *
 * <p>Pure string logic over {@link CursedStoryPool.Encounter} — no Minecraft types, no network — so it
 * is unit-testable, and it degrades to an empty string whenever the journal is missing (an old relay,
 * a target who logged out mid-fight). Books must therefore treat {@code %STORY%} as optional garnish,
 * not as the page's only content.</p>
 */
public final class CursedStoryNarrative {

    private CursedStoryNarrative() {}

    /**
     * The full account: what your echo carried, what the two of them did, what it took, and how it
     * ended — one sentence per beat, joined into a paragraph. Empty when there is no journal.
     */
    public static String story(CursedStoryPool.Story s) {
        CursedStoryPool.Encounter enc = s.encounter();
        if (enc == null) return "";
        List<String> parts = new ArrayList<>();
        String gear = gear(s);
        if (!gear.isEmpty()) parts.add(gear);
        for (String beat : enc.beats()) {
            String line = sentence(beat, target(s));
            if (line != null) parts.add(line);
        }
        String claimed = claimed(enc);
        if (!claimed.isEmpty()) parts.add(claimed);
        String ending = ending(s);
        if (!ending.isEmpty()) parts.add(ending);
        return String.join(" ", parts);
    }

    /** What your echo was still carrying when it found them, or empty when it carried nothing of note. */
    public static String gear(CursedStoryPool.Story s) {
        CursedStoryPool.Encounter enc = s.encounter();
        if (enc == null || enc.items().isEmpty()) return "";
        if (enc.items().size() == 1) {
            return "It still carried " + enc.items().get(0) + ".";
        }
        return "It still carried " + enc.items().get(0) + " and " + enc.items().get(1) + ".";
    }

    /** How it ended, in one sentence, or empty when nothing was ever reported. */
    public static String ending(CursedStoryPool.Story s) {
        CursedStoryPool.Encounter enc = s.encounter();
        String end = enc == null ? "" : enc.end();
        String target = target(s);
        return switch (end) {
            case "ECHO_SLAIN_BY_YOU" -> target + " killed it.";
            case "ECHO_SLAIN" -> "Something else killed it before " + target + " could.";
            case "YOU_SLAIN_BY_ECHO" -> "It killed " + target + ".";
            case "YOU_DIED" -> target + " died to something else entirely, and your echo wandered on.";
            case "LEFT_BEHIND" -> "The train rolled on and left your echo behind.";
            default -> fallbackEnding(s, target);
        };
    }

    /**
     * The ending when there is no journal to read it from: fall back to the bare outcome the target's
     * game reported, and to honest silence when even that never arrived.
     */
    private static String fallbackEnding(CursedStoryPool.Story s, String target) {
        return switch (s.outcome() == null ? "" : s.outcome()) {
            case "target_killed_echo" -> target + " killed it.";
            case "echo_killed_target" -> "It killed " + target + ".";
            default -> "";
        };
    }

    /** What your echo took off the train along the way, or empty when it gained nothing. */
    private static String claimed(CursedStoryPool.Encounter enc) {
        List<String> gained = enc.acquired();
        if (gained.isEmpty()) return "";
        if (gained.size() == 1) {
            return "Along the way it took " + gained.get(0) + ".";
        }
        String last = gained.get(gained.size() - 1);
        String rest = String.join(", ", gained.subList(0, gained.size() - 1));
        return "Along the way it took " + rest + ", and then " + last + ".";
    }

    /**
     * One sentence per beat. {@code null} for beats the opener or the ending already covers, and for
     * any beat name a future build sends that this one doesn't know — an unknown beat is skipped, never
     * printed raw into a book.
     */
    private static String sentence(String beat, String target) {
        return switch (beat) {
            case "SPAWNED" -> null;                      // the arrival is the book's own framing
            case "MET" -> "They crossed paths.";
            case "EYE_CONTACT" -> target + " looked it in the face — your face.";
            case "PLAYER_CROUCHED" -> target + " bowed to it.";
            case "ECHO_CROUCHED" -> "It bowed back, with your manners.";
            case "PLAYER_STRUCK_ECHO" -> target + " struck first.";
            case "ECHO_STRUCK_PLAYER" -> "It struck back.";
            case "GAVE_GIFT" -> target + " pressed a gift into its hands.";
            case "RECEIVED_GIFT" -> "It gave them something in return.";
            case "CHAT" -> target + " spoke to it. You will never know what was said.";
            case "PUSHED_OFF_TRAIN" -> target + " shoved it off the carriage.";
            case "FELL_OFF_TRAIN" -> "It lost its footing and slipped from the train.";
            default -> null;
        };
    }

    /** The cursed player's name, or a stand-in when the relay never had one. */
    private static String target(CursedStoryPool.Story s) {
        String name = s.targetName();
        return name == null || name.isBlank() ? CursedBookFactory.UNKNOWN_TARGET : name;
    }
}
