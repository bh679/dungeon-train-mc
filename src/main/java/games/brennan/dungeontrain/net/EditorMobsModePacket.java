package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorMobsModeState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the world's editor Mobs setting ({@code live == false} means Blocks), so the
 * Settings row can draw the active cell and the variant-cell mob ghosts know whether to show.
 *
 * <p>Its own channel for the reason {@link EditorObserversPacket} has one: the setting is world
 * state that must outlive the per-plot {@link EditorStatusPacket}. Sent to everyone online when the
 * setting changes, and once on login.</p>
 */
public record EditorMobsModePacket(boolean live) implements CustomPacketPayload {

    public static final Type<EditorMobsModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_mobs_mode"));

    public static final StreamCodec<FriendlyByteBuf, EditorMobsModePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeBoolean(packet.live),
            buf -> new EditorMobsModePacket(buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorMobsModePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorMobsModeState.setLive(packet.live));
    }
}
