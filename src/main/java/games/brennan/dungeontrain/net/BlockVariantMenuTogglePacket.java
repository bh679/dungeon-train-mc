package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.BlockVariantMenuController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: open or close the block-variant world-space menu.
 *
 * <p>Sent on Z-tap (open) or by clicking the menu's close button / re-tapping
 * Z while the menu is up (close). The server-side handler raycasts the
 * player's eye to find a flagged block and replies with a
 * {@link BlockVariantSyncPacket} when {@code open} is true; on close it
 * sends the empty sync packet to dismiss the panel client-side.</p>
 *
 * <p>Unlike the {@link PartMenuTogglePacket}, this is a per-event toggle
 * rather than a persistent enable/disable flag — the menu is a tap-to-open
 * UI, not an auto-opening hover overlay.</p>
 */
public record BlockVariantMenuTogglePacket(boolean open) implements CustomPacketPayload {

    public static final Type<BlockVariantMenuTogglePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "block_variant_menu_toggle"));

    public static final StreamCodec<FriendlyByteBuf, BlockVariantMenuTogglePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            BlockVariantMenuTogglePacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
    }

    public static BlockVariantMenuTogglePacket decode(FriendlyByteBuf buf) {
        return new BlockVariantMenuTogglePacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlockVariantMenuTogglePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                BlockVariantMenuController.toggle(sender, packet.open);
            }
        });
    }
}
