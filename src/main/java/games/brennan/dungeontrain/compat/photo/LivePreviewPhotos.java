package games.brennan.dungeontrain.compat.photo;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.SharedPhotos.PoolPhoto;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The live relay's photo pool, for the editor's Test the Carriage on a dev build. A dev build's own
 * pool comes from the dev relay, which holds almost nothing, so a test would nearly always hang
 * {@link TestFramePhoto}; this lets the author see real community photos in their frames.
 *
 * <p>Read-only: the fetch sends nothing about this world (no players, no exclusions), and the stacks
 * it makes carry no relay id ({@link SharedPhotos#stackFor}), so opening one never reports a view or
 * a Tribute to the dev relay under a live id. On a build that already talks to the live relay
 * ({@link #sameAsOwnPool}) the ordinary pool is used instead.</p>
 *
 * <p>Fetched lazily, on the first test that wants a photo and again once it goes stale; until the
 * first answer lands the test hangs the drawn photo, and the next reroll picks up live ones.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LivePreviewPhotos {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long a fetched pool is shown before the next test asks for a fresh one. */
    private static final long STALE_AFTER_MS = 5 * 60 * 1000L;

    private static volatile List<PoolPhoto> pool = List.of();
    private static volatile long fetchedAt = 0L;
    private static final AtomicBoolean fetchInFlight = new AtomicBoolean();

    private LivePreviewPhotos() {}

    /** True when this build's own relay is the live one, so its ordinary pool already is the live pool. */
    static boolean sameAsOwnPool() {
        return DungeonTrain.liveRelayBaseUrl().equals(DungeonTrain.relayBaseUrl());
    }

    /** A live community photo for a test frame, or {@link ItemStack#EMPTY} while none has arrived. */
    static ItemStack preview(long seed) {
        String base = DungeonTrain.liveRelayBaseUrl();
        if (System.currentTimeMillis() - fetchedAt > STALE_AFTER_MS) refresh(base);
        List<PoolPhoto> photos = pool;
        if (photos.isEmpty()) return ItemStack.EMPTY;
        PoolPhoto photo = photos.get((int) Math.floorMod(seed, (long) photos.size()));
        return SharedPhotos.stackFor(photo, base, false);
    }

    private static void refresh(String base) {
        if (!fetchInFlight.compareAndSet(false, true)) return;
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(base + "/photos/pool?limit=" + SharedPhotos.POOL_FETCH_LIMIT))
                .timeout(SharedPhotos.REQUEST_TIMEOUT).header("Accept", "application/json").GET().build();
        SharedPhotos.HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(response -> SharedPhotos.fetchNew(base, response, total -> {}))
                .whenComplete((fetched, error) -> {
                    fetchInFlight.set(false);
                    if (error != null) {
                        LOGGER.debug("[DungeonTrain] Live preview photo fetch failed: {}", error.toString());
                        return;
                    }
                    // An empty answer keeps what was held, and the next test tries again.
                    if (fetched.isEmpty()) return;
                    pool = List.copyOf(fetched);
                    fetchedAt = System.currentTimeMillis();
                    LOGGER.info("[DungeonTrain] Test frames now preview {} live relay photo(s)", fetched.size());
                });
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        pool = List.of();
        fetchedAt = 0L;
    }
}
