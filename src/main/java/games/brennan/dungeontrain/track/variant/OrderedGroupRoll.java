package games.brennan.dungeontrain.track.variant;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;

/**
 * The draw over a group's sub-variants once one player's visits are taken into account — ordered
 * steps, "seen again" and the weight that changes after the Xth visit ({@link VisitRules}).
 *
 * <ol>
 *   <li>A member that may not be seen again and has been visited is dropped.</li>
 *   <li>The current step is the lowest {@code order} none of whose members has been visited. While
 *       one stands, only its remaining members draw and the parent's own share is left out. A step
 *       with nothing left (gated out, or not seen again) is skipped.</li>
 *   <li>With every step done, every remaining member draws at its weight (its later weight past
 *       {@code afterVisits}) against the parent's {@code selfWeight}.</li>
 * </ol>
 *
 * <p>A group with no rules draws exactly what {@link TrackVariantGroup#pick} draws, consuming the
 * same single {@code nextInt} — so worlds whose groups never use these settings keep their rooms.
 * No Minecraft types.</p>
 */
public final class OrderedGroupRoll {

    private OrderedGroupRoll() {}

    /** One member at the weight it draws at for this player. */
    public record Weighted(TrackVariantGroup.Member member, int weight) {}

    /** What the draw is over: the members at their weights, and the parent's own share. */
    public record Pool(List<Weighted> members, int selfWeight) {}

    /**
     * The pool {@code eligible} draws from for a player with {@code visits}. {@code visits} answers
     * a member id's visit count (0 when never visited). {@code allMembers} is the whole group,
     * gated-out members included: a step finished through a member this context gates out is still
     * finished.
     */
    public static Pool pool(int selfWeight, List<TrackVariantGroup.Member> eligible,
                            List<TrackVariantGroup.Member> allMembers, ToIntFunction<String> visits) {
        List<TrackVariantGroup.Member> remaining = new ArrayList<>(eligible.size());
        for (TrackVariantGroup.Member m : eligible) {
            if (!m.visits().seenAgain() && visits.applyAsInt(m.id()) > 0) continue;
            remaining.add(m);
        }

        int step = currentStep(remaining, allMembers, visits);
        List<Weighted> out = new ArrayList<>(remaining.size());
        for (TrackVariantGroup.Member m : remaining) {
            if (step > 0 && m.visits().order() != step) continue;
            out.add(new Weighted(m, m.visits().weightAfter(m.weight(), visits.applyAsInt(m.id()))));
        }
        return new Pool(List.copyOf(out), step > 0 ? 0 : selfWeight);
    }

    /**
     * The lowest step with a member left to draw and none of its members visited, or 0 when every
     * step is done. A step counts as done when any member of it — eligible here or not — has been
     * visited, so a lobby seen at another level is not asked for again.
     */
    static int currentStep(List<TrackVariantGroup.Member> remaining, List<TrackVariantGroup.Member> allMembers,
                           ToIntFunction<String> visits) {
        int best = 0;
        for (TrackVariantGroup.Member m : remaining) {
            int order = m.visits().order();
            if (order == 0 || (best != 0 && order >= best)) continue;
            if (!stepVisited(order, allMembers, visits)) best = order;
        }
        return best;
    }

    private static boolean stepVisited(int order, List<TrackVariantGroup.Member> members,
                                       ToIntFunction<String> visits) {
        for (TrackVariantGroup.Member m : members) {
            if (m.visits().order() == order && visits.applyAsInt(m.id()) > 0) return true;
        }
        return false;
    }

    /**
     * Weighted draw over {@code pool} — the same shape as {@link TrackVariantGroup#pick}: one
     * {@code nextInt(total)}, the self share first. {@code null} means "the parent itself" (the self
     * share won, the pool is empty, or every weight is 0).
     */
    public static TrackVariantGroup.Member pick(Random rng, Pool pool) {
        if (pool.members().isEmpty()) return null;
        int total = pool.selfWeight();
        for (Weighted w : pool.members()) total += w.weight();
        if (total <= 0) return null;
        int r = rng.nextInt(total);
        if (r < pool.selfWeight()) return null;
        int cumulative = pool.selfWeight();
        for (Weighted w : pool.members()) {
            cumulative += w.weight();
            if (r < cumulative) return w.member();
        }
        return pool.members().get(pool.members().size() - 1).member();
    }
}
