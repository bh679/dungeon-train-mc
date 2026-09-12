package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.editor.TemplateBuilderLookup;
import games.brennan.dungeontrain.event.PortalCarriageEvents;
import games.brennan.dungeontrain.template.BuilderCredit;
import games.brennan.dungeontrain.track.variant.TrackKind;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells a player who built the dimensional carriage they are standing in, once per room.
 *
 * <p>The sibling of {@link PortalLibraryGreeter}, driven from the same portal-room occupancy scan
 * ({@code BoardingProgressEvents}) so it adds no scan of its own: it is handed the player and the
 * pair whose room body they are in and decides whether there is anything to say.</p>
 *
 * <h2>Once per room, not once per entry</h2>
 * <p>The last pair greeted is remembered per player, so standing in a doorway does not repeat the
 * line and stepping back into the room you just left stays quiet. A DIFFERENT credited room
 * announces again — that is news.</p>
 *
 * <h2>Local data, no gate</h2>
 * <p>The credit is read from the room template's own record — the shipped {@code weights.json}
 * builder, or the download credit of a build this install pulled from another player's profile
 * ({@link TemplateBuilderLookup#track}). Nothing is fetched, so unlike the library greeter there is
 * no network-consent gate to wait on: the answer is known on the first scan, and a room with no
 * credit is simply silent.</p>
 */
public final class PortalBuilderGreeter {

    /** Player → the pair whose builder they were last told about. */
    private static final Map<UUID, Integer> GREETED = new ConcurrentHashMap<>();

    private PortalBuilderGreeter() {}

    /**
     * Consider greeting {@code player}, who is standing in the room body of {@code pairKey}.
     *
     * <p>Cheap and total: an uncredited room costs one map read and one weights lookup, and nothing
     * here can throw into the occupancy scan — an unknown pair reads as "no room", and a credit that
     * names nobody reads as no credit.</p>
     */
    public static void tick(ServerPlayer player, int pairKey) {
        if (player == null) return;
        Integer last = GREETED.get(player.getUUID());
        if (last != null && last == pairKey) return;

        String roomName = PortalCarriageEvents.portalRoomNameFor(pairKey);
        BuilderCredit credit = roomName == null ? null
            : TemplateBuilderLookup.track(TrackKind.PORTAL_ROOM, roomName);
        if (credit == null || !credit.known()) {
            // Not credited. Forget the last one so walking out of a credited room and back in later
            // announces again — the map holds "the credited room you are in", not a history.
            GREETED.remove(player.getUUID());
            return;
        }

        GREETED.put(player.getUUID(), pairKey);
        player.sendSystemMessage(
            PortalBuilderMessage.random(player.getRandom(), credit.display(), isOwn(player, credit)));
    }

    /**
     * Whether the credit names the player themselves. Decided on the uuid, never the cached name —
     * a display name is a rename away from belonging to somebody else.
     */
    private static boolean isOwn(ServerPlayer player, BuilderCredit credit) {
        return credit.hasUuid()
            && credit.uuid().equals(BuilderCredit.normaliseUuid(player.getUUID().toString()));
    }

    /** Forget a player's current room — on logout, and on server stop. */
    public static void forget(UUID player) {
        GREETED.remove(player);
    }

    /** Drop every greeting record. */
    public static void clear() {
        GREETED.clear();
    }
}
