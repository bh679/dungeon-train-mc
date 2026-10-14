package games.brennan.dungeontrain.compat.photo.album;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.compat.photo.PhotoPngCodec;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Album pictures already decoded, so lending one to a world on the server thread is only a copy into
 * Exposure's store — never a file read or a PNG decode in the middle of a tick. Filled ahead of time
 * on a background thread ({@link #prewarm}) and straight from the pixels a save already has
 * ({@link #put}). Bounded: the pictures used least recently go first.
 */
final class AlbumPictureCache {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final int MAX_PICTURES = 64;

    private static final AlbumPictureCache INSTANCE = new AlbumPictureCache(MAX_PICTURES);

    private final int max;
    private final Map<String, PhotoPngCodec.Decoded> pictures;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DungeonTrain-AlbumPictures");
        thread.setDaemon(true);
        return thread;
    });

    AlbumPictureCache(int max) {
        this.max = max;
        this.pictures = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PhotoPngCodec.Decoded> eldest) {
                return size() > AlbumPictureCache.this.max;
            }
        };
    }

    static AlbumPictureCache get() {
        return INSTANCE;
    }

    synchronized Optional<PhotoPngCodec.Decoded> picture(String hash) {
        return Optional.ofNullable(pictures.get(hash));
    }

    synchronized void put(String hash, PhotoPngCodec.Decoded picture) {
        if (AlbumImageHash.isHash(hash) && picture != null) pictures.put(hash, picture);
    }

    synchronized boolean has(String hash) {
        return pictures.containsKey(hash);
    }

    synchronized int size() {
        return pictures.size();
    }

    /** Decode, on a background thread, each of these pictures not held yet, reading its PNG with {@code source}. */
    void prewarm(Collection<String> hashes, Function<String, Optional<byte[]>> source) {
        List<String> wanted = hashes.stream().filter(AlbumImageHash::isHash).distinct().toList();
        if (wanted.isEmpty()) return;
        decoder.execute(() -> {
            for (String hash : wanted) {
                if (has(hash)) continue;
                source.apply(hash).ifPresent(png -> {
                    try {
                        put(hash, PhotoPngCodec.decode(png));
                    } catch (Exception e) {
                        LOGGER.debug("[DungeonTrain] Album picture {} could not be decoded: {}", hash, e.toString());
                    }
                });
            }
        });
    }

    /** Tests: wait for the decodes queued so far. */
    void awaitPrewarm() throws Exception {
        decoder.submit(() -> {}).get();
    }

    synchronized void clear() {
        pictures.clear();
    }
}
