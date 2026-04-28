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
 * Server → client snapshot of every variant-flagged cell (locked or unlocked)
 * in the editor plot the player is currently in. Drives the white wireframe
 * outline overlay so authors can see at a glance which blocks have variants.
 *
 * <p>An "active" packet ({@code !empty()}) carries the plot's {@code plotKey}
 * (carriage / contents / part / track), the world-space
 * {@code plotOriginWorldPos} for {@code localPos → worldPos} translation on
 * the client, and the list of flagged local positions. An "empty" packet
 * ({@code plotKey == ""}, signalled by a sentinel byte) clears the client
 * cache when the player leaves the plot or toggles the editor overlay off.</p>
 *
 * <p>Sent by {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer}
 * with a per-player dedup hash so a steady-state hover over a plot generates
 * zero network traffic.</p>
 */
public record BlockVariantOutlinePacket(
    String plotKey,
    BlockPos plotOriginWorldPos,
    List<BlockPos> positions
) {

    public static BlockVariantOutlinePacket empty() {
        return new BlockVariantOutlinePacket("", BlockPos.ZERO, Collections.emptyList());
    }

    public boolean isEmpty() {
        return positions.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(plotKey, 128);
        if (positions.isEmpty()) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeBlockPos(plotOriginWorldPos);
        buf.writeVarInt(positions.size());
        for (BlockPos p : positions) {
            buf.writeVarInt(p.getX());
            buf.writeVarInt(p.getY());
            buf.writeVarInt(p.getZ());
        }
    }

    public static BlockVariantOutlinePacket decode(FriendlyByteBuf buf) {
        String key = buf.readUtf(128);
        boolean active = buf.readBoolean();
        if (!active) return new BlockVariantOutlinePacket(key, BlockPos.ZERO, Collections.emptyList());
        BlockPos origin = buf.readBlockPos();
        int n = buf.readVarInt();
        List<BlockPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new BlockVariantOutlinePacket(key, origin, out);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT, () -> () ->
                games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantWireframeRenderer.applySnapshot(this)));
        ctx.setPacketHandled(true);
    }
}
