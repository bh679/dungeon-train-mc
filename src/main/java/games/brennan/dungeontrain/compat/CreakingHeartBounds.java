package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * The room a creaking heart's Creaking may spawn in: the carriage interior it was stamped into
 * (recorded relative to the heart by {@link CreakingHeartStampGate}, so it holds in plot space on
 * a train), or the editor plot it sits in. A flood from the heart alone leaks out of a carriage's
 * windows, doors and gangways, onto the roof and into the next carriage.
 */
public final class CreakingHeartBounds {

    /** The block-entity key the stamped box is saved under (relative to the heart). */
    public static final String TAG = "dungeontrain:heart_bounds";

    private CreakingHeartBounds() {}

    /** The box {@code origin + size}, relative to {@code heart}. */
    public static BoundingBox relative(BlockPos heart, BlockPos origin, Vec3i size) {
        return new BoundingBox(
            origin.getX() - heart.getX(), origin.getY() - heart.getY(), origin.getZ() - heart.getZ(),
            origin.getX() + size.getX() - 1 - heart.getX(),
            origin.getY() + size.getY() - 1 - heart.getY(),
            origin.getZ() + size.getZ() - 1 - heart.getZ());
    }

    /** The absolute box for the heart at {@code heart}: its stamped interior, else its editor plot, else null. */
    public static @Nullable BoundingBox of(ServerLevel level, BlockPos heart, @Nullable BoundingBox stampedRelative) {
        if (stampedRelative != null) return stampedRelative.moved(heart.getX(), heart.getY(), heart.getZ());
        CarriageDims dims = DungeonTrainWorldData.get(level.getServer().overworld()).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAtPos(level, heart, dims);
        if (plot == null) return null;
        BlockPos o = plot.origin();
        Vec3i f = plot.footprint();
        return new BoundingBox(o.getX(), o.getY(), o.getZ(),
            o.getX() + f.getX() - 1, o.getY() + f.getY() - 1, o.getZ() + f.getZ() - 1);
    }

    public static void save(CompoundTag tag, @Nullable BoundingBox box) {
        if (box == null) return;
        tag.putIntArray(TAG, new int[] {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
    }

    public static @Nullable BoundingBox load(CompoundTag tag) {
        int[] a = tag.getIntArray(TAG);
        return a.length == 6 ? new BoundingBox(a[0], a[1], a[2], a[3], a[4], a[5]) : null;
    }
}
