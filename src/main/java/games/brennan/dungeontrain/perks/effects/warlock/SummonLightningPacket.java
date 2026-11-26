package games.brennan.dungeontrain.perks.effects.warlock;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the player pressed attack with an empty hand while looking at entity
 * {@code entityId}. The server checks everything again ({@link SummonLightning#cast}).
 */
public record SummonLightningPacket(int entityId) implements CustomPacketPayload {

    public static final Type<SummonLightningPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "summon_lightning"));

    public static final StreamCodec<FriendlyByteBuf, SummonLightningPacket> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeVarInt(p.entityId),
        buf -> new SummonLightningPacket(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SummonLightningPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) SummonLightning.cast(player, packet.entityId());
        });
    }
}
