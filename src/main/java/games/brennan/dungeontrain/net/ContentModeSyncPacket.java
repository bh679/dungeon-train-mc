package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ContentMode;
import games.brennan.dungeontrain.event.ContentModeMirror;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: tell the server which {@link ContentMode} this client is in, so the per-player
 * content gates ({@link games.brennan.dungeontrain.event.SharedBookGate},
 * {@link games.brennan.dungeontrain.event.DeathNoteGate}, the developer-message path) can act on it.
 * Sent on login and again whenever the player changes the setting while connected.
 *
 * <p>The client is authoritative — the mode is a CLIENT-scope config
 * ({@code ClientDisplayConfig.getContentMode()}), so the server can only know it if the client says
 * so. The server keeps a per-session mirror ({@link ContentModeMirror}).</p>
 *
 * <p>Mirror of {@link NetworkConsentSyncPacket}'s shape, with the mode sent as a single byte ordinal.
 * An ordinal a future version doesn't recognise decodes to {@link ContentMode#KID} rather than
 * throwing or defaulting to ADULT — the same fail-safe direction the mirror uses, applied at the wire
 * boundary so an unknown value can never widen what a client is served.</p>
 */
public record ContentModeSyncPacket(ContentMode mode) implements CustomPacketPayload {

    public static final Type<ContentModeSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "content_mode_sync"));

    public static final StreamCodec<FriendlyByteBuf, ContentModeSyncPacket> STREAM_CODEC =
        StreamCodec.of(ContentModeSyncPacket::encode, ContentModeSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, ContentModeSyncPacket pkt) {
        ContentMode mode = pkt.mode() == null ? ContentMode.KID : pkt.mode();
        buf.writeByte(mode.ordinal());
    }

    private static ContentModeSyncPacket decode(FriendlyByteBuf buf) {
        int ordinal = buf.readByte();
        ContentMode[] values = ContentMode.values();
        // Out-of-range = a mode this build doesn't know about → treat as the most restrictive one.
        ContentMode mode = ordinal >= 0 && ordinal < values.length ? values[ordinal] : ContentMode.KID;
        return new ContentModeSyncPacket(mode);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ContentModeSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ContentModeMirror.set(player, packet.mode());
        });
    }
}
