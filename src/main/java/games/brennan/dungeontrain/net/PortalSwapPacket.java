package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.portal.ClientPortalSwap;
import games.brennan.dungeontrain.portal.PortalTransitVelocity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: you have just been swapped between a portal corridor and its twin — the renderer needs a
 * fresh occlusion graph before it draws the next frame, and the velocity you arrive with may need
 * the carriage's own motion taken out of it.
 *
 * <p><b>Why the velocity is corrected here and not on the server.</b> The position change itself
 * arrives as vanilla's own teleport packet immediately before this one, flagged relative on every
 * axis so momentum and the render interpolation baseline both survive it. But the client, not the
 * server, is the authority on a player's movement: the dev client measured a player leaving a
 * carriage with roughly 0.49 blocks/tick while the server's own reading of them was 0.28, so a
 * server-side correction removes the wrong amount and a {@code ClientboundSetEntityMotionPacket}
 * carrying the server's figure makes it worse. The carrier's velocity is a fact only the server
 * knows, so the server sends that and the client does the subtraction against the velocity it
 * actually has. See {@link PortalTransitVelocity}.</p>
 *
 * <p>The carriage's twin is stamped into the static world, so its motion is no longer the
 * traveller's to keep; their own walking is. {@link #none()} is the way to say "no correction" —
 * the free-standing hallway portals shift a player within one moving frame, where the momentum is
 * entirely their own.</p>
 *
 * <p>Sent from both swap paths right after {@code connection.teleport}. Dropping one costs a flash
 * and a moment's drift, not a bug, which is why nothing acknowledges it.</p>
 *
 * @param carrierX the world-space velocity, in blocks per tick, of the frame the traveller is
 *                 leaving; zero on all three axes for "nothing to correct"
 */
public record PortalSwapPacket(double carrierX, double carrierY, double carrierZ)
    implements CustomPacketPayload {

    /** A swap with no frame change worth correcting for — see the class note. */
    public static PortalSwapPacket none() {
        return new PortalSwapPacket(0.0, 0.0, 0.0);
    }

    /** The carrier this names. */
    public Vec3 carrier() {
        return new Vec3(carrierX, carrierY, carrierZ);
    }

    public static final Type<PortalSwapPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_swap"));

    public static final StreamCodec<FriendlyByteBuf, PortalSwapPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeDouble(packet.carrierX);
                buf.writeDouble(packet.carrierY);
                buf.writeDouble(packet.carrierZ);
            },
            buf -> new PortalSwapPacket(buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalSwapPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientPortalSwap.arm();

            // ctx.player() is this client's own player here, which is the one the correction is
            // about — and asking for it this way keeps every Minecraft-client type out of this
            // common-side class.
            Player player = ctx.player();
            if (player == null) return;
            player.setDeltaMovement(PortalTransitVelocity.withoutCarrier(
                player.getDeltaMovement(), packet.carrier()));
        });
    }
}
