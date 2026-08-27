package games.brennan.dungeontrain.client.localization.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Which translation units are variations of the same thing.
 *
 * <p>A great deal of Dungeon Train's text is one line written many different ways —
 * {@code chat.dungeontrain.familiar_book.1} through {@code .13} are thirteen wordings of one
 * message, a random book's {@code variants[]} are alternative texts for one book, and
 * {@code death_lore}'s {@code 0.narration}…{@code 38.narration} are alternative narrations for one
 * death. They are never byte-identical — same meaning, different words — so nothing about the TEXT
 * can group them. What they share is their shape.</p>
 *
 * <p>So the rule is structural: a unit's group key is its id with the <b>last numeric path segment
 * replaced by {@code #}</b>, scoped by type and namespace. A unit with no numeric segment has no
 * group, and a group needs two members to exist — a lone {@code foo.1} is not a set of anything.
 * </p>
 *
 * <p>The LAST numeric segment rather than the first, because that is the axis that varies:
 * {@code letters.2.variants.1} is one of letter 2's variants, not one of the letters. Book ids
 * already carry their book path ({@code random_books/deathnote#variants.3}), so sets are scoped per
 * book without this having to know what a book is.</p>
 *
 * <p>Kept free of {@code Screen}/{@code Minecraft} for the same reason {@link TranslationFilters}
 * is: what a translator is offered is worth being able to answer without opening Minecraft.</p>
 */
public final class TranslationGroups {

    /** What replaces the varying index. Not a legal lang-key segment, so it cannot collide. */
    static final String INDEX = "#";

    /**
     * What a collapsed row reports about the set behind it: how many variations there are, and how
     * many of them still want a human.
     *
     * @param size          members in the set — always 2 or more, since a set of one is not a set
     * @param needingReview how many of them {@link TranslationFilters#needsHuman} still holds for
     */
    public record Badge(int size, int needingReview) {}

    private TranslationGroups() {}

    /**
     * The group key for {@code unit}, or {@code ""} when it belongs to no group.
     *
     * <p>Answers the question for the unit ALONE. Whether that key names a real group depends on
     * how many units share it, which only {@link #index} can see — a key here is a candidate.</p>
     */
    public static String groupKeyOf(TranslationUnit unit) {
        if (unit == null || unit.id() == null || unit.id().isEmpty()) {
            return "";
        }
        // A book id is <book path>#<field>, and only the FIELD carries the index. Splitting it off
        // first is what lets death lore group at all: its fields are 0.narration…38.narration, and
        // over the whole id the leading index is glued to the book path and invisible to the rule.
        boolean book = unit.type() == TranslationUnit.Type.BOOK;
        String scope = book ? unit.bookPath() + "#" : "";
        String path = book ? unit.bookField() : unit.id();
        if (path.isEmpty()) {
            return "";
        }
        String[] segments = path.split("\\.", -1);
        int last = -1;
        for (int i = 0; i < segments.length; i++) {
            if (isIndex(segments[i])) {
                last = i;
            }
        }
        if (last < 0) {
            return "";
        }
        segments[last] = INDEX;
        // Type and namespace join the key so a lang key and a book field that happen to flatten to
        // the same dotted path can never be pulled into one set.
        return unit.type() + "|" + unit.namespace() + "|" + scope + String.join(".", segments);
    }

    /** A path segment that is a bare non-negative integer — the index a variation varies by. */
    private static boolean isIndex(String segment) {
        if (segment.isEmpty()) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (segment.charAt(i) < '0' || segment.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Group key → members, in the order {@code all} gave them, keeping only keys with two or more.
     *
     * <p>Built in ONE pass and handed round, rather than asked per row: the list screen rebuilds on
     * every keystroke over a couple of thousand units, and the pairwise form of this question is
     * millions of key derivations a search box cannot afford.</p>
     *
     * <p>Give it the whole catalog. A set is a property of the text, not of what the filters happen
     * to be showing — a search that matches two of thirteen variations has still found two of
     * thirteen, and the row that says so is more use than one claiming the set is two long.</p>
     */
    public static Map<String, List<TranslationUnit>> index(List<TranslationUnit> all) {
        if (all == null || all.isEmpty()) {
            return Map.of();
        }
        Map<String, List<TranslationUnit>> byKey = new LinkedHashMap<>();
        for (TranslationUnit unit : all) {
            String key = groupKeyOf(unit);
            if (!key.isEmpty()) {
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(unit);
            }
        }
        byKey.values().removeIf(members -> members.size() < 2);
        return byKey;
    }

    /** The set {@code unit} belongs to within {@code index}, or empty when it belongs to none. */
    public static List<TranslationUnit> membersOf(Map<String, List<TranslationUnit>> index,
                                                  TranslationUnit unit) {
        if (index == null || index.isEmpty()) {
            return List.of();
        }
        List<TranslationUnit> members = index.get(groupKeyOf(unit));
        return members == null ? List.of() : members;
    }

    /** Where {@code unit} sits among {@code members} (0-based), or -1 if it is not one of them. */
    public static int indexIn(List<TranslationUnit> members, TranslationUnit unit) {
        if (members == null || unit == null) {
            return -1;
        }
        for (int i = 0; i < members.size(); i++) {
            if (sameUnit(members.get(i), unit)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sameUnit(TranslationUnit a, TranslationUnit b) {
        return a.type() == b.type() && a.namespace().equals(b.namespace()) && a.id().equals(b.id());
    }

    /** How many of {@code members} still want a human — the number the badge and the strip show. */
    public static int needingReview(List<TranslationUnit> members, TranslationEdits approved,
                                    Predicate<TranslationUnit> dismissed) {
        int count = 0;
        for (TranslationUnit unit : members == null ? List.<TranslationUnit>of() : members) {
            if (TranslationFilters.needsHuman(unit, approved, dismissed)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The next member after {@code current} that still needs a human, wrapping past the end, or null
     * when nothing else in the set does.
     *
     * <p>Wrapping rather than stopping at the last member: a set is a bag of variations, not a
     * sequence with an end, and a translator who opened one in the middle would otherwise be walked
     * to the end and told the set was done with work still sitting above where they started.</p>
     */
    public static TranslationUnit nextNeedingReview(List<TranslationUnit> members,
                                                    TranslationUnit current,
                                                    TranslationEdits approved,
                                                    Predicate<TranslationUnit> dismissed) {
        if (members == null || members.isEmpty()) {
            return null;
        }
        int from = indexIn(members, current);
        for (int step = 1; step <= members.size(); step++) {
            TranslationUnit candidate = members.get((Math.max(from, 0) + step) % members.size());
            if (from >= 0 && sameUnit(candidate, current)) {
                continue; // came all the way round to where we started
            }
            if (TranslationFilters.needsHuman(candidate, approved, dismissed)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * One row per set, with everything ungrouped passed through in place — what the list shows while
     * grouping is on.
     *
     * <p>The row that stands for a set is the first VISIBLE member still needing a human, falling
     * back to the first visible member: opening a collapsed row should land on work to do, not on
     * the variation that happens to be numbered lowest. Visible, because a row the filters have
     * already excluded cannot be the row that represents them.</p>
     *
     * <p>Order is the order each representative would have had, so turning grouping on shortens the
     * list without shuffling it.</p>
     */
    public static List<TranslationUnit> collapse(List<TranslationUnit> visible,
                                                 Map<String, List<TranslationUnit>> index,
                                                 TranslationEdits approved,
                                                 Predicate<TranslationUnit> dismissed) {
        if (visible == null || visible.isEmpty()) {
            return List.of();
        }
        if (index == null || index.isEmpty()) {
            return visible;
        }
        Map<String, TranslationUnit> representative = new LinkedHashMap<>();
        for (TranslationUnit unit : visible) {
            String key = groupKeyOf(unit);
            if (key.isEmpty() || !index.containsKey(key)) {
                continue;
            }
            TranslationUnit held = representative.get(key);
            if (held == null
                || (!TranslationFilters.needsHuman(held, approved, dismissed)
                    && TranslationFilters.needsHuman(unit, approved, dismissed))) {
                representative.put(key, unit);
            }
        }
        java.util.Set<String> emitted = new java.util.HashSet<>();
        List<TranslationUnit> out = new ArrayList<>();
        for (TranslationUnit unit : visible) {
            String key = groupKeyOf(unit);
            if (key.isEmpty() || !index.containsKey(key)) {
                out.add(unit);
                continue;
            }
            if (emitted.add(key)) {
                out.add(representative.get(key));
            }
        }
        return out;
    }
}
