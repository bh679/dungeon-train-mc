package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.PerkClientState;
import games.brennan.dungeontrain.perks.PerkState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the receiving player's whole {@link PerkState}. Sent on login, respawn,
 * dimension change and after every change ({@link games.brennan.dungeontrain.perks.Perks#sync}).
 * The state is small (a handful of ids and ints) so it is always sent whole, never as a delta.
 */
public record PerkStatePacket(PerkState state) implements CustomPacketPayload {

    public static final Type<PerkStatePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perk_state"));

    public static final StreamCodec<FriendlyByteBuf, PerkStatePacket> STREAM_CODEC =
        PerkState.STREAM_CODEC.<FriendlyByteBuf>cast().map(PerkStatePacket::new, PerkStatePacket::state);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound handler; only runs on the physical client. */
    public static void handle(PerkStatePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PerkClientState.apply(ctx.player().getUUID(), packet.state()));
    }
}
