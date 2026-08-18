package games.brennan.dungeontrain.builder;

/**
 * How the carriages of one Train Outside build are named: {@code cabin}, {@code cabin_2},
 * {@code cabin_3} — a <b>family</b>.
 *
 * <p>A build from outside spans every parked carriage, but a template is one carriage everywhere
 * downstream: {@code WholeCarriageTemplateStore} refuses anything whose footprint isn't exactly
 * {@link games.brennan.dungeontrain.train.CarriageDims}, and the train rolls a variant per slot. So a
 * three-carriage build is three ordinary templates that happen to share a name, rather than one
 * template three carriages long — which would also be unplaceable in any world whose {@code groupSize}
 * differs from the one it was authored at.</p>
 *
 * <p>Its own class, and pure, because the save and the open both have to spell the family the same
 * way. Save writes {@code cabin_2} into the second slot; Open has to know to look for it there, or
 * reopening a family shows three copies of its first member and the next save overwrites the rest.</p>
 */
public final class BuilderFamilyNames {

    /** Separates a base name from its member number. */
    private static final char SEPARATOR = '_';

    private BuilderFamilyNames() {}

    /**
     * The name the carriage in slot {@code index} is saved under.
     *
     * <p>Slot 0 is the base name itself, unsuffixed. That is what makes a family a superset of an
     * ordinary build rather than a different thing: a one-carriage save writes {@code cabin}, exactly
     * as it always has, and the extra members only exist when there were extra carriages to write.</p>
     */
    public static String memberName(String base, int index) {
        if (base == null || base.isEmpty()) return "";
        return index <= 0 ? base : base + SEPARATOR + (index + 1);
    }

    /**
     * The slot a member name belongs to, or {@code -1} when it isn't a member of {@code base}.
     *
     * <p>The inverse of {@link #memberName}, and deliberately strict: {@code cabin_2x} and
     * {@code cabin_0} are not members, so a template that merely starts with the base name is never
     * mistaken for part of the family and overwritten.</p>
     */
    public static int memberIndex(String base, String name) {
        if (base == null || base.isEmpty() || name == null || name.isEmpty()) return -1;
        if (base.equals(name)) return 0;
        String prefix = base + SEPARATOR;
        if (!name.startsWith(prefix)) return -1;
        String suffix = name.substring(prefix.length());
        if (suffix.isEmpty()) return -1;
        for (int i = 0; i < suffix.length(); i++) {
            if (suffix.charAt(i) < '0' || suffix.charAt(i) > '9') return -1;
        }
        int number;
        try {
            number = Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return -1;   // longer than an int: not a member, whatever else it is
        }
        // Members are numbered from 2 — slot 0 is the bare base name, so there is no `cabin_1`.
        return number < 2 ? -1 : number - 1;
    }

    /** Whether {@code name} is one of {@code base}'s extra carriages (slot 1 and up). */
    public static boolean isExtraMember(String base, String name) {
        return memberIndex(base, name) > 0;
    }
}
