package games.brennan.dungeontrain.compat.photo.album;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The PNG for each picture an album names: this computer's own copy when it holds a sound one,
 * else fetched from the relay. A picture that cannot be had either way is left out.
 *
 * <p>A local copy is returned with the rest rather than just skipped, so whoever keeps the result
 * ({@link AlbumStore#putImages}) holds it again even if the store pruned the file meanwhile.</p>
 */
final class AlbumPngFetch {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Fetches one picture from the relay; empty when the relay has no such picture. */
    @FunctionalInterface
    interface Remote {
        Optional<byte[]> fetch(AlbumSavePayload.Page page) throws Exception;
    }

    private AlbumPngFetch() {}

    /**
     * @param local  this computer's verified copy of a picture by hash ({@link AlbumStore#verifiedImage})
     * @param remote the relay, asked only for pictures {@code local} does not have
     */
    static Map<String, byte[]> collect(List<AlbumSavePayload.Page> pages, Function<String, Optional<byte[]>> local, Remote remote) {
        Map<String, byte[]> pngs = new HashMap<>();
        for (AlbumSavePayload.Page page : pages) {
            String hash = page.hash();
            if (hash == null || pngs.containsKey(hash)) continue;
            Optional<byte[]> held = local.apply(hash);
            if (held.isPresent()) {
                pngs.put(hash, held.get());
                continue;
            }
            try {
                remote.fetch(page).ifPresent(png -> pngs.put(hash, png));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.debug("[DungeonTrain] Album picture {} not fetched: {}", hash, e.toString());
            }
        }
        return pngs;
    }
}
