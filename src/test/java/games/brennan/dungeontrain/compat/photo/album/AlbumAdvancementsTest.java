package games.brennan.dungeontrain.compat.photo.album;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlbumAdvancementsTest {

    private static JsonObject adv(String name) throws IOException {
        return JsonParser.parseString(Files.readString(
            RepoPaths.advancements().resolve("enchiridion/" + name + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    @DisplayName("one photo earns Keepsake; a photo on all 16 pages also earns No Room Left")
    void actionsByPhotoCount() {
        assertEquals(List.of(), AlbumAdvancements.actionsFor(0));
        assertEquals(List.of(EnchiridionAdvancements.ALBUM_PHOTO), AlbumAdvancements.actionsFor(1));
        assertEquals(List.of(EnchiridionAdvancements.ALBUM_PHOTO), AlbumAdvancements.actionsFor(15));
        assertEquals(List.of(EnchiridionAdvancements.ALBUM_PHOTO, EnchiridionAdvancements.ALBUM_FULL), AlbumAdvancements.actionsFor(16));
        assertEquals(16, AlbumAdvancements.FULL);
    }

    @Test
    @DisplayName("Memory Lane → Keepsake → No Room Left, in The Darkroom, fired by the matching action ids")
    void dataMatchesTheCode() throws IOException {
        assertEquals("dungeontrain:enchiridion/darkroom", adv("memory_lane").get("parent").getAsString());
        assertEquals("dungeontrain:enchiridion/memory_lane", adv("keepsake").get("parent").getAsString());
        assertEquals("dungeontrain:enchiridion/keepsake", adv("no_room_left").get("parent").getAsString());
        assertEquals(EnchiridionAdvancements.FOUND_ALBUM, actionOf(adv("memory_lane")));
        assertEquals(EnchiridionAdvancements.ALBUM_PHOTO, actionOf(adv("keepsake")));
        assertEquals(EnchiridionAdvancements.ALBUM_FULL, actionOf(adv("no_room_left")));
    }

    @Test
    @DisplayName("Memory Lane is someone else's album: not your own, not an ordinary one")
    void someoneElsesAlbum() {
        UUID me = UUID.randomUUID(), them = UUID.randomUUID();
        assertTrue(AlbumAdvancements.isSomeoneElsesAlbum(Optional.of(them), me));
        assertFalse(AlbumAdvancements.isSomeoneElsesAlbum(Optional.of(me), me));
        assertFalse(AlbumAdvancements.isSomeoneElsesAlbum(Optional.empty(), me));
    }

    private static String actionOf(JsonObject adv) {
        JsonObject c = adv.getAsJsonObject("criteria").getAsJsonObject("album");
        assertEquals("dungeontrain:gameplay_action", c.get("trigger").getAsString());
        return c.getAsJsonObject("conditions").get("actionId").getAsString();
    }
}
