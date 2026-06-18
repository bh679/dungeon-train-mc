package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.CheatDetectionEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player answered the Free Play confirmation.
 *
 * <p>{@code confirmed=true} → commit Free Play and re-run the held action;
 * {@code false} → drop it (the action stayed canceled, the run is untouched).
 * The "Don't show again" preference is persisted client-side, so it isn't
 * carried here — see {@link games.brennan.dungeontrain.config.ClientDisplayConfig}.</p>
 */
public record FreePlayConfirmResponsePacket(boolean confirmed) implements CustomPacketPayload {

    public static final Type<FreePlayConfirmResponsePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "free_play_confirm_response"));

    public static final StreamCodec<FriendlyByteBuf, FreePlayConfirmResponsePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, pkt) -> buf.writeBoolean(pkt.confirmed()),
            buf -> new FreePlayConfirmResponsePacket(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FreePlayConfirmResponsePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            CheatDetectionEvents.onConfirmResponse(player, packet.confirmed());
        });
    }
}
