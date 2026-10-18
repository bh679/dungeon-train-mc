package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.AdvancementGapsClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Server → client: how many hidden advancements sit between each just-synced advancement and the one it
 * is drawn from. Sent right before the vanilla advancements packet it describes, so the client has the
 * counts when it lays the tab out. See {@link games.brennan.dungeontrain.advancement.HiddenGap}.
 *
 * <p>{@code gaps} covers only the advancements in that vanilla packet's added list (0 for a direct link),
 * because the client only changes an advancement's drawn parent when it is added. {@code reset} mirrors
 * the vanilla packet's reset flag: the client drops every count it held first.</p>
 *
 * <p>{@code relinks} names advancements already on the client whose nearest visible ancestor changed
 * because a skipped parent was revealed: child id → new parent id. The client moves each one in place once
 * the vanilla packet has added the new parent, and {@code gaps} carries its new count.</p>
 */
public record AdvancementGapsPacket(boolean reset, Map<ResourceLocation, Integer> gaps,
                                    Map<ResourceLocation, ResourceLocation> relinks) implements CustomPacketPayload {

    public static final Type<AdvancementGapsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "advancement_gaps"));

    public static final StreamCodec<FriendlyByteBuf, AdvancementGapsPacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL, AdvancementGapsPacket::reset,
        ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.VAR_INT), AdvancementGapsPacket::gaps,
        ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, ResourceLocation.STREAM_CODEC), AdvancementGapsPacket::relinks,
        AdvancementGapsPacket::new
    );

    public AdvancementGapsPacket {
        gaps = Map.copyOf(gaps);
        relinks = Map.copyOf(relinks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound handler — only ever runs on the physical client (mirrors {@link LifeDisqualifiedPacket#handle}). */
    public static void handle(AdvancementGapsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> AdvancementGapsClient.apply(packet.reset(), packet.gaps(), packet.relinks()));
    }
}
