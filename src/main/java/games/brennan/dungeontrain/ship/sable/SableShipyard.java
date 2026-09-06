package games.brennan.dungeontrain.ship.sable;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Sable adapter for {@link Shipyard}. Translates Dungeon Train's port
 * abstraction onto Sable's {@link SubLevelAssemblyHelper} +
 * {@link SubLevelContainer} APIs.
 *
 * <p>Replaces the Phase 1 stub at {@code ship.vs.VsShipyard}. Sable
 * (https://github.com/ryanhcode/sable, PolyForm Shield 1.0.0) ships an
 * actively maintained NeoForge 1.21.1 build, which Valkyrien Skies does
 * not (still 1.20.1-only as of 2026-04-28).</p>
 *
 * <p>Wrapper identity: the ship-yard caches one {@link SableManagedShip}
 * per {@link ServerSubLevel} so {@code findAt} / {@code findAll} return
 * the same wrapper across calls within a tick. This matters for the
 * train code, which uses identity equality of {@link ManagedShip}
 * handles to detect duplicates while iterating.</p>
 */
public final class SableShipyard implements Shipyard {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Dungeon Train's private force-load ticket type. Distinct from Sable's
     * built-in {@code COMMAND_FORCED} (used by {@code /sable forceload}) so our
     * trailing-segment tickets never collide with an admin's manual force-load:
     * {@link #releaseAllForceLoads} only removes tickets of <em>this</em> type.
     *
     * <p>{@link SubLevelLoadingTicketType#create} self-registers the type in
     * Sable's static registry at class-load — well before any world loads — so
     * a ticket that happened to persist and reload still resolves by name
     * (and is then swept by {@link #releaseAllForceLoads}). Keyed by
     * {@link Unit} because a sub-level is either held for this reason or not.</p>
     *
     * <p><b>One type per {@link Shipyard.Hold}, and that is load-bearing.</b> The ticket type is the
     * holder's identity: two subsystems sharing one are the same holder, and either's release drops
     * the other's hold. This class used to carry a single type on the reasoning that "there is only
     * ever one DT ticket per sub-level" — true until the portal rooms started holding carriage
     * groups, at which point the appender's per-tick trailing-window reconcile silently revoked the
     * room's hold and Sable culled the group out from under a player standing inside it.</p>
     */
    private static final SubLevelLoadingTicketType<Unit> DT_TRAILING_TICKET =
        SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "trailing_segment"),
            Unit.CODEC);

    /**
     * The portal rooms' hold — see the note above on why it is not the trailing one.
     *
     * <p>Its own name in Sable's registry, so it is a distinct ticket and the trailing window's
     * release cannot touch it. Swept by {@link #releaseAllForceLoads} alongside the other, because
     * both are Dungeon Train's and neither may survive a session boundary.</p>
     */
    private static final SubLevelLoadingTicketType<Unit> DT_PORTAL_ROOM_TICKET =
        SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_room"),
            Unit.CODEC);

    /** The ticket that carries {@code hold}. */
    private static SubLevelLoadingTicketType<Unit> ticketFor(Shipyard.Hold hold) {
        return hold == Shipyard.Hold.PORTAL_ROOM ? DT_PORTAL_ROOM_TICKET : DT_TRAILING_TICKET;
    }

    private final ServerLevel level;

    /**
     * Wrapper cache. Weak so that when Sable removes a {@link ServerSubLevel}
     * (after {@code markRemoved} + container tick), the corresponding
     * {@link SableManagedShip} entry can be GC'd without manual cleanup.
     */
    private final WeakHashMap<ServerSubLevel, SableManagedShip> wrappers = new WeakHashMap<>();

    public SableShipyard(ServerLevel level) {
        this.level = level;
    }

    @Override
    public ManagedShip assemble(Set<BlockPos> blocks, double density) {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("Cannot assemble an empty block set");
        }

        // Sable's API takes:
        //   anchor — a single BlockPos that ends up at the centre of the
        //            sub-level's plot (model-space origin).
        //   bounds — a world-space BoundingBox3ic for moving entities and
        //            tracking points along with the assembled blocks.
        // We compute both from the input set: anchor = AABB centre rounded
        // to BlockPos; bounds = exact integer AABB.
        BlockPos anchor = computeAnchor(blocks);
        BoundingBox3i bounds = computeBounds(blocks);

        ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(
            level, anchor, blocks, bounds);

        // Sable computes mass automatically from block types via MassTracker.
        // The `density` arg is informational for our adapter only.
        if (density > 0.0 && LOGGER.isTraceEnabled()) {
            LOGGER.trace("[Sable] Assembled {} blocks; ignoring requested density {}",
                blocks.size(), density);
        }

        return wrappers.computeIfAbsent(subLevel, SableManagedShip::new);
    }

    @Override
    public void delete(ManagedShip ship) {
        if (!(ship instanceof SableManagedShip sableShip)) {
            LOGGER.warn("[Sable] delete called with non-Sable ManagedShip: {}", ship);
            return;
        }
        sableShip.subLevel().markRemoved();
        // The container's per-tick removal pass picks this up next tick and
        // also clears our weak cache entry once the ServerSubLevel is GC'd.
    }

    @Override
    public List<ManagedShip> findAll() {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return List.of();
        }
        List<ManagedShip> out = new ArrayList<>();
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub instanceof ServerSubLevel server && !server.isRemoved()) {
                out.add(wrappers.computeIfAbsent(server, SableManagedShip::new));
            }
        }
        return out;
    }

    @Override
    @Nullable
    public ManagedShip findAt(BlockPos pos) {
        SubLevel sub = Sable.HELPER.getContaining(level, pos);
        if (!(sub instanceof ServerSubLevel server) || server.isRemoved()) {
            return null;
        }
        return wrappers.computeIfAbsent(server, SableManagedShip::new);
    }

    @Override
    public void forceLoad(ManagedShip ship, Shipyard.Hold hold) {
        if (!(ship instanceof SableManagedShip sableShip)) {
            LOGGER.warn("[Sable] forceLoad called with non-Sable ManagedShip: {}", ship);
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        container.addForceLoadTicket(sableShip.subLevel(), ticketFor(hold), Unit.INSTANCE);
    }

    @Override
    public void releaseForceLoad(ManagedShip ship, Shipyard.Hold hold) {
        if (!(ship instanceof SableManagedShip sableShip)) {
            LOGGER.warn("[Sable] releaseForceLoad called with non-Sable ManagedShip: {}", ship);
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        container.removeForceLoadTicket(sableShip.subLevel(), ticketFor(hold), Unit.INSTANCE);
    }

    @Override
    public void releaseAllForceLoads() {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        // collectForceLoadedSubLevels() returns a fresh set, so removing
        // tickets while iterating it is safe. removeForceLoadTicket is a no-op
        // for any sub-level not holding OUR ticket type (e.g. a manual
        // /sable forceload), so unrelated force-loads are left intact.
        Collection<ServerSubLevel> forceLoaded = container.collectForceLoadedSubLevels();
        int removed = 0;
        for (ServerSubLevel sub : forceLoaded) {
            // Both of Dungeon Train's ticket types, because this is the session-boundary sweep and
            // neither may survive it — Sable persists tickets and resurrects what they hold. A
            // sub-level can carry both at once (a trailing carriage whose group is also a portal
            // pair), so these are two independent removals rather than an either/or.
            if (container.removeForceLoadTicket(sub, DT_TRAILING_TICKET, Unit.INSTANCE)) {
                removed++;
            }
            if (container.removeForceLoadTicket(sub, DT_PORTAL_ROOM_TICKET, Unit.INSTANCE)) {
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.info("[Sable] Released {} Dungeon Train force-load ticket(s)", removed);
        }
    }


    // ---- Locating a held sub-level's holding chunk (see reloadFromHolding) ----

    /** How many chunks either side of the cull-time pose to scan for the filed holding chunk. */
    static final int HOLDING_SCAN_CHUNKS_X = 4;
    static final int HOLDING_SCAN_CHUNKS_Z = 1;

    /**
     * {@code SubLevelHoldingChunkMap.getOrLoadHoldingChunk(ChunkPos, boolean)} — private in Sable,
     * reached reflectively. Loads the holding chunk from disk when it is not in memory, which is
     * what makes a far-away held group findable at all. Null if the method is not reachable, in
     * which case reloads fall back to the last-save pointer (correct only right after a save).
     */
    @Nullable
    private static final java.lang.invoke.MethodHandle GET_OR_LOAD_HOLDING_CHUNK = resolveGetOrLoadHoldingChunk();

    static {
        if (GET_OR_LOAD_HOLDING_CHUNK == null) {
            // The index's whole value is telling the spawn lanes "wait, this group is on disk".
            // Without the handle DT cannot pull a group back off disk, so making that claim would
            // park the lanes on a promise it cannot keep. Fall back to Sable's in-memory map: the
            // pre-fix behaviour, which can duplicate a carriage across a save but never deadlocks.
            LOGGER.error("[Sable] getOrLoadHoldingChunk is unreachable — disabling the Dungeon Train holding index. "
                + "Held groups fall back to Sable's in-memory map and may duplicate across a save. "
                + "Re-check SubLevelHoldingChunkMap on this Sable version.");
            SableHoldingIndex.disable();
        }
    }

    @Nullable
    private static java.lang.invoke.MethodHandle resolveGetOrLoadHoldingChunk() {
        try {
            java.lang.reflect.Method m = dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap.class
                .getDeclaredMethod("getOrLoadHoldingChunk", net.minecraft.world.level.ChunkPos.class, boolean.class);
            m.setAccessible(true);
            return java.lang.invoke.MethodHandles.lookup().unreflect(m);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Sable] getOrLoadHoldingChunk is not reachable ({}) — held groups can only be reloaded by their last-save pointer",
                e.toString());
            return null;
        }
    }

    /**
     * The holding chunk {@code held} is filed in: the last-save pointer's chunk first (exact right
     * after a save), then the chunks around the cull-time pose. Null when none contains it.
     */
    @Nullable
    private static net.minecraft.world.level.ChunkPos findHoldingChunk(
        dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap holding,
        dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel held,
        java.util.UUID subLevelId,
        @Nullable dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer pointer
    ) {
        if (GET_OR_LOAD_HOLDING_CHUNK == null) return (pointer == null) ? null : pointer.chunkPos();
        List<net.minecraft.world.level.ChunkPos> candidates = new ArrayList<>();
        // DT's own record of where Sable filed it, first: it is exact, so the pose scan below is
        // only a fallback for groups culled before the index was populated.
        net.minecraft.world.level.ChunkPos indexed = SableHoldingIndex.chunkOf(subLevelId);
        if (indexed != null) candidates.add(indexed);
        if (pointer != null && !candidates.contains(pointer.chunkPos())) candidates.add(pointer.chunkPos());
        dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData data = held.data();
        if (data != null && data.pose() != null) {
            int cx = net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(data.pose().position().x()));
            int cz = net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(data.pose().position().z()));
            for (int dx = -HOLDING_SCAN_CHUNKS_X; dx <= HOLDING_SCAN_CHUNKS_X; dx++) {
                for (int dz = -HOLDING_SCAN_CHUNKS_Z; dz <= HOLDING_SCAN_CHUNKS_Z; dz++) {
                    net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(cx + dx, cz + dz);
                    if (!candidates.contains(pos)) candidates.add(pos);
                }
            }
        }
        for (net.minecraft.world.level.ChunkPos pos : candidates) {
            if (holdingChunkContains(holding, pos, subLevelId)) return pos;
        }
        return null;
    }

    private static boolean holdingChunkContains(
        dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap holding,
        net.minecraft.world.level.ChunkPos pos,
        java.util.UUID subLevelId
    ) {
        try {
            Object chunk = GET_OR_LOAD_HOLDING_CHUNK.invoke(holding, pos, false);
            if (!(chunk instanceof dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk c)) return false;
            for (dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel h : c.getLoadedHoldingSubLevels()) {
                if (h.data() != null && subLevelId.equals(h.data().uuid())) return true;
            }
            return false;
        } catch (Throwable t) {
            LOGGER.debug("[Sable] holding chunk probe at {} failed: {}", pos, t.toString());
            return false;
        }
    }

    @Override
    public boolean isHeld(java.util.UUID subLevelId) {
        if (isHeldInMemory(subLevelId)) return true;
        // Sable's in-memory map is NOT a durable record of what is held: saveAll() writes hidden
        // holding chunks to disk and then evicts them, dropping their sub-levels from
        // allHoldingSubLevels while the data stays on disk and will resurrect on chunk load.
        // Reading only that map made this method answer "gone for good" about a merely sleeping
        // carriage group, so the appender reaped its anchor and respawned an identical group that
        // then collided with the original — the duplicate-overlapping-carriages bug. The contract
        // this method owes its callers is "recoverable", not "in memory right now".
        return SableHoldingIndex.contains(subLevelId);
    }

    /**
     * Whether Sable's in-memory holding map lists {@code subLevelId} right now. Diagnostics and
     * the internals of {@link #isHeld} only — callers wanting "can this come back?" want
     * {@link #isHeld}, because this answer evaporates on every save.
     */
    boolean isHeldInMemory(java.util.UUID subLevelId) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return false;
        dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap holding =
            container.getHoldingChunkMap();
        return holding != null && holding.getHoldingSubLevel(subLevelId) != null;
    }

    @Override
    public boolean reloadFromHolding(java.util.UUID subLevelId) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return false;
        dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap holding =
            container.getHoldingChunkMap();
        if (holding == null) return false;
        // Sable does NOT snatch-from-holding for a force-load ticket at runtime
        // (the only such path, ServerSubLevelContainer.loadForceLoadedSubLevels(),
        // runs solely at container.initialize()). The public runtime reload is
        // snatchAndLoad(pointer, uuid) — this is what Sable itself uses.
        //
        // IMPORTANT: use snatchAndLoad, NOT a bare loadHoldingSubLevel. A held
        // sub-level lives in BOTH the global allHoldingSubLevels map (what
        // getHoldingSubLevel reads) AND its SubLevelHoldingChunk's
        // loadedHoldingSubLevels. loadHoldingSubLevel only removes it from the
        // global map; the chunk entry survives, so the very next
        // container.tick() → processChanges() → collectReadySubLevels() loads
        // the SAME sub-level AGAIN. The second fullyLoad returns null and Sable
        // NPEs in its unguarded reportSubLevelLoadFailure(pointer) if the
        // pointer is null — crashing the server and corrupting the save.
        // snatchAndLoad first removes the entry from the chunk (chunk.snatch),
        // then loads it, so there is no double-load.
        dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel held =
            holding.getHoldingSubLevel(subLevelId);
        if (held == null) {
            // Absent from Sable's in-memory map. If DT recorded it on disk, re-materialise the
            // holding chunk it was filed in — getOrLoadHoldingChunk reads from disk and registers
            // every sub-level it finds back into allHoldingSubLevels, which also repairs the
            // index for this group's siblings.
            net.minecraft.world.level.ChunkPos indexed = SableHoldingIndex.chunkOf(subLevelId);
            if (indexed == null) return false; // not in holding (still live, or genuinely gone)
            holdingChunkContains(holding, indexed, subLevelId);
            held = holding.getHoldingSubLevel(subLevelId);
            if (held == null) {
                LOGGER.warn("[Sable] reloadFromHolding: {} is recorded in holding chunk {} but did not "
                    + "re-materialise from disk — counting a failed recovery attempt", subLevelId, indexed);
                SableHoldingIndex.recordFailure(subLevelId);
                return false;
            }
        }
        dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer pointer = held.pointer();
        // Sable files a culled sub-level in the holding chunk of its CURRENT position but tags it
        // with the pointer from its LAST SAVE (moveToUnloaded → getLastSerializationPointer), and
        // snatchAndLoad looks only in the pointer's chunk. A train group moves a chunk every few
        // seconds, so the pointer is stale for almost every group culled since the last save and
        // the snatch fails with "wasn't present in the holding chunk" while loading nothing.
        // Find the chunk it was actually filed in by scanning the holding chunks around its
        // cull-time pose (which the serialized data carries), then snatch from THAT chunk. A null
        // pointer (never serialized) is fine here: only the chunk matters to the snatch.
        net.minecraft.world.level.ChunkPos filed = findHoldingChunk(holding, held, subLevelId, pointer);
        if (filed == null) {
            LOGGER.warn("[Sable] reloadFromHolding: held sub-level {} was not found in any holding chunk near its pose (pointer={}) — leaving it held",
                subLevelId, pointer);
            SableHoldingIndex.recordFailure(subLevelId);
            return false;
        }
        dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer target =
            (pointer != null && pointer.chunkPos().equals(filed))
                ? pointer
                : new dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer(filed, (short) 0, (short) 0);
        try {
            holding.snatchAndLoad(target, subLevelId);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[Sable] reloadFromHolding failed for sub-level {}: {}", subLevelId, t.toString());
            SableHoldingIndex.recordFailure(subLevelId);
            return false;
        }
    }

    /** Centre of the block set's integer AABB, rounded down to a {@link BlockPos}. */
    private static BlockPos computeAnchor(Set<BlockPos> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks) {
            if (p.getX() < minX) minX = p.getX();
            if (p.getY() < minY) minY = p.getY();
            if (p.getZ() < minZ) minZ = p.getZ();
            if (p.getX() > maxX) maxX = p.getX();
            if (p.getY() > maxY) maxY = p.getY();
            if (p.getZ() > maxZ) maxZ = p.getZ();
        }
        return new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
    }

    /** Inclusive integer AABB of the block set. */
    private static BoundingBox3i computeBounds(Set<BlockPos> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks) {
            if (p.getX() < minX) minX = p.getX();
            if (p.getY() < minY) minY = p.getY();
            if (p.getZ() < minZ) minZ = p.getZ();
            if (p.getX() > maxX) maxX = p.getX();
            if (p.getY() > maxY) maxY = p.getY();
            if (p.getZ() > maxZ) maxZ = p.getZ();
        }
        return new BoundingBox3i(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
