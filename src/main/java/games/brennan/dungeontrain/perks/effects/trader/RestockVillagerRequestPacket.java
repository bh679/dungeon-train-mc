package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the Restock button on trade menu {@code containerId} was pressed. The server
 * re-checks everything ({@link TraderPerkEvents#tryRestock}); the client's view is only a hint.
 */
public record RestockVillagerRequestPacket(int containerId) implements CustomPacketPayload {

    public static final Type<RestockVillagerRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "restock_villager_request"));

    public static final StreamCodec<FriendlyByteBuf, RestockVillagerRequestPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeVarInt(p.containerId), buf -> new RestockVillagerRequestPacket(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RestockVillagerRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                TraderPerkEvents.tryRestock(player, packet.containerId());
            }
        });
    }
}
