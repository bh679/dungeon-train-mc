package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.ContainerHotkeyState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: held / released state of the "container contents" key
 * (default {@code C}). Mirrors {@link VariantHotkeyPacket}; the server
 * stores the held flag in {@link ContainerHotkeyState}.
 *
 * <p>Sent only on transition (not per tick). Cleaned up on logout via the
 * listener inside {@link ContainerHotkeyState}.</p>
 */
public record ContainerHotkeyPacket(boolean held) implements CustomPacketPayload {

    public static final Type<ContainerHotkeyPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "container_hotkey"));

    public static final StreamCodec<FriendlyByteBuf, ContainerHotkeyPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            ContainerHotkeyPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(held);
    }

    public static ContainerHotkeyPacket decode(FriendlyByteBuf buf) {
        return new ContainerHotkeyPacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ContainerHotkeyPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                ContainerHotkeyState.setHeld(sender, packet.held());
            }
        });
    }
}
