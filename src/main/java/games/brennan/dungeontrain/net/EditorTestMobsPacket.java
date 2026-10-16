package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorTestMobsState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the world's editor Test Mobs setting ({@code live == false} means Frozen), so the
 * Settings row can draw the active cell. Its own channel for the reason {@link EditorMobsModePacket}
 * has one — world state, sent to everyone online when it changes and once on login.
 */
public record EditorTestMobsPacket(boolean live) implements CustomPacketPayload {

    public static final Type<EditorTestMobsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_test_mobs"));

    public static final StreamCodec<FriendlyByteBuf, EditorTestMobsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeBoolean(packet.live),
            buf -> new EditorTestMobsPacket(buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorTestMobsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorTestMobsState.setLive(packet.live));
    }
}
