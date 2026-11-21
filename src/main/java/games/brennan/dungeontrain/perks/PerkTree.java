package games.brennan.dungeontrain.perks;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The perks constellation as the editor laid it out: classes, stars and the lines between them, in
 * the editor's 800 x 800 space with The Engine at {@link #CENTRE}. Immutable; built once by
 * {@link PerkTreeLoader} from {@code data/dungeontrain/perks/tree.json} and read by the menu (positions,
 * names) and the server (costs, reachability). Nothing in the mod re-lays it out.
 *
 * <p>Reachability is the one rule both sides must agree on: a locked perk may be unlocked when a
 * <b>solid</b> (requirement) line joins it to the Engine or to an unlocked perk, either way round.
 * Dashed lines (chords) open nothing; they only show which perks belong together. This is the web
 * page's test mode ({@code perks.js reachableNow}).</p>
 */
public final class PerkTree {

    public static final String ROOT = "root";
    public static final double CENTRE = 400.0;
    public static final double SIZE = 800.0;

    public record PerkClass(String id, String name, int colour) {}

    /**
     * @param point the per-point effect line, or empty when the editor has none for it
     */
    public record Perk(String id, String cls, String name, String desc, String point, int tier, double x, double y) {
        public boolean isRoot() {
            return ROOT.equals(id);
        }

        public int unlockCost() {
            return isRoot() ? 0 : PerkDef.unlockCostFor(tier);
        }

        public double distanceToCentre() {
            return Math.hypot(x - CENTRE, y - CENTRE);
        }
    }

    public enum LinkKind { REQ, CROSS }

    public record Link(String a, String b, LinkKind kind, boolean oneWay) {
        public boolean touches(String id) {
            return a.equals(id) || b.equals(id);
        }

        public String other(String id) {
            return a.equals(id) ? b : a;
        }
    }

    private final int version;
    private final List<PerkClass> classes;
    private final List<Perk> perks;
    private final List<Link> links;
    private final Map<String, Perk> byId;
    private final Map<String, PerkClass> classById;
    private final Map<String, List<Link>> linksById;

    PerkTree(int version, List<PerkClass> classes, List<Perk> perks, List<Link> links) {
        this.version = version;
        this.classes = List.copyOf(classes);
        this.perks = List.copyOf(perks);
        this.links = List.copyOf(links);
        Map<String, Perk> ids = new LinkedHashMap<>();
        perks.forEach(p -> ids.put(p.id(), p));
        this.byId = Collections.unmodifiableMap(ids);
        Map<String, PerkClass> cls = new LinkedHashMap<>();
        classes.forEach(c -> cls.put(c.id(), c));
        this.classById = Collections.unmodifiableMap(cls);
        Map<String, List<Link>> adj = new LinkedHashMap<>();
        for (Link l : links) {
            adj.computeIfAbsent(l.a(), k -> new java.util.ArrayList<>()).add(l);
            adj.computeIfAbsent(l.b(), k -> new java.util.ArrayList<>()).add(l);
        }
        adj.replaceAll((k, v) -> List.copyOf(v));
        this.linksById = Collections.unmodifiableMap(adj);
    }

    public int version() {
        return version;
    }

    public List<PerkClass> classes() {
        return classes;
    }

    public List<Perk> perks() {
        return perks;
    }

    public List<Link> links() {
        return links;
    }

    public Optional<Perk> perk(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    public Optional<PerkClass> perkClass(String id) {
        return Optional.ofNullable(id == null ? null : classById.get(id));
    }

    public Perk root() {
        return byId.get(ROOT);
    }

    /** Every link touching {@code id}, in file order. */
    public List<Link> linksOf(String id) {
        return linksById.getOrDefault(id, List.of());
    }

    /** Perks of one class, nearest the Engine first. */
    public List<Perk> perksOf(String classId) {
        return perks.stream().filter(p -> classId.equals(p.cls()))
            .sorted(java.util.Comparator.comparingDouble(Perk::distanceToCentre)).toList();
    }

    /** The class's perk linked straight to the Engine, if any (its title hangs off that line). */
    public Optional<Perk> firstPerkOf(String classId) {
        return linksOf(ROOT).stream().map(l -> l.other(ROOT)).map(byId::get)
            .filter(p -> p != null && classId.equals(p.cls())).findFirst();
    }

    /**
     * Whether {@code id} may be unlocked now, given which perks are unlocked. The Engine itself is
     * never unlockable; an unknown id is not reachable.
     */
    public boolean reachable(String id, Predicate<String> unlocked) {
        return reachable(id, unlocked, other -> false);
    }

    /**
     * As {@link #reachable(String, Predicate)}, where a {@code passThrough} perk (one not built yet,
     * "Coming soon") counts as a step on the path: a solid line from the Engine or an unlocked perk
     * through any run of pass-through perks opens {@code id}. A pass-through perk is never itself
     * unlocked by this, only crossed.
     */
    public boolean reachable(String id, Predicate<String> unlocked, Predicate<String> passThrough) {
        Perk p = byId.get(id);
        if (p == null || p.isRoot()) return false;
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        seen.add(id);
        queue.add(id);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (Link l : linksOf(cur)) {
                if (l.kind() != LinkKind.REQ) continue;
                String other = l.other(cur);
                if (ROOT.equals(other) || unlocked.test(other)) return true;
                if (passThrough.test(other) && seen.add(other)) queue.add(other);
            }
        }
        return false;
    }

    public boolean reachable(String id, PerkState state) {
        return reachable(id, state::isUnlocked);
    }

    /** As {@link #reachable(String, PerkState)}, crossing perks that are not built yet. */
    public boolean reachableNow(String id, Predicate<String> unlocked) {
        return reachable(id, unlocked, other -> !PerkRegistry.isBuilt(other));
    }

    /** Ids joined to the Engine by some chain of links, the Engine included. */
    public Set<String> connectedToRoot() {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        seen.add(ROOT);
        queue.add(ROOT);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (Link l : linksOf(cur)) {
                String other = l.other(cur);
                if (seen.add(other)) queue.add(other);
            }
        }
        return seen;
    }

    /**
     * The same tree with some stars moved (display only: the menu's class layout). Perks missing from
     * {@code positions} keep their place; ids, links and costs are unchanged.
     */
    public PerkTree withPositions(Map<String, double[]> positions) {
        if (positions.isEmpty()) return this;
        List<Perk> moved = new java.util.ArrayList<>(perks.size());
        for (Perk p : perks) {
            double[] xy = positions.get(p.id());
            moved.add(xy == null ? p : new Perk(p.id(), p.cls(), p.name(), p.desc(), p.point(), p.tier(), xy[0], xy[1]));
        }
        return new PerkTree(version, classes, moved, links);
    }

    /** Sum of the unlock costs of every unlocked perk the tree knows, once each. */
    public int unlockCostOf(Set<String> unlocked) {
        int total = 0;
        for (String id : unlocked) {
            Perk p = byId.get(id);
            if (p != null) total += p.unlockCost();
        }
        return total;
    }

    /** Advancement Points spent on these unlock levels: each level costs the perk's flat tier price. */
    public int unlockCostOf(java.util.Map<String, Integer> unlocks) {
        int total = 0;
        for (java.util.Map.Entry<String, Integer> e : unlocks.entrySet()) {
            Perk p = byId.get(e.getKey());
            if (p != null) total += p.unlockCost() * Math.max(0, e.getValue());
        }
        return total;
    }
}
