package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.editor.BlockVariantMenuController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: open or close the block-variant world-space menu.
 *
 * <p>Sent on Z-tap (open) or by clicking the menu's close button / re-tapping
 * Z while the menu is up (close). The server-side handler raycasts the
 * player's eye to find a flagged block and replies with a
 * {@link BlockVariantSyncPacket} when {@code open} is true; on close it
 * sends the empty sync packet to dismiss the panel client-side.</p>
 *
 * <p>Unlike the {@link PartMenuTogglePacket}, this is a per-event toggle
 * rather than a persistent enable/disable flag — the menu is a tap-to-open
 * UI, not an auto-opening hover overlay.</p>
 */
public record BlockVariantMenuTogglePacket(boolean open) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
    }

    public static BlockVariantMenuTogglePacket decode(FriendlyByteBuf buf) {
        return new BlockVariantMenuTogglePacket(buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;
            BlockVariantMenuController.toggle(sender, open);
        });
        ctx.setPacketHandled(true);
    }
}
