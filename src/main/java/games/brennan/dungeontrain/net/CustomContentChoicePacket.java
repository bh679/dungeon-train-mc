package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.CustomContentPromptEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player answered the custom-content prompt.
 *
 * <p>{@code keepContent=true} → carry on with the content, and the run stays
 * Free Play while it's active; {@code false} → disable custom content for this
 * world. The "Remember decision" preference is persisted client-side, so it
 * isn't carried here — see {@link games.brennan.dungeontrain.config.ClientDisplayConfig}.</p>
 *
 * <p>The world's answer is per-world, not per-player: on a shared server the
 * first answer wins and later ones are reported back rather than applied
 * (see {@link CustomContentPromptEvents#onChoice}).</p>
 */
public record CustomContentChoicePacket(boolean keepContent) implements CustomPacketPayload {

    public static final Type<CustomContentChoicePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "custom_content_choice"));

    public static final StreamCodec<FriendlyByteBuf, CustomContentChoicePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, pkt) -> buf.writeBoolean(pkt.keepContent()),
            buf -> new CustomContentChoicePacket(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CustomContentChoicePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            CustomContentPromptEvents.onChoice(player, packet.keepContent());
        });
    }
}
