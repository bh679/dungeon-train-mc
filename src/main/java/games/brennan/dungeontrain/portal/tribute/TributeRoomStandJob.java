package games.brennan.dungeontrain.portal.tribute;

import games.brennan.dungeontrain.portal.PortalCarriageLayout;
import games.brennan.dungeontrain.portal.PortalStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One tribute room being stood up for a death visit, a step a tick, so no single tick of the server
 * carries the whole of it.
 *
 * <p>The site is far from anyone and normally unloaded. The room's chunk ticket is taken first, the
 * room's file is read on the IO thread, and nothing is written until every chunk of the window is
 * loaded: the chunks come in on the worker threads, never inside a stamp. Then the stamp runs in
 * {@link Phase} order, the same calls in the same order as when it was laid in one go. Server thread
 * only, except {@link #loaded}, which the IO callback hands back through the server's queue.</p>
 */
final class TributeRoomStandJob {

    /**
     * How long a visit that was asked for may wait on its room before the server gives up, sweeps it
     * and tells the client none is coming. Under the client's own wait ({@code TributeTvVisit.ROOM_TIMEOUT_MS},
     * 20 s), so the server never moves in a player whose client has already gone to the recap.
     */
    static final long BEGIN_DEADLINE_MS = 15_000L;
    /** A room still waiting on its chunks after this long is worth one log line. */
    static final long SLOW_CHUNKS_LOG_MS = 10_000L;

    enum Phase { LOADING, CHUNKS, STAMP_ROOM, STAMP_DRESS, FINISH }

    final UUID player;
    final String playerName;
    final int epoch;
    final int slot;
    final String key;
    final ResourceKey<Level> deathDim;
    final Vec3 deathPos;
    /** The structure without its blob: everything the window and the ticket need before the file is read. */
    final PortalStructure geometry;
    final PortalCarriageLayout layout;
    final BlockPos roomOrigin;
    final Vec3i roomSize;
    final BoundingBox room;
    final BoundingBox window;
    final long startedNs;

    Phase phase = Phase.LOADING;
    /** What the IO thread read; null until it has. */
    @Nullable Optional<TributeRoomBlob> loaded;
    @Nullable TributeRoomBlob blob;
    @Nullable PortalStructure structure;
    List<BoundingBox> tubes = List.of();
    /** Whether anything has been written into the site, so a cancel knows to sweep it. */
    boolean laid;
    /** The client pressed Next while this was still standing: move them in the moment it is done. */
    boolean beginRequested;
    long beginRequestedAtMs;
    boolean slowLogged;

    long loadedNs;
    long chunksNs;
    long stampRoomMs;
    long stampDressMs;
    long finishMs;

    TributeRoomStandJob(UUID player, String playerName, int epoch, int slot, String key, ResourceKey<Level> deathDim,
                        Vec3 deathPos, PortalStructure geometry, PortalCarriageLayout layout, BlockPos roomOrigin,
                        Vec3i roomSize, BoundingBox window, long startedNs) {
        this.player = player;
        this.playerName = playerName;
        this.epoch = epoch;
        this.slot = slot;
        this.key = key;
        this.deathDim = deathDim;
        this.deathPos = deathPos;
        this.geometry = geometry;
        this.layout = layout;
        this.roomOrigin = roomOrigin;
        this.roomSize = roomSize;
        this.room = BoundingBox.fromCorners(roomOrigin,
            roomOrigin.offset(roomSize.getX() - 1, roomSize.getY() - 1, roomSize.getZ() - 1));
        this.window = window;
        this.startedNs = startedNs;
    }

    void requestBegin(long nowMs) {
        if (beginRequested) return;
        beginRequested = true;
        beginRequestedAtMs = nowMs;
    }

    boolean beginOverdue(long nowMs) {
        return beginOverdue(beginRequested, beginRequestedAtMs, nowMs);
    }

    /** Whether a visit asked for at {@code requestedAtMs} has waited past {@link #BEGIN_DEADLINE_MS}. Pure, for tests. */
    static boolean beginOverdue(boolean requested, long requestedAtMs, long nowMs) {
        return requested && nowMs - requestedAtMs >= BEGIN_DEADLINE_MS;
    }

    /** One line for the log: where the time went, from the ticket to the room standing. */
    String timings(long nowNs) {
        return String.format("load %d ms, chunks %d ms, stamp room %d ms, dress %d ms, finish %d ms, total %d ms",
            ms(loadedNs - startedNs), ms(chunksNs - loadedNs), stampRoomMs, stampDressMs, finishMs, ms(nowNs - startedNs));
    }

    static long ms(long nanos) {
        return nanos / 1_000_000L;
    }
}
