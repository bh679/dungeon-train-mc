package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.DifficultyChangeConfirmEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player answered the difficulty-change confirmation.
 *
 * <p>{@code confirmed=true} → apply the held difficulty change; {@code false} → drop it (the change
 * stayed canceled, the player keeps what they are carrying).</p>
 */
public record DifficultyConfirmResponsePacket(boolean confirmed) implements CustomPacketPayload {

    public static final Type<DifficultyConfirmResponsePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "difficulty_confirm_response"));

    public static final StreamCodec<FriendlyByteBuf, DifficultyConfirmResponsePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, pkt) -> buf.writeBoolean(pkt.confirmed()),
            buf -> new DifficultyConfirmResponsePacket(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DifficultyConfirmResponsePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            DifficultyChangeConfirmEvents.onConfirmResponse(player, packet.confirmed());
        });
    }
}
