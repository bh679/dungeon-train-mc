package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.PerksMenuOpener;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server → client: open the perks menu (a point is ready to spend). Carries the active point count. */
public record OpenPerksMenuPacket(int activePoints) implements CustomPacketPayload {

    public static final Type<OpenPerksMenuPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "open_perks_menu"));

    public static final StreamCodec<ByteBuf, OpenPerksMenuPacket> STREAM_CODEC =
        ByteBufCodecs.VAR_INT.map(OpenPerksMenuPacket::new, OpenPerksMenuPacket::activePoints);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound; only runs on the physical client (mirrors {@link AdvancementsHintPacket#handle}). */
    public static void handle(OpenPerksMenuPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PerksMenuOpener.open(packet.activePoints()));
    }
}
