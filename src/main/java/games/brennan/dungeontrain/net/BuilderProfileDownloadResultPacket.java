package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: what became of a download, and what it left behind.
 *
 * <p>An outcome rather than a message, because the screen does two different things with it: it says
 * something to the player, and — when a build actually landed — it can offer to open the thing that
 * was just written. A pre-rendered chat line could do the first and never the second.</p>
 *
 * <p>{@code kind}/{@code id}/{@code subKind} name what was installed and are empty on every outcome
 * where nothing was.</p>
 */
public record BuilderProfileDownloadResultPacket(BuilderRelayDownload.Outcome outcome, String kindId,
                                                 String id, String subKind) implements CustomPacketPayload {

    public static final Type<BuilderProfileDownloadResultPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_profile_download_result"));

    public static final StreamCodec<FriendlyByteBuf, BuilderProfileDownloadResultPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeEnum(packet.outcome);
                buf.writeUtf(packet.kindId, 16);
                buf.writeUtf(packet.id, 64);
                buf.writeUtf(packet.subKind, 32);
            },
            buf -> new BuilderProfileDownloadResultPacket(
                    buf.readEnum(BuilderRelayDownload.Outcome.class),
                    buf.readUtf(16), buf.readUtf(64), buf.readUtf(32))
        );

    /** The wire form of one {@link BuilderRelayDownload.Result}. */
    public static BuilderProfileDownloadResultPacket of(BuilderRelayDownload.Result result) {
        BuilderPhotoPaths.Kind kind = result.kind();
        return new BuilderProfileDownloadResultPacket(result.outcome(),
                kind == null ? "" : kind.id(), result.id(), result.subKind());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderProfileDownloadResultPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BuilderProfileState.downloadResult(packet));
    }
}
