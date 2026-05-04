package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Client → server: the worldspace menu's unsaved-changes screen has just
 * drilled in. The server scans every editor plot for dirty (live ≠ disk)
 * and unpromoted (config ≠ source, devmode only) state, then replies
 * with an {@link EditorUnsavedListPacket} addressed to the requesting
 * player.
 *
 * <p>Empty payload — the server already knows who asked from the
 * {@link IPayloadContext#player()} reference, and the scan covers every
 * category at once (since {@link games.brennan.dungeontrain.editor.EditorCategory#clearAllPlots}
 * wipes everything on a category switch, the player needs visibility
 * across categories before deciding).</p>
 */
public record EditorUnsavedRequestPacket() implements CustomPacketPayload {

    public static final Type<EditorUnsavedRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_unsaved_request"));

    public static final StreamCodec<FriendlyByteBuf, EditorUnsavedRequestPacket> STREAM_CODEC =
        StreamCodec.unit(new EditorUnsavedRequestPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorUnsavedRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ServerLevel overworld = server.overworld();
            CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
            List<EditorDirtyCheck.DirtyEntry> rows = EditorDirtyCheck.findDirty(overworld, dims);
            DungeonTrainNet.sendTo(player, new EditorUnsavedListPacket(rows));
        });
    }
}
