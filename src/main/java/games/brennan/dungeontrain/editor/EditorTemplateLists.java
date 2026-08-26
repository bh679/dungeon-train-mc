package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.portal.PortalRoomMode;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
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
     * Every saved carriage group, alphabetical — a whole run of carriages saved as one build.
     *
     * <p>Always user-authored, like {@link #wholeCarriages()}: the mod ships none, so this is empty
     * until someone saves a build from the platform.</p>
     */
    public static List<String> carriageGroups() {
        return games.brennan.dungeontrain.train.CarriageGroupRegistry.ids();
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

    /**
     * Track tile template names, {@code default} first.
     *
     * <p>Here rather than read straight off {@link TrackVariantRegistry} at the call site for the
     * reason this class exists at all: the Builder's Open grid and the Editor's own track menus have
     * to agree about which tiles exist. {@code namesFor} already guarantees the synthetic
     * {@code default} leads the list, which is also the order a picker wants.</p>
     */
    public static List<String> tracks() {
        return TrackVariantRegistry.namesFor(TrackKind.TILE);
    }

    /**
     * Template names for any track-side kind, {@code default} first.
     *
     * <p>The general form of {@link #tracks()} and {@link #tunnels}, for callers that hold a
     * {@link TrackKind} rather than knowing which one they want — the Builder's Open grid browses
     * all eight, so naming them one method at a time would mean eight near-identical methods.</p>
     */
    public static List<String> trackVariants(TrackKind kind) {
        return kind == null ? List.of() : TrackVariantRegistry.namesFor(kind);
    }

    /**
     * Tunnel template names for one variant, {@code default} first.
     *
     * <p>Tunnels are keyed by variant <em>and</em> name — {@code PORTAL}'s {@code default} and
     * {@code SECTION}'s {@code default} are different files — so the variant is not optional.</p>
     */
    public static List<String> tunnels(TunnelPlacer.TunnelVariant variant) {
        return variant == null ? List.of() : TrackVariantRegistry.namesFor(tunnelKind(variant));
    }

    /** Testable core of {@link #tunnels}: the variant-to-store mapping, without a loaded registry. */
    static TrackKind tunnelKind(TunnelPlacer.TunnelVariant variant) {
        return variant == TunnelPlacer.TunnelVariant.PORTAL
                ? TrackKind.TUNNEL_PORTAL
                : TrackKind.TUNNEL_SECTION;
    }

    /**
     * Portal room template names of one {@link PortalRoomMode}, {@code default} first.
     *
     * <p>Filtered rather than listed whole because the mode is the first thing a builder picks: a
     * room's mode is what it does at its own walls, and a Bedrock room and an Endless one are
     * different enough that browsing them in one pile tells you nothing.</p>
     *
     * <p>Flat, group parents and their members alike — {@code beam} and its {@code beamlong} sit
     * beside each other here, unlike {@link #contents()}, which hides members behind their parent.
     * The difference is that a portal room's mode is authored <em>per name</em> in {@code
     * weights.json}, so a group's members are not guaranteed to share the parent's mode and
     * drilling into the parent would hide rooms that belong in this list.</p>
     */
    public static List<String> portalRooms(PortalRoomMode mode) {
        return filterByMode(TrackVariantRegistry.namesFor(TrackKind.PORTAL_ROOM),
                name -> TrackVariantWeights.modeFor(TrackKind.PORTAL_ROOM, name), mode);
    }

    /**
     * Testable core of {@link #portalRooms}: the mode filter, without a loaded registry.
     *
     * <p>{@code modeTags} hands back the raw stored tag, which is <b>not</b> a bare mode id — a room
     * stores its copies, contents and exits settings in the same string, so what is on disk reads
     * {@code endless_repetition/dynamic}. Reading it with {@code PortalRoomMode.parse} would match
     * none of those and quietly file every settings-carrying room under the default mode; {@link
     * PortalRoomSettings#parse} splits the segments off first, which is why it is the one used
     * here.</p>
     */
    static List<String> filterByMode(List<String> names,
                                     java.util.function.Function<String, String> modeTags,
                                     PortalRoomMode mode) {
        if (names == null || mode == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (PortalRoomSettings.parse(modeTags.apply(name)).mode() == mode) {
                out.add(name);
            }
        }
        return List.copyOf(out);
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
