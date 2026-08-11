package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderSave;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: save the builder's carriage.
 *
 * <p>Empty payload — a builder world holds one template, so there is nothing to identify. This is
 * deliberately not the editor's {@code /dungeontrain save}, which resolves its target from the plot
 * the player is standing in; here the answer doesn't depend on where they are.</p>
 */
public record BuilderSavePacket() implements CustomPacketPayload {

    public static final Type<BuilderSavePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_save"));

    public static final StreamCodec<FriendlyByteBuf, BuilderSavePacket> STREAM_CODEC =
        StreamCodec.unit(new BuilderSavePacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderSavePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ServerLevel level = server.overworld();

            BuilderSave.Result result = BuilderSave.save(level);
            if (result.saved()) {
                player.sendSystemMessage(Component.translatable(
                        "gui.dungeontrain.builder.saved", result.variantId())
                        .withStyle(ChatFormatting.GREEN));
                // Snapshots were re-baselined by the save, so the client's green Save can clear.
                DungeonTrainNet.sendTo(player, BuilderDirtyPacket.state(0));
            } else {
                player.sendSystemMessage(Component.translatable(
                        "gui.dungeontrain.builder.save_failed", result.failure())
                        .withStyle(ChatFormatting.RED));
            }
        });
    }
}
