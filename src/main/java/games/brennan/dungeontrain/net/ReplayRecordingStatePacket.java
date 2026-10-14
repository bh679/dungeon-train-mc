package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.sable.ReplayRecordingPlayers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * Client → server: "I am (no longer) recording a replay." Sent by
 * {@code client.ReplayRecordingStateClient} whenever ReForgedPlay's recording state changes, and
 * once on join. The server keeps it in {@link ReplayRecordingPlayers} so Sable's movement
 * snapshots for that player go over the ordered connection the replay records.
 */
public record ReplayRecordingStatePacket(boolean recording) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<ReplayRecordingStatePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "replay_recording_state"));

    public static final StreamCodec<FriendlyByteBuf, ReplayRecordingStatePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            ReplayRecordingStatePacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(recording);
    }

    public static ReplayRecordingStatePacket decode(FriendlyByteBuf buf) {
        return new ReplayRecordingStatePacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ReplayRecordingStatePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                ReplayRecordingPlayers.set(sender.getUUID(), packet.recording);
                LOGGER.info("[DungeonTrain] {} {} a replay — Sable snapshots {} the ordered connection",
                    sender.getGameProfile().getName(),
                    packet.recording ? "is recording" : "stopped recording",
                    packet.recording ? "now take" : "back off");
            }
        });
    }
}
