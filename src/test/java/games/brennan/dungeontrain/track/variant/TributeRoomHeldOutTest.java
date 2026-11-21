package games.brennan.dungeontrain.track.variant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TributeRoomHeldOutTest {

    @Test
    @DisplayName("tribute rooms leave the room roll and every other room keeps its place")
    void tributeRoomsHeldOut() {
        List<String> pool = List.of("gravityfalls", "gridpath", "tribute", "tribute_entrance", "house");
        assertEquals(List.of("gravityfalls", "gridpath", "house"), TrackVariantRegistry.heldOut(pool));
    }

    @Test
    @DisplayName("perks off: the portal room pool loses tribute rooms, other kinds are untouched")
    void onlyPortalRooms() {
        List<String> pool = List.of("a", "tribute");
        assertEquals(List.of("a"), TrackVariantRegistry.withoutHeldRooms(TrackKind.PORTAL_ROOM, pool));
        assertEquals(pool, TrackVariantRegistry.withoutHeldRooms(TrackKind.TUNNEL_SECTION, pool));
    }
}
