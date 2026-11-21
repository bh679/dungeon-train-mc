package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.perks.PerksScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: an unlock or spend was refused. {@code reasonKey} is a lang key
 * ({@code gui.dungeontrain.perks.refused.*}) the open menu shows on the card for a moment.
 */
public record PerkRefusedPacket(String perkId, String reasonKey) implements CustomPacketPayload {

    private static final int MAX_CHARS = 96;

    public static final Type<PerkRefusedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perk_refused"));

    public static final StreamCodec<FriendlyByteBuf, PerkRefusedPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> { buf.writeUtf(p.perkId, MAX_CHARS); buf.writeUtf(p.reasonKey, MAX_CHARS); },
            buf -> new PerkRefusedPacket(buf.readUtf(MAX_CHARS), buf.readUtf(MAX_CHARS)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PerkRefusedPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PerksScreen.onRefused(packet.perkId(), packet.reasonKey()));
    }
}
