package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.BuilderOpenRequest;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.BuilderPhotoRequest;
import games.brennan.dungeontrain.builder.BuilderSave;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
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
                requestPhoto(player, level, result.variantId());
            } else {
                player.sendSystemMessage(Component.translatable(
                        "gui.dungeontrain.builder.save_failed", result.failure())
                        .withStyle(ChatFormatting.RED));
            }
        });
    }

    /**
     * Photograph what was just saved.
     *
     * <p>The id is the saved template's own name, not the shell it came from — a save writes the
     * build, so that's what the picture is of. {@code onlyIfMissing} is false: an explicit save
     * always re-photographs, because what was just written is by definition not what an existing
     * picture shows.</p>
     */
    private static void requestPhoto(ServerPlayer player, ServerLevel level, String id) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        BuilderPhotoRequest request = new BuilderPhotoRequest(
                kindOf(data.builderSubType()), id, CarriagePartKind.fromId(data.builderPartKind()));
        BuilderPhotoPacket.send(player, level, request, false);
    }

    /** Which store the save wrote to — the same branch {@code BuilderSave} took. */
    private static BuilderPhotoPaths.Kind kindOf(String subTypeId) {
        if (BuilderNewOptions.SubType.CARRIAGE_ROOM.id().equals(subTypeId)) {
            return BuilderPhotoPaths.Kind.CONTENTS;
        }
        if (BuilderNewOptions.SubType.PARTS.id().equals(subTypeId)) {
            return BuilderPhotoPaths.Kind.PART;
        }
        if (BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(subTypeId)) {
            return BuilderPhotoPaths.Kind.PORTAL_ROOM;
        }
        return BuilderPhotoPaths.Kind.CARRIAGE;
    }
}
