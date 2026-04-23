package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → client: update the editor status HUD bar with the player's current
 * category + model. Empty strings clear the HUD (player is outside every
 * editor plot).
 *
 * <p>Sent only when the player's (category, model) pair changes — no per-tick
 * spam. See {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer}
 * for the detection loop.</p>
 */
public record EditorStatusPacket(String category, String model) {

    public static EditorStatusPacket empty() {
        return new EditorStatusPacket("", "");
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(category);
        buf.writeUtf(model);
    }

    public static EditorStatusPacket decode(FriendlyByteBuf buf) {
        String c = buf.readUtf(64);
        String m = buf.readUtf(64);
        return new EditorStatusPacket(c, m);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT, () -> () -> EditorStatusHudOverlay.setStatus(category, model)));
        ctx.setPacketHandled(true);
    }
}
