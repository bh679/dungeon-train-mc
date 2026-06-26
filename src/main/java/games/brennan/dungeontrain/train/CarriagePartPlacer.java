package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriagePartVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Stamp helpers for individual {@link CarriagePartKind} templates.
 *
 * <p>{@link #placeAt} looks up the named template via
 * {@link CarriagePartTemplateStore}, then stamps it once per
 * {@link CarriagePartKind.Placement} (1 stamp for FLOOR/ROOF, 2 mirrored
 * stamps for WALLS/DOORS) at positions relative to the carriage's origin.
 *
 * <p>Unlike tunnel stamps, carriage part stamps <b>do not</b> use the
 * {@code VSShipFilterProcessor} — a carriage is spawned into the shipyard
 * (Valkyrien Skies ship territory), so filtering out "positions on a VS ship"
 * would drop every single block and produce an empty carriage. Monolithic
 * {@code CarriagePlacer.stampTemplate} follows the same rule.
 *
 * <p>The reserved name {@link CarriagePartKind#NONE} is a no-op: the stamp is
 * skipped entirely so FLATBED-style carriages can declare {@code walls=none}
 * etc. without needing an empty NBT on disk.
 */
public final class CarriagePartPlacer {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private CarriagePartPlacer() {}

    /**
     * Stamp {@code kind}'s named template at every placement for a carriage
     * rooted at {@code carriageOrigin}. Silently skips when {@code name} is
     * {@code null} / blank / {@code "none"}, or when the store has no matching
     * template (the caller has already fallen back to this path, so logging
     * is handled inside the store).
     *
     * <p>After the template stamp, any {@link CarriagePartVariantBlocks}
     * sidecar for this part is applied on top — variant blocks picked
     * deterministically from {@code (seed, carriageIndex, localPos)} and
     * mirrored to match the placement's {@link Mirror} so wall- and door-
     * mirrored stamps land coherent block states on either side.</p>
     */
    public static void placeAt(ServerLevel level, BlockPos carriageOrigin,
                               CarriagePartKind kind, String name, CarriageDims dims,
                               long seed, int carriageIndex) {
        // Single-name overload: stamp the same template at every placement.
        // Equivalent to passing the name N times to the per-placement variant.
        List<CarriagePartKind.Placement> placements = kind.placements(dims);
        String[] names = new String[placements.size()];
        for (int i = 0; i < placements.size(); i++) names[i] = name;
        placeAtPerPlacement(level, carriageOrigin, kind, List.of(names), dims, seed, carriageIndex);
    }

    /**
     * Stamp {@code kind} at each placement using the matching name from
     * {@code names} (one entry per {@link CarriagePartKind.Placement}).
     * Honours the per-entry {@link CarriagePartAssignment.SideMode} —
     * placements whose name is {@link CarriagePartKind#NONE} or whose
     * template is missing on disk are skipped silently, leaving the base
     * geometry intact for that side.
     *
     * <p>Variant-block overlays still run per name; if the two placements
     * use different names, each runs its own sidecar lookup. The mirror
     * for each placement is applied independently.</p>
     */
    public static void placeAtPerPlacement(ServerLevel level, BlockPos carriageOrigin,
                                           CarriagePartKind kind, List<String> names, CarriageDims dims,
                                           long seed, int carriageIndex) {
        List<CarriagePartKind.Placement> placements = kind.placements(dims);
        Vec3i placementSize = kind.dims(dims);
        for (int i = 0; i < placements.size(); i++) {
            String name = i < names.size() ? names.get(i) : null;
            if (name == null || name.isBlank() || CarriagePartKind.NONE.equals(name)) continue;
            Optional<StructureTemplate> stored = CarriagePartTemplateStore.get(level, kind, name, dims);
            if (stored.isEmpty()) continue;
            StructureTemplate template = stored.get();
            CarriagePartKind.Placement p = placements.get(i);
            BlockPos stampOrigin = carriageOrigin.offset(p.originOffset());
            // Silent pre-erase. Sparse parts templates ("open" doorway with
            // only a frame block) need the placement region cleared first
            // because vanilla StructureTemplate captures with toIgnore=AIR,
            // so placeInWorld below skips air cells in the palette — without
            // a pre-erase, the overlay can't ever clear an existing block
            // sitting in a cell the template wants to leave open.
            //
            // Uses SilentBlockOps.setBlockSilentNoCascade so any 2-tall
            // paired-half block already in the region (door / tall flower
            // / bed) doesn't drop via the shape-update cascade — the
            // previous flag-3 erase loop was the source of the visible
            // "door places, then breaks and drops a frame" symptom.
            //
            // This pre-erase runs for both call paths:
            //   * train assembly (after stampBase, where the base filter
            //     usually leaves these cells already air — no-op here)
            //   * in-carriage part swap via PartPositionMenuController,
            //     where no base stamp runs first and the previous variant's
            //     blocks are still in the cells.
            eraseRegion(level, stampOrigin, placementSize);
            StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setMirror(p.mirror());
            template.placeInWorld(level, stampOrigin, stampOrigin, settings, level.getRandom(), 3);
            applyVariantBlocksForPlacement(level, carriageOrigin, kind, name, dims, seed, carriageIndex, p);
        }
    }

    /**
     * Air out every placement region of {@code kind} <b>without</b> stamping any template — the
     * counterpart to {@link #placeAtPerPlacement} for the stage-filtered editor preview. When a
     * carriage is previewed for a selected {@link games.brennan.dungeontrain.editor.EditorStageSelection
     * stage}, a swappable slot with no part linked to that stage is cleared to air here, leaving the
     * carriage's base shell outside the swappable bands intact (the "Keep shell, swap parts" preview).
     *
     * <p>Uses the same per-cell {@link SilentBlockOps#setBlockSilentNoCascade silent, no-cascade} clear
     * that {@link #placeAtPerPlacement} runs as its pre-erase, so any 2-tall paired-half block already
     * in the region (door / bed / tall flower) doesn't drop a dangling half via the shape-update
     * cascade.</p>
     */
    public static void eraseKind(ServerLevel level, BlockPos carriageOrigin,
                                 CarriagePartKind kind, CarriageDims dims) {
        List<CarriagePartKind.Placement> placements = kind.placements(dims);
        Vec3i placementSize = kind.dims(dims);
        for (CarriagePartKind.Placement p : placements) {
            eraseRegion(level, carriageOrigin.offset(p.originOffset()), placementSize);
        }
    }

    /** Clear an {@code placementSize}-sized box rooted at {@code stampOrigin} to air, silently and without cascade. */
    private static void eraseRegion(ServerLevel level, BlockPos stampOrigin, Vec3i placementSize) {
        for (int dx = 0; dx < placementSize.getX(); dx++) {
            for (int dy = 0; dy < placementSize.getY(); dy++) {
                for (int dz = 0; dz < placementSize.getZ(); dz++) {
                    SilentBlockOps.setBlockSilentNoCascade(
                        level, stampOrigin.offset(dx, dy, dz), AIR, null);
                }
            }
        }
    }

    /**
     * Overlay any {@link CarriagePartVariantBlocks} entries for {@code name}
     * onto the stamped part. Each placement stamps again with its mirror
     * applied to both the {@code localPos} (for world coordinates) and the
     * chosen {@link BlockState} (for facing / axis properties), so the result
     * tracks the part template's own mirror-handling.
     *
     * <p>The {@code CarriageVariantBlocks} command-block sentinel is honoured
     * — a picked sentinel resolves to {@link Blocks#AIR} so authors can
     * randomise "block or empty" within a part.</p>
     */
    private static void applyVariantBlocksForPlacement(ServerLevel level, BlockPos carriageOrigin,
                                                       CarriagePartKind kind, String name, CarriageDims dims,
                                                       long seed, int carriageIndex,
                                                       CarriagePartKind.Placement p) {
        Vec3i partSize = kind.dims(dims);
        CarriagePartVariantBlocks sidecar = CarriagePartVariantBlocks.loadFor(kind, name, partSize);
        if (sidecar.isEmpty()) return;

        BlockPos stampOrigin = carriageOrigin.offset(p.originOffset());
        for (var entry : sidecar.entries()) {
            VariantState picked = sidecar.resolve(entry.localPos(), seed, carriageIndex);
            if (picked == null) continue;
            BlockPos world = transformLocal(stampOrigin, entry.localPos(), p.mirror(), partSize);
            if (CarriageVariantBlocks.isEmptyPlaceholder(picked.state())) {
                SilentBlockOps.setBlockSilent(level, world, Blocks.AIR.defaultBlockState());
            } else {
                // Apply per-entry rotation BEFORE mirror so the placement
                // mirror still flips the result correctly (mirror operates
                // on the final FACING/AXIS, regardless of how it was set).
                BlockState rotated = games.brennan.dungeontrain.editor.RotationApplier.apply(
                    picked.state(), picked.rotation(), picked.half(),
                    entry.localPos(), seed, carriageIndex,
                    sidecar.lockIdAt(entry.localPos()));
                // Mirror flips state properties (FACING/AXIS); BE NBT
                // passes through unchanged — vanilla StructureTemplate
                // does the same. Asymmetric BE content (sign text,
                // banner patterns) reads forward on both sides.
                BlockState toPlace = rotated.mirror(p.mirror());
                games.brennan.dungeontrain.editor.ContainerContentsPlacement.place(
                    level, world, toPlace, picked.blockEntityNbt(),
                    "part:" + kind.id() + ":" + name, entry.localPos(), seed, carriageIndex,
                    picked.linkedLootPrefabId());
            }
        }
    }

    /**
     * Mob-variant entity-pass for {@code kind}. For each placement (left/right
     * wall etc.), re-rolls the matching part variant sidecar with the same
     * {@code (seed, carriageIndex)} the block pass used and spawns a mob at
     * every cell whose pick has {@code entityId != null}. The block pass
     * already AIRed those cells through the empty-placeholder branch.
     *
     * <p>World position uses the same mirror-aware {@link #transformLocal}
     * as the block pass so mob spawn positions track the placement's mirror
     * orientation (left vs right wall etc.).</p>
     */
    public static void spawnPartVariantMobsAt(ServerLevel level, BlockPos carriageOrigin,
                                               CarriagePartKind kind, List<String> names, CarriageDims dims,
                                               long seed, int carriageIndex) {
        List<CarriagePartKind.Placement> placements = kind.placements(dims);
        Vec3i partSize = kind.dims(dims);
        int spawnedTotal = 0;
        for (int i = 0; i < placements.size(); i++) {
            String name = i < names.size() ? names.get(i) : null;
            if (name == null || name.isBlank() || CarriagePartKind.NONE.equals(name)) continue;
            CarriagePartVariantBlocks sidecar = CarriagePartVariantBlocks.loadFor(kind, name, partSize);
            if (sidecar.isEmpty()) continue;
            CarriagePartKind.Placement p = placements.get(i);
            BlockPos stampOrigin = carriageOrigin.offset(p.originOffset());
            for (var entry : sidecar.entries()) {
                VariantState picked = sidecar.resolve(entry.localPos(), seed, carriageIndex);
                if (picked == null || !picked.isMob()) continue;
                BlockPos world = transformLocal(stampOrigin, entry.localPos(), p.mirror(), partSize);
                if (CarriageContentsPlacer.spawnVariantMob(level, world, picked, carriageIndex)) {
                    spawnedTotal++;
                }
            }
        }
        if (spawnedTotal > 0) {
            // Single-line summary keeps log volume bounded — per-spawn detail
            // already lives in spawnVariantMob's WARN-on-failure logging.
            org.slf4j.LoggerFactory.getLogger(CarriagePartPlacer.class)
                .info("[DungeonTrain] Mob-variant: spawned {} parts mobs for kind={} pIdx={}",
                    spawnedTotal, kind.id(), carriageIndex);
        }
    }

    /**
     * Apply the placement's mirror to a local part position, then offset by
     * {@code stampOrigin} to land the world position. Matches vanilla
     * {@code StructureTemplate.calculateRelativePosition} with
     * {@code pivot == stampOrigin} and {@code Rotation.NONE}.
     */
    private static BlockPos transformLocal(BlockPos stampOrigin, BlockPos local, Mirror mirror, Vec3i size) {
        int lx = local.getX();
        int ly = local.getY();
        int lz = local.getZ();
        return switch (mirror) {
            case LEFT_RIGHT -> new BlockPos(stampOrigin.getX() + lx,
                                             stampOrigin.getY() + ly,
                                             stampOrigin.getZ() - lz);
            case FRONT_BACK -> new BlockPos(stampOrigin.getX() - lx,
                                             stampOrigin.getY() + ly,
                                             stampOrigin.getZ() + lz);
            default -> new BlockPos(stampOrigin.getX() + lx,
                                     stampOrigin.getY() + ly,
                                     stampOrigin.getZ() + lz);
        };
    }

    /**
     * Erase a part-kind-sized footprint at {@code plotOrigin}. Used by the
     * part editor to clear its plot before restamping a template on enter.
     *
     * <p>Uses {@link SilentBlockOps#setBlockSilentNoCascade} so the
     * shape-update cascade is short-circuited — without that, clearing the
     * lower half of a door (or any 2-tall paired-half block: tall flowers,
     * sunflowers, beds) would trigger the upper half's
     * {@code DoorBlock.updateShape → Block.updateOrDestroy → destroyBlock(true)}
     * chain and drop the block as an item with break particles + sound.
     * The next {@code stampCurrent} call still uses flag 3 so neighbour
     * shape updates propagate correctly after the restamp.</p>
     */
    public static void eraseAt(ServerLevel level, BlockPos plotOrigin, CarriagePartKind kind, CarriageDims dims) {
        Vec3i size = kind.dims(dims);
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    SilentBlockOps.setBlockSilentNoCascade(
                        level, plotOrigin.offset(dx, dy, dz), AIR, null);
                }
            }
        }
    }
}
