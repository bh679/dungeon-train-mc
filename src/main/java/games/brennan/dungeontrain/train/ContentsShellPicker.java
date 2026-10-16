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
import java.util.function.ToIntFunction;

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
        return drawByWeight(eligible(contentsId), weightOf(), seed);
    }

    /**
     * Every carriage that would carry {@code contentsId} on the train today, in registry order: its
     * size, a weight above zero, real blocks, not a portal part or flatbed, and the contents enabled
     * on its allow-list.
     */
    public static List<CarriageVariant> eligible(String contentsId) {
        String topId = topParentOf(contentsId);
        // A shell only takes contents of its own size, so only shells of this contents' size qualify.
        ContentsSize size = CarriageContentsPlacer.sizeOf(topId);
        CarriageWeights weights = CarriageWeights.current();
        List<CarriageVariant> eligible = new ArrayList<>();
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            int weight = weights.weightFor(v.id());
            if (weight <= 0 || isPortalPart(v) || isFlatbed(v)) continue;
            if (CarriagePlacer.sizeOf(v) != size) continue;
            // A carriage made blank and never built is air — nothing to stand contents in.
            if (!games.brennan.dungeontrain.editor.CarriageTemplateStore.hasBlocks(v.id())) continue;
            // No sidecar is no explicit decision, not "everything": an opt-in template is still off.
            // A Workbench build under test is on no carriage's list — any carriage of its size may hold it.
            boolean allowed = games.brennan.dungeontrain.editor.workbench.WorkbenchTestScratch.isScratchId(topId)
                || CarriageVariantContentsAllowStore.get(v)
                .orElse(CarriageContentsAllowList.EMPTY).isAllowed(topId);
            if (allowed) eligible.add(v);
        }
        return eligible;
    }

    /**
     * Another carriage for {@code contentsId}: the same weighted draw with {@code exclude} taken out,
     * so a reroll always changes what the author sees. Empty when nothing else would carry it.
     */
    public static Optional<CarriageVariant> pickOther(String contentsId, CarriageVariant exclude, long seed) {
        return drawExcluding(eligible(contentsId), exclude, weightOf(), seed);
    }

    private static ToIntFunction<CarriageVariant> weightOf() {
        CarriageWeights weights = CarriageWeights.current();
        return v -> weights.weightFor(v.id());
    }

    /** {@link #drawByWeight} over {@code candidates} without {@code exclude}. */
    static <T> Optional<T> drawExcluding(List<T> candidates, T exclude, ToIntFunction<T> weight, long seed) {
        List<T> others = new ArrayList<>(candidates);
        others.remove(exclude);
        return drawByWeight(others, weight, seed);
    }

    /** One of {@code candidates}, each in proportion to its weight; empty when there are none. */
    static <T> Optional<T> drawByWeight(List<T> candidates, ToIntFunction<T> weight, long seed) {
        if (candidates.isEmpty()) return Optional.empty();
        int total = 0;
        for (T v : candidates) total += Math.max(0, weight.applyAsInt(v));
        if (total <= 0) return Optional.of(candidates.get(0));
        int roll = new Random(seed).nextInt(total);
        for (T v : candidates) {
            roll -= Math.max(0, weight.applyAsInt(v));
            if (roll < 0) return Optional.of(v);
        }
        return Optional.of(candidates.get(candidates.size() - 1));
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
