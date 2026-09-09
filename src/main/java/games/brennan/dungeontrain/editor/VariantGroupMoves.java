package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.train.CarriageContentsGroup;

import java.util.Locale;
import java.util.Optional;

/**
 * Moving a sub-variant from one parent to another — the pure half, shared by the contents and the
 * dimensional-carriage {@code group move} commands.
 *
 * <p>A member's place in a group is data in the <b>parent's</b> sidecar and nowhere else, so a move
 * is two sidecar edits and no file ever moves. What matters is that the member record travels
 * whole: its weight, its inline gate and its Stage links are how often and where the room comes up,
 * and a reorganisation is not the moment to reroll any of that. The refusals mirror the ones
 * {@code group add} already makes (self, nesting, cycles); the command layer owns the messages.</p>
 *
 * <p>Two overloads rather than a generic, because the two group records are deliberately separate
 * types (see {@link TrackVariantGroup}) and the member records do not share a supertype.</p>
 */
public final class VariantGroupMoves {

    /** Why a move was refused, for the command layer to turn into a line the author can act on. */
    public enum Refusal {
        /** The child is not a member of the parent it was said to leave. */
        NOT_A_MEMBER,
        /** Source and target parent are the same group. */
        SAME_PARENT,
        /** The child would become a member of itself. */
        SELF,
        /** The target parent is itself a member of some group — one hop only. */
        TARGET_IS_CHILD,
        /** The child has sub-variants of its own — it cannot sit under another parent. */
        CHILD_IS_PARENT
    }

    /** The two rewritten groups, or a refusal. Exactly one of {@code refusal} / the pair is set. */
    public record TrackMove(Refusal refusal, TrackVariantGroup from, TrackVariantGroup to) {
        public boolean ok() { return refusal == null; }
        static TrackMove refused(Refusal r) { return new TrackMove(r, null, null); }
    }

    /** As {@link TrackMove}, for contents groups. */
    public record ContentsMove(Refusal refusal, CarriageContentsGroup from, CarriageContentsGroup to) {
        public boolean ok() { return refusal == null; }
        static ContentsMove refused(Refusal r) { return new ContentsMove(r, null, null); }
    }

    private VariantGroupMoves() {}

    /**
     * Move {@code child} from {@code fromGroup} (under {@code fromParent}) to {@code toGroup} (under
     * {@code toParent}), carrying its member record verbatim.
     *
     * @param toGroup       the target's current group, or empty when it has none yet
     * @param toParentIsChild whether the target parent is itself somebody's member (cycle guard)
     * @param childIsParent   whether the child has a group of its own (single-hop guard)
     */
    public static TrackMove move(String fromParent, TrackVariantGroup fromGroup,
                                 String toParent, Optional<TrackVariantGroup> toGroup,
                                 String child, boolean toParentIsChild, boolean childIsParent) {
        Refusal r = check(fromParent, toParent, child, toParentIsChild, childIsParent);
        if (r != null) return TrackMove.refused(r);
        Optional<TrackVariantGroup.Member> member = fromGroup == null
            ? Optional.empty() : fromGroup.member(child);
        if (member.isEmpty()) return TrackMove.refused(Refusal.NOT_A_MEMBER);
        TrackVariantGroup from = fromGroup.withoutMember(child);
        TrackVariantGroup to = toGroup.orElse(TrackVariantGroup.EMPTY).withMember(member.get());
        return new TrackMove(null, from, to);
    }

    /** Contents twin of {@link #move(String, TrackVariantGroup, String, Optional, String, boolean, boolean)}. */
    public static ContentsMove move(String fromParent, CarriageContentsGroup fromGroup,
                                    String toParent, Optional<CarriageContentsGroup> toGroup,
                                    String child, boolean toParentIsChild, boolean childIsParent) {
        Refusal r = check(fromParent, toParent, child, toParentIsChild, childIsParent);
        if (r != null) return ContentsMove.refused(r);
        Optional<CarriageContentsGroup.Member> member = fromGroup == null
            ? Optional.empty() : fromGroup.member(child);
        if (member.isEmpty()) return ContentsMove.refused(Refusal.NOT_A_MEMBER);
        CarriageContentsGroup from = fromGroup.withoutMember(child);
        CarriageContentsGroup to = toGroup.orElse(CarriageContentsGroup.EMPTY).withMember(member.get());
        return new ContentsMove(null, from, to);
    }

    private static Refusal check(String fromParent, String toParent, String child,
                                 boolean toParentIsChild, boolean childIsParent) {
        String f = norm(fromParent);
        String t = norm(toParent);
        String c = norm(child);
        if (t.equals(c)) return Refusal.SELF;
        if (f.equals(t)) return Refusal.SAME_PARENT;
        if (childIsParent) return Refusal.CHILD_IS_PARENT;
        if (toParentIsChild) return Refusal.TARGET_IS_CHILD;
        return null;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
