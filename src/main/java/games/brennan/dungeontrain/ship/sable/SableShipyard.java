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
     * {@link Unit} because a sub-level is either held by us or not; there is
     * only ever one DT ticket per sub-level.</p>
     */
    private static final SubLevelLoadingTicketType<Unit> DT_TRAILING_TICKET =
        SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "trailing_segment"),
            Unit.CODEC);

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
    public void forceLoad(ManagedShip ship) {
        if (!(ship instanceof SableManagedShip sableShip)) {
            LOGGER.warn("[Sable] forceLoad called with non-Sable ManagedShip: {}", ship);
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        container.addForceLoadTicket(sableShip.subLevel(), DT_TRAILING_TICKET, Unit.INSTANCE);
    }

    @Override
    public void releaseForceLoad(ManagedShip ship) {
        if (!(ship instanceof SableManagedShip sableShip)) {
            LOGGER.warn("[Sable] releaseForceLoad called with non-Sable ManagedShip: {}", ship);
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        container.removeForceLoadTicket(sableShip.subLevel(), DT_TRAILING_TICKET, Unit.INSTANCE);
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
            if (container.removeForceLoadTicket(sub, DT_TRAILING_TICKET, Unit.INSTANCE)) {
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.info("[Sable] Released {} Dungeon Train force-load ticket(s)", removed);
        }
    }

    @Override
    public boolean isHeld(java.util.UUID subLevelId) {
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
        if (held == null) return false; // not in holding (still live, or genuinely gone)
        dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer pointer = held.pointer();
        if (pointer == null) {
            // No serialization pointer ⇒ Sable can't locate its holding chunk:
            // snatchAndLoad would NPE and a bare load would leave a chunk entry
            // (double-load crash). Leave it held; the appender simply keeps the
            // edge deferred (no void) rather than crash. Rare — only a sub-level
            // culled before it was ever serialized.
            LOGGER.warn("[Sable] reloadFromHolding: held sub-level {} has a null serialization pointer — leaving it held (cannot safely snatch)", subLevelId);
            return false;
        }
        try {
            holding.snatchAndLoad(pointer, subLevelId);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[Sable] reloadFromHolding failed for sub-level {}: {}", subLevelId, t.toString());
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
