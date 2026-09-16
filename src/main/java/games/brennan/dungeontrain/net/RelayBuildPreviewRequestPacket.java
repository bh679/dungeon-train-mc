package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayPreview;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
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
public record RelayBuildPreviewRequestPacket(int relayId, String ownerUuid, boolean live, int seq)
        implements CustomPacketPayload {

    /** The build as it is now — a tile's ask. */
    public RelayBuildPreviewRequestPacket(int relayId, String ownerUuid, boolean live) {
        this(relayId, ownerUuid, live, 0);
    }

    public static final Type<RelayBuildPreviewRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "relay_build_preview_request"));

    public static final StreamCodec<FriendlyByteBuf, RelayBuildPreviewRequestPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeUtf(packet.ownerUuid, 48);
                buf.writeBoolean(packet.live);
                buf.writeVarInt(packet.seq);
            },
            buf -> new RelayBuildPreviewRequestPacket(buf.readVarInt(), buf.readUtf(48), buf.readBoolean(),
                buf.readVarInt())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RelayBuildPreviewRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || player.getServer() == null) return;
            ServerLevel level = player.getServer().overworld();
            // Blank stays blank, so the preview resolves it the way a download does — through the
            // world's own record of the row, else the caller (see BuilderRelayOwners).
            String owner = packet.ownerUuid == null || packet.ownerUuid.isBlank() ? ""
                : BuilderProfileRequestPacket.viewedOwner(player, packet.ownerUuid);
            boolean live = BuilderProfileRequestPacket.liveRequested(packet.live);
            if (packet.seq != 0) {
                // A saved version: seq > 0 is that version, -1 is the newest one along with every
                // version the relay holds — what the previewer's arrows page through.
                BuilderRelayPreview.fetchVersion(player, level, packet.relayId, owner, live, Math.max(0, packet.seq))
                    .thenAccept(v -> player.getServer().execute(() -> {
                        if (player.hasDisconnected()) return;
                        java.util.List<SharedCarriageClient.Version> versions = v.versions();
                        int shown = packet.seq > 0 ? packet.seq
                            : (versions.isEmpty() ? 0 : versions.get(versions.size() - 1).seq());
                        DungeonTrainNet.sendTo(player, new RelayBuildPreviewPacket(packet.relayId, shown,
                            versions.stream().mapToInt(SharedCarriageClient.Version::seq).toArray(),
                            versions.stream().mapToInt(SharedCarriageClient.Version::parentSeq).toArray(),
                            versions.stream().map(ver -> ver.name() == null ? "" : ver.name()).toArray(String[]::new),
                            v.attempt().bytes() != null, v.attempt().retryable(),
                            v.attempt().bytes() == null ? new byte[0] : v.attempt().bytes()));
                    }));
                return;
            }
            BuilderRelayPreview.fetch(player, level, packet.relayId, owner, live)
                .thenAccept(attempt -> player.getServer().execute(() -> {
                    if (player.hasDisconnected()) return;
                    // An answer always goes back, "nothing to draw" included: the client is holding
                    // a slot open for this build and a silence would hold it forever.
                    DungeonTrainNet.sendTo(player, attempt.bytes() == null
                        ? RelayBuildPreviewPacket.none(packet.relayId, attempt.retryable())
                        : new RelayBuildPreviewPacket(packet.relayId, true, false, attempt.bytes()));
                }));
        });
    }
}
