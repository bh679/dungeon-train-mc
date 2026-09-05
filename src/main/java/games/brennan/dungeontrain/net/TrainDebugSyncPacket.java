package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.TrainDebugState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: whether this player may open the F3+4 debug panel, and the data only a grant
 * holder is entitled to see.
 *
 * <p>Sent on join and again whenever the player's grant is issued, refreshed, or lapses. The world's
 * generation seed rides along <em>only</em> when {@code permitted} — an ungranted client is never
 * handed it at all, rather than being handed it and asked not to draw it.</p>
 *
 * <p>{@code expiresAtMs} is epoch millis UTC, or {@code 0} for a grant that never expires.</p>
 */
public record TrainDebugSyncPacket(boolean permitted, long expiresAtMs, long seed) implements CustomPacketPayload {

    public static final Type<TrainDebugSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "train_debug_sync"));

    public static final StreamCodec<FriendlyByteBuf, TrainDebugSyncPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            TrainDebugSyncPacket::decode
        );

    /** The "you have no access" form — carries no seed. */
    public static TrainDebugSyncPacket denied() {
        return new TrainDebugSyncPacket(false, 0L, 0L);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(permitted);
        if (permitted) {
            buf.writeLong(expiresAtMs);
            buf.writeLong(seed);
        }
    }

    public static TrainDebugSyncPacket decode(FriendlyByteBuf buf) {
        boolean permitted = buf.readBoolean();
        if (!permitted) {
            return denied();
        }
        return new TrainDebugSyncPacket(true, buf.readLong(), buf.readLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TrainDebugSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            TrainDebugState.applyPermission(packet.permitted, packet.expiresAtMs, packet.seed));
    }
}
