package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Async chunk pre-warming for dimension transits. Issues a region ticket
 * around a target {@link ChunkPos} so the surrounding chunks are eagerly
 * loaded by the chunk worker pool before any carriage tries to materialise
 * in that dimension.
 *
 * <p>Design rationale: the cross-dimension train transit (Phase 8 +) reloads
 * a Sable sub-level into a target {@link ServerLevel} via
 * {@code SubLevelSerializer.fullyLoad}. That call must run on the server
 * thread, but if the target chunks aren't already loaded, vanilla blocks the
 * tick while it generates them — and Nether/End worldgen can spike
 * server-tick time well past the 50 ms budget. Pre-warming chunks ahead of
 * time keeps the actual transit tick cheap.</p>
 *
 * <p>API shape: fire-and-poll, not future-based. Callers invoke
 * {@link #warm} once at portal-pair creation, then later check
 * {@link #isReady} when they need to commit a transit. If the chunks aren't
 * ready, the transit is re-queued for a later tick. There's no
 * CompletableFuture because the only consumer is the per-tick
 * {@code CarriageTransitDetector} (Phase 9) which is already polling on a
 * tick cadence — a future would just add bookkeeping for no benefit.</p>
 *
 * <p>Ticket lifecycle: {@link #warm} adds a ticket; {@link #release} drops
 * it. Tickets do NOT persist across server restart (TicketType is in-memory
 * only) — that's correct: on reload, the PortalRegistry (Phase 2) will
 * replay {@code warm()} for every known pair during world load.</p>
 */
public final class ChunkPrewarmer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Our custom ticket type. UUID payload lets multiple portals share a
     * radius without their tickets colliding (each portal pair has a unique
     * id from {@link PortalRegistry#addPair}).
     */
    public static final TicketType<UUID> TICKET =
        TicketType.create("dungeontrain_portal_prewarm", UUID::compareTo);

    private ChunkPrewarmer() {}

    /**
     * Add a region ticket around {@code center} with the given chunk
     * radius. The chunks load asynchronously on the chunk worker pool —
     * this call returns immediately. Use {@link #isReady} to check
     * completion.
     *
     * <p>The {@code radius} argument is the chunk-radius (not block-radius)
     * of forced loading. Internally vanilla converts this to a ticket level
     * of {@code 33 - radius}, which means radius=3 → level 30 → chunks
     * reach FULL status (no entity ticking; sufficient for our
     * {@code fullyLoad} use case).</p>
     */
    public static void warm(ServerLevel level, ChunkPos center, int radius, UUID id) {
        level.getChunkSource().addRegionTicket(TICKET, center, radius, id);
        LOGGER.debug("[Portal] Warming {} chunks around {} in {} (id={})",
            (2 * radius + 1) * (2 * radius + 1), center, level.dimension().location(), id);
    }

    /**
     * Drop the ticket previously issued by {@link #warm}. Must use the same
     * {@code center}, {@code radius}, and {@code id} — vanilla's ticket
     * matcher is value-equality on all four (type + pos + level + payload).
     */
    public static void release(ServerLevel level, ChunkPos center, int radius, UUID id) {
        level.getChunkSource().removeRegionTicket(TICKET, center, radius, id);
        LOGGER.debug("[Portal] Released warm ticket around {} in {} (id={})",
            center, level.dimension().location(), id);
    }

    /**
     * True if every chunk within {@code radius} of {@code center} is
     * currently loaded. Conservative: we check {@code hasChunk(...)} which
     * returns true only once the chunk has reached FULL status — exactly
     * the precondition Sable's {@code SubLevelSerializer.fullyLoad} needs.
     */
    public static boolean isReady(ServerLevel level, ChunkPos center, int radius) {
        ServerChunkCache cache = level.getChunkSource();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!cache.hasChunk(center.x + dx, center.z + dz)) {
                    return false;
                }
            }
        }
        return true;
    }
}
