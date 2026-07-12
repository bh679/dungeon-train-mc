package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.editor.TemplateBlocksMenuController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;

/**
 * Client → server: open or close the template-blocks world-space menu.
 *
 * <p>Sent when the player presses the (rebindable) template-blocks key or
 * runs {@code /dungeontrain editor blocks}. On open the server resolves the
 * editor plot the player stands in, tallies every block used (base structure
 * + block-variant candidates) and replies with a
 * {@link TemplateBlocksSyncPacket}; on close it sends the empty sync to
 * dismiss the panel client-side. Mirrors {@link BlockVariantMenuTogglePacket}.</p>
 */
public record TemplateBlocksMenuTogglePacket(boolean open) implements CustomPacketPayload {

    public static final Type<TemplateBlocksMenuTogglePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "template_blocks_menu_toggle"));

    public static final StreamCodec<FriendlyByteBuf, TemplateBlocksMenuTogglePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            TemplateBlocksMenuTogglePacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
    }

    public static TemplateBlocksMenuTogglePacket decode(FriendlyByteBuf buf) {
        return new TemplateBlocksMenuTogglePacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TemplateBlocksMenuTogglePacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                TemplateBlocksMenuController.toggle(sender, packet.open);
            }
        });
    }
}
