package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: which builders match this name?
 *
 * <p>The name→uuid step behind "load another player's builds", and a DEV-BUILD affordance throughout:
 * a release server answers with nothing at all rather than an error, because on a release build
 * nothing asks. The relay refuses the same search on the live cap, so this gate and the relay's agree
 * without depending on each other.</p>
 *
 * <p>Gated on {@link BuilderRelayUpload#canUpload} as well, for the same reason
 * {@link BuilderProfileRequestPacket} is: a player whose builds may not go to the relay has no
 * business reading it either.</p>
 */
public record BuilderCreatorSearchPacket(String query) implements CustomPacketPayload {

    public static final Type<BuilderCreatorSearchPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_creator_search"));

    /** Long enough for a full uuid, short enough that a hostile packet allocates nothing of note. */
    static final int MAX_QUERY = 48;

    /** What one search answers with — a picker, not a directory listing. */
    private static final int MAX_RESULTS = 25;

    public static final StreamCodec<FriendlyByteBuf, BuilderCreatorSearchPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeUtf(packet.query, MAX_QUERY),
            buf -> new BuilderCreatorSearchPacket(buf.readUtf(MAX_QUERY))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderCreatorSearchPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            String query = packet.query == null ? "" : packet.query.trim();
            if (query.isEmpty() || !DungeonTrain.isDevBuild() || !BuilderRelayUpload.canUpload(player)) {
                DungeonTrainNet.sendTo(player, BuilderCreatorResultsPacket.empty(query));
                return;
            }
            SharedCarriageClient.searchCreators(query, MAX_RESULTS).thenAccept(creators -> {
                if (player.getServer() == null) return;
                player.getServer().execute(() -> {
                    if (player.hasDisconnected()) return;
                    DungeonTrainNet.sendTo(player, BuilderCreatorResultsPacket.of(query, creators));
                });
            });
        });
    }
}
