package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: bring one of my relay builds down into this install's template library.
 *
 * <p>Carries only the relay id. Who is allowed to download it is not on the wire and never could be:
 * the relay hands a build to its owner, and the owner is whoever this connection belongs to, which
 * the server already knows. A client naming somebody else's build gets a refusal from the relay, not
 * their work.</p>
 *
 * <p>The answer comes back as a {@link BuilderProfileDownloadResultPacket} once the relay has
 * answered, rather than from this handler — the fetch is a network call and the server thread does
 * not wait on one.</p>
 */
public record BuilderProfileDownloadPacket(int relayId) implements CustomPacketPayload {

    public static final Type<BuilderProfileDownloadPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_profile_download"));

    public static final StreamCodec<FriendlyByteBuf, BuilderProfileDownloadPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeVarInt(packet.relayId),
            buf -> new BuilderProfileDownloadPacket(buf.readVarInt())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderProfileDownloadPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (player.getServer() == null) return;
            ServerLevel level = player.getServer().overworld();
            BuilderRelayDownload.download(player, level, packet.relayId)
                    .thenAccept(result -> player.getServer().execute(() -> {
                        if (player.hasDisconnected()) return;
                        DungeonTrainNet.sendTo(player, BuilderProfileDownloadResultPacket.of(result));
                    }));
        });
    }
}
