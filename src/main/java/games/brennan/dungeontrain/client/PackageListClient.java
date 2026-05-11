package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PackageListRequestPacket;
import games.brennan.dungeontrain.net.PackageListSyncPacket;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
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

    /** Min ticks between consecutive throttled refreshes (~250ms at 20 TPS). */
    private static final long THROTTLE_TICKS = 5L;

    /** A small grace period after a Save / Activate / Enable click — refresh sooner. */
    private static final long FORCE_AFTER_ACTION_TICKS = 1L;

    private static volatile PackageListSyncPacket SNAPSHOT;
    private static long lastRequestTick = Long.MIN_VALUE;

    private PackageListClient() {}

    /** Replace the cached snapshot. Called by {@link PackageListSyncPacket#handle}. */
    public static void setSnapshot(PackageListSyncPacket packet) {
        SNAPSHOT = packet;
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

    /** Just the entries list (empty when no snapshot). */
    public static List<PackageListSyncPacket.Entry> entries() {
        PackageListSyncPacket snap = SNAPSHOT;
        return snap == null ? Collections.emptyList() : snap.entries();
    }

    /** Locate the entry currently flagged as active (Optional.empty if no snapshot). */
    public static Optional<PackageListSyncPacket.Entry> activeEntry() {
        for (PackageListSyncPacket.Entry e : entries()) {
            if (e.isActive()) return Optional.of(e);
        }
        return Optional.empty();
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
        DungeonTrainNet.sendToServer(new PackageListRequestPacket());
        lastRequestTick = currentTick();
    }

    /**
     * Send a refresh request unless one was sent in the last
     * {@link #THROTTLE_TICKS} ticks. Safe to call from per-tick rebuilds.
     */
    public static void requestRefreshThrottled() {
        long now = currentTick();
        if (now - lastRequestTick < THROTTLE_TICKS) return;
        lastRequestTick = now;
        DungeonTrainNet.sendToServer(new PackageListRequestPacket());
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
