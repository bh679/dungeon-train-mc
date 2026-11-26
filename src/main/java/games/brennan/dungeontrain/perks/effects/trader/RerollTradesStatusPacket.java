package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server to client: how many Reroll Trades charges the player has left on the villager behind
 * trade menu {@code containerId}. Sent when the menu opens (only with the perk active) and after
 * each reroll; the menu's Reroll button shows it ({@link RerollTradesClientState}).
 */
public record RerollTradesStatusPacket(int containerId, int remaining) implements CustomPacketPayload {

    public static final Type<RerollTradesStatusPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "reroll_trades_status"));

    public static final StreamCodec<FriendlyByteBuf, RerollTradesStatusPacket> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.containerId);
            buf.writeVarInt(p.remaining);
        },
        buf -> new RerollTradesStatusPacket(buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RerollTradesStatusPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> RerollTradesClientState.set(packet.containerId(), packet.remaining()));
    }
}
