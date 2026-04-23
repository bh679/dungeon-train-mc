package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → client: update the editor status HUD bar with the player's current
 * category + model and the session's dev-mode flag. Empty strings for both
 * category and model clear the HUD (player is outside every editor plot).
 *
 * <p>Sent only when the player's (category, model, devmode) triple changes —
 * no per-tick spam. See
 * {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer} for the
 * detection loop.</p>
 */
public record EditorStatusPacket(String category, String model, boolean devmode) {

    public static EditorStatusPacket empty() {
        return new EditorStatusPacket("", "", false);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(category);
        buf.writeUtf(model);
        buf.writeBoolean(devmode);
    }

    public static EditorStatusPacket decode(FriendlyByteBuf buf) {
        String c = buf.readUtf(64);
        String m = buf.readUtf(64);
        boolean d = buf.readBoolean();
        return new EditorStatusPacket(c, m, d);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT, () -> () -> EditorStatusHudOverlay.setStatus(category, model, devmode)));
        ctx.setPacketHandled(true);
    }
}
