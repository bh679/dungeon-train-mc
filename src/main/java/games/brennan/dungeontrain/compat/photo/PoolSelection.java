package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What the shared-photo pool keeps and which images it downloads. Every refresh asks the relay for a
 * few photos (the JSON is small), but an image (~100 KB) is fetched only when there is room for it,
 * or when it must not be lost: a photographer's own tributed photo coming back ({@code tributedBy},
 * which the relay marks delivered as it answers), and a photo at a Tribute price a player still
 * needs ({@link PhotoTierNeed}) that the pool holds none of.
 *
 * <p>Held photos are dropped, unseen (so they can come back), when they grow old or when a player
 * is online whom the relay never checked them against: its "already opened" filter only covers the
 * players named in the request.</p>
 */
final class PoolSelection {

    private static final Logger LOGGER = LoggerFactory.getLogger(PoolSelection.class);

    private PoolSelection() {}

    /** One photo as the relay's pool lists it, before its image is fetched. */
    record Row(int id, String author, String tributedBy, int tributes, int viewsLeft, int views, int maxViews,
               List<String> advancements, String image) {
        /** A row with no CDN URL (an older relay, or a picture not mirrored yet). */
        Row(int id, String author, String tributedBy, int tributes, int viewsLeft, int views, int maxViews,
            List<String> advancements) {
            this(id, author, tributedBy, tributes, viewsLeft, views, maxViews, advancements, "");
        }

        Row {
            if (image == null) image = "";
        }

        /** A photographer's own tributed photo, returned to them. */
        boolean owed() { return !tributedBy.isBlank(); }

        /** Emeralds a Tribute to it costs. */
        int price() { return SharedPhotos.TRIBUTE_BASE_COST + Math.max(0, tributes); }
    }

    /**
     * One photo from the relay, decoded. {@code fetchedAt} is when its row arrived; {@code vettedFor}
     * the players (bare uuids) the relay checked it against.
     */
    record PoolPhoto(Row row, PhotoPngCodec.Decoded image, long fetchedAt, Set<String> vettedFor) {
        PoolPhoto {
            vettedFor = Set.copyOf(vettedFor);
        }

        int id() { return row.id(); }
        String author() { return row.author(); }
        String tributedBy() { return row.tributedBy(); }
        int tributes() { return row.tributes(); }
        int viewsLeft() { return row.viewsLeft(); }
        int views() { return row.views(); }
        int maxViews() { return row.maxViews(); }
        List<String> advancements() { return row.advancements(); }
        boolean owed() { return row.owed(); }
        int price() { return row.price(); }
    }

    /** Fetches one photo's image bytes; empty when the relay has none. */
    @FunctionalInterface
    interface ImageFetcher {
        Optional<byte[]> fetch(int id) throws Exception;

        /** {@code row}'s image: by default by id; the relay fetcher also tries the row's CDN URL ({@link PhotoCdn}). */
        default Optional<byte[]> fetch(Row row) throws Exception {
            return fetch(row.id());
        }
    }

    // ---- parsing --------------------------------------------------------------

    /** The rows of a pool answer, in the relay's order; {@code onTotal} hears the archive's size. */
    static List<Row> parseRows(String body, IntConsumer onTotal) {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        if (json.has("total")) onTotal.accept(json.get("total").getAsInt());
        JsonArray photos = json.getAsJsonArray("photos");
        List<Row> rows = new ArrayList<>();
        for (int i = 0; photos != null && i < photos.size(); i++) {
            JsonObject row = photos.get(i).getAsJsonObject();
            rows.add(new Row(
                    row.get("id").getAsInt(),
                    string(row, "author"),
                    string(row, "tributedBy"),
                    integer(row, "tributes", 0),
                    integer(row, "viewsLeft", SharedPhotos.VIEWS_MAX),
                    integer(row, "views", 0),
                    integer(row, "maxViews", SharedPhotos.VIEWS_MAX),
                    // What the photo earned its photographer, shown under it in the viewer (older relays send none).
                    PhotoAdvancementBadges.idsFrom(row.has("advancements") && row.get("advancements").isJsonArray()
                            ? row.getAsJsonArray("advancements") : null),
                    // The relay's CDN copy of the image (relay v0.373+), or "".
                    PhotoCdn.urlOf(row, "image")));
        }
        return List.copyOf(rows);
    }

    private static String string(JsonObject row, String key) {
        return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsString() : "";
    }

    private static int integer(JsonObject row, String key, int fallback) {
        return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsInt() : fallback;
    }

    // ---- choosing -------------------------------------------------------------

    /** Of {@code needs} (Tribute prices), those no held photo meets. */
    static Set<Integer> openNeeds(List<PoolPhoto> held, Collection<Integer> needs) {
        return needs.stream()
                .filter(need -> held.stream().noneMatch(photo -> photo.price() >= need))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * The rows whose images to download, in the relay's order: every owed photo; one photo per open
     * need; and the rest only while the pool has free slots ({@code poolMax} less what it holds).
     */
    static List<Row> toDownload(List<Row> rows, List<PoolPhoto> held, int poolMax, Collection<Integer> needs) {
        int free = Math.max(0, poolMax - held.size());
        Set<Integer> open = new TreeSet<>(openNeeds(held, needs));
        List<Row> chosen = new ArrayList<>();
        for (Row row : rows) {
            Optional<Integer> need = open.stream().filter(n -> row.price() >= n).findFirst();
            if (row.owed() || need.isPresent() || free > 0) {
                chosen.add(row);
                need.ifPresent(open::remove);
                free = Math.max(0, free - 1);
            }
        }
        return List.copyOf(chosen);
    }

    // ---- downloading ----------------------------------------------------------

    /**
     * Decode the chosen rows. A row the pool already holds reuses that image (an owed photo can come
     * back for one held as an ordinary find); the rest are fetched. A failed image is skipped.
     */
    static List<PoolPhoto> download(List<Row> chosen, List<PoolPhoto> held, ImageFetcher fetcher,
                                    long now, Set<String> vettedFor) {
        Map<Integer, PhotoPngCodec.Decoded> heldImages = new HashMap<>();
        held.forEach(photo -> heldImages.put(photo.id(), photo.image()));
        List<PoolPhoto> out = new ArrayList<>();
        for (Row row : chosen) {
            try {
                PhotoPngCodec.Decoded image = heldImages.get(row.id());
                if (image == null) {
                    Optional<byte[]> bytes = fetcher.fetch(row);
                    if (bytes.isEmpty()) continue;
                    image = PhotoPngCodec.decode(bytes.get());
                }
                out.add(new PoolPhoto(row, image, now, vettedFor));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.debug("[DungeonTrain] Shared photo {} could not be fetched: {}", row.id(), e.toString());
            }
        }
        return List.copyOf(out);
    }

    // ---- keeping --------------------------------------------------------------

    /**
     * The held photos still worth keeping: none spent; no ordinary find older than {@code maxAgeMs}
     * (its views and Tributes have gone stale); none the relay didn't check against everyone
     * {@code online}. Owed photos always stay: the relay won't send them again.
     */
    static List<PoolPhoto> prune(List<PoolPhoto> held, long now, long maxAgeMs, Set<String> online,
                                 Collection<Integer> spentIds) {
        Set<Integer> spent = new HashSet<>(spentIds);
        return held.stream()
                .filter(photo -> !spent.contains(photo.id()))
                .filter(photo -> photo.owed()
                        || (now - photo.fetchedAt() <= maxAgeMs && photo.vettedFor().containsAll(online)))
                .toList();
    }

    /**
     * Newly fetched photos replace a held copy of the same id and spent ids stay out. Over
     * {@code poolMax}, the oldest give way, owed photos and those meeting a need last.
     */
    static List<PoolPhoto> merge(List<PoolPhoto> held, List<PoolPhoto> fetched, Collection<Integer> spentIds,
                                 int poolMax, Collection<Integer> needs) {
        Set<Integer> fresh = fetched.stream().map(PoolPhoto::id).collect(Collectors.toSet());
        Set<Integer> spent = new HashSet<>(spentIds);
        List<PoolPhoto> merged = new ArrayList<>(Stream.concat(
                        held.stream().filter(photo -> !fresh.contains(photo.id())), fetched.stream())
                .filter(photo -> !spent.contains(photo.id())).toList());
        while (merged.size() > poolMax) merged.remove(evictionIndex(merged, needs));
        return List.copyOf(merged);
    }

    /** The oldest ordinary find; failing that the oldest that isn't owed; failing that the oldest. */
    private static int evictionIndex(List<PoolPhoto> photos, Collection<Integer> needs) {
        for (int i = 0; i < photos.size(); i++) {
            PoolPhoto photo = photos.get(i);
            if (!photo.owed() && needs.stream().noneMatch(need -> photo.price() >= need)) return i;
        }
        for (int i = 0; i < photos.size(); i++) {
            if (!photos.get(i).owed()) return i;
        }
        return 0;
    }
}
