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
 * Client → server: act on one row of the template-blocks menu.
 *
 * <ul>
 *   <li>{@link Op#PREVIEW_BLOCK} — for every block-variant cell whose
 *       candidate list contains {@code blockId}, set that cell's displayed
 *       world block to the matching candidate (transient, world-only). Lets
 *       the author see where a block is used across the template.</li>
 *   <li>{@link Op#SWAP_BLOCK} — replace {@code blockId} everywhere in the
 *       plot with the player's held block: base structure blocks (mutated
 *       in-world, captured by the normal editor Save) and every matching
 *       {@code VariantState} inside every block-variant cell (written to the
 *       sidecar immediately). Orientation properties (facing / half / axis …)
 *       are copied from each old state onto the new block so the shape is
 *       preserved.</li>
 * </ul>
 *
 * <p>{@code key} is the {@link games.brennan.dungeontrain.editor.BlockVariantPlot#key()}
 * of the plot the menu was opened on — the server re-resolves the player's
 * plot and rejects the edit if the keys don't match. Mirrors the OP + plot
 * authorisation model of {@link BlockVariantEditPacket}.</p>
 */
public record TemplateBlocksEditPacket(Op op, String key, String blockId) implements CustomPacketPayload {

    public enum Op { PREVIEW_BLOCK, SWAP_BLOCK }

    public static final Type<TemplateBlocksEditPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "template_blocks_edit"));

    public static final StreamCodec<FriendlyByteBuf, TemplateBlocksEditPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            TemplateBlocksEditPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(op.ordinal());
        buf.writeUtf(key, 128);
        buf.writeUtf(blockId, 256);
    }

    public static TemplateBlocksEditPacket decode(FriendlyByteBuf buf) {
        Op op = Op.values()[buf.readByte()];
        String key = buf.readUtf(128);
        String blockId = buf.readUtf(256);
        return new TemplateBlocksEditPacket(op, key, blockId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TemplateBlocksEditPacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                TemplateBlocksMenuController.applyEdit(sender, packet);
            }
        });
    }
}
