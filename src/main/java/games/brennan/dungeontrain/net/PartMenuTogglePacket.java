package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.editor.PartPositionMenuController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: toggle the per-player auto-open flag for the
 * part-position world-space menu. Default is on; clicking the panel's
 * {@code X} or the editor menu's "Part Variant Menu" toggle row sends
 * this. While disabled, the server suppresses
 * {@link PartAssignmentSyncPacket} sync packets (which is what causes
 * the menu to render); enabling it again resumes the sync stream so the
 * menu auto-reopens on the next valid hover.
 */
public record PartMenuTogglePacket(boolean enabled) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(enabled);
    }

    public static PartMenuTogglePacket decode(FriendlyByteBuf buf) {
        return new PartMenuTogglePacket(buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;
            PartPositionMenuController.setMenuEnabled(sender, enabled);
        });
        ctx.setPacketHandled(true);
    }
}
