package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.snapshot.EchoSnapshotClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: capture a framed third-person screenshot of the remote echo with this entity id,
 * sent once at first eye-contact by {@code echo.EchoSnapshotRequests}. The client frames the snapshot
 * camera on that entity and uploads the PNG back via {@link EchoPhotoPacket} (→
 * {@code RemoteEchoEncounters.onPhoto}), where it rides the encounter story's Discord embed.
 *
 * <p>Best-effort: if the client can't find a clean angle (or the entity is gone) no photo returns and
 * the story posts text-only.</p>
 */
public record CaptureEchoPacket(int echoEntityId) implements CustomPacketPayload {

    public static final Type<CaptureEchoPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "capture_echo"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CaptureEchoPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CaptureEchoPacket::echoEntityId,
            CaptureEchoPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Client-bound handler — only ever runs on the physical client, so the direct reference to
     * {@link EchoSnapshotClient} (a client-package class) is safe (mirrors {@code SpawnDeckHoldPacket.handle}).
     */
    public static void handle(CaptureEchoPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EchoSnapshotClient.capture(packet.echoEntityId()));
    }
}
