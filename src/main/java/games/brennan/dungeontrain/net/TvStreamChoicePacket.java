package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the wearer of a TV soloed a stream (1 or 2) or went back to both (0) from the
 * inventory ({@code client/live/HeadViewerPanes}). The server writes it onto the TV on their head as
 * {@code TV_STREAM}, so the choice travels with that TV: taken off and put back on, or placed and
 * broken later, it is still on that stream. Nothing happens unless a TV is on the sender's head.
 */
public record TvStreamChoicePacket(int stream) implements CustomPacketPayload {

    public static final Type<TvStreamChoicePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "tv_stream_choice"));

    public static final StreamCodec<FriendlyByteBuf, TvStreamChoicePacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeVarInt(p.stream()), buf -> new TvStreamChoicePacket(clamp(buf.readVarInt())));

    /** 0, 1 or 2; anything else reads as both (0). */
    static int clamp(int v) {
        return v == 1 || v == 2 ? v : 0;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TvStreamChoicePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
            if (!TvItems.isTv(head)) return;
            player.setItemSlot(EquipmentSlot.HEAD, TvItems.withStream(head, clamp(packet.stream())));
        });
    }
}
