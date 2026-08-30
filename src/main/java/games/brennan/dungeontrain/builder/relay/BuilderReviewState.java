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

    private BuilderReviewState() {}

    /** Coerce a relay-supplied value. Anything absent, empty or unrecognised reads as never-asked. */
    public static String of(String review) {
        if (SUBMITTED.equals(review) || ACCEPTED.equals(review) || DECLINED.equals(review)) return review;
        return NONE;
    }

    /**
     * The caption under a submittable build's tile: where it stands, in one word.
     *
     * <p>Only three of the four states have one. A build nobody has submitted is described by where
     * it lives ({@code in_profile}), which is what the screen said before there was a queue at all —
     * "not submitted" would be the same fact told twice, and worse.</p>
     */
    public static String labelKeyFor(String review) {
        return switch (of(review)) {
            case SUBMITTED -> "gui.dungeontrain.builder.profile.review.submitted";
            case ACCEPTED -> "gui.dungeontrain.builder.profile.review.accepted";
            case DECLINED -> "gui.dungeontrain.builder.profile.review.declined";
            default -> null;
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
            default -> null;
        };
    }
}
