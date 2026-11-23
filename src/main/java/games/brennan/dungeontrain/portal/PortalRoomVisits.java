package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * One player's visits to dimensional sub-variants whose group carries visit rules (ordered steps,
 * seen again, weight after the Xth visit — {@link games.brennan.dungeontrain.track.variant.VisitRules}).
 *
 * <p><b>What counts as a visit:</b> being swapped into the pair's twin, once per pair per player.
 * Counts live on the player ({@link ModDataAttachments#PORTAL_ROOM_VISITS}, kept across deaths).</p>
 *
 * <p><b>Reservations:</b> a room rolled for a rider and stamped, but not yet walked into, counts as
 * visited for that rider's next rolls — two dimensional carriages planned before the player reaches
 * either must not both be the lobby. In memory only; dropped on entry, on eviction and at shutdown.</p>
 *
 * <p><b>Restart:</b> a pair whose room came out of a visit-ruled roll is remembered in
 * {@link DungeonTrainWorldData#visitRuledPick}, so re-planning after a restart lands on the same room.</p>
 */
public final class PortalRoomVisits {

    private record Reservation(UUID rider, String room) {}

    private static final Map<Integer, Reservation> RESERVED = new ConcurrentHashMap<>();
    private static final Set<String> COUNTED = ConcurrentHashMap.newKeySet();

    private PortalRoomVisits() {}

    /** Whether {@code room} is rolled through a group that uses visit rules — as its parent or a member. */
    public static boolean isRuled(String room) {
        if (room == null) return false;
        Optional<TrackVariantGroup> own = TrackVariantGroupStore.get(TrackKind.PORTAL_ROOM, room);
        if (own.isPresent() && own.get().usesVisitRules()) return true;
        return TrackVariantGroupStore.findParentOf(TrackKind.PORTAL_ROOM, room)
            .flatMap(parent -> TrackVariantGroupStore.get(TrackKind.PORTAL_ROOM, parent))
            .map(TrackVariantGroup::usesVisitRules)
            .orElse(false);
    }

    /** {@code player}'s stored visit counts. */
    public static Map<String, Integer> counts(ServerPlayer player) {
        return player.getData(ModDataAttachments.PORTAL_ROOM_VISITS.get());
    }

    /**
     * The visit counts a roll for {@code rider} at {@code pairKey} reads: stored visits plus one for
     * each room reserved for them at another pair. {@code null} with no rider — the roll then
     * ignores visit rules.
     */
    public static ToIntFunction<String> viewFor(ServerPlayer rider, int pairKey) {
        if (rider == null) return null;
        Map<String, Integer> stored = counts(rider);
        Map<String, Integer> reserved = new HashMap<>();
        UUID id = rider.getUUID();
        RESERVED.forEach((key, r) -> {
            if (key != pairKey && r.rider().equals(id)) reserved.merge(r.room(), 1, Integer::sum);
        });
        return room -> stored.getOrDefault(room, 0) + reserved.getOrDefault(room, 0);
    }

    /** A pair's room is standing: remember a visit-ruled roll and reserve it for its rider. */
    public static void onStamped(ServerLevel level, int pairKey, String room, ServerPlayer rider) {
        if (!isRuled(room)) return;
        DungeonTrainWorldData.get(level).rememberVisitRuledPick(pairKey, room);
        if (rider != null) RESERVED.put(pairKey, new Reservation(rider.getUUID(), room));
    }

    /** {@code player} was swapped into {@code pairKey}'s twin, which stands {@code room}. */
    public static void onEntered(ServerPlayer player, int pairKey, String room) {
        Reservation r = RESERVED.get(pairKey);
        if (r != null && r.rider().equals(player.getUUID())) RESERVED.remove(pairKey);
        if (!isRuled(room)) return;
        if (!COUNTED.add(player.getUUID() + "|" + pairKey + "|" + room)) return;
        player.setData(ModDataAttachments.PORTAL_ROOM_VISITS.get(), increment(counts(player), room));
    }

    /** {@code counts} with {@code room} one higher — a new map, the attachment is never mutated. */
    static Map<String, Integer> increment(Map<String, Integer> counts, String room) {
        Map<String, Integer> next = new HashMap<>(counts);
        next.merge(room, 1, Integer::sum);
        return Map.copyOf(next);
    }

    /** Wipe {@code player}'s visit counts (debug command). */
    public static void reset(ServerPlayer player) {
        player.setData(ModDataAttachments.PORTAL_ROOM_VISITS.get(), Map.of());
        UUID id = player.getUUID();
        RESERVED.values().removeIf(r -> r.rider().equals(id));
        COUNTED.removeIf(k -> k.startsWith(id + "|"));
    }

    /** {@code pairKey}'s structure is gone; its reservation and entry marks go with it. */
    public static void release(int pairKey) {
        RESERVED.remove(pairKey);
        String mid = "|" + pairKey + "|";
        COUNTED.removeIf(k -> k.contains(mid));
    }

    public static void clear() {
        RESERVED.clear();
        COUNTED.clear();
    }
}
