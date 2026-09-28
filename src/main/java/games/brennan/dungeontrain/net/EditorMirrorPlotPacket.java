package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorMirrorPlotClient;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the geometry and mirror axes of the mirror-enabled editor plot the player is
 * standing in, so client-side previews (Effortless Building's ghost blocks) can show the images the
 * server's live mirror will write. {@link #empty()} — no axes on — clears it.
 *
 * <p>Sent only when it changes. See {@link games.brennan.dungeontrain.editor.EditorMirrorPlotSync}.</p>
 */
public record EditorMirrorPlotPacket(BlockPos origin, Vec3i footprint,
                                     boolean mirrorX, boolean mirrorY, boolean mirrorZ)
    implements CustomPacketPayload {

    public static final Type<EditorMirrorPlotPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_mirror_plot"));

    public static final StreamCodec<FriendlyByteBuf, EditorMirrorPlotPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), EditorMirrorPlotPacket::decode);

    public static EditorMirrorPlotPacket empty() {
        return new EditorMirrorPlotPacket(BlockPos.ZERO, Vec3i.ZERO, false, false, false);
    }

    public boolean active() {
        return mirrorX || mirrorY || mirrorZ;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(origin);
        buf.writeVarInt(footprint.getX());
        buf.writeVarInt(footprint.getY());
        buf.writeVarInt(footprint.getZ());
        buf.writeBoolean(mirrorX);
        buf.writeBoolean(mirrorY);
        buf.writeBoolean(mirrorZ);
    }

    public static EditorMirrorPlotPacket decode(FriendlyByteBuf buf) {
        BlockPos origin = buf.readBlockPos();
        Vec3i footprint = new Vec3i(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        return new EditorMirrorPlotPacket(origin, footprint, buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorMirrorPlotPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorMirrorPlotClient.set(packet.active() ? packet : null));
    }
}
