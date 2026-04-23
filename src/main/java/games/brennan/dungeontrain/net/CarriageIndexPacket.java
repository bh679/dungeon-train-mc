package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.client.VersionHudOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → client: the closest train carriage index for the receiving player.
 * {@code present=false} means "no train within range — hide the suffix";
 * otherwise {@code pIdx} is the signed carriage index (0 = origin carriage,
 * positive forward, negative back). Sent only when the value changes so the
 * pipe stays quiet.
 */
public record CarriageIndexPacket(boolean present, int pIdx) {

    public static CarriageIndexPacket absent() {
        return new CarriageIndexPacket(false, 0);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(present);
        if (present) {
            buf.writeVarInt(pIdx);
        }
    }

    public static CarriageIndexPacket decode(FriendlyByteBuf buf) {
        boolean present = buf.readBoolean();
        int pIdx = present ? buf.readVarInt() : 0;
        return new CarriageIndexPacket(present, pIdx);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT, () -> () -> VersionHudOverlay.setCarriageIndex(present, pIdx)));
        ctx.setPacketHandled(true);
    }
}
