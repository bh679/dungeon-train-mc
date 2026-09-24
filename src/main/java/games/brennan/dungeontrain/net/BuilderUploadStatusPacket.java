package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorUploadStatus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: where a save's relay upload has got to.
 *
 * <p>The upload runs after the local write and resolves seconds later on an HTTP callback, so without
 * this the editor screen has no way to know when the build's relay row exists — the Submit icon, the
 * version strip and the tile's relay id all stayed as they were until the screen was reopened. One
 * {@link #STARTED} per upload that actually goes out, then exactly one {@link #DONE} or
 * {@link #FAILED}.</p>
 *
 * <p>{@code photoKind} is the {@code BuilderPhotoPaths.Kind} name and {@code id} the template id, the
 * same pair the client's tile art is keyed on. {@code relayId} is the build's relay row on DONE, 0
 * otherwise. The phase goes out as a byte constant rather than an enum ordinal so reordering nothing
 * can ever silently remap it.</p>
 */
public record BuilderUploadStatusPacket(String photoKind, String id, byte phase, int relayId)
    implements CustomPacketPayload {

    public static final byte STARTED = 0;
    public static final byte DONE = 1;
    public static final byte FAILED = 2;

    private static final int MAX_STRING = 256;

    public static final Type<BuilderUploadStatusPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_upload_status"));

    public static final StreamCodec<FriendlyByteBuf, BuilderUploadStatusPacket> STREAM_CODEC =
        StreamCodec.of(BuilderUploadStatusPacket::encode, BuilderUploadStatusPacket::decode);

    private static void encode(FriendlyByteBuf buf, BuilderUploadStatusPacket p) {
        buf.writeUtf(clamp(p.photoKind), MAX_STRING);
        buf.writeUtf(clamp(p.id), MAX_STRING);
        buf.writeByte(p.phase);
        buf.writeVarInt(Math.max(0, p.relayId));
    }

    private static String clamp(String s) {
        if (s == null) return "";
        return s.length() > MAX_STRING ? s.substring(0, MAX_STRING) : s;
    }

    private static BuilderUploadStatusPacket decode(FriendlyByteBuf buf) {
        String kind = buf.readUtf(MAX_STRING);
        String id = buf.readUtf(MAX_STRING);
        byte phase = buf.readByte();
        int relayId = buf.readVarInt();
        return new BuilderUploadStatusPacket(kind, id, phase, relayId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderUploadStatusPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorUploadStatus.onPacket(packet));
    }
}
