package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.VariantHotkeyState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: report the press / release state of the "variant place"
 * keymap (default {@code Z}). The server stores the held flag in
 * {@link VariantHotkeyState} so {@link
 * games.brennan.dungeontrain.editor.VariantBlockInteractions} can gate
 * variant authoring on the rebindable key instead of vanilla sneak.
 *
 * <p>Sent only on the press / release transition (not per tick) to keep the
 * pipe quiet. Server cleans up on logout via the
 * {@link net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent}
 * listener inside {@link VariantHotkeyState}.</p>
 */
public record VariantHotkeyPacket(boolean held) implements CustomPacketPayload {

    public static final Type<VariantHotkeyPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "variant_hotkey"));

    public static final StreamCodec<FriendlyByteBuf, VariantHotkeyPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            VariantHotkeyPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(held);
    }

    public static VariantHotkeyPacket decode(FriendlyByteBuf buf) {
        return new VariantHotkeyPacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(VariantHotkeyPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                VariantHotkeyState.setHeld(sender, packet.held);
            }
        });
    }
}
