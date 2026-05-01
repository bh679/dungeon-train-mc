package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.PartPositionMenuController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: toggle the per-player auto-open flag for the
 * part-position world-space menu. Default is on; clicking the panel's
 * {@code X} or the editor menu's "Part Variant Menu" toggle row sends
 * this. While disabled, the server suppresses
 * {@link PartAssignmentSyncPacket} sync packets (which is what causes
 * the menu to render); enabling it again resumes the sync stream so the
 * menu auto-reopens on the next valid hover.
 */
public record PartMenuTogglePacket(boolean enabled) implements CustomPacketPayload {

    public static final Type<PartMenuTogglePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "part_menu_toggle"));

    public static final StreamCodec<FriendlyByteBuf, PartMenuTogglePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            PartMenuTogglePacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(enabled);
    }

    public static PartMenuTogglePacket decode(FriendlyByteBuf buf) {
        return new PartMenuTogglePacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PartMenuTogglePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                PartPositionMenuController.setMenuEnabled(sender, packet.enabled);
            }
        });
    }
}
