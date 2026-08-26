package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalRoomResize;
import games.brennan.dungeontrain.template.TemplateDecor;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.world.PortalRoomResizeMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The row a portal-room resize adds or takes away, and the two sidecars that have to move with it.
 *
 * <p>A room's authored state is three things, not one: the blocks, the per-cell variant sidecar
 * ({@link TrackVariantBlocks}), and the container-contents pools each chest rolls from
 * ({@link ContainerContentsStore}). All three are addressed in <b>plot-local</b> coordinates, so a
 * resize that moves the {@code MIN} face — which is half of them, now that the faces alternate — has
 * to shift all three together or the chests come back rolling the pool of whatever cell has taken
 * their old coordinate.</p>
 *
 * <p>{@link #stash} runs before a shrink clears the world and files the row it is about to lose;
 * {@link #restore} runs after a grow has laid the shell and puts a filed row back. Between them a
 * shrink is undoable, which it never used to be — the block layer clipped the template to the new box
 * and the sidecar dropped its out-of-bounds cells on the next parse, so one tap too far cost the
 * author that row permanently.</p>
 *
 * <p>Nothing here touches disk. The sidecars are mutated in their session caches and the blocks go
 * into a per-world {@link PortalRoomResizeMemory}; {@code /dt save} remains the only thing that
 * writes a room's files.</p>
 */
final class PortalRoomResizeSlabs {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PortalRoomResizeSlabs() {}

    private static String plotKey(String name) {
        return ContainerContentsStore.trackPlotKey(TrackKind.PORTAL_ROOM, name);
    }

    /**
     * File the row {@code step} is about to remove — blocks, variant cells, contents pools.
     *
     * <p>Called while the plot still stands at {@code sizeBefore}: the row is read out of the world,
     * not out of the saved template, so what is remembered is what the author could see.</p>
     */
    static void stash(ServerLevel overworld, String name, BlockPos origin, Vec3i sizeBefore,
                      PortalRoomResize.Step step) {
        PortalRoomResize.Axis axis = step.axis();
        Vec3i slabSize = PortalRoomResize.with(sizeBefore, axis, 1);
        BlockPos slabOrigin = origin.offset(PortalRoomResize.along(axis, step.slabIndex()));

        StructureTemplate blocks = TemplateDecor.capture(overworld, slabOrigin, slabSize, Blocks.STRUCTURE_VOID);

        TrackVariantBlocks sidecar = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, name, sizeBefore);
        TrackVariantBlocks slabSidecar = TrackVariantBlocks.emptyFor(TrackKind.PORTAL_ROOM);
        int cells = 0;
        for (Map.Entry<BlockPos, Integer> e : slabCells(sidecar, axis, step.slabIndex()).entrySet()) {
            BlockPos local = e.getKey().subtract(PortalRoomResize.along(axis, step.slabIndex()));
            slabSidecar.put(local, sidecar.statesAt(e.getKey()));
            if (e.getValue() > 0) slabSidecar.setLockId(local, e.getValue());
            sidecar.remove(e.getKey());
            cells++;
        }

        ContainerContentsStore store = ContainerContentsStore.loadFor(plotKey(name));
        ContainerContentsStore slabStore = ContainerContentsStore.detached(plotKey(name));
        int pools = 0;
        for (BlockPos pos : List.copyOf(store.allPositions())) {
            if (PortalRoomResize.of(pos, axis) != step.slabIndex()) continue;
            BlockPos local = pos.subtract(PortalRoomResize.along(axis, step.slabIndex()));
            // A linked cell has no pool of its own — the link is the authored thing, and carrying
            // the prefab's resolved pool instead would silently un-link the chest on the way back.
            String link = store.linkAt(pos);
            if (link != null) {
                slabStore.setLink(local, link);
                store.clearLink(pos);
            } else if (store.hasPoolAt(pos)) {
                slabStore.putPool(local, store.poolAt(pos));
            }
            store.removePool(pos);
            pools++;
        }

        PortalRoomResizeMemory.get(overworld).put(name, axis, step.memoryKey(),
            new PortalRoomResizeMemory.Slab(blocks.save(new CompoundTag()),
                slabSidecar.isEmpty() ? "" : slabSidecar.asJsonText(),
                pools == 0 ? "" : slabStore.asJsonText()));

        LOGGER.debug("[DungeonTrain] Portal room '{}': filed the {} row cropped at {}={} "
            + "({} variant cells, {} contents cells)",
            name, step.side(), axis, step.memoryKey(), cells, pools);
    }

    /**
     * Put back the row filed for {@code step}, if this world remembers one.
     *
     * <p>Runs after the shell and the live room have been laid, so a remembered row wins over the
     * built-in blocks that filled the space a moment earlier. Nothing remembered — a room being grown
     * past any size it has ever been — leaves the built-in shell, which is what it should be.</p>
     *
     * @return true when a row was restored
     */
    static boolean restore(ServerLevel overworld, String name, BlockPos origin, Vec3i sizeAfter,
                           PortalRoomResize.Step step) {
        PortalRoomResizeMemory.Slab slab =
            PortalRoomResizeMemory.get(overworld).take(name, step.axis(), step.memoryKey());
        if (slab == null) return false;

        PortalRoomResize.Axis axis = step.axis();
        Vec3i offset = PortalRoomResize.along(axis, step.slabIndex());
        BlockPos at = origin.offset(offset);

        StructureTemplate blocks = new StructureTemplate();
        blocks.load(overworld.holderLookup(Registries.BLOCK), slab.blocks());
        // Bounded by the plot rather than trusted: the other two axes may have been resized since
        // the row was filed, and a row wider than the box it is going back into would otherwise
        // spill into the plot next door.
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setIgnoreEntities(true)
            .setBoundingBox(new BoundingBox(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + sizeAfter.getX() - 1,
                origin.getY() + sizeAfter.getY() - 1,
                origin.getZ() + sizeAfter.getZ() - 1));
        blocks.placeInWorld(overworld, at, at, settings, overworld.getRandom(), 3);
        // The row's own decoration comes back with it — a picture the author hung on the wall a
        // shrink took away is part of the row, not of the box it was cut from.
        TemplateDecor.replace(overworld, at, blocks, settings, null);

        TrackVariantBlocks sidecar = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, name, sizeAfter);
        TrackVariantBlocks slabSidecar = TrackVariantBlocks.fromJsonText(
            slab.variantsJson(), TrackKind.PORTAL_ROOM, name,
            PortalRoomResize.with(sizeAfter, axis, 1));
        for (CarriageVariantBlocks.Entry e : slabSidecar.entries()) {
            BlockPos target = e.localPos().offset(offset);
            if (!inBounds(target, sizeAfter)) continue;
            sidecar.put(target, e.states());
            int lock = slabSidecar.lockIdAt(e.localPos());
            if (lock > 0) sidecar.setLockId(target, lock);
        }

        if (!slab.contentsJson().isEmpty()) {
            ContainerContentsStore store = ContainerContentsStore.loadFor(plotKey(name));
            ContainerContentsStore slabStore =
                ContainerContentsStore.fromJsonText(plotKey(name), slab.contentsJson());
            for (BlockPos pos : slabStore.allPositions()) {
                BlockPos target = pos.offset(offset);
                if (!inBounds(target, sizeAfter)) continue;
                String link = slabStore.linkAt(pos);
                if (link != null) store.setLink(target, link);
                else store.putPool(target, slabStore.poolAt(pos));
            }
        }

        LOGGER.debug("[DungeonTrain] Portal room '{}': restored the {} row filed at {}={}",
            name, step.side(), axis, step.memoryKey());
        return true;
    }

    /**
     * Move every sidecar cell by {@code shift} — what the {@code MIN} face growing or shrinking does
     * to everything already in the room.
     *
     * <p>Cells that land outside the new box are dropped. On a shrink there are none left to drop by
     * the time this runs ({@link #stash} has already taken that row), so this only bites if a future
     * caller shifts without stashing — which is a bug worth losing a cell over rather than silently
     * keeping a cell nothing can address.</p>
     */
    static void shiftSidecars(String name, Vec3i sizeAfter, Vec3i shift) {
        if (shift.equals(Vec3i.ZERO)) return;

        TrackVariantBlocks sidecar = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, name, sizeAfter);
        Map<BlockPos, List<VariantState>> moved = new LinkedHashMap<>();
        Map<BlockPos, Integer> movedLocks = new LinkedHashMap<>();
        for (CarriageVariantBlocks.Entry e : sidecar.entries()) {
            BlockPos target = e.localPos().offset(shift);
            int lock = sidecar.lockIdAt(e.localPos());
            sidecar.remove(e.localPos());
            if (!inBounds(target, sizeAfter)) continue;
            moved.put(target, e.states());
            if (lock > 0) movedLocks.put(target, lock);
        }
        moved.forEach(sidecar::put);
        movedLocks.forEach(sidecar::setLockId);

        ContainerContentsStore store = ContainerContentsStore.loadFor(plotKey(name));
        Map<BlockPos, String> movedLinks = new LinkedHashMap<>();
        Map<BlockPos, ContainerContentsPool> movedPools = new LinkedHashMap<>();
        for (BlockPos pos : List.copyOf(store.allPositions())) {
            BlockPos target = pos.offset(shift);
            String link = store.linkAt(pos);
            if (link != null) {
                store.clearLink(pos);
                if (inBounds(target, sizeAfter)) movedLinks.put(target, link);
            } else if (store.hasPoolAt(pos)) {
                if (inBounds(target, sizeAfter)) movedPools.put(target, store.poolAt(pos));
            }
            store.removePool(pos);
        }
        movedLinks.forEach(store::setLink);
        movedPools.forEach(store::putPool);
    }

    /** Every sidecar cell on the {@code slabIndex} row of {@code axis}, with its lock id. */
    private static Map<BlockPos, Integer> slabCells(TrackVariantBlocks sidecar,
                                                    PortalRoomResize.Axis axis, int slabIndex) {
        Map<BlockPos, Integer> out = new LinkedHashMap<>();
        for (CarriageVariantBlocks.Entry e : sidecar.entries()) {
            if (PortalRoomResize.of(e.localPos(), axis) == slabIndex) {
                out.put(e.localPos(), sidecar.lockIdAt(e.localPos()));
            }
        }
        return out;
    }

    private static boolean inBounds(BlockPos p, Vec3i size) {
        return p.getX() >= 0 && p.getX() < size.getX()
            && p.getY() >= 0 && p.getY() < size.getY()
            && p.getZ() >= 0 && p.getZ() < size.getZ();
    }
}
