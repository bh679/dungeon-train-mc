package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderDirtyCheck;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: "do I have unsaved builder changes?"
 *
 * <p>Empty payload — the sender is the question. Asked when the builder pause menu opens, so the
 * Save button can be green before the player wonders whether they need it. Answered with
 * {@link BuilderDirtyPacket#state(int)}; a non-builder world answers zero rather than staying
 * silent, so a stale green button from a previous world can't survive.</p>
 */
public record BuilderDirtyRequestPacket() implements CustomPacketPayload {

    public static final Type<BuilderDirtyRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_dirty_request"));

    public static final StreamCodec<FriendlyByteBuf, BuilderDirtyRequestPacket> STREAM_CODEC =
        StreamCodec.unit(new BuilderDirtyRequestPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderDirtyRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ServerLevel level = server.overworld();
            int dirty = level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)
                    ? BuilderDirtyCheck.dirtyCarriages(level).size()
                    : 0;
            DungeonTrainNet.sendTo(player, BuilderDirtyPacket.state(dirty));
        });
    }
}
