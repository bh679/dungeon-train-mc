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
 * Client → server: middle-click on a variant cell — copy that cell's variant
 * list into the player's hotbar as a {@code VARIANT_CLIPBOARD} item.
 *
 * <p>The shortcut half of the block-variant menu's Copy button: the server
 * raycasts the sender's eye to find the cell (no open menu required) and
 * hands back a clipboard item in hand rather than somewhere in the backpack.
 * See {@link BlockVariantMenuController#copyAtCrosshair}.</p>
 *
 * <p>Payload-free — the target cell is whatever the sender is looking at, and
 * the server re-derives it rather than trusting a client-supplied position.
 * The client half ({@code VariantCopyPickClient}) only sends when the
 * crosshair is on a cell with authored variants, so vanilla pick-block is
 * left alone everywhere else.</p>
 */
public record BlockVariantCopyPickPacket() implements CustomPacketPayload {

    public static final Type<BlockVariantCopyPickPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "block_variant_copy_pick"));

    public static final StreamCodec<FriendlyByteBuf, BlockVariantCopyPickPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> {}, buf -> new BlockVariantCopyPickPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlockVariantCopyPickPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                BlockVariantMenuController.copyAtCrosshair(sender);
            }
        });
    }
}
