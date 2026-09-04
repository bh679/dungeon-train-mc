package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorRoster;
import games.brennan.dungeontrain.editor.EditorStampedCategoryState;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the inventory-style editor screen wants the full template roster.
 *
 * <p>Empty payload; the server replies to {@link IPayloadContext#player()} with an
 * {@link EditorRosterPacket}. OP-only, matching the editor commands the screen goes on to
 * send — a non-OP request is silently dropped.</p>
 */
public record EditorRosterRequestPacket() implements CustomPacketPayload {

    /** Matches {@code EditorCommand.requiresPermissions}. */
    private static final int PERMISSION_LEVEL = 2;

    public static final Type<EditorRosterRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_roster_request"));

    public static final StreamCodec<FriendlyByteBuf, EditorRosterRequestPacket> STREAM_CODEC =
        StreamCodec.unit(new EditorRosterRequestPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorRosterRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!player.hasPermissions(PERMISSION_LEVEL)) return;
            String stamped = EditorStampedCategoryState.current().map(EditorCategory::id).orElse("");
            CarriageDims dims = DungeonTrainWorldData.get(player.server.overworld()).dims();
            DungeonTrainNet.sendTo(player, new EditorRosterPacket(EditorRoster.all(), stamped,
                new EditorRosterPacket.TrainSize(dims.length(), dims.width(), dims.height())));
        });
    }
}
