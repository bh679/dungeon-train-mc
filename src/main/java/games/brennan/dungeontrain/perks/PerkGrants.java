package games.brennan.dungeontrain.perks;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * What {@code /dungeontrain perks grant|revoke} changes, worked out without a player: which perks a
 * scope covers, and the unlock level each one ends at. Pure, so the rules are unit-tested.
 *
 * <ul>
 *   <li>Only built perks are covered: a Coming soon perk has no effect to unlock.</li>
 *   <li>A grant raises a perk to at least {@code levels} and never lowers one already past it, as
 *       {@code /advancement grant} never takes anything away.</li>
 *   <li>A revoke locks the perk (unlock level 0).</li>
 * </ul>
 */
public final class PerkGrants {

    /** Which perks a command covers. */
    public sealed interface Scope permits Everything, Only, OfClass {}

    public record Everything() implements Scope {}

    public record Only(String perkId) implements Scope {}

    public record OfClass(PerkClass perkClass) implements Scope {}

    private PerkGrants() {}

    /** The built perks {@code scope} covers, in registration order. */
    public static List<PerkDef> covered(Scope scope, Collection<PerkDef> all, Predicate<String> built) {
        return all.stream()
            .filter(def -> built.test(def.id()))
            .filter(def -> switch (scope) {
                case Everything e -> true;
                case Only o -> def.id().equals(o.perkId());
                case OfClass c -> def.perkClass() == c.perkClass();
            })
            .toList();
    }

    /**
     * The unlock level each covered perk moves to, for the perks that change only.
     *
     * @param levels  grant: the level to reach (at least 1); revoke: ignored
     * @param current the player's unlock level per perk id
     */
    public static Map<String, Integer> changes(boolean grant, int levels, List<PerkDef> covered,
                                               ToIntFunction<String> current) {
        int target = Math.max(1, levels);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (PerkDef def : covered) {
            int now = Math.max(0, current.applyAsInt(def.id()));
            int next = grant ? Math.max(now, target) : 0;
            if (next != now) out.put(def.id(), next);
        }
        return out;
    }

    /** A class from its command id ({@code lover}), case-insensitive. */
    public static Optional<PerkClass> classOf(String id) {
        if (id == null) return Optional.empty();
        for (PerkClass c : PerkClass.values()) {
            if (c.id().equalsIgnoreCase(id.trim())) return Optional.of(c);
        }
        return Optional.empty();
    }
}
