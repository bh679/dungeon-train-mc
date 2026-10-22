package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the owner's answer to an edit the developer sent back on one of their builds —
 * {@code accept} takes it into their build (relay and this install), otherwise they keep theirs.
 * Authorised by the owner's secret, exactly as the other My Builds actions are.
 */
public record BuilderSuggestionPacket(int relayId, boolean accept) implements CustomPacketPayload {

    public static final Type<BuilderSuggestionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_suggestion"));

    public static final StreamCodec<FriendlyByteBuf, BuilderSuggestionPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeBoolean(packet.accept);
            },
            buf -> new BuilderSuggestionPacket(buf.readVarInt(), buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderSuggestionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (player.getServer() == null || !BuilderRelayUpload.canUpload(player)) return;
            ServerLevel level = player.getServer().overworld();
            BuilderRelayUpload.answerSuggestion(player, level, packet.relayId, packet.accept)
                    .thenAccept(message -> player.getServer().execute(() -> {
                        if (player.hasDisconnected()) return;
                        player.sendSystemMessage(message);
                        BuilderProfileRequestPacket.handle(new BuilderProfileRequestPacket(), ctx);
                    }));
        });
    }
}
