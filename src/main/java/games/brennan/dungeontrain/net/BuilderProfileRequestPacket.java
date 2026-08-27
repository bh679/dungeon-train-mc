package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: what have I built?
 *
 * <p>Empty payload — the answer is about whoever asked, and the server knows who that is. The reply is
 * a {@link BuilderProfilePacket}, sent once the relay answers rather than in this handler: the fetch is
 * a network call and the server thread does not wait on one.</p>
 */
public record BuilderProfileRequestPacket() implements CustomPacketPayload {

    public static final Type<BuilderProfileRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_profile_request"));

    public static final StreamCodec<FriendlyByteBuf, BuilderProfileRequestPacket> STREAM_CODEC =
        StreamCodec.unit(new BuilderProfileRequestPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderProfileRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // The same gate the upload uses, but reported one limb at a time: with profiles off, or
            // without this player's network consent, nothing of theirs is on the relay and nothing
            // should be asked about them. Which limb closed decides what the screen can tell them to
            // do about it, so the two are never collapsed into one answer.
            BuilderProfilePacket.Status blocked = blockedReason(player);
            if (blocked != null) {
                DungeonTrainNet.sendTo(player, BuilderProfilePacket.of(blocked));
                return;
            }
            SharedCarriageClient.listMine(player.getUUID().toString()).thenAccept(rows -> {
                if (player.getServer() == null) return;
                player.getServer().execute(() -> {
                    if (player.hasDisconnected()) return;
                    DungeonTrainNet.sendTo(player, rows == null
                            ? BuilderProfilePacket.of(BuilderProfilePacket.Status.UNAVAILABLE)
                            : BuilderProfilePacket.of(rows));
                });
            });
        });
    }

    /**
     * Why this player may not upload, or {@code null} when they may.
     *
     * <p>{@link BuilderRelayUpload#canUpload} stays the authority on the yes/no — this method only
     * decomposes a {@code no} into something the screen can say. Asking it first rather than
     * re-deriving its condition matters: should that gate ever grow a third limb, the worst this can
     * do is name the wrong reason for a refusal, never wave through an upload it would have blocked.</p>
     *
     * <p>Order within the refusal: the server's own switch is named first, because when profiles are
     * off the player's consent is moot and pointing them at their own setting would be a dead end.</p>
     */
    private static BuilderProfilePacket.Status blockedReason(ServerPlayer player) {
        if (BuilderRelayUpload.canUpload(player)) return null;
        if (!DungeonTrainConfig.isBuilderProfileEnabled()) {
            return BuilderProfilePacket.Status.DISABLED;
        }
        // Absent from the mirror means the login sync is still in flight, not that they said no.
        return NetworkConsentMirror.isKnown(player.getUUID())
                ? BuilderProfilePacket.Status.NO_CONSENT
                : BuilderProfilePacket.Status.CONSENT_PENDING;
    }
}
