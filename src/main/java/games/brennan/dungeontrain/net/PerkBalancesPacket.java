package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.PerkClientBalances;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the receiving player's perk balances, unspent unlock points and active points.
 * Sent by {@link games.brennan.dungeontrain.perks.PerkBalances#sync} whenever either can have changed.
 */
public record PerkBalancesPacket(int unlockPoints, int activePoints) implements CustomPacketPayload {

    public static final Type<PerkBalancesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perk_balances"));

    public static final StreamCodec<ByteBuf, PerkBalancesPacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, PerkBalancesPacket::unlockPoints,
        ByteBufCodecs.VAR_INT, PerkBalancesPacket::activePoints,
        PerkBalancesPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PerkBalancesPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PerkClientBalances.apply(packet.unlockPoints(), packet.activePoints()));
    }
}
