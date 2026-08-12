package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.FreePlayConfirmClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: a tainting action (a creative/spectator switch, a cheat
 * command, or a difficulty drop below the default) was intercepted and held; ask
 * the player to confirm entering Free Play before it commits.
 *
 * <p>Carries a short label of what triggered it — a literal command string
 * (e.g. {@code "/gamemode creative"}) or a translatable difficulty name, which is
 * why it's a {@link Component} rather than a raw string: the client resolves it in
 * the player's own language. The client either opens
 * {@link games.brennan.dungeontrain.client.FreePlayConfirmScreen} or, if the
 * player has opted out of the prompt, immediately replies confirmed — see
 * {@link FreePlayConfirmClient}.</p>
 */
public record ShowFreePlayConfirmPacket(Component label) implements CustomPacketPayload {

    public static final Type<ShowFreePlayConfirmPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "show_free_play_confirm"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShowFreePlayConfirmPacket> STREAM_CODEC =
        StreamCodec.composite(
            ComponentSerialization.STREAM_CODEC, ShowFreePlayConfirmPacket::label,
            ShowFreePlayConfirmPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound — only runs on the physical client (mirrors {@code CinematicIntroPacket}). */
    public static void handle(ShowFreePlayConfirmPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> FreePlayConfirmClient.onShow(packet.label()));
    }
}
