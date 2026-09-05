package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
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
 * <p>Carries the relay id and, on a second press, what to do about a name this install already uses
 * ({@code resolution}) and the name the player picked for it ({@code name}).</p>
 *
 * <p>{@code overwriteUnsaved} is the player's answer to the other second-press question: the
 * template this build lands on has unsaved editor edits, and installing replaces it. False on every
 * first press, so the question is always asked before anything is written.</p>
 *
 * <p>{@code ownerUuid} names whose build it is, and like
 * {@link BuilderProfileRequestPacket#ownerUuid} it is honoured on a dev build only — a release server
 * downloads the caller's own build instead, so nothing a client sends widens what it may fetch.
 * A foreign build installs as a local copy: the link back to its relay row is deliberately not
 * recorded, so a later save here can never overwrite the original.</p>
 *
 * <p>The answer comes back as a {@link BuilderProfileDownloadResultPacket} once the relay has
 * answered, rather than from this handler — the fetch is a network call and the server thread does
 * not wait on one.</p>
 */
public record BuilderProfileDownloadPacket(int relayId, BuilderRelayInstall.Resolution resolution,
                                           String name, String ownerUuid,
                                           boolean live, boolean overwriteUnsaved)
        implements CustomPacketPayload {

    /** The first press on one of my own builds: install it, unless the name is already in use here. */
    public BuilderProfileDownloadPacket(int relayId) {
        this(relayId, BuilderRelayInstall.Resolution.AS_IS, "", "", false, false);
    }

    /**
     * The first press on a build in the profile being viewed — which may be somebody else's, and may
     * be on the live relay. Both come from the screen that listed it, so a build is always fetched
     * from the pool it was shown in.
     */
    public BuilderProfileDownloadPacket(int relayId, String ownerUuid, boolean live) {
        this(relayId, BuilderRelayInstall.Resolution.AS_IS, "", ownerUuid, live, false);
    }

    public static final Type<BuilderProfileDownloadPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_profile_download"));

    public static final StreamCodec<FriendlyByteBuf, BuilderProfileDownloadPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeEnum(packet.resolution);
                buf.writeUtf(packet.name, 64);
                buf.writeUtf(packet.ownerUuid, 48);
                buf.writeBoolean(packet.live);
                buf.writeBoolean(packet.overwriteUnsaved);
            },
            buf -> new BuilderProfileDownloadPacket(buf.readVarInt(),
                    buf.readEnum(BuilderRelayInstall.Resolution.class), buf.readUtf(64), buf.readUtf(48),
                    buf.readBoolean(), buf.readBoolean())
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
            String owner = BuilderProfileRequestPacket.viewedOwner(player, packet.ownerUuid);
            boolean live = BuilderProfileRequestPacket.liveRequested(packet.live);
            BuilderRelayDownload.download(player, level, packet.relayId, packet.resolution, packet.name,
                            owner, live, packet.overwriteUnsaved)
                    .thenAccept(result -> player.getServer().execute(() -> {
                        if (player.hasDisconnected()) return;
                        DungeonTrainNet.sendTo(player, BuilderProfileDownloadResultPacket.of(result));
                    }));
        });
    }
}
