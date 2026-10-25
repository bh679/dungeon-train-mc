package games.brennan.dungeontrain.compat.vista;

import net.mehvahdjukaar.vista.client.video_source.IVideoSource;
import net.mehvahdjukaar.vista.common.cassette.IBroadcastSource;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Live Feed as a Vista broadcast source with no block behind it, one per stream: {@link #INSTANCE}
 * is the first (main) stream, {@link #SECOND} the second stream the Two Streams reward opens.
 * {@link LiveBroadcastLocation} resolves to them on both sides; the client plugs its video sources in
 * at setup ({@link #setClientSource}) so no client class is ever touched on a dedicated server.
 */
public final class LiveBroadcastSource implements IBroadcastSource {

    /** The first stream: what every TV showed before Two Streams. */
    public static final UUID MAIN_FEED_UUID =
        UUID.nameUUIDFromBytes("dungeontrain:live/main".getBytes(StandardCharsets.UTF_8));
    /** The second stream (Two Streams reward). */
    public static final UUID SECOND_FEED_UUID =
        UUID.nameUUIDFromBytes("dungeontrain:live/second".getBytes(StandardCharsets.UTF_8));

    public static final LiveBroadcastSource INSTANCE = new LiveBroadcastSource(MAIN_FEED_UUID, false);
    public static final LiveBroadcastSource SECOND = new LiveBroadcastSource(SECOND_FEED_UUID, true);

    private final UUID feed;
    private final boolean second;
    @Nullable
    private volatile Supplier<IVideoSource> clientSource;

    private LiveBroadcastSource(UUID feed, boolean second) {
        this.feed = feed;
        this.second = second;
    }

    /** The client's video source for each stream: {@code main} for {@link #INSTANCE}, {@code second} for {@link #SECOND}. */
    public static void setClientSource(Supplier<IVideoSource> main, Supplier<IVideoSource> second) {
        INSTANCE.clientSource = main;
        SECOND.clientSource = second;
    }

    /** Whether this is the second stream's source. */
    public boolean isSecond() {
        return second;
    }

    @Override
    public UUID getBroadcastUUID() {
        return feed;
    }

    @Override
    public @Nullable IVideoSource getBroadcastVideo() {
        Supplier<IVideoSource> s = clientSource;
        return s == null ? null : s.get();
    }
}
