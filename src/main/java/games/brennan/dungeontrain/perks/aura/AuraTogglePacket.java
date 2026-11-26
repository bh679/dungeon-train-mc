package games.brennan.dungeontrain.perks.aura;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: switch one drawn aura effect off or on. The server checks it is the player's own draw. */
public record AuraTogglePacket(String auraId, ResourceLocation effect) implements CustomPacketPayload {

    public static final Type<AuraTogglePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perk_aura_toggle"));

    public static final StreamCodec<FriendlyByteBuf, AuraTogglePacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(64), AuraTogglePacket::auraId,
        ResourceLocation.STREAM_CODEC, AuraTogglePacket::effect,
        AuraTogglePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AuraTogglePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) AuraTicker.toggle(player, packet.auraId(), packet.effect());
        });
    }
}
