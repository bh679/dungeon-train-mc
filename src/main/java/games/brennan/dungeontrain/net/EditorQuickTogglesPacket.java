package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorQuickTogglesState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the world's quick-toggle settings, Mirror (live mirroring paused or not), Frame
 * (Contents plots stand in their carriage shell or not) and Auto fly (falling below the plots' frame
 * turns flying on), so the X menu's ▼ dropdown and the
 * Settings tab's Frame row draw the active state, and the Effortless Building preview mirrors only
 * when the server will.
 *
 * <p>Its own channel for the reason {@link EditorObserversPacket} has one: world state that outlives
 * stepping out of a plot. Sent to everyone online on a change, and once on login.</p>
 */
public record EditorQuickTogglesPacket(boolean mirrorOn, boolean frameOn, boolean autoFlyOn) implements CustomPacketPayload {

    public static final Type<EditorQuickTogglesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_quick_toggles"));

    public static final StreamCodec<FriendlyByteBuf, EditorQuickTogglesPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeBoolean(packet.mirrorOn);
                buf.writeBoolean(packet.frameOn);
                buf.writeBoolean(packet.autoFlyOn);
            },
            buf -> new EditorQuickTogglesPacket(buf.readBoolean(), buf.readBoolean(), buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorQuickTogglesPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorQuickTogglesState.set(packet.mirrorOn, packet.frameOn, packet.autoFlyOn));
    }
}
