package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.portal.ClientPortalPrewarm;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: where you would land if you crossed this portal now — sent while you are walking down the
 * corridor, so the client can build that place before it has to draw it.
 *
 * <p><b>The companion to {@link PortalSwapPacket}, and deliberately earlier.</b> That one says "the
 * swap has happened, repair this frame"; this one says "it is about to", which is the only moment at
 * which the destination's chunk meshes can be built without a player watching it happen.
 * {@link ClientPortalPrewarm} explains what is done with it and why the arrival fixes alone leave a
 * flash.</p>
 *
 * <p><b>A place, not an event</b>, and so it can be re-sent freely: the client keys its span on the
 * destination's own section and a restatement of the same one merely refreshes the clock. The server
 * sends it only when that section changes or every couple of seconds, so a player standing still in a
 * corridor costs one packet.</p>
 *
 * <p>Only ever the world-side destination — the twin corridor or the copy of it a player is bound to.
 * The other direction lands on the train, whose sections have been compiled all along by the simple
 * fact of riding it, and which Sable draws from a sub-level rather than from the world sections this
 * builds. Dropping one costs a flash, not a bug, which is why nothing acknowledges it.</p>
 */
public record PortalPrewarmPacket(BlockPos destination) implements CustomPacketPayload {

    public static final Type<PortalPrewarmPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_prewarm"));

    public static final StreamCodec<FriendlyByteBuf, PortalPrewarmPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeBlockPos(packet.destination),
            buf -> new PortalPrewarmPacket(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalPrewarmPacket packet, IPayloadContext ctx) {
        BlockPos at = packet.destination();
        ctx.enqueueWork(() -> ClientPortalPrewarm.arm(at.getX(), at.getY(), at.getZ()));
    }
}
