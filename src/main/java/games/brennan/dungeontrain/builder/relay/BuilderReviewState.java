package games.brennan.dungeontrain.builder.relay;

/**
 * Where one of a player's builds stands in the operator's submission queue — the mod's side of the
 * relay's {@code review} column.
 *
 * <p>A second axis, deliberately not the moderation {@code flag}. The flag answers "is this content
 * acceptable" and is mostly a screening routine's read of the build's scraped text; this answers
 * "has a person accepted this build into the game", which only the operator decides. The two are
 * independent: the ordinary case for a submitted build is a clean flag and a pending verdict, and
 * conflating them would tell a player waiting their turn that something is wrong with their work.</p>
 *
 * <p>Strings rather than an enum, because they cross the wire from a relay that deploys on its own
 * schedule: a state added there before the mod ships has to degrade to {@link #NONE} rather than
 * throw at a player. {@link #of} is that gate, and every read goes through it.</p>
 */
public final class BuilderReviewState {

    /** Never submitted. What every build is on upload, and what a play capture always is. */
    public static final String NONE = "none";
    /** The author pressed Submit for Review and is waiting on a person. */
    public static final String SUBMITTED = "submitted";
    /** Accepted into the game — the state a builder build must reach before a train can hold it. */
    public static final String ACCEPTED = "accepted";
    /** Looked at and turned down. The build stays in its author's profile. */
    public static final String DECLINED = "declined";
    /**
     * Looked at and sent back with notes — neither in nor out. The reviewer wants changes and has said
     * which ({@code reviewComment}); editing and submitting again puts it back in the queue.
     */
    public static final String FEEDBACK = "feedback";
    /**
     * Sent back to be re-saved and re-submitted from a particular Dungeon Train version — the build was
     * made on one with a bug or a missing block. The relay holds it here until a submit arrives from a
     * client on that version ({@code reviewVersion} + {@code reviewVersionOp}).
     */
    public static final String RESUBMIT = "resubmit";

    /** How a resubmit rule reads its version: this one, this one or newer, this one or older. */
    public static final String OP_EXACT = "exact";
    public static final String OP_GTE = "gte";
    public static final String OP_LTE = "lte";

    /** Coerce a relay-supplied op; anything unknown reads as "or above", the commonest rule. */
    public static String opOf(String op) {
        return OP_EXACT.equals(op) || OP_LTE.equals(op) ? op : OP_GTE;
    }

    /** A plausible Dungeon Train version: dotted numbers like {@code 0.1130.0}. */
    public static boolean isDottedVersion(String v) {
        return v != null && v.strip().matches("\\d+(\\.\\d+){0,3}([-+][0-9A-Za-z.+-]*)?") && v.strip().length() <= 40;
    }

    /** "0.1130.0 or above" — a resubmit rule in the player's language; empty when there is no rule. */
    public static net.minecraft.network.chat.MutableComponent ruleText(String version, String op) {
        if (version == null || version.isBlank()) return net.minecraft.network.chat.Component.empty();
        String key = switch (opOf(op)) {
            case OP_EXACT -> "gui.dungeontrain.builder.profile.review.rule_exact";
            case OP_LTE -> "gui.dungeontrain.builder.profile.review.rule_lte";
            default -> "gui.dungeontrain.builder.profile.review.rule_gte";
        };
        return net.minecraft.network.chat.Component.translatable(key, version.strip());
    }

    private BuilderReviewState() {}

    /** Coerce a relay-supplied value. Anything absent, empty or unrecognised reads as never-asked. */
    public static String of(String review) {
        if (SUBMITTED.equals(review) || ACCEPTED.equals(review) || DECLINED.equals(review)
                || FEEDBACK.equals(review) || RESUBMIT.equals(review)) return review;
        return NONE;
    }

    /** Waiting on a person: the blue of Minecraft's own §b, which reads as "in progress", not "wrong". */
    public static final int BORDER_SUBMITTED = 0xFF55AAFF;
    /** In the game — §a. */
    public static final int BORDER_ACCEPTED = 0xFF55FF55;
    /** Turned down — §c. */
    public static final int BORDER_DECLINED = 0xFFFF5555;
    /** Sent back with notes — §e, the yellow of "look at this", between waiting's blue and declined's red. */
    public static final int BORDER_FEEDBACK = 0xFFFFFF55;
    /** Sent back for a version — §6 gold, between feedback's yellow and declined's red. */
    public static final int BORDER_RESUBMIT = 0xFFFFAA00;
    /** No colour: the tile keeps the ordinary border every other builder grid draws. */
    public static final int BORDER_NONE = 0;

    /**
     * The colour to ring a build's tile with, so "which of mine are in, out, or waiting" is answerable
     * without reading a single caption — which is the question My Builds exists to answer.
     *
     * <p>A never-submitted build gets {@link #BORDER_NONE} rather than a fourth colour. Most builds
     * are in that state most of the time, and colouring them too would turn the wall into noise and
     * leave the three that mean something with nothing to stand out against.</p>
     */
    public static int borderColourFor(String review) {
        return switch (of(review)) {
            case SUBMITTED -> BORDER_SUBMITTED;
            case ACCEPTED -> BORDER_ACCEPTED;
            case DECLINED -> BORDER_DECLINED;
            case FEEDBACK -> BORDER_FEEDBACK;
            case RESUBMIT -> BORDER_RESUBMIT;
            default -> BORDER_NONE;
        };
    }

    /**
     * The line under the grid explaining a state that needs more than a word — or null when it
     * doesn't. Waiting and declined both leave a player looking at a build that never appears in
     * anyone's train, and this screen is the only place either can be explained.
     */
    public static String noteKeyFor(String review) {
        return switch (of(review)) {
            case SUBMITTED -> "gui.dungeontrain.builder.profile.review.submitted_note";
            case DECLINED -> "gui.dungeontrain.builder.profile.review.declined_note";
            case FEEDBACK -> "gui.dungeontrain.builder.profile.review.feedback_note";
            // Takes the rule as its one argument — see ruleText; callers pass it.
            case RESUBMIT -> "gui.dungeontrain.builder.profile.review.resubmit_note";
            default -> null;
        };
    }
}
