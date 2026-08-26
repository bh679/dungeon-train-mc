package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: put one of my builds on the train, or take it back off.
 *
 * <p>Carries only the relay id and the direction. The credential that authorises it — the build's owner
 * secret — is deliberately not on the wire: it lives in the world's saved data, and the server looks it
 * up by id. A client that names a build this world never uploaded gets told so, because there is no
 * secret to find, which is also what stops the packet being a way to publish somebody else's work.</p>
 */
public record BuilderProfileActionPacket(int relayId, boolean publish) implements CustomPacketPayload {

    public static final Type<BuilderProfileActionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_profile_action"));

    public static final StreamCodec<FriendlyByteBuf, BuilderProfileActionPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeBoolean(packet.publish);
            },
            buf -> new BuilderProfileActionPacket(buf.readVarInt(), buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderProfileActionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (player.getServer() == null || !BuilderRelayUpload.canUpload(player)) return;
            ServerLevel level = player.getServer().overworld();
            BuilderRelayUpload.submitToTrain(player, level, packet.relayId, packet.publish)
                    .thenAccept(message -> player.getServer().execute(() -> {
                        if (player.hasDisconnected()) return;
                        player.sendSystemMessage(message);
                        // Re-read the profile so the screen shows what actually happened rather than
                        // what was asked for — the relay may have refused (a build in use elsewhere).
                        BuilderProfileRequestPacket.handle(new BuilderProfileRequestPacket(), ctx);
                    }));
        });
    }
}
