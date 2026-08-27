package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity.FreePlayCause;
import games.brennan.dungeontrain.client.FreePlayCauseClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: why this player's run is in Free Play, so the effect's hover tooltip can say so
 * ({@link games.brennan.dungeontrain.client.FreePlayTooltip}).
 *
 * <p>The answer is server-side knowledge the client cannot derive — five of the six causes are
 * session/world state that never touches the player, and the sixth is a saved attachment. It is
 * pushed from the two methods that put the badge on and take it off
 * ({@code RunIntegrity.applyFreePlayEffect} / {@code clearFreePlayEffect}), so the explanation
 * arrives with the icon it explains and leaves with it. An empty list means "not Free Play".</p>
 *
 * <p>Causes travel as {@link Component}s rather than resolved text so the reader localizes them in
 * their own language.</p>
 */
public record FreePlayCausePacket(List<FreePlayCause> causes) implements CustomPacketPayload {

    /**
     * Wire cap on how many causes are read. There are only six possible, and a tooltip could not
     * usefully show more — this just stops a malformed length from allocating without bound.
     */
    private static final int MAX_CAUSES = 8;

    public static final Type<FreePlayCausePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "free_play_cause"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FreePlayCausePacket> STREAM_CODEC =
        StreamCodec.of(FreePlayCausePacket::encode, FreePlayCausePacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, FreePlayCausePacket pkt) {
        List<FreePlayCause> causes = pkt.causes();
        int count = Math.min(causes.size(), MAX_CAUSES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            FreePlayCause cause = causes.get(i);
            ComponentSerialization.STREAM_CODEC.encode(buf, cause.cause());
            buf.writeBoolean(cause.detail() != null);
            if (cause.detail() != null) {
                ComponentSerialization.STREAM_CODEC.encode(buf, cause.detail());
            }
        }
    }

    private static FreePlayCausePacket decode(RegistryFriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_CAUSES);
        List<FreePlayCause> causes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Component cause = ComponentSerialization.STREAM_CODEC.decode(buf);
            Component detail = buf.readBoolean() ? ComponentSerialization.STREAM_CODEC.decode(buf) : null;
            causes.add(new FreePlayCause(cause, detail));
        }
        return new FreePlayCausePacket(List.copyOf(causes));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound — only runs on the physical client (mirrors {@link ShowFreePlayConfirmPacket}). */
    public static void handle(FreePlayCausePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> FreePlayCauseClient.set(packet.causes()));
    }
}
