package games.brennan.dungeontrain.perks.effects.alchemist;

import games.brennan.dungeontrain.DungeonTrain;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the inventory's Brew button was pressed. The server checks the perk again and opens
 * the player's pocket brewing stand ({@link PocketBrewing#open}).
 */
public record PocketBrewOpenPacket() implements CustomPacketPayload {

    public static final Type<PocketBrewOpenPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "pocket_brewing_open"));

    public static final StreamCodec<ByteBuf, PocketBrewOpenPacket> STREAM_CODEC = StreamCodec.unit(new PocketBrewOpenPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PocketBrewOpenPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) PocketBrewing.open(player);
        });
    }
}
