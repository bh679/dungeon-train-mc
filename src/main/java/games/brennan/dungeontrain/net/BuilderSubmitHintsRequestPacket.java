package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.builder.relay.BuilderSubmitHints;
import games.brennan.dungeontrain.editor.SubmitHints;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: I am about to submit this build — which questions should its note ask?
 *
 * <p>Asked between the Submit for Review press and the note screen, because the blocks that decide it
 * live with the server (its template files, or the relay), not with the screen that was pressed. An
 * answer always goes back, {@link SubmitHints.Hints#NONE} included: the client is waiting to open the
 * screen and a silence would only be covered by its timeout.</p>
 */
public record BuilderSubmitHintsRequestPacket(int relayId) implements CustomPacketPayload {

    public static final Type<BuilderSubmitHintsRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_submit_hints_request"));

    public static final StreamCodec<FriendlyByteBuf, BuilderSubmitHintsRequestPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeVarInt(packet.relayId),
            buf -> new BuilderSubmitHintsRequestPacket(buf.readVarInt())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderSubmitHintsRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || player.getServer() == null) return;
            if (!BuilderRelayUpload.canUpload(player)) {
                DungeonTrainNet.sendTo(player, new BuilderSubmitHintsPacket(packet.relayId, SubmitHints.Hints.NONE));
                return;
            }
            ServerLevel level = player.getServer().overworld();
            BuilderSubmitHints.forBuild(player, level, packet.relayId)
                .thenAccept(hints -> player.getServer().execute(() -> {
                    if (player.hasDisconnected()) return;
                    DungeonTrainNet.sendTo(player, new BuilderSubmitHintsPacket(packet.relayId, hints));
                }));
        });
    }
}
