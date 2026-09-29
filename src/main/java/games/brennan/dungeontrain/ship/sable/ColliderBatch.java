package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.object.ArbitraryPhysicsObject;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicket;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import games.brennan.dungeontrain.mixin.PhysicsChunkTicketManagerAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Batches Sable's per-block voxel-collider updates over one Dungeon Train stamp.
 *
 * <p><b>Why.</b> Every {@code LevelChunk.setBlockState} in a sub-level (and in any world section the
 * physics scene tracks) runs {@code SubLevelPhysicsSystem.handleBlockChange}, whose
 * {@code pipeline.handleBlockChange} re-derives the neighbourhood state of the changed cell and its
 * six neighbours and pushes seven native {@code Rapier3D.changeBlock} calls, then
 * {@code wakeUpObjectsAt} queries every body around the cell. One {@code TrainAssembler.spawnGroup}
 * hits that three to four times per block — the source-world stamp, Sable's lift into the plot, the
 * airing of the source cells and the contents stamp — which is the 2,000–7,000 {@code blockChanges=}
 * per window in player lag logs and the 1.3–2.7× tick after every append.</p>
 *
 * <p><b>What Sable does instead when it loads a sub-level.</b> {@code ServerLevelPlot.load} never
 * goes per block: it writes the chunks, then calls {@code addTicketForSection} +
 * {@code PhysicsPipeline.handleChunkSectionAddition(section, x, y, z, true)} once per non-air section
 * — a whole 16³ upload in one native {@code addChunk}. That is the seam this class uses.</p>
 *
 * <p><b>How.</b> While a scope is active on this thread, {@code SubLevelPhysicsBlockChangeBatchMixin}
 * skips the two calls above and hands the changed section to {@link #record}. Sable's ticket,
 * mass-tracker, plot-bounding-box, heat-map and floating-block work still runs per block exactly as
 * before, so the pivot pin and the mass-upload guard see nothing new. When the <em>outermost</em>
 * scope exits — in a {@code finally}, so a stamp that throws still leaves the collider correct — every
 * touched section is rebuilt once: a section the ticket manager already tracks is removed and
 * re-added (the native upsert semantics of {@code addChunk} are unknown, and Sable's own expire path
 * removes before the next add), an untracked one goes through {@code addSectionIfNotTracked} so the
 * ticket is registered with it. One wake-up per section then replaces the per-block
 * {@code wakeUpObjectsAt}.</p>
 *
 * <p><b>Correctness window.</b> The collider is stale only between the first deferred block and the
 * rebuild, and both ends are inside one synchronous server-thread call: no physics step, entity tick
 * or player movement runs in between. Entities a carriage template carries are already spawned
 * ticks later by the appender's settle tracker, never inside the stamp.</p>
 *
 * <p><b>Thread-local depth counter</b>, like {@code WorldgenForceGuard} and {@code CarriageStampGuard}:
 * only the server thread ever holds a scope, and nested scopes ({@code spawnGroup} inside a bootstrap
 * loop, say) fold into the outermost one's rebuild. {@link #ENABLED} is the runtime kill switch
 * ({@code /dungeontrain debug colliderbatch off}) for the Gate 2 matched-toggle A/B: switched off
 * mid-scope, the per-block path resumes at once and whatever was already deferred is still rebuilt
 * on exit.</p>
 *
 * <p>Accounting: each deferred block change bumps {@code [mspt] batchedBlockChanges=}, each rebuilt
 * section {@code colliderRebuilds=}; {@code blockChanges=} keeps counting the un-batched per-block
 * updates (a rider mining, redstone, an explosion), so it stays comparable with older logs.</p>
 */
public final class ColliderBatch {

    private static final Logger LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    /** Runtime toggle; volatile so the command thread's write is seen by the server thread. */
    public static volatile boolean ENABLED = true;

    private static final ThreadLocal<Batch> CURRENT = new ThreadLocal<>();

    private ColliderBatch() {}

    /** One thread's open scope: the sections whose collider update was deferred, and how many. */
    static final class Batch {
        /** How many nested scopes are open; the rebuild runs when the last one closes. */
        int depth;
        /** Block changes whose collider update was skipped. */
        long deferred;
        /** The level the deferred sections belong to (one stamp never spans two levels). */
        @Nullable
        ServerLevel level;
        /**
         * Touched sections, keyed by {@link SectionPos#asLong} → the section Sable handed the hook.
         * The section object is what the rebuild uploads: a plot chunk's sections are not reachable
         * through {@code level.getChunk}, and a chunk's section array is stable for its lifetime.
         * Insertion-ordered so the rebuild log reads in stamp order.
         */
        final Long2ObjectMap<LevelChunkSection> sections = new Long2ObjectLinkedOpenHashMap<>();
    }

    /** True while a scope is open on this thread and batching is enabled. */
    public static boolean isActive() {
        return ENABLED && CURRENT.get() != null;
    }

    /** True while a scope is open on this thread, enabled or not (the bookkeeping test seam). */
    static boolean scopeOpen() {
        return CURRENT.get() != null;
    }

    /**
     * Called by the mixin for every block change whose collider update was skipped. Remembers the
     * section for the rebuild. A {@code null} level (a call outside any scope, which the mixin's
     * {@link #isActive} check prevents) is ignored rather than trusted.
     */
    public static void record(ServerLevel level, SectionPos sectionPos, LevelChunkSection section) {
        Batch batch = CURRENT.get();
        if (batch == null) return;
        if (batch.level == null) batch.level = level;
        batch.sections.put(sectionPos.asLong(), section);
        batch.deferred++;
    }

    /** Run {@code body} inside a scope; the outermost exit rebuilds every touched section. */
    public static void run(Runnable body) {
        call(() -> { body.run(); return null; });
    }

    /** {@link #run} for a body that returns a value. */
    public static <T> T call(Supplier<T> body) {
        return call(body, ColliderBatch::rebuild);
    }

    /**
     * The scope mechanics with the rebuild pluggable — what the unit test drives, since the real
     * rebuild needs a live Sable physics system.
     */
    static <T> T call(Supplier<T> body, Consumer<Batch> rebuild) {
        Batch batch = CURRENT.get();
        boolean outermost = batch == null;
        if (outermost) {
            batch = new Batch();
            CURRENT.set(batch);
        }
        batch.depth++;
        try {
            return body.get();
        } finally {
            batch.depth--;
            if (outermost) {
                CURRENT.remove();
                try {
                    rebuild.accept(batch);
                } catch (Throwable t) {
                    LOGGER.error("[colliderBatch] rebuild failed — Sable collider may be stale for {} section(s): {}",
                        batch.sections.size(), t.toString(), t);
                }
            }
        }
    }

    /** Upload every touched section once and wake what stands near it. */
    private static void rebuild(Batch batch) {
        if (batch.sections.isEmpty()) return;
        ServerLevel level = batch.level;
        SubLevelPhysicsSystem system = level == null ? null : SubLevelPhysicsSystem.get(level);
        if (system == null) {
            LOGGER.warn("[colliderBatch] no physics system for {} deferred change(s) — nothing rebuilt", batch.deferred);
            return;
        }
        long start = System.nanoTime();
        PhysicsPipeline pipeline = system.getPipeline();
        PhysicsChunkTicketManager tickets = system.getTicketManager();
        Map<SectionPos, PhysicsChunkTicket> tracked =
            ((PhysicsChunkTicketManagerAccessor) tickets).dungeontrain$physicsChunks();
        int rebuilt = 0;
        for (Long2ObjectMap.Entry<LevelChunkSection> e : batch.sections.long2ObjectEntrySet()) {
            SectionPos pos = SectionPos.of(e.getLongKey());
            try {
                rebuildSection(level, system, pipeline, tickets, tracked, pos, e.getValue());
                rebuilt++;
                PhysicsStepTimer.countColliderRebuild();
            } catch (Throwable t) {
                LOGGER.error("[colliderBatch] section {} rebuild failed: {}", pos, t.toString(), t);
            }
        }
        PhysicsStepTimer.addBatchedBlockChanges(batch.deferred);
        LOGGER.debug("[colliderBatch] sections={} rebuilt={} blockChanges={} ms={}",
            batch.sections.size(), rebuilt, batch.deferred,
            String.format("%.2f", (System.nanoTime() - start) / 1_000_000.0));
    }

    private static void rebuildSection(ServerLevel level, SubLevelPhysicsSystem system, PhysicsPipeline pipeline,
                                       PhysicsChunkTicketManager tickets, Map<SectionPos, PhysicsChunkTicket> tracked,
                                       SectionPos pos, LevelChunkSection section) {
        if (tracked.containsKey(pos)) {
            // Already in the native scene (a plot section the first deferred block ticketed, or a
            // world section near the train): drop it and upload the section as it now stands. The
            // `true` is the load path's form — a plot section is attached to its sub-level's body.
            pipeline.handleChunkSectionRemoval(pos.x(), pos.y(), pos.z());
            pipeline.handleChunkSectionAddition(section, pos.x(), pos.y(), pos.z(), true);
        } else {
            // Not in the scene, so nothing is stale; upload once and register the ticket with it.
            tickets.addSectionIfNotTracked(level, section, pos, pipeline);
        }
        wake(level, system, pipeline, pos);
    }

    /** {@code SubLevelPhysicsSystem.wakeUpObjectsAt} over a whole section instead of one cell. */
    private static void wake(ServerLevel level, SubLevelPhysicsSystem system, PhysicsPipeline pipeline, SectionPos pos) {
        BoundingBox3d bounds = new BoundingBox3d(
            pos.minBlockX(), pos.minBlockY(), pos.minBlockZ(),
            pos.maxBlockX() + 1, pos.maxBlockY() + 1, pos.maxBlockZ() + 1);
        bounds.expand(0.1, bounds);
        for (SubLevel sub : Sable.HELPER.getAllIntersecting(level, bounds)) {
            if (sub instanceof ServerSubLevel server && !server.isRemoved()) {
                pipeline.wakeUp(server);
            }
        }
        BoundingBox3d objectBounds = new BoundingBox3d();
        for (ArbitraryPhysicsObject object : system.getArbitraryObjects()) {
            object.getBoundingBox(objectBounds);
            if (objectBounds.intersects(bounds)) object.wakeUp();
        }
    }
}
