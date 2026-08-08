package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.MaxCarriagesMirror;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: tell the server this player's personal carriage ceiling (the Max carriages row in
 * Options → Dungeon Train…), so the per-player rolling window can honour it. Sent on login and again
 * whenever the player moves the slider while connected.
 *
 * <p>The client is authoritative — the ceiling is a CLIENT-scope config
 * ({@code ClientDisplayConfig.getMaxCarriages()}), because it describes the machine rendering the
 * train, so the server can only know it if the client says so. The server keeps a per-session mirror
 * ({@link MaxCarriagesMirror}).</p>
 *
 * <p>Mirror of {@link ContentModeSyncPacket}'s shape. Unlike that packet there is no permission at
 * stake: the value is applied as one ceiling among several and can only ever shorten the sender's own
 * window, never lengthen it and never touch anyone else's. So the wire boundary only has to reject
 * nonsense — an out-of-range value decodes to {@code 0} (auto, no personal cap) rather than throwing.</p>
 */
public record MaxCarriagesSyncPacket(int maxCarriages) implements CustomPacketPayload {

    public static final Type<MaxCarriagesSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "max_carriages_sync"));

    public static final StreamCodec<FriendlyByteBuf, MaxCarriagesSyncPacket> STREAM_CODEC =
        StreamCodec.of(MaxCarriagesSyncPacket::encode, MaxCarriagesSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, MaxCarriagesSyncPacket pkt) {
        buf.writeVarInt(sanitize(pkt.maxCarriages()));
    }

    private static MaxCarriagesSyncPacket decode(FriendlyByteBuf buf) {
        return new MaxCarriagesSyncPacket(sanitize(buf.readVarInt()));
    }

    /** {@code 0} (auto) or {@code [4, 50]}. Anything else — including negatives — reads as auto. */
    private static int sanitize(int value) {
        if (value <= 0) return 0;
        if (value < DungeonTrainConfig.MIN_CARRIAGES_EXPLICIT) return DungeonTrainConfig.MIN_CARRIAGES_EXPLICIT;
        return Math.min(DungeonTrainConfig.MAX_CARRIAGES, value);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MaxCarriagesSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MaxCarriagesMirror.set(player, packet.maxCarriages());
        });
    }
}
