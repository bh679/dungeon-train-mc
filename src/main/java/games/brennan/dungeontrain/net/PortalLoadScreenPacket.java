package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.PortalLoadScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the player is about to be teleported along the ride by a Nether portal
 * ({@code event/NetherPortalBandJump}). Vanilla shows its "Loading terrain" screen only on a
 * <em>dimension</em> change, so for this same-dimension trip the client puts up the same
 * Nether-portal loading screen itself until the terrain under the player has rendered
 * ({@link PortalLoadScreen}). Sent just before the teleport, so it precedes the position packet.
 */
public record PortalLoadScreenPacket() implements CustomPacketPayload {

    public static final Type<PortalLoadScreenPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_load_screen"));

    public static final StreamCodec<FriendlyByteBuf, PortalLoadScreenPacket> STREAM_CODEC =
        StreamCodec.unit(new PortalLoadScreenPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound handler — only ever runs on the physical client (mirrors {@code SpawnDeckHoldPacket.handle}). */
    public static void handle(PortalLoadScreenPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(PortalLoadScreen::show);
    }
}
