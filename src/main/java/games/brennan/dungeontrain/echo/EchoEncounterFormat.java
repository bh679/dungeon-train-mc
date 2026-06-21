package games.brennan.dungeontrain.echo;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a finished {@link EchoEncounter} into the Discord story — a title and a short third-person
 * narrative woven from the ordered beats plus the terminal {@link EndReason}. Pure string logic
 * (no Minecraft types), so it is unit-testable without a game bootstrap, like
 * {@code util.SecondPersonDeathMessage}.
 *
 * <p>The feed is public, so everything is third person: the player by name, the echo as "the echo".
 * The opener (the spawn) and the closer (the outcome) bracket the middle beats, which are joined into
 * one flowing paragraph in the order they happened.</p>
 */
final class EchoEncounterFormat {

    private EchoEncounterFormat() {}

    /** Embed title, e.g. "👁 Brennan met the echo of Steve". */
    static String title(String playerName, String sourceName) {
        return "👁 " + playerName + " met the echo of " + sourceName;
    }

    /** The full story body: opener · the beats · closer, separated by blank lines. */
    static String story(String playerName, EchoEncounter enc, EndReason reason) {
        StringBuilder out = new StringBuilder();
        out.append(opener(playerName, enc));

        String middle = middle(playerName, enc);
        if (!middle.isEmpty()) {
            out.append("\n\n").append(middle);
        }
        out.append("\n\n").append(closer(playerName, enc, reason));
        return out.toString();
    }

    private static String opener(String playerName, EchoEncounter enc) {
        String where = enc.sourceCarriage >= 0
                ? " — a soul lost around carriage " + enc.sourceCarriage
                : "";
        return "A remote echo of " + enc.sourceName + where
                + " stepped aboard " + playerName + "'s train.";
    }

    /** The middle beats (everything but SPAWNED), each as a sentence, joined into one paragraph. */
    private static String middle(String playerName, EchoEncounter enc) {
        List<String> lines = new ArrayList<>();
        for (EchoEvent beat : enc.beats()) {
            String s = sentence(beat, playerName, enc.sourceName);
            if (s != null) {
                lines.add(s);
            }
        }
        return String.join(" ", lines);
    }

    /** One sentence per middle beat; {@code null} for beats handled by the opener/closer. */
    private static String sentence(EchoEvent beat, String playerName, String sourceName) {
        return switch (beat) {
            case SPAWNED -> null; // the opener
            case MET -> "They crossed paths.";
            case EYE_CONTACT -> "Their eyes met across the carriage.";
            case PLAYER_CROUCHED -> playerName + " bowed low in greeting.";
            case ECHO_CROUCHED -> "The echo bowed back.";
            case PLAYER_STRUCK_ECHO -> playerName + " drew steel and struck the echo.";
            case ECHO_STRUCK_PLAYER -> "The echo struck back.";
            case GAVE_GIFT -> playerName + " pressed a gift into its hands.";
            case RECEIVED_GIFT -> "The echo offered a gift in return.";
            case PUSHED_OFF_TRAIN -> playerName + " shoved it from the carriage.";
            case FELL_OFF_TRAIN -> "It lost its footing and slipped from the train.";
        };
    }

    private static String closer(String playerName, EchoEncounter enc, EndReason reason) {
        String source = enc.sourceName;
        return switch (reason) {
            case ECHO_SLAIN_BY_YOU -> "And there, by " + playerName + "'s hand, the echo of " + source + " fell once more.";
            case ECHO_SLAIN -> "The echo of " + source + " fell.";
            case YOU_SLAIN_BY_ECHO -> "The echo of " + source + " struck " + playerName + " down.";
            case YOU_DIED -> playerName + " fell, and the echo of " + source + " wandered on.";
            case LEFT_BEHIND -> "The train rolled on, and the echo of " + source + " was left behind.";
        };
    }
}
