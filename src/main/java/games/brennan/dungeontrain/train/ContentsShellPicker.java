package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.editor.CarriageVariantContentsAllowStore;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCorridorKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Which carriage a contents template is shown standing in — its editor plot and its Test the
 * Carriage copy both ask here, so neither can wrap it in a shell the train would never put it in.
 *
 * <p>The rule is the train's own, read backwards: a carriage carries a contents template when its
 * contents allow-list has it enabled, and a carriage spawns in proportion to its weight. So the
 * candidates are the carriages whose list enables it, drawn by weight. Portal parts, flatbeds and
 * weight-0 carriages are never candidates — none of them holds contents on the train.</p>
 */
public final class ContentsShellPicker {

    private ContentsShellPicker() {}

    /**
     * A carriage that currently has {@code contentsId} enabled, drawn by weight with {@code seed}, or
     * empty when no carriage enables it. A sub-variant is enabled through its group's top parent —
     * the allow-lists only ever name top-level templates, because that is the level the train's pick
     * consults them at.
     */
    public static Optional<CarriageVariant> pick(String contentsId, long seed) {
        String topId = topParentOf(contentsId);
        CarriageWeights weights = CarriageWeights.current();
        List<CarriageVariant> eligible = new ArrayList<>();
        int total = 0;
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            int weight = weights.weightFor(v.id());
            if (weight <= 0 || isPortalPart(v) || isFlatbed(v)) continue;
            boolean allowed = CarriageVariantContentsAllowStore.get(v)
                .map(a -> a.isAllowed(topId)).orElse(true);
            if (!allowed) continue;
            eligible.add(v);
            total += weight;
        }
        if (eligible.isEmpty()) return Optional.empty();
        int roll = new Random(seed).nextInt(total);
        for (CarriageVariant v : eligible) {
            roll -= weights.weightFor(v.id());
            if (roll < 0) return Optional.of(v);
        }
        return Optional.of(eligible.get(eligible.size() - 1));
    }

    /**
     * The same pick, fixed for one template: the editor shows an author one carriage for their
     * contents, not a different one every time the plot is stamped.
     */
    public static Optional<CarriageVariant> stableFor(String contentsId) {
        return pick(contentsId, contentsId.hashCode());
    }

    /** The group chain's root: the id the carriage allow-lists name. */
    public static String topParentOf(String id) {
        String cur = id;
        Set<String> seen = new HashSet<>();
        while (seen.add(cur)) {
            Optional<String> parent = CarriageContentsGroupStore.findParentOf(cur);
            if (parent.isEmpty()) return cur;
            cur = parent.get();
        }
        return cur;
    }

    /** The corridors and the cart between a portal's corridors: parts of a dimensional carriage. */
    public static boolean isPortalPart(CarriageVariant variant) {
        if (variant.equals(PortalCarriageBuilder.middleVariant())) return true;
        for (PortalCorridorKind k : PortalCorridorKind.values()) {
            if (variant.equals(PortalCarriageBuilder.portalVariant(k))) return true;
        }
        return false;
    }

    public static boolean isFlatbed(CarriageVariant variant) {
        return variant instanceof CarriageVariant.Builtin b
            && b.type() == CarriagePlacer.CarriageType.FLATBED;
    }
}
