package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tunnel template groups on the editor roster wire. */
final class EditorRosterTunnelGroupsTest {

    private static EditorRosterPacket roundTrip(EditorRosterPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        packet.encode(buf);
        return EditorRosterPacket.decode(buf);
    }

    @Test
    @DisplayName("a row's groups and the group registry survive the trip")
    void roundTrips() {
        EditorTypeMenusPacket.Variant v = new EditorTypeMenusPacket.Variant("arch", 2, 0, -1, 1,
            "TRACKS", "tunnel_section", "arch", true, false, List.of(), List.of())
            .withGroups(List.of("brick", "stone"));
        EditorRosterPacket sent = new EditorRosterPacket(
            List.of(new EditorRosterPacket.Group("tracks", "Tunnel Section", "tunnel_section",
                List.of(new EditorRosterPacket.Entry(v, 0)))),
            "", EditorRosterPacket.TrainSize.UNKNOWN, List.of(),
            new EditorRosterPacket.TunnelGroups(Map.of("stone", 3, "brick", 1), 0));

        EditorRosterPacket back = roundTrip(sent);

        assertEquals(List.of("brick", "stone"), back.groups().get(0).entries().get(0).variant().groupIds());
        assertEquals(Map.of("stone", 3, "brick", 1), back.tunnelGroups().weights());
        assertEquals(0, back.tunnelGroups().ungroupedWeight());
    }

    @Test
    @DisplayName("a roster built the old way carries an empty registry")
    void defaultsEmpty() {
        EditorRosterPacket back = roundTrip(new EditorRosterPacket(List.of(), ""));
        assertEquals(EditorRosterPacket.TunnelGroups.EMPTY, back.tunnelGroups());
    }
}
