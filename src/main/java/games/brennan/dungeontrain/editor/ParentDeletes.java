package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.train.CarriageContentsGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Deleting a sub-variant <b>parent</b> — the pure half, shared by the contents and the
 * dimensional-carriage {@code reset} commands.
 *
 * <p>A parent's group sidecar is the only place its members' weights, gates and Stage links live
 * (see {@link VariantGroupMoves}), so deleting the parent file alone strands them: the members
 * survive as top-level templates with none of that. The author picks what happens instead:</p>
 * <ul>
 *   <li>{@link Mode#ALL} — the members go with the parent.</li>
 *   <li>{@link Mode#UNPARENT} — each member becomes a top-level template carrying its member
 *       record as its own weights entry ({@link #unparentContents} / {@link #unparentTrack}).</li>
 *   <li>{@link Mode#PROMOTE_FIRST} — the first member takes the parent's place at the head of the
 *       group ({@link #promoteContents} / {@link #promoteTrack}).</li>
 * </ul>
 *
 * <p>Two overloads per operation rather than a generic, because the two group records are
 * deliberately separate types and their member records share no supertype.</p>
 */
public final class ParentDeletes {

    /** What to do with a parent's sub-variants when the parent is deleted. */
    public enum Mode {
        /** Delete every sub-variant along with the parent. */
        ALL("all"),
        /** Keep the sub-variants as top-level templates. */
        UNPARENT("unparent"),
        /** The first sub-variant becomes the parent of the rest. */
        PROMOTE_FIRST("promote");

        private final String literal;

        Mode(String literal) { this.literal = literal; }

        /** The word the command tree spells this mode as. */
        public String literal() { return literal; }

        /** The mode named by {@code word}, or empty for anything else (including null). */
        public static Optional<Mode> parse(String word) {
            if (word == null) return Optional.empty();
            String w = word.trim().toLowerCase(Locale.ROOT);
            for (Mode m : values()) if (m.literal.equals(w)) return Optional.of(m);
            return Optional.empty();
        }

        /** {@code all|unparent|promote} — for the refusal line a bare reset on a parent gets. */
        public static String literals() {
            StringBuilder sb = new StringBuilder();
            for (Mode m : values()) {
                if (sb.length() > 0) sb.append('|');
                sb.append(m.literal);
            }
            return sb.toString();
        }
    }

    /**
     * A member's record recast as a top-level weights entry. {@code stageId} is the member's first
     * Stage link (a top-level entry links to one Stage where a member may link to several);
     * {@code droppedStages} counts the links that did not fit.
     */
    public record TopLevel(String id, int weight, TemplateGate gate, String stageId, int droppedStages) {
        static TopLevel of(String id, int weight, TemplateGate gate, List<String> stageIds) {
            String first = stageIds.isEmpty() ? null : stageIds.get(0);
            return new TopLevel(id, weight, gate, first, Math.max(0, stageIds.size() - 1));
        }
    }

    /**
     * The first member's promotion: it becomes {@code newParent}; the rest of the old group becomes
     * its group, with the promoted member's former weight as the new {@code selfWeight} so the draw
     * ratios among the former siblings are unchanged. {@code group} is empty when the old group had a
     * single member — the promoted member is then a plain leaf and no sidecar is written.
     */
    public record ContentsPromotion(String newParent, Optional<CarriageContentsGroup> group) {}

    /** Track-side twin of {@link ContentsPromotion}. */
    public record TrackPromotion(String newParent, Optional<TrackVariantGroup> group) {}

    private ParentDeletes() {}

    // ---------- unparent ----------

    /** Each member of {@code group} as the top-level entry it should become. Sidecar order. */
    public static List<TopLevel> unparentContents(CarriageContentsGroup group) {
        List<TopLevel> out = new ArrayList<>(group.members().size());
        for (CarriageContentsGroup.Member m : group.members()) {
            out.add(TopLevel.of(m.id(), m.weight(), m.gate(), m.stageIds()));
        }
        return List.copyOf(out);
    }

    /** Track-side twin of {@link #unparentContents}. */
    public static List<TopLevel> unparentTrack(TrackVariantGroup group) {
        List<TopLevel> out = new ArrayList<>(group.members().size());
        for (TrackVariantGroup.Member m : group.members()) {
            out.add(TopLevel.of(m.id(), m.weight(), m.gate(), m.stageIds()));
        }
        return List.copyOf(out);
    }

    // ---------- promote first ----------

    /** @return empty when {@code group} has no members (nothing to promote) */
    public static Optional<ContentsPromotion> promoteContents(CarriageContentsGroup group) {
        if (group.members().isEmpty()) return Optional.empty();
        CarriageContentsGroup.Member first = group.members().get(0);
        CarriageContentsGroup rest = group.withoutMember(first.id()).withSelfWeight(first.weight());
        return Optional.of(new ContentsPromotion(first.id(),
            rest.members().isEmpty() ? Optional.empty() : Optional.of(rest)));
    }

    /** Track-side twin of {@link #promoteContents}. */
    public static Optional<TrackPromotion> promoteTrack(TrackVariantGroup group) {
        if (group.members().isEmpty()) return Optional.empty();
        TrackVariantGroup.Member first = group.members().get(0);
        TrackVariantGroup rest = group.withoutMember(first.id()).withSelfWeight(first.weight());
        return Optional.of(new TrackPromotion(first.id(),
            rest.members().isEmpty() ? Optional.empty() : Optional.of(rest)));
    }
}
