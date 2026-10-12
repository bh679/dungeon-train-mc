package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.builder.relay.BuilderReviewEdits;
import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.discord.BuildReviewReporter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Arrays;
import java.util.Objects;

/**
 * Client → server: the developer's verdict on somebody's build — the editor's Accept / Feedback /
 * Decline buttons, with the comment typed for its author.
 *
 * <p>Carries the build's owner, name and kind so the server can refuse a self-review and word the
 * Discord announcement without a second relay round-trip, and the client's picture of the build
 * ({@code render}) for that announcement — kept only on an accept, as a submit's is kept only on a
 * publish. The reply is a chat line; the screen has already flipped the tile optimistically.</p>
 */
public record BuilderReviewPacket(int relayId, String ownerUuid, String ownerName, String buildName,
                                  String kind, String subKind, boolean live, String review, String comment,
                                  byte[] render, String version, String versionOp) implements CustomPacketPayload {

    private static final byte[] NO_RENDER = new byte[0];
    private static final int MAX_STRING = 64;

    public BuilderReviewPacket {
        ownerUuid = ownerUuid == null ? "" : ownerUuid;
        ownerName = ownerName == null ? "" : ownerName;
        buildName = buildName == null ? "" : buildName;
        kind = kind == null ? "" : kind;
        subKind = subKind == null ? "" : subKind;
        review = BuilderReviewState.of(review);
        comment = comment == null ? "" : comment;
        // The resubmit rule rides only with a resubmit; every other verdict has none.
        version = version == null || !BuilderReviewState.RESUBMIT.equals(review) ? "" : version.strip();
        versionOp = version.isEmpty() ? "" : BuilderReviewState.opOf(versionOp);
        // A picture rides only with a verdict the channel hears about — it is for the announcement.
        render = render == null || !BuildReviewReporter.announces(review) ? NO_RENDER : render;
    }

    public static final Type<BuilderReviewPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_review"));

    public static final StreamCodec<FriendlyByteBuf, BuilderReviewPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.relayId);
                buf.writeUtf(p.ownerUuid, 48);
                buf.writeUtf(p.ownerName, MAX_STRING);
                buf.writeUtf(p.buildName, MAX_STRING);
                buf.writeUtf(p.kind, MAX_STRING);
                buf.writeUtf(p.subKind, MAX_STRING);
                buf.writeBoolean(p.live);
                buf.writeUtf(p.review, MAX_STRING);
                buf.writeUtf(p.comment, BuilderReviewEdits.COMMENT_MAX);
                buf.writeByteArray(p.render);
                buf.writeUtf(p.version, MAX_STRING);
                buf.writeUtf(p.versionOp, MAX_STRING);
            },
            buf -> new BuilderReviewPacket(buf.readVarInt(), buf.readUtf(48), buf.readUtf(MAX_STRING),
                buf.readUtf(MAX_STRING), buf.readUtf(MAX_STRING), buf.readUtf(MAX_STRING), buf.readBoolean(),
                buf.readUtf(MAX_STRING), buf.readUtf(BuilderReviewEdits.COMMENT_MAX),
                buf.readByteArray(BuilderProfileActionPacket.RENDER_MAX), buf.readUtf(MAX_STRING), buf.readUtf(MAX_STRING))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderReviewPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || player.getServer() == null) return;
            if (!BuilderRelayUpload.canUpload(player)) return;
            boolean live = BuilderProfileRequestPacket.liveRequested(packet.live);
            BuilderReviewEdits.review(player, packet.relayId, packet.ownerUuid, live, packet.review, packet.comment,
                    packet.version, packet.versionOp)
                .thenAccept(outcome -> player.getServer().execute(() -> {
                    if (player.hasDisconnected()) return;
                    player.sendSystemMessage(outcome.message());
                    if (outcome.ok() && BuildReviewReporter.announces(packet.review)) {
                        BuildReviewReporter.postSafely(player, packet.relayId, packet.ownerName, packet.kind,
                                packet.subKind, packet.buildName, packet.comment, packet.render,
                                packet.review, packet.version, packet.versionOp);
                    }
                }));
        });
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BuilderReviewPacket p)) return false;
        return relayId == p.relayId && live == p.live && ownerUuid.equals(p.ownerUuid) && ownerName.equals(p.ownerName)
                && buildName.equals(p.buildName) && kind.equals(p.kind) && subKind.equals(p.subKind)
                && review.equals(p.review) && comment.equals(p.comment) && Arrays.equals(render, p.render)
                && version.equals(p.version) && versionOp.equals(p.versionOp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relayId, ownerUuid, ownerName, buildName, kind, subKind, live, review, comment,
                Arrays.hashCode(render), version, versionOp);
    }

    @Override
    public String toString() {
        return "BuilderReviewPacket[relayId=" + relayId + ", review=" + review + ", owner=" + ownerName
                + ", render=" + render.length + " bytes]";
    }
}
