package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.net.relay.DonationSummaryClient.Goal;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reading the relay's support ladder for the death screen.
 *
 * <p>The support page used to show one open-ended figure — this month's raised over the running
 * cost — which past 100% just climbed, reading as "you already gave too much" instead of as an
 * ask. The relay now serves an ordered ladder instead (funding-goals.js): running costs first,
 * then the next thing the project is trying to fund. This class decides which rung the screen is
 * asking against and how to name it.</p>
 *
 * <p><b>Why the relay owns the ladder.</b> Shipped jars can never be updated, so goals baked into
 * one would freeze on release day. Served from the relay, they can be retuned — or a new rung
 * added — with a deploy. The cost of that is that a jar can meet a goal id it has never heard of,
 * which {@link #label} handles: a known id gets this jar's translation, an unknown one falls back
 * to the relay's own English label, so a later goal still renders rather than showing blank.</p>
 *
 * <p>Everything here tolerates an empty ladder — an older relay, or one with no cost snapshot to
 * build the first rung from. The screen then draws the coverage percentage exactly as it did
 * before goals existed.</p>
 */
public final class FundingGoals {

    /**
     * The first rung, which the death screen gives a dedicated tile rather than treating as one
     * goal among many: it is the bill that has to be paid before anything else can be, so the
     * screen shows it whether it is the current ask or already settled.
     */
    public static final String RUNNING_COSTS = "running_costs";

    /** Translation-key stem; a goal's id is appended (e.g. {@code …goal_running_costs}). */
    private static final String LABEL_KEY_PREFIX = "gui.dungeontrain.death.narr.goal_";
    /** Per-goal tooltip stem, same id suffix. Falls back to the generic coverage tooltip. */
    private static final String TIP_KEY_PREFIX = "gui.dungeontrain.death.narr.tip_goal_";
    private static final String TIP_FALLBACK = "gui.dungeontrain.death.narr.tip_covered";

    private FundingGoals() {}

    /**
     * The rung to put in front of the player: the one the relay named active, else the first
     * incomplete one, else the last (every goal funded — the screen shows the final rung standing
     * complete rather than going blank on the project's best month). Null when there is no ladder.
     */
    public static Goal active(List<Goal> goals, String activeGoalId) {
        if (goals == null || goals.isEmpty()) return null;
        if (activeGoalId != null && !activeGoalId.isBlank()) {
            for (Goal g : goals) {
                if (activeGoalId.equals(g.id())) return g;
            }
        }
        for (Goal g : goals) {
            if (!g.complete()) return g;
        }
        return goals.get(goals.size() - 1);
    }

    /** The rung with this id, or null — the screen tiles {@link #RUNNING_COSTS} specifically. */
    public static Goal byId(List<Goal> goals, String id) {
        if (goals == null || id == null) return null;
        for (Goal g : goals) {
            if (id.equals(g.id())) return g;
        }
        return null;
    }

    /**
     * The goals already funded, in ladder order, EXCLUDING any the caller already draws a tile for
     * — a goal shown as a tile and ticked off above it would be the same goal twice. With the
     * standard two-rung ladder that leaves nothing, and the ✓ line disappears entirely; it exists
     * for the case where the relay has added rungs beyond the two the grid has slots for.
     */
    public static List<Goal> completed(List<Goal> goals, Collection<String> tiledIds) {
        List<Goal> out = new ArrayList<>();
        if (goals == null) return out;
        for (Goal g : goals) {
            if (g.complete() && (tiledIds == null || !tiledIds.contains(g.id()))) out.add(g);
        }
        return out;
    }

    /** The translation key a goal id would use, whether or not this jar has it. */
    public static String labelKey(String id) {
        return LABEL_KEY_PREFIX + id;
    }

    /**
     * A goal's display name: this jar's translation when it knows the id, otherwise the relay's
     * own label verbatim (untranslated, but present — see the class note on relay-added goals).
     */
    public static Component label(Goal goal) {
        String key = labelKey(goal.id());
        if (Language.getInstance().has(key)) return Component.translatable(key);
        String fallback = goal.label();
        return Component.literal(fallback == null || fallback.isBlank() ? goal.id() : fallback);
    }

    /**
     * The tooltip key for a goal's tile — its own if this jar has one, else the generic
     * "what is this percentage?" text, which is true of any rung.
     */
    public static String tipKey(Goal goal) {
        String key = TIP_KEY_PREFIX + goal.id();
        return Language.getInstance().has(key) ? key : TIP_FALLBACK;
    }
}
