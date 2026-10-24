package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server to client: how many Restock Villager charges the player has left on the villager behind
 * trade menu {@code containerId}. Sent when the menu opens (only with the perk active) and after
 * each restock; the menu's Restock button shows it ({@link RestockVillagerClientState}).
 */
public record RestockVillagerStatusPacket(int containerId, int remaining) implements CustomPacketPayload {

    public static final Type<RestockVillagerStatusPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "restock_villager_status"));

    public static final StreamCodec<FriendlyByteBuf, RestockVillagerStatusPacket> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.containerId);
            buf.writeVarInt(p.remaining);
        },
        buf -> new RestockVillagerStatusPacket(buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RestockVillagerStatusPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> RestockVillagerClientState.set(packet.containerId(), packet.remaining()));
    }
}
