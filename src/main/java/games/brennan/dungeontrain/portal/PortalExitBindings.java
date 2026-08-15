package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalExitSites.Site;
import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each player left an endless room, so the way back in leads where they came out.
 *
 * <p>A carriage has one twin, and without this it is always the original at {@link Tile#BASE}.
 * So a player who found an extra corridor eight rooms out ({@link PortalRoomExits}) and rode it
 * back to the train re-entered at the start and had to walk those eight rooms again — which makes a
 * second way <i>out</i> only half of a second way <i>through</i>.</p>
 *
 * <h2>Bound to a tile, not to the corridor</h2>
 * <p>This is the whole design. Come out through an <b>exit</b> copy at tile 8 and the natural way
 * back in is the <b>entry</b> carriage — the way in. Remembering the corridor would send you to the
 * primary entry twin at tile 0 and lose your place at exactly the moment you were trying to keep it.
 * Remembering the tile means both carriages of the pair lead back to it, each through its own role's
 * corridor.</p>
 *
 * <h2>Per player</h2>
 * <p>Two people can be at opposite ends of the same endless room. A binding shared by the pair would
 * drag one of them to the other's corridor the moment either walked back in.</p>
 *
 * <h2>Stale bindings heal themselves</h2>
 * <p>Nothing invalidates a binding. {@link #twinOriginFor} re-checks against the copies actually
 * standing every single time it is asked, so a copy that has retired, a structure that has relocated
 * (which drops every copy), or a room whose Exits setting was turned off all resolve to "the
 * original" on their own. An invalidation callback is a thing that can be missed, and missing one
 * here means teleporting somebody into solid rock at the bottom of the world.</p>
 *
 * <p>In memory only, like {@code PortalCarriageEvents.STRUCTURES} itself — a reload that forgets
 * which rooms were standing may as well forget where you were standing in them.</p>
 */
public final class PortalExitBindings {

    /** One player's place in one pair's room. */
    private record Key(UUID player, int pairKey) {}

    /** One carriage of one pair — which corridor a follower would be walking into. */
    private record Lead(int pairKey, PortalCarriageRole role) {}

    /** Who last walked in through a carriage, and until when that still says anything. */
    private record Trail(UUID player, long expiresAt) {}

    /**
     * How long after a player walks in that whatever is behind them is still counted as following,
     * in ticks — ten seconds.
     *
     * <p>Sized for the physical story rather than for anything in the code. A villager led into a
     * corridor crosses the midpoint a second or two after the player it is following, because it is
     * <i>behind</i> them; a leash-length of ten seconds covers a mob that got stuck on a doorframe
     * without being so long that the next person through inherits somebody else's followers. And if
     * somebody else does walk in first, the trail simply re-points to them — which is right, because
     * they are the leader now.</p>
     */
    private static final long FOLLOW_TICKS = 200;

    /** Written and read on the server thread; concurrent for the same reason the pair index is. */
    private static final Map<Key, Tile> BOUND = new ConcurrentHashMap<>();

    /** Per carriage, who last walked in through it — see {@link #followerTwinFor}. */
    private static final Map<Lead, Trail> TRAILS = new ConcurrentHashMap<>();

    private PortalExitBindings() {}

    /**
     * Remember that {@code player} last used the corridor standing at {@code tile}.
     *
     * <p>Called on every twin → carriage swap, {@link Tile#BASE} included — coming back through the
     * original is how a binding is cleared, and saying so explicitly is simpler than a separate
     * clear that a caller could forget.</p>
     */
    public static void bind(UUID player, int pairKey, Tile tile) {
        if (player == null || tile == null) return;
        if (Tile.BASE.equals(tile)) {
            BOUND.remove(new Key(player, pairKey));
            return;
        }
        BOUND.put(new Key(player, pairKey), tile);
    }

    /** The tile this player is bound to in this pair, or {@code null} for the original twin. */
    public static Tile boundTile(UUID player, int pairKey) {
        return player == null ? null : BOUND.get(new Key(player, pairKey));
    }

    /**
     * True when anybody is relying on the corridors at {@code tile} still being there.
     *
     * <p>What stops the sliding window pulling the rug out. The moment a player steps back onto the
     * train the room has nobody in it, so the window collapses to
     * {@link PortalRoomTiling#APPROACH_RADIUS} and every distant copy is due to retire — including
     * the one they just walked out of, in the very same tick. Observed live: out through the copy at
     * tile (0, 8) and back in one second later to the original twin, because by then the copy was
     * gone. Sparing a bound copy is what makes "walk back in and you are where you left" survive the
     * walk to the train and back.</p>
     *
     * <p>It is bounded by the same thing everything else is: a pair stops being active once nobody
     * is near either of its carriages, and a draining structure sheds bound copies along with the
     * rest — at which point the binding goes stale on its own and the next visit starts at the
     * original. Riding away is what clears it, not walking a few steps.</p>
     */
    public static boolean anyBoundTo(int pairKey, Tile tile) {
        if (tile == null || BOUND.isEmpty()) return false;
        for (Map.Entry<Key, Tile> entry : BOUND.entrySet()) {
            if (entry.getKey().pairKey() == pairKey && tile.equals(entry.getValue())) return true;
        }
        return false;
    }

    /**
     * Where this player should arrive when they walk into the {@code role} carriage of this pair —
     * or {@code null} to mean "the original twin, as always".
     *
     * <p>Null covers every way a binding can go stale, and it covers them by <b>checking</b> rather
     * than by having been told: the bound tile must still owe a corridor of this role, and that
     * corridor must still be standing in {@code structure}. A copy retires as the window slides, a
     * relocated structure drops all of them, an author can turn Exits off between one visit and the
     * next — all three end here, and all three end with the player walking into the corridor that
     * has always been there.</p>
     */
    public static BlockPos twinOriginFor(ServerLevel level, PortalStructure structure,
                                         CarriageDims dims, UUID player, int pairKey,
                                         PortalCarriageRole role) {
        BlockPos origin = boundOriginFor(structure, dims, player, pairKey, role);
        if (origin == null) return null;
        return chunksLoaded(level, origin, dims, structure.kind()) ? origin : null;
    }

    /**
     * The corridor this player is bound to, before asking whether the world currently has it.
     *
     * <p>Split from {@link #twinOriginFor} so the binding rule — which tile, which role, is that copy
     * standing — is pure integer geometry and unit-tests without a NeoForge bootstrap, the same split
     * {@link PortalExitSites} makes. The one thing left in the caller above is the question that
     * genuinely needs a live world.</p>
     */
    public static BlockPos boundOriginFor(PortalStructure structure, CarriageDims dims,
                                          UUID player, int pairKey, PortalCarriageRole role) {
        Tile tile = boundTile(player, pairKey);
        if (tile == null || structure == null) return null;

        Site site = new Site(tile, role);
        // Standing, not merely owed: the site function says where a copy BELONGS, and a copy that
        // has not been stamped yet — or has retired — is somewhere a player must not be sent.
        if (!structure.exitCopies().has(site)) return null;
        return structure.exitCopyOrigin(dims, site);
    }

    /**
     * True when the corridor at {@code origin} is in chunks the server currently has.
     *
     * <p>The original twin needs no such check — it is stamped in the <b>carriage's own chunk
     * columns</b>, which is the guarantee the whole crossing rests on. A copy is not: it can be
     * eight rooms along the track, far enough out that its columns are no longer loaded by the time
     * somebody walks back in. Teleporting into one drops the player through a floor that has not
     * arrived yet, so an unloaded copy is treated exactly like a retired one and they arrive at the
     * original instead.</p>
     *
     * <p>Asked, never forced: a {@code getChunk(FULL, true)} on the server tick is the shape of the
     * Sable worldgen deadlock, and the cost of saying no is only that the player lands where they
     * always used to.</p>
     */
    /**
     * True when it is safe to put somebody in the corridor whose origin is {@code origin}.
     *
     * <p>Public because more than one thing needs to ask now. The original twin never has to — it is
     * stamped in the carriage's own chunk columns, which is the guarantee the crossing rests on — but
     * a copy is somewhere else, and so is a pair's own exit once {@link PortalRoomExits} has stood it
     * beside another tile.</p>
     */
    public static boolean corridorLoaded(ServerLevel level, PortalFrames.Origin origin,
                                         CarriageDims dims, PortalCorridorKind kind) {
        if (origin == null) return false;
        return chunksLoaded(level,
            BlockPos.containing(origin.x(), origin.y(), origin.z()), dims, kind);
    }

    private static boolean chunksLoaded(ServerLevel level, BlockPos origin, CarriageDims dims,
                                        PortalCorridorKind kind) {
        if (level == null) return false;
        int length = PortalCorridorSize.corridorLength(dims, kind);
        int minChunkX = SectionPos.blockToSectionCoord(origin.getX());
        int maxChunkX = SectionPos.blockToSectionCoord(origin.getX() + length - 1);
        int minChunkZ = SectionPos.blockToSectionCoord(origin.getZ());
        int maxChunkZ = SectionPos.blockToSectionCoord(origin.getZ() + dims.width() - 1);
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.hasChunk(cx, cz)) return false;
            }
        }
        return true;
    }

    /** As {@link #twinOriginFor}, in the shape {@link PortalFrames} works in. */
    public static PortalFrames.Origin twinFrameFor(ServerLevel level, PortalStructure structure,
                                                   CarriageDims dims, UUID player, int pairKey,
                                                   PortalCarriageRole role) {
        return asOrigin(twinOriginFor(level, structure, dims, player, pairKey, role));
    }

    /** Note that {@code player} has just walked in through this carriage, taking followers with them. */
    public static void noteInbound(int pairKey, PortalCarriageRole role, UUID player, long gameTime) {
        if (player == null) return;
        TRAILS.put(new Lead(pairKey, role), new Trail(player, gameTime + FOLLOW_TICKS));
    }

    /**
     * Where anything walking into this carriage's corridor should come out — the copy the last player
     * through it is bound to, or {@code null} for the original twin.
     *
     * <p>Entities have no memory of their own: a villager did not walk out of anything, it was led.
     * So the answer comes from whoever led it, and it has to be remembered rather than looked up,
     * because a follower is by definition <b>behind</b> — it crosses the midpoint a second or two
     * after the player it is following, by which time that player has left the corridor entirely.
     * Asking "who is standing here now" therefore finds nobody exactly when it matters, and the
     * villager is left at the original twin while its player is eight rooms away: the parting-company
     * bug {@link PortalEntityTransit} exists to prevent, one level up.</p>
     *
     * <p>The trail names a <i>player</i>, not a place, so the binding is re-resolved — and so
     * re-validated against the copies actually standing — every time it is read.</p>
     */
    public static PortalFrames.Origin followerTwinFor(ServerLevel level, PortalStructure structure,
                                                      CarriageDims dims, int pairKey,
                                                      PortalCarriageRole role, long gameTime) {
        UUID leader = followerFor(pairKey, role, gameTime);
        return leader == null ? null : twinFrameFor(level, structure, dims, leader, pairKey, role);
    }

    /**
     * Who anything walking into this carriage right now counts as following, or {@code null}.
     *
     * <p>Split from {@link #followerTwinFor} for the reason {@link #boundOriginFor} is split from
     * {@link #twinOriginFor}: the leash rule is arithmetic on a tick count and should be testable
     * without a world. An expired trail is dropped on the way past rather than swept — the map has
     * one entry per portal carriage a player has walked into, so reading it is when it is cheapest
     * to notice.</p>
     */
    public static UUID followerFor(int pairKey, PortalCarriageRole role, long gameTime) {
        Lead lead = new Lead(pairKey, role);
        Trail trail = TRAILS.get(lead);
        if (trail == null) return null;
        if (gameTime > trail.expiresAt()) {
            TRAILS.remove(lead, trail);
            return null;
        }
        return trail.player();
    }

    private static PortalFrames.Origin asOrigin(BlockPos origin) {
        return origin == null
            ? null
            : new PortalFrames.Origin(origin.getX(), origin.getY(), origin.getZ());
    }

    /** Forget one player entirely — every pair. Called when they leave. */
    public static void forget(UUID player) {
        if (player == null) return;
        BOUND.keySet().removeIf(key -> key.player().equals(player));
        TRAILS.values().removeIf(trail -> trail.player().equals(player));
    }

    /**
     * Forget everybody who is no longer here.
     *
     * <p>Kept beside the tick rather than hung off a disconnect event alone: a binding is a few bytes
     * and a crash-disconnected player would otherwise leave one behind for the life of the server.</p>
     */
    public static void pruneTo(Collection<UUID> present) {
        if (!BOUND.isEmpty()) BOUND.keySet().removeIf(key -> !present.contains(key.player()));
        if (!TRAILS.isEmpty()) TRAILS.values().removeIf(trail -> !present.contains(trail.player()));
    }

    public static void clear() {
        BOUND.clear();
        TRAILS.clear();
    }

    public static boolean isEmpty() {
        return BOUND.isEmpty() && TRAILS.isEmpty();
    }

    /** How many <b>bindings</b> are live — for tests and for a diagnostic that wants to see a leak. */
    public static int size() {
        return BOUND.size();
    }
}
