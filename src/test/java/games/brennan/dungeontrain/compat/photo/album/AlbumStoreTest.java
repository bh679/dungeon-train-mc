package games.brennan.dungeontrain.compat.photo.album;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlbumStoreTest {

    private static final UUID ALEX = UUID.fromString("0000000a-0000-0000-0000-000000000001");
    private static final UUID BO = UUID.fromString("0000000b-0000-0000-0000-000000000002");
    private static final String H1 = "1".repeat(64);
    private static final String H2 = "2".repeat(64);

    @TempDir
    Path dir;

    private final List<AlbumStore> stores = new ArrayList<>();

    /** A store on the temp dir; every one is flushed before the dir is deleted, so no write lands mid-cleanup. */
    private AlbumStore newStore() {
        AlbumStore store = new AlbumStore(dir);
        stores.add(store);
        return store;
    }

    @AfterEach
    void flushAll() {
        stores.forEach(AlbumStore::flush);
    }

    private static AlbumStore.Entry album(long rev, AlbumStore.Page... pages) {
        return new AlbumStore.Entry(rev, List.of(pages));
    }

    @Test
    @DisplayName("a player's live and Free Play albums are separate albums")
    void kindsAreSeparate() {
        AlbumStore store = newStore();
        store.put(ALEX, AlbumKind.LIVE, album(1, new AlbumStore.Page(H1, "live", null)));
        store.put(ALEX, AlbumKind.FREE_PLAY, album(2, new AlbumStore.Page(H2, "free", null)));
        assertEquals(H1, store.entry(ALEX, AlbumKind.LIVE).pages().get(0).hash());
        assertEquals(H2, store.entry(ALEX, AlbumKind.FREE_PLAY).pages().get(0).hash());
        assertSame(AlbumStore.Entry.EMPTY, store.entry(BO, AlbumKind.LIVE));
    }

    @Test
    @DisplayName("an album survives a restart — it is read back from disk with its rev, notes and photo")
    void survivesReload() {
        AlbumStore store = newStore();
        store.put(ALEX, AlbumKind.FREE_PLAY, album(42,
                new AlbumStore.Page(H1, "first", "{id:\"exposure:photograph\",count:1}"),
                new AlbumStore.Page(null, "a gap", null)));
        store.flush();
        AlbumStore fresh = newStore();
        AlbumStore.Entry read = fresh.entry(ALEX, AlbumKind.FREE_PLAY);
        assertEquals(42L, read.rev());
        assertEquals("first", read.pages().get(0).note());
        assertEquals("{id:\"exposure:photograph\",count:1}", read.pages().get(0).photo());
        assertNull(read.pages().get(1).hash());
        assertEquals("a gap", read.pages().get(1).note());
        assertTrue(Files.exists(dir.resolve(ALEX.toString()).resolve("free_play.json")));
    }

    @Test
    @DisplayName("a picture is readable as soon as it is handed over, and after it lands on disk")
    void imagesReadableBeforeAndAfterWrite() {
        AlbumStore store = newStore();
        byte[] png = {1, 2, 3};
        store.put(ALEX, AlbumKind.LIVE, album(1, new AlbumStore.Page(H1, "", null)));
        store.putImages(Map.of(H1, png));
        assertArrayEquals(png, store.image(H1).orElseThrow());
        store.flush();
        assertArrayEquals(png, newStore().image(H1).orElseThrow());
        assertTrue(store.hasImage(H1));
    }

    @Test
    @DisplayName("a picture no album names any more is deleted; one still named elsewhere stays")
    void prunesUnnamedPictures() {
        AlbumStore store = newStore();
        store.put(ALEX, AlbumKind.LIVE, album(1, new AlbumStore.Page(H1, "", null), new AlbumStore.Page(H2, "", null)));
        store.put(BO, AlbumKind.FREE_PLAY, album(1, new AlbumStore.Page(H2, "", null)));
        store.putImages(Map.of(H1, new byte[] {1}, H2, new byte[] {2}));
        store.flush();
        store.put(ALEX, AlbumKind.LIVE, album(2));   // Alex empties their album
        store.flush();
        assertFalse(store.hasImage(H1));
        assertTrue(store.hasImage(H2), "Bo's album still names it");
    }

    @Test
    void junkHashesAndUnknownPicturesAreSafe() {
        AlbumStore store = newStore();
        assertTrue(store.image("../../etc/passwd").isEmpty());
        assertTrue(store.image(H1).isEmpty());
        assertNull(new AlbumStore.Page("nope", null, null).hash());
        assertEquals("", new AlbumStore.Page(null, null, null).note());
    }

    @Test
    void anUnreadableFileStartsEmpty() throws Exception {
        Path file = dir.resolve(ALEX.toString()).resolve("live.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ not json");
        assertSame(AlbumStore.Entry.EMPTY, newStore().entry(ALEX, AlbumKind.LIVE));
    }

    @Test
    void kindForRun() {
        assertEquals(AlbumKind.FREE_PLAY, AlbumKind.forRun(true));
        assertEquals(AlbumKind.LIVE, AlbumKind.forRun(false));
    }
}
