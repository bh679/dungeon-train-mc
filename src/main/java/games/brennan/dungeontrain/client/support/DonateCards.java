package games.brennan.dungeontrain.client.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Which cards the death screen's donation page draws, and where.
 *
 * <p>The grid is four cells. Two are fixed: the <b>ask</b> leads (top-left) and <b>Contribute</b>
 * closes (bottom-right). The two cells between them are the experiment's — an arm names which two
 * of five cards fill them, and nothing else about the page varies. The ask is never a variable:
 * an arm that could remove it would produce a page that asks for money without saying what for,
 * and Contribute never moves because it is the conversion event being measured — a button that
 * changed position between arms would confound its own result.</p>
 *
 * <p>This class is pure and Minecraft-free so the layout is unit-testable: the arm table below is
 * the thing that decides what every player sees, and it should be readable and provable without a
 * running client.</p>
 */
public final class DonateCards {

    /** The five cards an arm can place. The ask and Contribute are not here — they never vary. */
    public enum Card {
        /** "63%" — how much of the ask is covered so far. */
        COVERED,
        /** "765 / Updates this week" — releases shipped (see {@link UpdateStats}). */
        UPDATES,
        /** "1,240 / Hours Spent" — clock hours with a commit in them (see {@link DevHours}). */
        HOURS,
        /** "3 hours / Last Active" — how long ago work last landed (see {@link LastActive}). */
        LAST_ACTIVE,
        /** "$265 / Raised" — this month's takings. */
        RAISED,
    }

    /**
     * The arms of {@code donate_cards_v1}, matching dp-relay {@code experiments.js}.
     *
     * <p>A cyclic rotation of adjacent pairs over [covered, updates, hours, last_active, raised]:
     * every card appears in exactly two arms, so each is seen by 40% of players, and every pair
     * occurs at most once, so no two cards are permanently confounded. {@code a_covered_updates}
     * is the page as it shipped, which puts the control inside the rotation rather than beside
     * it.</p>
     *
     * <p>Ids name the cards rather than a hypothesis: hypotheses get revised, but what an arm drew
     * is a fact, and it is what a funnel row has to stay readable against a year later.</p>
     */
    public enum Arm {
        A_COVERED_UPDATES("a_covered_updates", Card.COVERED, Card.UPDATES),
        B_UPDATES_HOURS("b_updates_hours", Card.UPDATES, Card.HOURS),
        C_HOURS_ACTIVE("c_hours_active", Card.HOURS, Card.LAST_ACTIVE),
        D_ACTIVE_RAISED("d_active_raised", Card.LAST_ACTIVE, Card.RAISED),
        E_RAISED_COVERED("e_raised_covered", Card.RAISED, Card.COVERED);

        private final String id;
        private final Card first;
        private final Card second;

        Arm(String id, Card first, Card second) {
            this.id = id;
            this.first = first;
            this.second = second;
        }

        public String id() {
            return id;
        }

        public List<Card> cards() {
            return List.of(first, second);
        }
    }

    /**
     * The layout every player gets when no experiment applies — offline, an old relay, a relay
     * that has ended the experiment, or an arm this jar cannot draw. Identical to
     * {@link Arm#A_COVERED_UPDATES}, which is the page as it shipped.
     */
    public static final Arm CONTROL = Arm.A_COVERED_UPDATES;

    private DonateCards() {}

    /** Every arm id this jar can draw — handed to {@code DonateExperiment.resolve}. */
    public static List<String> knownArms() {
        List<String> out = new ArrayList<>(Arm.values().length);
        for (Arm a : Arm.values()) out.add(a.id());
        return List.copyOf(out);
    }

    /** The arm with this id, or {@link #CONTROL} for null or anything unrecognised. */
    public static Arm armOf(String id) {
        if (id == null || id.isBlank()) return CONTROL;
        for (Arm a : Arm.values()) {
            if (a.id().equals(id)) return a;
        }
        return CONTROL;
    }

    /**
     * The two variable cards for {@code arm}, minus any whose data this client does not have.
     *
     * <p>An absent card leaves its cell EMPTY rather than being replaced by one of the others.
     * Substituting would make an arm's identity depend on what happened to be available, so a
     * player recorded as being in {@code c_hours_active} might have seen a raised figure instead —
     * and the funnel row would be describing a page nobody was assigned.</p>
     *
     * @param arm         the assigned arm
     * @param availability which cards have something true to show right now
     */
    public static List<Card> slots(Arm arm, Availability availability) {
        List<Card> out = new ArrayList<>(2);
        for (Card c : arm.cards()) {
            if (availability.has(c)) out.add(c);
        }
        return List.copyOf(out);
    }

    /**
     * Whether each card has a figure worth drawing. Every one of these is a real "unknown" case,
     * not a preference: a jar built without an hour count, a relay too old to serve updates or an
     * activity timestamp, a ladder with no percentage to quote. An unknown card is withheld rather
     * than shown as a zero — the rule the page has followed since the updates card was added.
     */
    public record Availability(boolean covered, boolean updates, boolean hours,
                               boolean lastActive, boolean raised) {

        /** Everything available — the ordinary case, and the one the layout tests assume. */
        public static Availability all() {
            return new Availability(true, true, true, true, true);
        }

        public boolean has(Card card) {
            return switch (card) {
                case COVERED -> covered;
                case UPDATES -> updates;
                case HOURS -> hours;
                case LAST_ACTIVE -> lastActive;
                case RAISED -> raised;
            };
        }
    }
}
