package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.ContainerContentsMenuController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: open or close the container-contents world-space menu.
 *
 * <p>Sent on C-tap (open) or by the menu's close button. The server-side
 * handler raycasts the player's eye to find a Container BlockEntity and
 * replies with a {@link ContainerContentsSyncPacket}.</p>
 */
public record ContainerContentsMenuTogglePacket(boolean open) implements CustomPacketPayload {

    public static final Type<ContainerContentsMenuTogglePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "container_contents_menu_toggle"));

    public static final StreamCodec<FriendlyByteBuf, ContainerContentsMenuTogglePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            ContainerContentsMenuTogglePacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
    }

    public static ContainerContentsMenuTogglePacket decode(FriendlyByteBuf buf) {
        return new ContainerContentsMenuTogglePacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ContainerContentsMenuTogglePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                ContainerContentsMenuController.toggle(sender, packet.open());
            }
        });
    }
}
