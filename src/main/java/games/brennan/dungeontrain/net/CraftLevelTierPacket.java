package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CraftLevelTierClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the level tier Craft Level Stats would stamp a craft against right now
 * ({@code CraftLevelStats.levelTierFor}). The tier formula's config is server-only, so the
 * result-slot tooltip can't work it out itself. Sent only to players with the perk, and only
 * when the value changes.
 */
public record CraftLevelTierPacket(int levelTier) implements CustomPacketPayload {

    public static final Type<CraftLevelTierPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "craft_level_tier"));

    public static final StreamCodec<FriendlyByteBuf, CraftLevelTierPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), CraftLevelTierPacket::decode);

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(levelTier);
    }

    public static CraftLevelTierPacket decode(FriendlyByteBuf buf) {
        return new CraftLevelTierPacket(Math.max(0, buf.readVarInt()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CraftLevelTierPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CraftLevelTierClient.set(packet.levelTier));
    }
}
