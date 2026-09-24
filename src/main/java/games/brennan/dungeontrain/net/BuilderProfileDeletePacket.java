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
 * Client → server: remove one of my builds from the relay for good — the My Builds trash button.
 *
 * <p>The same shape as {@link BuilderProfileActionPacket}, for the same reason: only the relay id
 * travels. The owner secret that authorises the delete lives in the world's saved data (or is
 * recovered from the relay on the owner's uuid), so a client naming a build that is not this
 * player's gets told so rather than deleting it. The client has already asked "are you sure" —
 * this packet is the answer, not the question.</p>
 */
public record BuilderProfileDeletePacket(int relayId) implements CustomPacketPayload {

    public static final Type<BuilderProfileDeletePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_profile_delete"));

    public static final StreamCodec<FriendlyByteBuf, BuilderProfileDeletePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeVarInt(packet.relayId),
            buf -> new BuilderProfileDeletePacket(buf.readVarInt())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderProfileDeletePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (player.getServer() == null || !BuilderRelayUpload.canUpload(player)) return;
            ServerLevel level = player.getServer().overworld();
            BuilderRelayUpload.deleteBuild(player, level, packet.relayId)
                    .thenAccept(message -> player.getServer().execute(() -> {
                        if (player.hasDisconnected()) return;
                        player.sendSystemMessage(message);
                        // Re-read the profile so the screen shows what actually happened — the row
                        // gone, or still there because the relay refused (a build in use elsewhere).
                        BuilderProfileRequestPacket.handle(new BuilderProfileRequestPacket(), ctx);
                    }));
        });
    }
}
