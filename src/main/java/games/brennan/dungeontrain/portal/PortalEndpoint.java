package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * One side of a {@link PortalPair}: the position of a portal core block in
 * a specific dimension. Immutable. Used both as the canonical pair-endpoint
 * representation and as the key for the
 * {@link PortalRegistry#findPartner position → partner-endpoint} lookup.
 *
 * <p>Equality is value-based: two endpoints are equal iff they share both
 * dimension and block position — which is exactly the index semantics we
 * want.</p>
 */
public record PortalEndpoint(ResourceKey<Level> dim, BlockPos pos) {

    private static final String TAG_DIM = "Dim";
    private static final String TAG_POS = "Pos";

    /** Serialise to a fresh {@link CompoundTag}. */
    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_DIM, dim.location().toString());
        tag.putIntArray(TAG_POS, new int[] { pos.getX(), pos.getY(), pos.getZ() });
        return tag;
    }

    /**
     * Deserialise from an NBT tag previously produced by {@link #toNbt}.
     * Returns {@code null} on malformed input (unknown dim string, wrong
     * pos array length, etc.) so callers can drop the bad pair without
     * crashing world load.
     */
    @Nullable
    public static PortalEndpoint fromNbt(CompoundTag tag) {
        ResourceLocation rl = ResourceLocation.tryParse(tag.getString(TAG_DIM));
        if (rl == null) return null;
        int[] arr = tag.getIntArray(TAG_POS);
        if (arr.length != 3) return null;
        return new PortalEndpoint(
            ResourceKey.create(Registries.DIMENSION, rl),
            new BlockPos(arr[0], arr[1], arr[2])
        );
    }
}
