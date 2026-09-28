package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: how far the reversed bands behind spawn have slid back ({@code worldgen.ReverseSlide}), so the
 * client's band visuals (sky, fog, upside-down render flip) place them where the server generates them;
 * plus the dev-HUD read-out next to Diff-Car — {@code earned}, the world blocks behind spawn the world's
 * riders have covered on the train, and {@code earning}, whether this player is pushing it right now.
 * Sent on login and whenever any of the three changes for the receiving player.
 */
public record ReverseSlideSyncPacket(long slide, long earned, boolean earning) implements CustomPacketPayload {

    public static final Type<ReverseSlideSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "reverse_slide_sync"));

    public static final StreamCodec<FriendlyByteBuf, ReverseSlideSyncPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeVarLong(packet.slide);
                        buf.writeVarLong(packet.earned);
                        buf.writeBoolean(packet.earning);
                    },
                    buf -> new ReverseSlideSyncPacket(buf.readVarLong(), buf.readVarLong(), buf.readBoolean())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ReverseSlideSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            WorldGenCycle.setReverseSlide(packet.slide);
            games.brennan.dungeontrain.client.VersionHudOverlay.setTrainBack(packet.earned, packet.earning);
        });
    }
}
