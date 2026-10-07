package games.brennan.dungeontrain.compat.photo.album;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Every player's albums on this computer, outside any world ({@code <gameDir>/dungeontrain/albums/}),
 * so an album follows its owner into each new world: a world only borrows the pictures while it is
 * being played.
 *
 * <pre>
 *   albums/&lt;uuid&gt;/live.json        { rev, pages: [ { hash|null, note, photo|null } ] }
 *   albums/&lt;uuid&gt;/free_play.json
 *   albums/images/&lt;hash&gt;.png        each picture once, named by {@link AlbumImageHash}
 * </pre>
 *
 * <p>{@code photo} is the photograph stack as SNBT, so its photographer and capture details survive;
 * this class never parses it. Reads come from memory after the first; writes run in order on one
 * background thread, and a picture no album names any more is deleted after each album write.</p>
 */
public final class AlbumStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String IMAGES = "images";

    /** One page: its picture's hash (null for an empty page), its note, and the photograph stack as SNBT (may be null). */
    public record Page(String hash, String note, String photo) {
        public Page {
            hash = AlbumImageHash.isHash(hash) ? hash : null;
            note = note == null ? "" : note;
        }
    }

    public record Entry(long rev, List<Page> pages) {
        public static final Entry EMPTY = new Entry(0L, List.of());

        public Entry {
            pages = List.copyOf(pages);
        }
    }

    private record Key(UUID owner, AlbumKind kind) {}

    private static volatile AlbumStore instance;

    private final Path root;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    /** Pictures handed over but not yet on disk. */
    private final Map<String, byte[]> pendingImages = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DungeonTrain-AlbumStore");
        thread.setDaemon(true);
        return thread;
    });

    AlbumStore(Path root) {
        this.root = root;
    }

    public static AlbumStore get() {
        AlbumStore store = instance;
        if (store == null) {
            synchronized (AlbumStore.class) {
                if (instance == null) instance = new AlbumStore(FMLPaths.GAMEDIR.get().resolve("dungeontrain").resolve("albums"));
                store = instance;
            }
        }
        return store;
    }

    // ---- albums -------------------------------------------------------------------

    /** The owner's album of this kind, read from disk the first time; {@link Entry#EMPTY} when they have none. */
    public Entry entry(UUID owner, AlbumKind kind) {
        return entries.computeIfAbsent(new Key(owner, kind), key -> read(albumFile(key)));
    }

    /** Keep {@code entry} as the owner's album of this kind: at once in memory, on disk in the background. */
    public void put(UUID owner, AlbumKind kind, Entry entry) {
        Key key = new Key(owner, kind);
        entries.put(key, entry);
        io.execute(() -> {
            try {
                write(albumFile(key), GSON.toJson(toJson(entry)).getBytes(StandardCharsets.UTF_8));
                prune();
            } catch (IOException | UncheckedIOException e) {
                LOGGER.warn("[DungeonTrain] Album {} of {} not saved: {}", kind.fileName(), owner, e.toString());
            }
        });
    }

    // ---- pictures -----------------------------------------------------------------

    /** Keep these PNGs, by hash. A picture already held is left as it is. */
    public void putImages(Map<String, byte[]> pngs) {
        if (pngs.isEmpty()) return;
        pngs.forEach((hash, png) -> {
            if (AlbumImageHash.isHash(hash)) pendingImages.put(hash, png);
        });
        io.execute(() -> pngs.forEach((hash, png) -> {
            try {
                Path file = imageFile(hash);
                if (!Files.exists(file)) write(file, png);
            } catch (IOException | UncheckedIOException e) {
                LOGGER.warn("[DungeonTrain] Album picture {} not saved: {}", hash, e.toString());
            } finally {
                pendingImages.remove(hash);
            }
        }));
    }

    /** The PNG for {@code hash}, if this computer holds it. */
    public Optional<byte[]> image(String hash) {
        if (!AlbumImageHash.isHash(hash)) return Optional.empty();
        byte[] pending = pendingImages.get(hash);
        if (pending != null) return Optional.of(pending);
        try {
            Path file = imageFile(hash);
            return Files.exists(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Album picture {} unreadable: {}", hash, e.toString());
            return Optional.empty();
        }
    }

    public boolean hasImage(String hash) {
        return AlbumImageHash.isHash(hash) && (pendingImages.containsKey(hash) || Files.exists(imageFile(hash)));
    }

    /** Tests and shutdown: wait for the background writes queued so far. */
    void flush() {
        try {
            io.submit(() -> {}).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Album store flush interrupted: {}", e.toString());
        }
    }

    /** Tests only: forget what was read, so the next read comes from disk. */
    void forgetCache() {
        entries.clear();
    }

    // ---- files --------------------------------------------------------------------

    private Path albumFile(Key key) {
        return root.resolve(key.owner().toString()).resolve(key.kind().fileName() + ".json");
    }

    private Path imageFile(String hash) {
        return root.resolve(IMAGES).resolve(hash + ".png");
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Entry read(Path file) {
        if (!Files.exists(file)) return Entry.EMPTY;
        try {
            return fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Album {} unreadable, starting empty: {}", file, e.toString());
            return Entry.EMPTY;
        }
    }

    static JsonObject toJson(Entry entry) {
        JsonObject o = new JsonObject();
        o.addProperty("rev", entry.rev());
        JsonArray pages = new JsonArray();
        for (Page page : entry.pages()) {
            JsonObject p = new JsonObject();
            if (page.hash() != null) p.addProperty("hash", page.hash());
            p.addProperty("note", page.note());
            if (page.photo() != null) p.addProperty("photo", page.photo());
            pages.add(p);
        }
        o.add("pages", pages);
        return o;
    }

    static Entry fromJson(JsonObject o) {
        List<Page> pages = new ArrayList<>();
        JsonArray array = o.getAsJsonArray("pages");
        for (int i = 0; array != null && i < array.size() && i < AlbumSavePayload.MAX_PAGES; i++) {
            JsonObject p = array.get(i).getAsJsonObject();
            pages.add(new Page(
                    p.has("hash") ? p.get("hash").getAsString() : null,
                    p.has("note") ? p.get("note").getAsString() : "",
                    p.has("photo") ? p.get("photo").getAsString() : null));
        }
        return new Entry(o.has("rev") ? o.get("rev").getAsLong() : 0L, pages);
    }

    /** Delete pictures no album on disk names. Runs on the store thread, after an album write. */
    private void prune() throws IOException {
        Path images = root.resolve(IMAGES);
        if (!Files.isDirectory(images)) return;
        Set<String> named = new HashSet<>();
        try (Stream<Path> owners = Files.list(root)) {
            for (Path dir : owners.filter(Files::isDirectory).filter(d -> !d.getFileName().toString().equals(IMAGES)).toList()) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                        read(file).pages().forEach(page -> {
                            if (page.hash() != null) named.add(page.hash());
                        });
                    }
                }
            }
        }
        try (Stream<Path> files = Files.list(images)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".png")).toList()) {
                String hash = file.getFileName().toString().replace(".png", "");
                if (!named.contains(hash) && !pendingImages.containsKey(hash)) Files.deleteIfExists(file);
            }
        }
    }
}
