package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.PackageInfo;
import games.brennan.dungeontrain.net.platform.DtNetSender;
import games.brennan.dungeontrain.net.PackageListRequestPacket;
import games.brennan.dungeontrain.net.PackageListSyncPacket;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client-side cache of the most recent {@link PackageListSyncPacket}
 * the server sent. The Package menu reads from this on every rebuild;
 * the server pushes a fresh snapshot whenever a mutation slash command
 * lands (via {@link #requestRefresh}).
 *
 * <p>Throttled request side: {@link #requestRefreshThrottled} only
 * actually fires a packet every {@link #THROTTLE_TICKS} ticks so the
 * menu's per-tick rebuild can call it unconditionally without flooding
 * the network. The first call after the menu opens always fires
 * immediately so the pane populates with no perceptible delay.</p>
 *
 * <p>Stateless when never populated — the menu shows a single "loading"
 * row instead of a stale view.</p>
 */
public final class PackageListClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Min ticks between consecutive throttled refreshes (~1 s at 20 TPS).
     * The menu rebuilds every tick, so this is the dominant cadence —
     * 1 s is fast enough that a click-then-look interaction feels live
     * but slow enough not to fire 20 filesystem-walking syncs per second.
     * Explicit mutations call {@link #scheduleRefreshAfterAction()} to
     * bypass the throttle when responsiveness actually matters.
     */
    private static final long THROTTLE_TICKS = 20L;

    /** A small grace period after a Save / Activate / Enable click — refresh sooner. */
    private static final long FORCE_AFTER_ACTION_TICKS = 1L;

    private static volatile PackageListSyncPacket SNAPSHOT;
    private static long lastRequestTick = Long.MIN_VALUE;

    /**
     * Synthetic placeholder used when the server snapshot hasn't arrived
     * yet. Always represents the unsaved pseudo-package — without it the
     * menu would render an empty list (or just "Loading...") on first
     * open, which looks broken even though the unsaved package's
     * existence is a client-side invariant.
     */
    private static final PackageListSyncPacket.Entry UNSAVED_PLACEHOLDER =
        new PackageListSyncPacket.Entry(PackageInfo.UNSAVED_NAME, false, true, true, Map.of());

    private PackageListClient() {}

    /** Replace the cached snapshot. Called by {@link PackageListSyncPacket#handle}. */
    public static void setSnapshot(PackageListSyncPacket packet) {
        SNAPSHOT = packet;
        LOGGER.debug("[DungeonTrain] PackageListClient: snapshot updated, {} packages", packet.entries().size());
    }

    /** Drop the cached snapshot — used when the player leaves a world / server. */
    public static void clear() {
        SNAPSHOT = null;
        lastRequestTick = Long.MIN_VALUE;
    }

    /** The current snapshot, or empty when no sync has been received yet. */
    public static Optional<PackageListSyncPacket> snapshot() {
        return Optional.ofNullable(SNAPSHOT);
    }

    /**
     * Entries to render. Falls back to a single synthetic
     * {@code (unsaved)} entry when the server snapshot hasn't arrived
     * yet — that way the menu always looks "alive" on first open, and
     * if the sync is slow or fails (e.g. permission-rejected on a
     * dedicated server, network hiccup) the player still sees the
     * unsaved package they can Save into.
     */
    public static List<PackageListSyncPacket.Entry> entries() {
        PackageListSyncPacket snap = SNAPSHOT;
        if (snap == null || snap.entries().isEmpty()) {
            return List.of(UNSAVED_PLACEHOLDER);
        }
        return snap.entries();
    }

    /**
     * The entry currently flagged active. Falls back to the synthetic
     * unsaved entry when no snapshot exists yet, so the contents pane
     * has something coherent to render on first open.
     */
    public static Optional<PackageListSyncPacket.Entry> activeEntry() {
        PackageListSyncPacket snap = SNAPSHOT;
        if (snap == null || snap.entries().isEmpty()) {
            return Optional.of(UNSAVED_PLACEHOLDER);
        }
        for (PackageListSyncPacket.Entry e : snap.entries()) {
            if (e.isActive()) return Optional.of(e);
        }
        // Snapshot exists but flagged nothing active — defensive fallback.
        return Optional.of(snap.entries().get(0));
    }

    /** Look up an entry by name. */
    @Nullable
    public static PackageListSyncPacket.Entry byName(String name) {
        for (PackageListSyncPacket.Entry e : entries()) {
            if (e.name().equals(name)) return e;
        }
        return null;
    }

    /**
     * Send a refresh request without throttling. Use this after the
     * player clicks an action whose effect should be visible in the
     * menu immediately (Save, Activate, Enable, Reload).
     */
    public static void requestRefresh() {
        DtNetSender.get().sendToServer(new PackageListRequestPacket());
        lastRequestTick = currentTick();
        LOGGER.debug("[DungeonTrain] PackageListClient: sent immediate refresh request");
    }

    /**
     * Send a refresh request unless one was sent in the last
     * {@link #THROTTLE_TICKS} ticks. Safe to call from per-tick rebuilds.
     *
     * <p>The {@code lastRequestTick == Long.MIN_VALUE} check is load-bearing:
     * without it, the first call would compute {@code now - Long.MIN_VALUE}
     * which overflows to a large negative number (NOT a large positive),
     * making the {@code < THROTTLE_TICKS} test pass forever — no request
     * would ever fire and the menu would render the synthetic-unsaved
     * fallback indefinitely.</p>
     */
    public static void requestRefreshThrottled() {
        long now = currentTick();
        boolean firstRequest = lastRequestTick == Long.MIN_VALUE;
        if (!firstRequest && now - lastRequestTick < THROTTLE_TICKS) return;
        lastRequestTick = now;
        DtNetSender.get().sendToServer(new PackageListRequestPacket());
        if (firstRequest) {
            LOGGER.debug("[DungeonTrain] PackageListClient: sent initial refresh request");
        }
    }

    /**
     * Force a refresh after a delay short enough to capture the
     * server-side mutation's effects. Used after Save / Activate clicks
     * — those need the new state in the very next menu rebuild.
     */
    public static void scheduleRefreshAfterAction() {
        // The slash command we just dispatched runs on the next server tick;
        // the cache-only reload completes immediately within that tick;
        // the next request fires from the menu's tick rebuild. For now we
        // just request immediately — the response will land before the
        // following client tick.
        if (lastRequestTick == Long.MIN_VALUE) {
            requestRefresh();
            return;
        }
        long now = currentTick();
        if (now - lastRequestTick < FORCE_AFTER_ACTION_TICKS) {
            lastRequestTick = now - THROTTLE_TICKS; // ensure next throttled call fires
            return;
        }
        requestRefresh();
    }

    private static long currentTick() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.level != null) return mc.level.getGameTime();
        return System.currentTimeMillis() / 50L; // 20 TPS-ish fallback
    }
}
