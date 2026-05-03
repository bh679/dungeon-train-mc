package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: J-keybind press requesting one manual spawn cycle. Empty
 * payload — the server side reads
 * {@link TrainCarriageAppender#MANUAL_MODE} and the most recent planned
 * anchor for each train and produces a single spawn cycle on the next
 * appender tick. See {@link TrainCarriageAppender#requestManualSpawn()}.
 */
public record ManualSpawnRequestPacket() implements CustomPacketPayload {

    public static final Type<ManualSpawnRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "manual_spawn_request"));

    public static final StreamCodec<FriendlyByteBuf, ManualSpawnRequestPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> {}, buf -> new ManualSpawnRequestPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ManualSpawnRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(TrainCarriageAppender::requestManualSpawn);
    }
}
