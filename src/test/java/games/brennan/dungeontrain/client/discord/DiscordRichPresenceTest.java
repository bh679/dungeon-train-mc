package games.brennan.dungeontrain.client.discord;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.client.discord.PresenceSnapshot.Place;
import games.brennan.dungeontrain.worldgen.BandDimensionName;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordRichPresenceTest {

    @Test
    @DisplayName("Only the three vanilla places are named; every other band reads as Other")
    void placeMapping() {
        assertEquals(Place.OVERWORLD, PresenceSnapshot.placeFor("minecraft:overworld"));
        assertEquals(Place.NETHER, PresenceSnapshot.placeFor("minecraft:the_nether"));
        assertEquals(Place.END, PresenceSnapshot.placeFor("minecraft:the_end"));
        assertEquals(Place.OTHER, PresenceSnapshot.placeFor(BandDimensionName.DIMENSIONAL_CARRIAGE));
        assertEquals(Place.OTHER, PresenceSnapshot.placeFor("othermod:somewhere"));
        assertEquals(Place.OTHER, PresenceSnapshot.placeFor(null));
        for (TrainPhase phase : TrainPhase.values()) {
            String id = BandDimensionName.of(phase);
            if (id.startsWith("dungeontrain:")) {
                assertEquals(Place.OTHER, PresenceSnapshot.placeFor(id), "band " + phase + " must not be named");
            }
        }
    }

    @Test
    @DisplayName("In the overworld the band stands in for the dimension, as on the F3 screen")
    void effectiveDimension() {
        String carriage = BandDimensionName.DIMENSIONAL_CARRIAGE;
        assertEquals("minecraft:overworld", PresenceSnapshot.effectiveDimensionId("minecraft:overworld", "", false, carriage));
        assertEquals("minecraft:the_nether",
                PresenceSnapshot.effectiveDimensionId("minecraft:overworld", "minecraft:the_nether", false, carriage));
        assertEquals(carriage,
                PresenceSnapshot.effectiveDimensionId("minecraft:overworld", "minecraft:the_nether", true, carriage));
        // A real dimension is already the truth; the band is ignored there.
        assertEquals("minecraft:the_end",
                PresenceSnapshot.effectiveDimensionId("minecraft:the_end", "dungeontrain:void", true, carriage));
    }

    @Test
    @DisplayName("Frames are opcode and length as little-endian int32s, then UTF-8 JSON")
    void frameEncoding() {
        String json = "{\"v\":1,\"client_id\":\"é\"}";
        byte[] frame = DiscordIpcConnection.encodeFrame(DiscordIpcConnection.OP_HANDSHAKE, json);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer header = ByteBuffer.wrap(frame, 0, 8).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, header.getInt());
        assertEquals(payload.length, header.getInt());
        byte[] body = new byte[frame.length - 8];
        System.arraycopy(frame, 8, body, 0, body.length);
        assertArrayEquals(payload, body);
    }

    @Test
    @DisplayName("The card carries one line and the logo: no state, no timer")
    void activityShape() {
        JsonObject activity = DiscordIpcConnection.activityJson(new PresenceSnapshot(Place.NETHER, "In the Nether"));
        assertEquals("In the Nether", activity.get("details").getAsString());
        assertFalse(activity.has("state"));
        assertFalse(activity.has("timestamps"));
        JsonObject assets = activity.getAsJsonObject("assets");
        assertEquals("logo", assets.get("large_image").getAsString());
        assertEquals("nether", assets.get("small_image").getAsString());

        JsonObject menu = DiscordIpcConnection.activityJson(new PresenceSnapshot(Place.MENU, "In the menu"));
        assertFalse(menu.getAsJsonObject("assets").has("small_image"));
    }

    @Test
    @DisplayName("Windows probes the ten named pipes; other systems only list sockets that exist")
    void candidatePaths() {
        List<String> windows = DiscordIpcConnection.candidatePaths(true, Map.of());
        assertEquals(10, windows.size());
        assertEquals("\\\\.\\pipe\\discord-ipc-0", windows.get(0));
        List<String> unix = DiscordIpcConnection.candidatePaths(false,
                Map.of("XDG_RUNTIME_DIR", "/definitely/not/a/dir"));
        assertTrue(unix.stream().noneMatch(p -> p.startsWith("/definitely")));
    }
}
