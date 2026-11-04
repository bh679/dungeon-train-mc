package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.RiddenBiomes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Server → client: every biome the local player has ever ridden through, for Terra Omnia's checklist
 * ({@link games.brennan.dungeontrain.client.BiomeChecklistScreen}). Sent whole on join and each time a new
 * biome joins the list; new ones are rare, so a delta isn't worth it.
 */
public record RiddenBiomesPacket(List<String> biomes) implements CustomPacketPayload {

    public static final Type<RiddenBiomesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "ridden_biomes"));

    public static final StreamCodec<FriendlyByteBuf, RiddenBiomesPacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), RiddenBiomesPacket::biomes,
        RiddenBiomesPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound handler — only ever runs on the physical client. */
    public static void handle(RiddenBiomesPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> RiddenBiomes.set(packet.biomes()));
    }
}
