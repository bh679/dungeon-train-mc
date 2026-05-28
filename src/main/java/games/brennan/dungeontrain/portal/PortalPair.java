package games.brennan.dungeontrain.portal;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A bidirectional link between two {@link PortalEndpoint}s in (potentially)
 * different dimensions. The {@code id} is generated at pair creation time
 * and stays stable across save/load cycles.
 *
 * <p>The pair is symmetric — there is no "source" or "destination" baked
 * into the data structure. {@link PortalRegistry#findPartner} handles the
 * direction lookup based on which endpoint the caller already knows.</p>
 */
public record PortalPair(UUID id, PortalEndpoint a, PortalEndpoint b) {

    private static final String TAG_ID = "Id";
    private static final String TAG_A = "A";
    private static final String TAG_B = "B";

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.put(TAG_ID, NbtUtils.createUUID(id));
        tag.put(TAG_A, a.toNbt());
        tag.put(TAG_B, b.toNbt());
        return tag;
    }

    /** Returns {@code null} if any endpoint failed to deserialise — caller should drop the pair. */
    @Nullable
    public static PortalPair fromNbt(CompoundTag tag) {
        if (!tag.contains(TAG_ID) || !tag.contains(TAG_A) || !tag.contains(TAG_B)) {
            return null;
        }
        UUID id;
        try {
            id = NbtUtils.loadUUID(tag.get(TAG_ID));
        } catch (Exception e) {
            return null;
        }
        PortalEndpoint a = PortalEndpoint.fromNbt(tag.getCompound(TAG_A));
        PortalEndpoint b = PortalEndpoint.fromNbt(tag.getCompound(TAG_B));
        if (a == null || b == null) return null;
        return new PortalPair(id, a, b);
    }
}
