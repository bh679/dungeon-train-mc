package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Set;

/**
 * Client → server: the player did something the server cannot observe because it
 * happens entirely in client-side settings — currently only changing the train
 * engine volume, which lives in the client config
 * ({@code ClientDisplayConfig.setTrainEngineVolume}). The server turns the
 * reported id into a {@link ModAdvancementTriggers#GAMEPLAY_ACTION} fire.
 *
 * <p><b>The allowlist is the security story.</b> A client asserting an action id
 * can only ever claim one of {@link #ALLOWED}, and every id on that list is a
 * cosmetic one-shot marker with no gameplay reward — the same trust level as the
 * client-measured book-read path ({@link BookReadClosedPacket}). Anything a
 * cheating client could gain by lying here, it could grant itself anyway; ids
 * with real stakes must keep their server-side detection instead of being added
 * to this list.</p>
 */
public record ClientActionPacket(String actionId) implements CustomPacketPayload {

    /** Action ids this packet may fire. Deliberately tiny — see the class note. */
    private static final Set<String> ALLOWED = Set.of("changed_engine_volume");

    /** Defensive cap on the string decoded off the wire. */
    private static final int MAX_ID_LENGTH = 64;

    public static final Type<ClientActionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "client_action"));

    public static final StreamCodec<FriendlyByteBuf, ClientActionPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> buf.writeUtf(p.actionId == null ? "" : p.actionId, MAX_ID_LENGTH),
            buf -> new ClientActionPacket(buf.readUtf(MAX_ID_LENGTH)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientActionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!ALLOWED.contains(packet.actionId)) return;
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, packet.actionId);
        });
    }
}
