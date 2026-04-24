package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriagePartVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

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
 * {@code CarriageTemplate.stampTemplate} follows the same rule.
 *
 * <p>The reserved name {@link CarriagePartKind#NONE} is a no-op: the stamp is
 * skipped entirely so FLATBED-style carriages can declare {@code walls=none}
 * etc. without needing an empty NBT on disk.
 */
public final class CarriagePartTemplate {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private CarriagePartTemplate() {}

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
        if (name == null || name.isBlank() || CarriagePartKind.NONE.equals(name)) return;
        Optional<StructureTemplate> stored = CarriagePartTemplateStore.get(level, kind, name, dims);
        if (stored.isEmpty()) return;
        StructureTemplate template = stored.get();
        for (CarriagePartKind.Placement p : kind.placements(dims)) {
            BlockPos stampOrigin = carriageOrigin.offset(p.originOffset());
            StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setMirror(p.mirror());
            template.placeInWorld(level, stampOrigin, stampOrigin, settings, level.getRandom(), 3);
        }
        applyVariantBlocks(level, carriageOrigin, kind, name, dims, seed, carriageIndex);
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
    private static void applyVariantBlocks(ServerLevel level, BlockPos carriageOrigin,
                                            CarriagePartKind kind, String name, CarriageDims dims,
                                            long seed, int carriageIndex) {
        Vec3i partSize = kind.dims(dims);
        CarriagePartVariantBlocks sidecar = CarriagePartVariantBlocks.loadFor(kind, name, partSize);
        if (sidecar.isEmpty()) return;

        for (CarriagePartKind.Placement p : kind.placements(dims)) {
            BlockPos stampOrigin = carriageOrigin.offset(p.originOffset());
            for (var entry : sidecar.entries()) {
                BlockState picked = sidecar.resolve(entry.localPos(), seed, carriageIndex);
                if (picked == null) continue;
                BlockState toPlace = CarriageVariantBlocks.isEmptyPlaceholder(picked)
                    ? Blocks.AIR.defaultBlockState()
                    : picked.mirror(p.mirror());
                BlockPos world = transformLocal(stampOrigin, entry.localPos(), p.mirror(), partSize);
                SilentBlockOps.setBlockSilent(level, world, toPlace);
            }
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
     */
    public static void eraseAt(ServerLevel level, BlockPos plotOrigin, CarriagePartKind kind, CarriageDims dims) {
        Vec3i size = kind.dims(dims);
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    level.setBlock(plotOrigin.offset(dx, dy, dz), AIR, 3);
                }
            }
        }
    }
}
