package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → client: update the editor status HUD bar with the player's current
 * category + model, the session's dev-mode flag, and — for carriage models —
 * the variant's random-selection weight. Empty strings for both category and
 * model clear the HUD (player is outside every editor plot).
 *
 * <p>Sent only when the player's (category, model, devmode, weight) tuple
 * changes — no per-tick spam. See
 * {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer} for the
 * detection loop.</p>
 *
 * <p>{@code weight} is the carriage variant's pick weight (0..100) when the
 * model is a carriage; {@link #NO_WEIGHT} ({@value #NO_WEIGHT}) for any model
 * where weight is not meaningful (pillars, tunnels, track) or for the empty
 * clear packet.</p>
 */
public record EditorStatusPacket(String category, String model, boolean devmode, int weight) {

    /** Sentinel for "weight is not applicable to this model". */
    public static final int NO_WEIGHT = -1;

    public static EditorStatusPacket empty() {
        return new EditorStatusPacket("", "", false, NO_WEIGHT);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(category);
        buf.writeUtf(model);
        buf.writeBoolean(devmode);
        buf.writeVarInt(weight);
    }

    public static EditorStatusPacket decode(FriendlyByteBuf buf) {
        String c = buf.readUtf(64);
        String m = buf.readUtf(64);
        boolean d = buf.readBoolean();
        int w = buf.readVarInt();
        return new EditorStatusPacket(c, m, d, w);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT, () -> () -> EditorStatusHudOverlay.setStatus(category, model, devmode, weight)));
        ctx.setPacketHandled(true);
    }
}
