package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.editor.VariantHotkeyState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: report the press / release state of the "variant place"
 * keymap (default {@code Z}). The server stores the held flag in
 * {@link VariantHotkeyState} so {@link
 * games.brennan.dungeontrain.editor.VariantBlockInteractions} can gate
 * variant authoring on the rebindable key instead of vanilla sneak.
 *
 * <p>Sent only on the press / release transition (not per tick) to keep the
 * pipe quiet. Server cleans up on logout via the
 * {@link net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent}
 * listener inside {@link VariantHotkeyState}.</p>
 */
public record VariantHotkeyPacket(boolean held) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(held);
    }

    public static VariantHotkeyPacket decode(FriendlyByteBuf buf) {
        return new VariantHotkeyPacket(buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;
            VariantHotkeyState.setHeld(sender, held);
        });
        ctx.setPacketHandled(true);
    }
}
