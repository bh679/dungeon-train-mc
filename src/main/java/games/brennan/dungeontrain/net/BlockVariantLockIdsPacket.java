package games.brennan.dungeontrain.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → client snapshot of every locked cell ({@code lockId > 0}) in the
 * editor plot the player is currently in. Drives the all-faces lock-id
 * label overlay, which is independent of the per-cell block-variant menu.
 *
 * <p>An "active" packet ({@code !empty()}) carries the plot's
 * {@code plotKey} (carriage / contents / part / track), the world-space
 * {@code plotOriginWorldPos} for {@code localPos → worldPos} translation
 * on the client, and a list of {@link Entry}. An "empty" packet
 * ({@code plotKey == ""}, signalled by an empty entries list and a
 * sentinel byte) clears the client cache when the player leaves the plot.</p>
 *
 * <p>Sent by {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer}
 * with a per-player dedup hash so a steady-state hover over a plot generates
 * zero network traffic.</p>
 */
public record BlockVariantLockIdsPacket(
    String plotKey,
    BlockPos plotOriginWorldPos,
    List<Entry> entries
) {

    /** A single locked cell — local position plus the lock-id digit to render. */
    public record Entry(BlockPos localPos, int lockId) {}

    public static BlockVariantLockIdsPacket empty() {
        return new BlockVariantLockIdsPacket("", BlockPos.ZERO, Collections.emptyList());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(plotKey, 128);
        if (entries.isEmpty()) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeBlockPos(plotOriginWorldPos);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeVarInt(e.localPos().getX());
            buf.writeVarInt(e.localPos().getY());
            buf.writeVarInt(e.localPos().getZ());
            buf.writeVarInt(e.lockId());
        }
    }

    public static BlockVariantLockIdsPacket decode(FriendlyByteBuf buf) {
        String key = buf.readUtf(128);
        boolean active = buf.readBoolean();
        if (!active) return new BlockVariantLockIdsPacket(key, BlockPos.ZERO, Collections.emptyList());
        BlockPos origin = buf.readBlockPos();
        int n = buf.readVarInt();
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BlockPos local = new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
            int lockId = buf.readVarInt();
            out.add(new Entry(local, lockId));
        }
        return new BlockVariantLockIdsPacket(key, origin, out);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT, () -> () ->
                games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantLockIdRenderer.applySnapshot(this)));
        ctx.setPacketHandled(true);
    }
}
