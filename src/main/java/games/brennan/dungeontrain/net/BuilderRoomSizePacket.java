package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderRoomResize;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import net.minecraft.core.Vec3i;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: set one axis of the open portal room's footprint.
 *
 * <p>A packet rather than a command, unlike the mirror row beside it. The mirror toggles have a
 * server command already — {@code dungeontrain editor mirror} — because the Train Editor needed
 * one; a builder room's size has no such command, and inventing {@code /dungeontrain} syntax to be
 * typed by a button is a worse contract than a payload that says exactly what it means.</p>
 *
 * <p>The value is a request, not an instruction: {@link BuilderRoomResize} clamps it to what
 * {@code PortalRoomLayout} allows and the authoritative result comes back in the
 * {@link BuilderBoundsPacket} pushed afterwards. A client that asks for a 400-block room gets the
 * maximum, not a room, and not an error.</p>
 */
public record BuilderRoomSizePacket(String axisId, int value) implements CustomPacketPayload {

    public static final Type<BuilderRoomSizePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_room_size"));

    public static final StreamCodec<FriendlyByteBuf, BuilderRoomSizePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.axisId, 16);
                buf.writeVarInt(p.value);
            },
            buf -> new BuilderRoomSizePacket(buf.readUtf(16), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderRoomSizePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ServerLevel level = server.overworld();
            // A client can send anything, and this one clears and stamps blocks.
            if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
                return;
            }

            Vec3i applied = BuilderRoomResize.apply(
                    level, BuilderRoomResize.Axis.fromId(packet.axisId), packet.value);
            if (applied == null) {
                return;   // no room open, or an axis this build doesn't recognise
            }

            // The room's box has moved, so the client's out-of-bounds wash and its size read-out are
            // both stale until this lands. Dirty state too: the restamp re-baselined the snapshot,
            // so a Save that was green for the old box should not stay green for the new one.
            BuilderBoundsPacket.sendTo(player, level);
            BuilderGhostCellsPacket.sendTo(player, level);
            DungeonTrainNet.sendTo(player, BuilderDirtyPacket.state(0));
        });
    }
}
