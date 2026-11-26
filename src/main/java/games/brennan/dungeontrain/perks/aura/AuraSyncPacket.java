package games.brennan.dungeontrain.perks.aura;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server to client: the player's aura draws this life and their switched-off effects, for the menu's chips. */
public record AuraSyncPacket(AuraState.Draws draws, AuraState.Toggles toggles) implements CustomPacketPayload {

    public static final Type<AuraSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perk_aura_sync"));

    public static final StreamCodec<FriendlyByteBuf, AuraSyncPacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.fromCodec(AuraState.Draws.CODEC), AuraSyncPacket::draws,
        ByteBufCodecs.fromCodec(AuraState.Toggles.CODEC), AuraSyncPacket::toggles,
        AuraSyncPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AuraSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> AuraClientState.set(packet.draws(), packet.toggles()));
    }
}
