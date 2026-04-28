package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.editor.ContainerHotkeyState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: held / released state of the "container contents" key
 * (default {@code C}). Mirrors {@link VariantHotkeyPacket}; the server
 * stores the held flag in {@link ContainerHotkeyState}.
 *
 * <p>Sent only on transition (not per tick). Cleaned up on logout via the
 * listener inside {@link ContainerHotkeyState}.</p>
 */
public record ContainerHotkeyPacket(boolean held) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(held);
    }

    public static ContainerHotkeyPacket decode(FriendlyByteBuf buf) {
        return new ContainerHotkeyPacket(buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;
            ContainerHotkeyState.setHeld(sender, held);
        });
        ctx.setPacketHandled(true);
    }
}
