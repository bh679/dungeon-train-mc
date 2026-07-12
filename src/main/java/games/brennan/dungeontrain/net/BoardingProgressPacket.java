package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.client.VersionHudOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;

/**
 * Server → client: current global travelled-carriage-index counter and the
 * tier it maps to. Sent whenever the counter changes (after a boarded
 * player's movement registers a non-zero delta) and once on player login so
 * the HUD has a value to show before the first delta. The dev-build HUD
 * ({@code VersionHudOverlay}) renders this as the in-game "difficulty id"
 * read-out so you can sanity-check progression while testing.
 */
public record BoardingProgressPacket(int travelled, int tier) implements CustomPacketPayload {

    public static final Type<BoardingProgressPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "boarding_progress"));

    public static final StreamCodec<FriendlyByteBuf, BoardingProgressPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            BoardingProgressPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(travelled);
        buf.writeVarInt(tier);
    }

    public static BoardingProgressPacket decode(FriendlyByteBuf buf) {
        int travelled = buf.readVarInt();
        int tier = buf.readVarInt();
        return new BoardingProgressPacket(travelled, tier);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BoardingProgressPacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(() -> VersionHudOverlay.setBoardingProgress(packet.travelled, packet.tier));
    }
}
