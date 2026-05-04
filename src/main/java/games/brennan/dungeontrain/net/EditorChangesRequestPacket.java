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
 * Client → server: the unsaved-changes screen has drilled into a
 * specific template's View column. The server walks the post-stamp
 * snapshot for that template and emits the per-position diff via
 * {@link EditorChangesListPacket}, addressed to the requesting player.
 */
public record EditorChangesRequestPacket(String categoryId, String modelId) implements CustomPacketPayload {

    public static final Type<EditorChangesRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_changes_request"));

    public static final StreamCodec<FriendlyByteBuf, EditorChangesRequestPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> { buf.writeUtf(packet.categoryId, 32); buf.writeUtf(packet.modelId, 96); },
            buf -> new EditorChangesRequestPacket(buf.readUtf(32), buf.readUtf(96))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorChangesRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ServerLevel overworld = server.overworld();
            CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
            List<EditorDirtyCheck.DiffEntry> changes =
                EditorDirtyCheck.findChanges(overworld, dims, packet.categoryId, packet.modelId);
            DungeonTrainNet.sendTo(player, new EditorChangesListPacket(packet.categoryId, packet.modelId, changes));
        });
    }
}
