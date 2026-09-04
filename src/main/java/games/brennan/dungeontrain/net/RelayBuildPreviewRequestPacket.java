package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayPreview;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the blocks of one relay build, to draw it rather than to install it.
 *
 * <p>Sent by the editor screen for each of a builder's uploads as its tile scrolls into view, so
 * the grid shows the builds instead of a wall of name plates. Nothing is written on either side:
 * the answer is a mesh that lives in a client cache until the screen closes, and loading a build
 * into the world is still the separate, deliberate {@link BuilderProfileDownloadPacket}.</p>
 *
 * <p>{@code ownerUuid} and {@code live} are honoured on a dev build only, exactly as they are for a
 * profile listing or a download — a release server previews the caller's own builds off its own
 * relay, so nothing a client sends widens what it may read.</p>
 */
public record RelayBuildPreviewRequestPacket(int relayId, String ownerUuid, boolean live)
        implements CustomPacketPayload {

    public static final Type<RelayBuildPreviewRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "relay_build_preview_request"));

    public static final StreamCodec<FriendlyByteBuf, RelayBuildPreviewRequestPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeUtf(packet.ownerUuid, 48);
                buf.writeBoolean(packet.live);
            },
            buf -> new RelayBuildPreviewRequestPacket(buf.readVarInt(), buf.readUtf(48), buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RelayBuildPreviewRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || player.getServer() == null) return;
            ServerLevel level = player.getServer().overworld();
            String owner = BuilderProfileRequestPacket.viewedOwner(player, packet.ownerUuid);
            boolean live = BuilderProfileRequestPacket.liveRequested(packet.live);
            BuilderRelayPreview.fetch(player, level, packet.relayId, owner, live)
                .thenAccept(tag -> player.getServer().execute(() -> {
                    if (player.hasDisconnected()) return;
                    // An answer always goes back, empty tag and all: the client is holding a slot
                    // open for this build and a silence would hold it forever.
                    DungeonTrainNet.sendTo(player, new RelayBuildPreviewPacket(packet.relayId,
                        tag != null, tag == null ? new CompoundTag() : tag));
                }));
        });
    }
}
