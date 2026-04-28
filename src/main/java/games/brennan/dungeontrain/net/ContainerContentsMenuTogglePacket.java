package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.editor.ContainerContentsMenuController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: open or close the container-contents world-space menu.
 *
 * <p>Sent on C-tap (open) or by the menu's close button. The server-side
 * handler raycasts the player's eye to find a Container BlockEntity and
 * replies with a {@link ContainerContentsSyncPacket}.</p>
 */
public record ContainerContentsMenuTogglePacket(boolean open) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
    }

    public static ContainerContentsMenuTogglePacket decode(FriendlyByteBuf buf) {
        return new ContainerContentsMenuTogglePacket(buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;
            ContainerContentsMenuController.toggle(sender, open);
        });
        ctx.setPacketHandled(true);
    }
}
