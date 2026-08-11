package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What templates exist, for anything that needs to offer a list of them.
 *
 * <p>The Train Editor's own template list and the Train Builder's New screen are two front ends
 * onto one set of files, so "which contents are there?" has to have one answer. It didn't: both
 * called the registries, each with its own rules, and they had already diverged over group
 * members — the Editor hides them and drills into the parent, the Builder listed all of them flat
 * as if {@code copper} were a sibling of {@code maze} rather than one of its sub-variants.</p>
 *
 * <p>The registries these read are populated on {@code ServerStartingEvent}, so calling them from
 * client code only works where the client <em>is</em> the server. That has always been true of the
 * Editor's menus, and a Builder world is single-player by construction; recording the constraint
 * here beats leaving it implied at each call site.</p>
 */
public final class EditorTemplateLists {

    private EditorTemplateLists() {}

    /** Every registered carriage shell, in registry order — which is also editor plot order. */
    public static List<String> carriages() {
        List<String> ids = new ArrayList<>();
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            ids.add(v.id());
        }
        return ids;
    }

    /**
     * Every saved whole carriage, alphabetical.
     *
     * <p>Always user-authored, so this list is empty on a fresh install — the Builder's picker
     * shows the Stage presets alone until someone saves their first one.</p>
     */
    public static List<String> wholeCarriages() {
        return WholeCarriageRegistry.ids();
    }

    /**
     * Top-level contents only: a group's members are reached through their parent, never listed
     * beside it. Matches the spawn-time pick in {@code CarriageContentsRegistry.buildPickContext}
     * and the allow-list screen, so what you can choose is what the game can roll.
     */
    public static List<String> contents() {
        List<String> all = new ArrayList<>();
        for (CarriageContents c : CarriageContentsRegistry.allContents()) {
            all.add(c.id());
        }
        return topLevelContents(all, CarriageContentsGroupStore.allChildIds());
    }

    /** Testable core of {@link #contents()} — the subtraction, without a loaded registry. */
    static List<String> topLevelContents(List<String> allIds, Set<String> childIds) {
        List<String> out = new ArrayList<>(allIds.size());
        for (String id : allIds) {
            if (childIds == null || !childIds.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** True when {@code id} names a contents group with sub-variants underneath it. */
    public static boolean isContentsGroup(String id) {
        return id != null && CarriageContentsGroupStore.exists(id);
    }

    /** {@code parentId}'s sub-variant ids, or empty when it isn't a group. */
    public static List<String> contentsMembers(String parentId) {
        if (parentId == null) {
            return List.of();
        }
        Optional<CarriageContentsGroup> group = CarriageContentsGroupStore.get(parentId);
        if (group.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (CarriageContentsGroup.Member m : group.get().members()) {
            ids.add(m.id());
        }
        return ids;
    }

    /** The group {@code childId} belongs to, or empty when it's top-level. */
    public static Optional<String> contentsParentOf(String childId) {
        return childId == null ? Optional.empty() : CarriageContentsGroupStore.findParentOf(childId);
    }

    /**
     * Part templates of one kind. Deliberately {@code registeredNames} rather than {@code names} —
     * the latter prepends the {@code none} sentinel, which is a command-completion affordance
     * ("skip this kind") and not something you can copy or open.
     */
    public static List<String> parts(CarriagePartKind kind) {
        return kind == null ? List.of() : CarriagePartRegistry.registeredNames(kind);
    }

    /** Stage ids, earliest stretch of the game first. */
    public static List<String> stages() {
        return orderedStages(StageStore.allStages());
    }

    /**
     * Testable core of {@link #stages()}: ordered by where each band starts, id as the tie-break.
     *
     * <p>Alphabetical would put whichever stage happens to sort first at the top of a picker, and
     * the first entry in a picker is its default — so the order decides what a builder gets when
     * they don't choose. "The beginning of the game" is a defensible default; "d comes before s"
     * is not.</p>
     */
    static List<String> orderedStages(List<Stage> stages) {
        List<Stage> sorted = new ArrayList<>(stages);
        sorted.sort(Comparator.comparingInt((Stage s) -> s.gate() == null
                        ? TemplateGate.DEFAULT.minLevel()
                        : s.gate().minLevel())
                .thenComparing(Stage::id));
        List<String> ids = new ArrayList<>(sorted.size());
        for (Stage s : sorted) {
            ids.add(s.id());
        }
        return ids;
    }
}
