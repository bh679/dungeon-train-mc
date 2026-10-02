package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.ShellWinsStore;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The cells of a carriage's interior the carriage itself already fills, as a mask its contents must
 * stamp around — what "carriage blocks win" ({@link ShellWinsStore}) switches on.
 *
 * <p>Read off the world rather than the template: by the time the contents go in, the shell's
 * template, its rolled block variants and its parts are all standing, so whatever is there is what
 * the carriage put there. Contents still fill every cell the carriage left empty.</p>
 */
public final class ShellWinsMask {

    /** Whether a cell, given in interior-local coordinates, is already filled. */
    @FunctionalInterface
    public interface Filled {
        boolean at(int x, int y, int z);
    }

    private ShellWinsMask() {}

    /**
     * The mask for {@code shell}'s contents at {@code carriageOrigin}: {@link PortalCorridorMask#NONE}
     * unless the template has the setting on.
     */
    public static PortalCorridorMask forShell(ServerLevel level, BlockPos carriageOrigin,
                                              CarriageVariant shell, CarriageContents contents, CarriageDims dims) {
        if (shell == null || contents == null || !ShellWinsStore.wins(shell.id())) return PortalCorridorMask.NONE;
        Vec3i size = CarriageContentsPlacer.interiorSizeFor(contents, dims);
        BlockPos origin = CarriageContentsPlacer.interiorOrigin(carriageOrigin);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        List<BoundingBox> runs = runs(size,
            (x, y, z) -> !level.getBlockState(cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z)).isAir(),
            origin);
        return runs.isEmpty() ? PortalCorridorMask.NONE : new PortalCorridorMask(runs);
    }

    /**
     * Every filled cell of a {@code size} box, as one box per unbroken run along X, offset by
     * {@code origin}. Runs rather than cells because a mask is tested once per box per write.
     */
    static List<BoundingBox> runs(Vec3i size, Filled filled, BlockPos origin) {
        List<BoundingBox> out = new ArrayList<>();
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                int start = -1;
                for (int x = 0; x <= size.getX(); x++) {
                    boolean on = x < size.getX() && filled.at(x, y, z);
                    if (on && start < 0) start = x;
                    if (!on && start >= 0) {
                        out.add(new BoundingBox(
                            origin.getX() + start, origin.getY() + y, origin.getZ() + z,
                            origin.getX() + x - 1, origin.getY() + y, origin.getZ() + z));
                        start = -1;
                    }
                }
            }
        }
        return out;
    }
}
