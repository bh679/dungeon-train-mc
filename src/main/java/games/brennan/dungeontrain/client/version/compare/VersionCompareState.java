package games.brennan.dungeontrain.client.version.compare;

import net.minecraft.client.Minecraft;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Session-scoped holder for each platform's modpack listing, the data behind
 * {@link VersionCompareScreen}. One JVM, one successful fetch per platform; a failed fetch is
 * retried the next time the data is asked for, so a page opened offline recovers on the next
 * open rather than staying blank all session.
 *
 * <p>Writes happen on the fetcher's single daemon thread; the render thread only reads the
 * {@code volatile} slots. When a listing lands while the Versions page is open, the page is told
 * on the render thread so its rows and changelog fill in without a resize.</p>
 */
public final class VersionCompareState {

    public enum Status { LOADING, OK, ERROR }

    private static final Map<Platform, Slot> SLOTS = new EnumMap<>(Platform.class);

    static {
        for (Platform p : Platform.values()) {
            SLOTS.put(p, new Slot());
        }
    }

    private VersionCompareState() {}

    /** Kick off any fetch that has not succeeded yet. Idempotent while one is in flight. */
    public static void ensureFetched() {
        for (Platform p : Platform.values()) {
            Slot slot = SLOTS.get(p);
            if (!slot.attempted || slot.status == Status.ERROR) {
                slot.attempted = true;
                slot.status = Status.LOADING;
                PackVersionFetcher.fetchAsync(p);
            }
        }
    }

    public static Status status(Platform platform) {
        return SLOTS.get(platform).status;
    }

    public static Optional<PlatformVersions> versions(Platform platform) {
        return Optional.ofNullable(SLOTS.get(platform).versions);
    }

    static void accept(PlatformVersions versions) {
        Slot slot = SLOTS.get(versions.platform());
        slot.versions = versions;
        slot.status = Status.OK;
        notifyScreen();
    }

    static void fail(Platform platform) {
        Slot slot = SLOTS.get(platform);
        slot.status = Status.ERROR;
        notifyScreen();
    }

    /** Runs on the HTTP thread; everything Minecraft-side is deferred to the render thread. */
    private static void notifyScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.screen instanceof VersionCompareScreen screen) {
                screen.onDataChanged();
            }
        });
    }

    private static final class Slot {
        volatile Status status = Status.LOADING;
        volatile PlatformVersions versions;
        volatile boolean attempted;
    }
}
