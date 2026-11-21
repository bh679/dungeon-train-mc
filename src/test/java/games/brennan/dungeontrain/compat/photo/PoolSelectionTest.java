package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.compat.photo.PoolSelection.PoolPhoto;
import games.brennan.dungeontrain.compat.photo.PoolSelection.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoolSelectionTest {

    private static final int POOL_MAX = 40;
    private static final long HOUR = 60 * 60 * 1000L;
    private static final PhotoPngCodec.Decoded IMAGE = new PhotoPngCodec.Decoded(1, 1, new byte[] {0});

    private static Row row(int id) { return row(id, "", 0); }

    private static Row row(int id, String tributedBy, int tributes) {
        return new Row(id, "author", tributedBy, tributes, 5, 0, 5, List.of());
    }

    private static PoolPhoto held(Row row, long fetchedAt, String... vettedFor) {
        return new PoolPhoto(row, IMAGE, fetchedAt, Set.of(vettedFor));
    }

    private static List<PoolPhoto> heldPool(int count) {
        return IntStream.range(0, count).mapToObj(i -> held(row(1000 + i), 0L, "a")).toList();
    }

    private static List<Row> answer(int first, int count) {
        return IntStream.range(first, first + count).mapToObj(PoolSelectionTest::row).toList();
    }

    private static List<Integer> ids(List<Row> rows) { return rows.stream().map(Row::id).toList(); }

    @Test
    @DisplayName("Empty pool: every row of a 10-photo answer is downloaded")
    void emptyPoolTakesAll() {
        assertEquals(10, PoolSelection.toDownload(answer(1, 10), List.of(), POOL_MAX, List.of()).size());
    }

    @Test
    @DisplayName("Full pool: nothing ordinary is downloaded")
    void fullPoolTakesNothing() {
        assertEquals(0, PoolSelection.toDownload(answer(1, 10), heldPool(40), POOL_MAX, List.of()).size());
    }

    @Test
    @DisplayName("Partly full pool: only as many as there are free slots, in the relay's order")
    void freeSlotsOnly() {
        assertEquals(List.of(1, 2, 3), ids(PoolSelection.toDownload(answer(1, 10), heldPool(37), POOL_MAX, List.of())));
    }

    @Test
    @DisplayName("Owed Tribute returns are always downloaded, even into a full pool")
    void owedAlwaysTaken() {
        List<Row> rows = new ArrayList<>(List.of(row(7, "Payer", 2), row(8, "Other", 0)));
        rows.addAll(answer(1, 8));
        assertEquals(List.of(7, 8), ids(PoolSelection.toDownload(rows, heldPool(40), POOL_MAX, List.of())));
    }

    @Test
    @DisplayName("Owed returns take free slots first, so ordinary finds fill only what is left")
    void owedUseFreeSlots() {
        List<Row> rows = new ArrayList<>(List.of(row(7, "Payer", 0)));
        rows.addAll(answer(1, 9));
        assertEquals(List.of(7, 1), ids(PoolSelection.toDownload(rows, heldPool(38), POOL_MAX, List.of())));
    }

    @Test
    @DisplayName("Full pool: one photo per Tribute price a player still needs, when nothing held meets it")
    void unmetNeedTaken() {
        // price = 1 + tributes; a need of 5 wants 4+ Tributes.
        List<Row> rows = List.of(row(1), row(2, "", 4), row(3, "", 6), row(4));
        assertEquals(List.of(2), ids(PoolSelection.toDownload(rows, heldPool(40), POOL_MAX, List.of(5))));
        assertEquals(List.of(2, 3), ids(PoolSelection.toDownload(rows, heldPool(40), POOL_MAX, List.of(5, 7))));
    }

    @Test
    @DisplayName("A need some held photo already meets downloads nothing more")
    void metNeedSkipped() {
        List<PoolPhoto> pool = new ArrayList<>(heldPool(39));
        pool.add(held(row(900, "", 6), 0L, "a"));
        assertEquals(Set.of(), PoolSelection.openNeeds(pool, List.of(5, 7)));
        assertEquals(0, PoolSelection.toDownload(List.of(row(2, "", 6)), pool, POOL_MAX, List.of(5, 7)).size());
    }

    @Test
    @DisplayName("Download reuses a held image and counts only real fetches")
    void downloadCountsFetches() throws Exception {
        byte[] png = PhotoPngCodec.encode(1, 1, new byte[] {0}, new int[] {0xFF000000});
        AtomicInteger fetches = new AtomicInteger();
        PoolSelection.ImageFetcher fetcher = id -> {
            fetches.incrementAndGet();
            return id == 3 ? Optional.empty() : Optional.of(png);
        };
        List<PoolPhoto> pool = List.of(held(row(1), 0L, "a"));
        List<PoolPhoto> out = PoolSelection.download(List.of(row(1, "Payer", 1), row(2), row(3)), pool, fetcher, 5L, Set.of("a"));
        assertEquals(2, fetches.get(), "held id 1 reused; 2 and 3 fetched");
        assertEquals(List.of(1, 2), out.stream().map(PoolPhoto::id).toList(), "3 had no image");
        assertTrue(out.get(0).owed());
        assertEquals(5L, out.get(1).fetchedAt());
    }

    @Test
    @DisplayName("Refresh cycle against a full pool fetches 0 images; old behaviour would fetch 10")
    void measuredDropFullPool() {
        AtomicInteger fetches = new AtomicInteger();
        PoolSelection.ImageFetcher fetcher = id -> { fetches.incrementAndGet(); return Optional.empty(); };
        List<PoolPhoto> pool = heldPool(40);
        PoolSelection.download(PoolSelection.toDownload(answer(1, 10), pool, POOL_MAX, List.of()), pool, fetcher, 0L, Set.of());
        assertEquals(0, fetches.get());
        PoolSelection.download(PoolSelection.toDownload(answer(1, 10), heldPool(39), POOL_MAX, List.of()), pool, fetcher, 0L, Set.of());
        assertEquals(1, fetches.get(), "one handed out, one fetched");
    }

    @Test
    @DisplayName("Merge over the cap evicts the oldest ordinary find, never an owed photo or one meeting a need")
    void mergeProtectsOwedAndNeeded() {
        List<PoolPhoto> pool = new ArrayList<>();
        pool.add(held(row(1, "Payer", 0), 0L));
        pool.add(held(row(2, "", 6), 0L));
        pool.addAll(heldPool(38));
        List<PoolPhoto> merged = PoolSelection.merge(pool, List.of(held(row(50), 1L)), List.of(), POOL_MAX, List.of(7));
        List<Integer> ids = merged.stream().map(PoolPhoto::id).toList();
        assertEquals(40, merged.size());
        assertTrue(ids.contains(1) && ids.contains(2) && ids.contains(50));
        assertTrue(!ids.contains(1000), "oldest ordinary find gave way");
    }

    @Test
    @DisplayName("Merge: fresh copies replace held ones and spent ids stay out")
    void mergeReplacesAndDropsSpent() {
        List<PoolPhoto> pool = List.of(held(row(1), 0L), held(row(2), 0L));
        List<PoolPhoto> merged = PoolSelection.merge(pool, List.of(held(row(1, "Payer", 0), 1L)), List.of(2), POOL_MAX, List.of());
        assertEquals(1, merged.size());
        assertTrue(merged.get(0).owed());
    }

    @Test
    @DisplayName("Prune drops stale and spent finds, keeps owed ones whatever their age")
    void pruneAge() {
        List<PoolPhoto> pool = List.of(held(row(1), 0L), held(row(2), 2 * HOUR), held(row(3, "Payer", 0), 0L), held(row(4), 2 * HOUR));
        List<PoolPhoto> kept = PoolSelection.prune(pool, 2 * HOUR, HOUR, Set.of(), List.of(4));
        assertEquals(List.of(2, 3), kept.stream().map(PoolPhoto::id).toList());
    }

    @Test
    @DisplayName("A joiner the relay never checked held photos against: those go, owed ones stay")
    void pruneUnvetted() {
        List<PoolPhoto> pool = List.of(held(row(1), 0L, "a"), held(row(2), 0L, "a", "b"), held(row(3, "Payer", 0), 0L, "a"));
        assertEquals(List.of(2, 3), PoolSelection.prune(pool, 0L, HOUR, Set.of("a", "b"), List.of())
                .stream().map(PoolPhoto::id).toList());
        assertEquals(3, PoolSelection.prune(pool, 0L, HOUR, Set.of("a"), List.of()).size(), "rejoin of a vetted player drops nothing");
    }

    @Test
    @DisplayName("Rows parse with defaults for fields older relays leave out")
    void parseRows() {
        AtomicInteger total = new AtomicInteger();
        List<Row> rows = PoolSelection.parseRows(
                "{\"total\":1234,\"photos\":[{\"id\":5,\"author\":\"x\",\"tributedBy\":\"y\",\"tributes\":2},{\"id\":6}]}",
                total::set);
        assertEquals(1234, total.get());
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).owed());
        assertEquals(3, rows.get(0).price());
        assertEquals("", rows.get(1).author());
        assertEquals(SharedPhotos.VIEWS_MAX, rows.get(1).viewsLeft());
    }
}
