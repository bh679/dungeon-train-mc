package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.portal.ClientPortalSwap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: you have just been swapped between a portal corridor and its twin — the renderer needs a
 * fresh occlusion graph before it draws the next frame.
 *
 * <p><b>Empty payload.</b> The position change itself arrives as vanilla's own teleport packet
 * immediately before this one; all this adds is "that one was a portal swap, not ordinary movement",
 * which is the part the client cannot tell from the position alone. {@link ClientPortalSwap} explains
 * what the renderer does with it and why the swap flashed without it.</p>
 *
 * <p>Sent from both swap paths — the portal carriages on the train and the free-standing portals —
 * right after {@code connection.teleport}. Dropping one costs a flash, not a bug, which is why
 * nothing acknowledges it.</p>
 */
public record PortalSwapPacket() implements CustomPacketPayload {

    public static final Type<PortalSwapPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_swap"));

    public static final StreamCodec<FriendlyByteBuf, PortalSwapPacket> STREAM_CODEC =
        StreamCodec.unit(new PortalSwapPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalSwapPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(ClientPortalSwap::arm);
    }
}
