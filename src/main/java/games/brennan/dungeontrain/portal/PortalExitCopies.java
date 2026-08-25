package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalExitSites.Site;
import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which of an endless room's extra corridors are currently standing — the {@link PortalRoomTiling}
 * analogue for {@link PortalExitSites.Site sites} rather than tiles.
 *
 * <p>Nothing here knows about Minecraft, for the same reason nothing in {@link PortalRoomTiling}
 * does: a site is a tile and a role, and turning it into blocks is the caller's job.</p>
 *
 * <h2>Copies outlive the tiles around them, deliberately</h2>
 * <p>A room copy retires the moment it leaves the window. An extra corridor must not: its blocks
 * reach into the tiles either side of its anchor ({@link PortalExitSites#tileReach}), and those tiles
 * were stamped <i>around</i> it through the corridor mask. Clearing it while one of them still stands
 * would leave a corridor-shaped pit with no floor in a room the player can walk straight back into.
 * So a copy is held until its anchor is {@link PortalRoomTiling#MAX_RADIUS} <b>plus the reach</b>
 * away, by which point every tile it touches has left the window too and the erase lands in space
 * that is already gone. See {@link #nextToRemove}.</p>
 *
 * @param sites the standing set
 */
public record PortalExitCopies(Set<Site> sites) {

    /** Nothing standing — what every structure starts and ends as. */
    public static final PortalExitCopies NONE = new PortalExitCopies(Set.of());

    /**
     * Farthest first, ties broken on coordinates and role so a tick's choice is deterministic.
     *
     * <p>Distance is to the <b>nearest</b> of the centres, so with two players in one room the copy
     * shed first is the one furthest from either of them rather than the one furthest from whichever
     * was listed first.</p>
     */
    private static Comparator<Site> orderFarthestFrom(Set<Tile> centres) {
        return Comparator.comparingInt((Site s) -> chebyshevToNearest(s, centres))
            .thenComparingInt(s -> s.tile().x())
            .thenComparingInt(s -> s.tile().z())
            .thenComparing(Site::role);
    }

    /** How far {@code site}'s anchor is from the closest of {@code centres}, in tiles. */
    private static int chebyshevToNearest(Site site, Set<Tile> centres) {
        int best = Integer.MAX_VALUE;
        for (Tile centre : centres) {
            best = Math.min(best, site.chebyshevTo(centre));
        }
        return best;
    }

    public PortalExitCopies {
        sites = Set.copyOf(sites);
    }

    public boolean has(Site site) {
        return sites.contains(site);
    }

    public boolean isEmpty() {
        return sites.isEmpty();
    }

    public int size() {
        return sites.size();
    }

    /** This set plus {@code site}. */
    public PortalExitCopies with(Site site) {
        if (sites.contains(site)) return this;
        Set<Site> next = new LinkedHashSet<>(sites);
        next.add(site);
        return new PortalExitCopies(next);
    }

    /** This set minus {@code site}. */
    public PortalExitCopies without(Site site) {
        if (!sites.contains(site)) return this;
        Set<Site> next = new LinkedHashSet<>(sites);
        next.remove(site);
        return new PortalExitCopies(next);
    }

    /**
     * The copy to retire next: the farthest standing one whose anchor has fallen more than
     * {@code radius + reach} tiles from {@code centre}, or {@code null} when every copy is still
     * close enough to hold.
     *
     * <p>The {@code reach} margin is the whole of this class's reason to exist — see the note above
     * on why a copy outlives the tiles around it. Farthest first, so a window sliding after a walking
     * player sheds what they have left behind rather than what is beside them.</p>
     */
    public Site nextToRemove(Tile centre, int radius, int reach) {
        return nextToRemove(Set.of(centre), radius, reach, site -> true);
    }

    /**
     * As {@link #nextToRemove(Tile, int, int)}, but never offering up a copy {@code removable}
     * rejects.
     *
     * <p>How a copy somebody is still relying on is held. When a player steps back onto the train the
     * room has nobody in it, so the window collapses to {@link PortalRoomTiling#APPROACH_RADIUS} and
     * every distant copy falls outside it at once — the one they just walked out of included, in the
     * very same tick they walked out of it. Without this, walking back in always lands at the
     * original twin and {@link PortalExitBindings} can never do the one thing it is for. The mirror
     * of {@link PortalRoomTiling#nextToRemove(Tile, int, java.util.function.Predicate)} sparing the
     * tile a player is standing in, for the same kind of reason.</p>
     */
    public Site nextToRemove(Tile centre, int radius, int reach, Predicate<Site> removable) {
        return nextToRemove(Set.of(centre), radius, reach, removable);
    }

    /**
     * As {@link #nextToRemove(Tile, int, int, Predicate)} for a room with several people in it: a
     * copy is offered up only when its anchor is more than {@code radius + reach} from <b>every</b>
     * centre.
     *
     * <p>The same safety rule {@code PortalRoomTiling} applies to tiles, and it matters more here,
     * not less: a player standing in a copy's corridor resolves to a tile that corridor reaches into,
     * and erasing the corridor around them drops them to the world floor. Following one centre, that
     * is exactly what happened to everybody but the first player in the room.</p>
     */
    public Site nextToRemove(Set<Tile> centres, int radius, int reach, Predicate<Site> removable) {
        if (centres.isEmpty()) return null;
        int hold = radius + Math.max(0, reach);
        Comparator<Site> order = orderFarthestFrom(centres);
        Site worst = null;
        for (Site site : sites) {
            if (chebyshevToNearest(site, centres) <= hold) continue;
            if (worst != null && order.compare(site, worst) <= 0) continue;
            if (!removable.test(site)) continue;
            worst = site;
        }
        return worst;
    }

    /**
     * The farthest standing copy from {@code centre}, or {@code null} when none are standing.
     *
     * <p>What draining uses. A structure with nobody in it sheds its copies from the outside in,
     * before it sheds any tiles, so that no erase can strand a corridor in a room that has already
     * gone.</p>
     */
    public Site farthestFrom(Tile centre) {
        Comparator<Site> order = orderFarthestFrom(Set.of(centre));
        Site worst = null;
        for (Site site : sites) {
            if (worst != null && order.compare(site, worst) <= 0) continue;
            worst = site;
        }
        return worst;
    }

    public int minTileX() {
        return sites.stream().mapToInt(s -> s.tile().x()).min().orElse(0);
    }

    public int maxTileX() {
        return sites.stream().mapToInt(s -> s.tile().x()).max().orElse(0);
    }

    public int minTileZ() {
        return sites.stream().mapToInt(s -> s.tile().z()).min().orElse(0);
    }

    public int maxTileZ() {
        return sites.stream().mapToInt(s -> s.tile().z()).max().orElse(0);
    }
}
