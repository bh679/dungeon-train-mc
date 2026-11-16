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
    @DisplayName("Vanilla places and every band are named as on F3; carriages and other mods read as Other")
    void placeMapping() {
        assertEquals("dungeontrain.discord_status.overworld", PresenceSnapshot.placeFor("minecraft:overworld").langKey());
        assertEquals("dungeontrain.discord_status.nether", PresenceSnapshot.placeFor("minecraft:the_nether").langKey());
        assertEquals("end", PresenceSnapshot.placeFor("minecraft:the_end").imageKey());
        assertEquals(Place.OTHER, PresenceSnapshot.placeFor(BandDimensionName.DIMENSIONAL_CARRIAGE));
        assertEquals(Place.OTHER, PresenceSnapshot.placeFor("othermod:somewhere"));
        assertEquals(Place.OTHER, PresenceSnapshot.placeFor("dungeontrain:ud"));
        assertEquals(Place.OTHER, PresenceSnapshot.placeFor(null));
        for (TrainPhase phase : TrainPhase.values()) {
            String id = BandDimensionName.of(phase);
            if (id.startsWith("dungeontrain:")) {
                assertEquals("gui.dungeontrain.editor_menu.phase." + phase.token(),
                        PresenceSnapshot.placeFor(id).langKey(), "band " + phase);
            }
        }
    }

    @Test
    @DisplayName("Every band name the card can show exists in every shipped locale")
    void bandNamesInEveryLocale() throws Exception {
        java.nio.file.Path dir = games.brennan.dungeontrain.RepoPaths.root().resolve("src/main/resources/assets/dungeontrain/lang");
        try (var files = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject lang = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
                for (TrainPhase phase : TrainPhase.values()) {
                    String key = PresenceSnapshot.placeFor(BandDimensionName.of(phase)).langKey();
                    assertTrue(lang.has(key), file.getFileName() + " lacks " + key);
                }
            }
        }
    }

    @Test
    @DisplayName("A band shows only once the player is as deep in as its advancement needs")
    void depthGate() {
        assertEquals(games.brennan.dungeontrain.advancement.BandAdvancements.ENTRY_DEPTH_BLOCKS, BandDepthGate.DEPTH_BLOCKS);
        String ud = "dungeontrain:upside_down";
        BandDepthGate g = BandDepthGate.EMPTY.next("minecraft:overworld", 0, true);
        assertEquals("minecraft:overworld", g.shownId(), "spawn overworld is named at once");
        g = g.next(ud, 1000, true);
        assertEquals("minecraft:overworld", g.shownId(), "just entered: keep the last place");
        g = g.next(ud, 1399, true);
        assertEquals("minecraft:overworld", g.shownId());
        g = g.next(ud, 1400, true);
        assertEquals(ud, g.shownId(), "400 blocks in: named");
        g = g.next("minecraft:the_nether", 5000, true).next("minecraft:the_nether", 4600, true);
        assertEquals("minecraft:the_nether", g.shownId(), "riding backwards gates the same");
        assertEquals("minecraft:the_end", BandDepthGate.EMPTY.next("minecraft:the_end", 0, false).shownId(),
                "a real dimension is named at once");
        assertEquals(null, BandDepthGate.EMPTY.next(ud, 0, true).shownId(), "joining mid-band waits too");
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
        JsonObject activity = DiscordIpcConnection.activityJson(new PresenceSnapshot("nether", "In the Nether"));
        assertEquals("In the Nether", activity.get("details").getAsString());
        assertFalse(activity.has("state"));
        assertFalse(activity.has("timestamps"));
        JsonObject assets = activity.getAsJsonObject("assets");
        assertEquals("logo", assets.get("large_image").getAsString());
        assertEquals("nether", assets.get("small_image").getAsString());

        JsonObject menu = DiscordIpcConnection.activityJson(new PresenceSnapshot(Place.MENU.imageKey(), "In the menu"));
        assertFalse(menu.getAsJsonObject("assets").has("small_image"));
    }

    @Test
    @DisplayName("A normal SET_ACTIVITY reply has evt null and is not an error; evt ERROR is")
    void replyErrorDetection() {
        assertFalse(DiscordIpcConnection.isErrorReply("{\"cmd\":\"SET_ACTIVITY\",\"data\":{},\"evt\":null,\"nonce\":\"x\"}"));
        assertFalse(DiscordIpcConnection.isErrorReply("{\"cmd\":\"DISPATCH\",\"evt\":\"READY\"}"));
        assertTrue(DiscordIpcConnection.isErrorReply("{\"cmd\":\"SET_ACTIVITY\",\"evt\":\"ERROR\",\"data\":{\"code\":4000}}"));
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
