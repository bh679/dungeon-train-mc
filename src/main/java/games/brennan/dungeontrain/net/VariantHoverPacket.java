package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.client.VariantHoverHudOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → client: the player is currently hovering a variant-flagged block
 * and these are the candidate block ids that can spawn at that position.
 * An empty list means "not hovering anything variant-flagged" — clears the
 * client-side HUD.
 *
 * <p>Sent only when the hover state changes (not per-tick) to keep the pipe
 * quiet. See {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer}.</p>
 */
public record VariantHoverPacket(List<ResourceLocation> blockIds) {

    public static VariantHoverPacket empty() {
        return new VariantHoverPacket(Collections.emptyList());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(blockIds.size());
        for (ResourceLocation id : blockIds) {
            buf.writeResourceLocation(id);
        }
    }

    public static VariantHoverPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<ResourceLocation> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(buf.readResourceLocation());
        }
        return new VariantHoverPacket(ids);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT, () -> () -> VariantHoverHudOverlay.setHover(blockIds)));
        ctx.setPacketHandled(true);
    }
}
