package games.brennan.dungeontrain.compat.photo.album;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.compat.photo.PhotoPngCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlbumSavePayloadTest {

    private static final UUID OWNER = UUID.fromString("0000000a-0000-0000-0000-00000000000b");
    private static final String HASH = "ba6071d28faa0e17453bee65d96fec949a2860f95d7abf785499d784a7be6121";

    @Test
    @DisplayName("the picture hash is the relay's: a 3x2 image hashes to what playerphotos.inspectImage gives")
    void hashMatchesRelay() {
        // Value from the relay: photos.inspectImage(<3x2 palette PNG of indices 0..5>).hash
        assertEquals(HASH, AlbumImageHash.of(3, 2, new byte[] {0, 1, 2, 3, 4, 5}));
    }

    @Test
    @DisplayName("hashing the PNG the codec writes, as the relay does, names it the same")
    void hashSurvivesCodecRoundTrip() throws IOException {
        byte[] pixels = {0, 1, 2, 3, 4, 5};
        PhotoPngCodec.Decoded decoded = PhotoPngCodec.decode(PhotoPngCodec.encode(3, 2, pixels, new int[256]));
        assertEquals(HASH, AlbumImageHash.of(decoded.width(), decoded.height(), decoded.pixels()));
    }

    @Test
    void hashRefusesMismatchedPixels() {
        assertThrows(IllegalArgumentException.class, () -> AlbumImageHash.of(3, 3, new byte[4]));
    }

    @Test
    void isHash() {
        assertTrue(AlbumImageHash.isHash(HASH));
        assertFalse(AlbumImageHash.isHash(HASH.toUpperCase()));
        assertFalse(AlbumImageHash.isHash("abc"));
        assertFalse(AlbumImageHash.isHash(null));
    }

    @Test
    @DisplayName("a save is numbered by the clock, but always after the last save here")
    void nextRev() {
        assertEquals(5_000L, AlbumSavePayload.nextRev(1_000L, 5_000L));
        assertEquals(5_001L, AlbumSavePayload.nextRev(5_000L, 5_000L));
        assertEquals(9_001L, AlbumSavePayload.nextRev(9_000L, 5_000L));
    }

    @Test
    @DisplayName("the save keeps empty pages in place and their notes")
    void saveShape() {
        JsonObject body = AlbumSavePayload.save(OWNER, "Alex", 42L,
                List.of(new AlbumSavePayload.Page(HASH, "first"), new AlbumSavePayload.Page(null, "gap")));
        assertEquals("0000000a00000000000000000000000b", body.get("uuid").getAsString());
        assertEquals(42L, body.get("rev").getAsLong());
        assertEquals(HASH, body.getAsJsonArray("pages").get(0).getAsJsonObject().get("hash").getAsString());
        assertTrue(body.getAsJsonArray("pages").get(1).getAsJsonObject().get("hash").isJsonNull());
        assertEquals("gap", body.getAsJsonArray("pages").get(1).getAsJsonObject().get("note").getAsString());
    }

    @Test
    void saveNeverSendsMoreThanAnAlbumHolds() {
        List<AlbumSavePayload.Page> pages = new ArrayList<>();
        for (int i = 0; i < 20; i++) pages.add(new AlbumSavePayload.Page(null, ""));
        assertEquals(AlbumSavePayload.MAX_PAGES, AlbumSavePayload.save(OWNER, "Alex", 1, pages).getAsJsonArray("pages").size());
    }

    @Test
    @DisplayName("an album from the relay round-trips through the save shape")
    void parseRoundTrip() {
        JsonObject body = AlbumSavePayload.save(OWNER, "Alex", 7L,
                List.of(new AlbumSavePayload.Page(HASH, "n"), new AlbumSavePayload.Page(null, "")));
        Optional<AlbumSavePayload.Album> album = AlbumSavePayload.parse(body);
        assertTrue(album.isPresent());
        assertEquals(OWNER, album.get().owner());
        assertEquals(7L, album.get().rev());
        assertEquals(HASH, album.get().pages().get(0).hash());
        assertNull(album.get().pages().get(1).hash());
    }

    @Test
    void parseRefusesJunk() {
        assertTrue(AlbumSavePayload.parse(null).isEmpty());
        assertTrue(AlbumSavePayload.parse(JsonParser.parseString("{\"uuid\":\"nope\",\"rev\":1}")).isEmpty());
        assertTrue(AlbumSavePayload.parse(JsonParser.parseString("[]")).isEmpty());
    }

    @Test
    @DisplayName("a page hash the relay should never have sent is read as an empty page")
    void badHashIsEmptyPage() {
        assertNull(new AlbumSavePayload.Page("../../etc", "x").hash());
    }

    @Test
    @DisplayName("a first album's save carries no index, so its body is what older relays already take")
    void firstAlbumBodyUnchanged() {
        List<AlbumSavePayload.Page> pages = List.of(new AlbumSavePayload.Page(HASH, "n"));
        assertEquals(AlbumSavePayload.save(OWNER, "Alex", 5, pages), AlbumSavePayload.save(OWNER, 0, "Alex", 5, pages));
        assertFalse(AlbumSavePayload.save(OWNER, 0, "Alex", 5, pages).has("index"));
        assertEquals(2, AlbumSavePayload.save(OWNER, 2, "Alex", 5, pages).get("index").getAsInt());
    }

    @Test
    @DisplayName("mine: every album with its index, and how many the owner may keep")
    void parseMineWithAlbums() {
        JsonObject body = JsonParser.parseString("{\"ok\":true,\"albumLimit\":10,\"album\":null,\"albums\":["
                + "{\"uuid\":\"0000000a00000000000000000000000b\",\"rev\":3,\"pages\":[]},"
                + "{\"uuid\":\"0000000a00000000000000000000000b\",\"index\":1,\"rev\":4,\"pages\":[]}]}").getAsJsonObject();
        AlbumSavePayload.Mine mine = AlbumSavePayload.parseMine(body);
        assertEquals(10, mine.albumLimit());
        assertEquals(2, mine.albums().size());
        assertEquals(0, mine.albums().get(0).index());
        assertEquals(1, mine.albums().get(1).index());
    }

    @Test
    @DisplayName("mine from an older relay: just the first album, and a limit of one")
    void parseMineFromOldRelay() {
        JsonObject body = JsonParser.parseString("{\"ok\":true,\"album\":"
                + "{\"uuid\":\"0000000a00000000000000000000000b\",\"rev\":3,\"pages\":[]}}").getAsJsonObject();
        AlbumSavePayload.Mine mine = AlbumSavePayload.parseMine(body);
        assertEquals(1, mine.albumLimit());
        assertEquals(1, mine.albums().size());
        assertEquals(OWNER, mine.albums().get(0).owner());
        assertEquals(1, AlbumSavePayload.parseMine(JsonParser.parseString("{\"ok\":true,\"album\":null}").getAsJsonObject()).albumLimit());
    }
}
