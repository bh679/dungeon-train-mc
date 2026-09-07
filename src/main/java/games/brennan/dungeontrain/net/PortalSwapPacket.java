package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.portal.ClientPortalSwap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import games.brennan.dungeontrain.ship.sable.SableEntityCarry;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: you have just been swapped between a portal corridor and its twin — the renderer needs a
 * fresh occlusion graph before it draws the next frame, and if you have just left a moving carriage,
 * Sable's carry of you has to be shed on your side too.
 *
 * <p><b>Why the client has to shed it.</b> The position change arrives as vanilla's own teleport
 * packet immediately before this one, flagged relative on every axis so momentum and the render
 * interpolation baseline both survive it. What must not survive it is the carriage's motion, which
 * Sable keeps on the entity outside {@code deltaMovement} ({@link SableEntityCarry}) — and the
 * client is the authority on a player's movement, so the server clearing its own copy changes
 * nothing the player can feel. So the server says "you left a carrier" and the client sheds it
 * against the state it actually holds.</p>
 *
 * <p>{@link #none()} is the way to say "nothing to shed" — the free-standing hallway portals shift
 * a player within one frame, where the momentum is entirely their own, and a swap back onto the
 * train wants Sable to pick the player up again, not be told to let go.</p>
 *
 * <p>Sent from both swap paths right after {@code connection.teleport}. Dropping one costs a flash
 * and a moment's slide, not a bug, which is why nothing acknowledges it.</p>
 *
 * @param leftCarrier whether this swap took the player off a moving carriage
 */
public record PortalSwapPacket(boolean leftCarrier) implements CustomPacketPayload {

    /** A swap that changes nothing about what is carrying the player — see the class note. */
    public static PortalSwapPacket none() {
        return new PortalSwapPacket(false);
    }

    public static final Type<PortalSwapPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_swap"));

    public static final StreamCodec<FriendlyByteBuf, PortalSwapPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeBoolean(packet.leftCarrier),
            buf -> new PortalSwapPacket(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalSwapPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientPortalSwap.arm();
            if (!packet.leftCarrier) return;

            // ctx.player() is this client's own player here, which is the one being carried — and
            // asking for it this way keeps every Minecraft-client type out of this common-side
            // class.
            Player player = ctx.player();
            if (player != null) SableEntityCarry.shed(player);
        });
    }
}
