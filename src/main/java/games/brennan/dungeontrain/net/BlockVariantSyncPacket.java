package games.brennan.dungeontrain.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → client: the block-variant world-space menu's current state for
 * a single cell.
 *
 * <p>An "active" packet ({@code localPos != null}) tells the client to
 * render the menu anchored at {@code anchorPos} with axes {@code anchorRight}
 * / {@code anchorUp}, listing {@code entries} for the carriage variant
 * {@code variantId}'s cell at {@code localPos}.</p>
 *
 * <p>An "inactive" packet ({@code localPos == null}, signalled by writing
 * a sentinel byte) tells the client to close its menu — the player has
 * tapped Z while looking away from any flagged block, or explicitly
 * dismissed the panel.</p>
 *
 * <p>Search registry: the full list of block IDs is NOT transmitted; the
 * client builds its own search list from {@code BuiltInRegistries.BLOCK}
 * since vanilla guarantees identical registries on both sides.</p>
 */
public record BlockVariantSyncPacket(
    String variantId,
    @Nullable BlockPos localPos,
    List<Entry> entries,
    Vec3 anchorPos,
    Vec3 anchorRight,
    Vec3 anchorUp
) {

    /** Single per-cell candidate, mirrored on the wire. */
    public record Entry(String stateString, @Nullable String beNbt, int weight, boolean locked) {}

    public static BlockVariantSyncPacket empty() {
        return new BlockVariantSyncPacket(
            "", null, Collections.emptyList(),
            Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(variantId);
        if (localPos == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeVarInt(localPos.getX());
        buf.writeVarInt(localPos.getY());
        buf.writeVarInt(localPos.getZ());
        writeVec3(buf, anchorPos);
        writeVec3(buf, anchorRight);
        writeVec3(buf, anchorUp);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.stateString(), 256);
            boolean hasNbt = e.beNbt() != null && !e.beNbt().isEmpty();
            buf.writeBoolean(hasNbt);
            if (hasNbt) buf.writeUtf(e.beNbt(), 32767);
            buf.writeVarInt(e.weight());
            buf.writeBoolean(e.locked());
        }
    }

    public static BlockVariantSyncPacket decode(FriendlyByteBuf buf) {
        String id = buf.readUtf(64);
        boolean active = buf.readBoolean();
        if (!active) {
            return new BlockVariantSyncPacket(
                id, null, Collections.emptyList(),
                Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);
        }
        BlockPos local = new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        Vec3 anchor = readVec3(buf);
        Vec3 right = readVec3(buf);
        Vec3 up = readVec3(buf);
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String stateStr = buf.readUtf(256);
            boolean hasNbt = buf.readBoolean();
            String nbt = hasNbt ? buf.readUtf(32767) : null;
            int weight = buf.readVarInt();
            boolean locked = buf.readBoolean();
            entries.add(new Entry(stateStr, nbt, weight, locked));
        }
        return new BlockVariantSyncPacket(id, local, entries, anchor, right, up);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT, () -> () ->
                games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenu.applySync(this)));
        ctx.setPacketHandled(true);
    }

    private static void writeVec3(FriendlyByteBuf buf, Vec3 v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    private static Vec3 readVec3(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
