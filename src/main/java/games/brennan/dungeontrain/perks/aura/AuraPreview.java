package games.brennan.dungeontrain.perks.aura;

/**
 * What a hovered point would do to an aura, for its chips: grow one effect's range (shown as an arrow on
 * that chip), or something only words can say (a dashed chip): a new effect, +1 block to all with or
 * without a new effect, a level round. Pure, so the menu and the tests agree.
 */
public final class AuraPreview {

    public enum Kind { NONE, ONE_RANGE, NEW, ALL_AND_NEW, ALL, LEVEL }

    /** {@code index} is the chip that grows for {@link Kind#ONE_RANGE}, else -1. */
    public record Change(Kind kind, int index) {
        static final Change NONE = new Change(Kind.NONE, -1);
    }

    private AuraPreview() {}

    public static Change between(AuraLadder.Shape now, AuraLadder.Shape then) {
        int changed = changedRanges(now, then);
        if (then.count() > now.count()) return new Change(changed > 0 ? Kind.ALL_AND_NEW : Kind.NEW, -1);
        if (then.levelUps() > now.levelUps()) return new Change(Kind.LEVEL, -1);
        if (changed == 1) {
            for (int i = 0; i < now.count(); i++) if (now.range(i) != then.range(i)) return new Change(Kind.ONE_RANGE, i);
        }
        if (changed > 1) return new Change(Kind.ALL, -1);
        return Change.NONE;
    }

    /** How many of {@code now}'s effects have a different range in {@code then}. */
    private static int changedRanges(AuraLadder.Shape now, AuraLadder.Shape then) {
        int n = 0;
        for (int i = 0; i < now.count(); i++) if (now.range(i) != then.range(i)) n++;
        return n;
    }
}
