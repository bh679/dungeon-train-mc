package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.PriorityBookPending;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player ticked "Use a Priority Book" on the sign page, sent immediately BEFORE
 * vanilla's {@code ServerboundEditBookPacket} so it is waiting in {@link PriorityBookPending} when
 * {@code signBook} runs — the same ordering argument as {@link EditorBookAuthorPacket}. If it somehow
 * lands late, the book simply goes up without the boost and the credit stays the player's.
 *
 * <p>Carries no value: it is only ever sent for a ticked box. It spends nothing by itself; the relay
 * charges the player's own wallet when the upload arrives, and refuses when there is nothing to spend.</p>
 */
public record PriorityBookIntentPacket() implements CustomPacketPayload {

    public static final PriorityBookIntentPacket INSTANCE = new PriorityBookIntentPacket();

    public static final Type<PriorityBookIntentPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "priority_book_intent"));

    public static final StreamCodec<FriendlyByteBuf, PriorityBookIntentPacket> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PriorityBookIntentPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            PriorityBookPending.put(player.getUUID());
        });
    }
}
