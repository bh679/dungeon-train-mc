package games.brennan.dungeontrain.compat.vista;

import net.mehvahdjukaar.vista.client.video_source.IVideoSource;
import net.mehvahdjukaar.vista.common.cassette.IBroadcastSource;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Live Feed as a Vista broadcast source with no block behind it. {@link LiveBroadcastLocation}
 * resolves to this on both sides; the client plugs its video source in at setup ({@link #setClientSource})
 * so no client class is ever touched on a dedicated server.
 */
public final class LiveBroadcastSource implements IBroadcastSource {

    public static final LiveBroadcastSource INSTANCE = new LiveBroadcastSource();

    /** One channel for now; a second feed would hash a different string. */
    public static final UUID MAIN_FEED_UUID =
        UUID.nameUUIDFromBytes("dungeontrain:live/main".getBytes(StandardCharsets.UTF_8));

    @Nullable
    private static volatile Supplier<IVideoSource> clientSource;

    private LiveBroadcastSource() {}

    public static void setClientSource(Supplier<IVideoSource> source) {
        clientSource = source;
    }

    @Override
    public UUID getBroadcastUUID() {
        return MAIN_FEED_UUID;
    }

    @Override
    public @Nullable IVideoSource getBroadcastVideo() {
        Supplier<IVideoSource> s = clientSource;
        return s == null ? null : s.get();
    }
}
