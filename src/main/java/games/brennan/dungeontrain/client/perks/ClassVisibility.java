package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which classes and perks the menu draws, the web page's test-mode rules ({@code perks.js}
 * {@code classHidden}, {@code classOnCircle}, {@code nextToUnlocked}). Immutable and pure: built per
 * frame from the player's unlocks, points and the classes they have hidden.
 *
 * <ul>
 *   <li>A shown class draws every perk.</li>
 *   <li>A hidden class draws only its unlocked perks and, while Advancement Points are held, the perks a
 *       line (solid or dashed) joins to the Engine or an unlocked perk.</li>
 *   <li>Previewing a class (hovering it in the Classes list) draws all of it.</li>
 * </ul>
 *
 * @param off          classes the player has hidden
 * @param preview      the class being previewed, or null
 * @param unlocks      perk id to unlock level
 * @param active       perk id to points this life
 * @param unlockPoints Advancement Points held
 */
public record ClassVisibility(PerkTree tree, Set<String> off, String preview, Map<String, Integer> unlocks,
                              Map<String, Integer> active, int unlockPoints) {

    /** Hidden classes take this share of the circle when shown only because points are held. */
    public static final double EXTRA_SHARE = 0.5;

    private boolean unlocked(String id) {
        return PerkTree.ROOT.equals(id) || unlocks.getOrDefault(id, 0) > 0;
    }

    /** Joined to the Engine or an unlocked perk by any line, whatever class either is in. */
    public boolean nextToUnlocked(PerkTree.Perk p) {
        for (PerkTree.Link l : tree.linksOf(p.id())) {
            if (unlocked(l.other(p.id()))) return true;
        }
        return false;
    }

    /** Hidden from the drawing. The Engine always shows. */
    public boolean hidden(PerkTree.Perk p) {
        if (p.isRoot() || p.cls() == null) return false;
        if (!off.contains(p.cls()) || p.cls().equals(preview)) return false;
        if (unlocks.getOrDefault(p.id(), 0) > 0) return false;
        return !(unlockPoints > 0 && nextToUnlocked(p));
    }

    /** A class still on the circle: shown, previewed, or with a perk drawn. */
    public boolean onCircle(String classId) {
        if (!off.contains(classId) || classId.equals(preview)) return true;
        for (PerkTree.Perk p : tree.perksOf(classId)) if (!hidden(p)) return true;
        return false;
    }

    /** On the circle for its own sake (shown, previewed, or holding an unlock), not only because points are held. */
    public boolean anchored(String classId) {
        if (!off.contains(classId) || classId.equals(preview)) return true;
        for (PerkTree.Perk p : tree.perksOf(classId)) if (unlocks.getOrDefault(p.id(), 0) > 0) return true;
        return false;
    }

    /** The class's title is drawn: it has a drawn perk, and a hidden class only once one of its perks is unlocked. */
    public boolean titleShown(String classId) {
        boolean anyDrawn = tree.perksOf(classId).stream().anyMatch(p -> !hidden(p));
        if (!anyDrawn) return false;
        if (off.contains(classId) && !classId.equals(preview)) {
            return tree.perksOf(classId).stream().anyMatch(p -> unlocks.getOrDefault(p.id(), 0) > 0);
        }
        return true;
    }

    /** The title takes its colour once a point is in one of the class's perks (or while it glows). */
    public boolean titleLit(String classId, String glow) {
        if (classId.equals(glow)) return true;
        return tree.perksOf(classId).stream().anyMatch(p -> active.getOrDefault(p.id(), 0) > 0);
    }

    /** Previewed: drawn faint. */
    public boolean ghost(PerkTree.Perk p) {
        return preview != null && preview.equals(p.cls());
    }

    /** Classes that hold at least one perk. */
    public static List<PerkTree.PerkClass> liveClasses(PerkTree tree) {
        return tree.classes().stream().filter(c -> !tree.perksOf(c.id()).isEmpty()).toList();
    }

    /**
     * Live classes in the order they sit round the authored constellation: anticlockwise from Lover
     * (web {@code circleClasses}). Falls back to the first class without a Lover.
     */
    public static List<PerkTree.PerkClass> circleOrder(PerkTree tree) {
        List<PerkTree.PerkClass> all = liveClasses(tree);
        if (all.isEmpty()) return all;
        PerkTree.PerkClass first = all.stream().filter(c -> c.name().equalsIgnoreCase("lover")).findFirst().orElse(all.get(0));
        double a0 = meanAngle(tree, first.id());
        double tau = Math.PI * 2;
        List<PerkTree.PerkClass> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingDouble(c -> (((a0 - meanAngle(tree, c.id())) % tau) + tau) % tau));
        return sorted;
    }

    /** Circular mean of the class's perk angles round the Engine. */
    static double meanAngle(PerkTree tree, String classId) {
        double sx = 0, sy = 0;
        for (PerkTree.Perk p : tree.perksOf(classId)) {
            double a = Math.atan2(p.y() - PerkTree.CENTRE, p.x() - PerkTree.CENTRE);
            sx += Math.cos(a);
            sy += Math.sin(a);
        }
        return Math.atan2(sy, sx);
    }
}
