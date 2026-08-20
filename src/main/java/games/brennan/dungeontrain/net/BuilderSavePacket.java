package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.BuilderPhotoRequest;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.builder.BuilderSave;
import games.brennan.dungeontrain.builder.BuilderStructureStamp;
import games.brennan.dungeontrain.builder.structure.BuilderStructureCells;
import games.brennan.dungeontrain.builder.structure.BuilderStructureRefresh;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.train.CarriagePartKind;
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
                refreshStructures(level);
                requestPhoto(player, level, result.written());
                // …and, when the player has opted in, the build goes to their relay profile. After the
                // local write, never instead of it: the file on disk is the build, and an upload that
                // can't happen costs the player nothing.
                BuilderRelayUpload.afterSave(player, level, result.written());
            } else {
                player.sendSystemMessage(Component.translatable(
                        "gui.dungeontrain.builder.save_failed", result.failure())
                        .withStyle(ChatFormatting.RED));
            }
        });
    }

    /**
     * Point the structures at what was just written.
     *
     * <p>The store now holds a different template than it did a moment ago, and anything resolved
     * from it is cached under the assumption that it doesn't change. Dropping the cache is what makes
     * <em>Update on save</em> mean anything; it is skipped only for <em>Don't update</em>, which is
     * the setting whose whole content is "keep showing me what I opened".</p>
     *
     * <p>A direct call rather than a packet: a builder world is single-player by construction — the
     * same thing {@code BuilderStructureCells} already rests on to reach the template stores from the
     * client — so there is one process holding one static cache, and clearing it here reaches both
     * sides. The client picks the change up on its next sweep, within a quarter of a second.</p>
     *
     * <p>Here rather than inside {@code BuilderSave.save}, which is also reached from commands: what
     * a cache of drawn scenery should do is not the saver's business.</p>
     */
    private static void refreshStructures(ServerLevel level) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!BuilderStructureRefresh.orDefault(data.builderStructureRefresh()).refreshesOnSave()) {
            return;
        }
        BuilderStructureCells.clear();
        // Only does anything while the structures are solid; cheap and idempotent otherwise.
        BuilderStructureStamp.apply(level);
    }

    /**
     * Photograph what was just saved.
     *
     * <p>Read straight off the {@link BuilderSave.Written} record rather than re-derived from the
     * world's sub type. The save has already decided which store it wrote to, and that decision is
     * not always what the sub type alone implies — from outside the wall a Carriage Room build is a
     * <em>carriage</em>, so re-deriving filed its picture in the contents directory where the tile
     * showing it would never look. One answer, from the thing that made it.</p>
     *
     * <p>The first written template when a save wrote a family: its name is the build's name, so its
     * picture is the one the library tile shows. {@code onlyIfMissing} is false because an explicit
     * save always re-photographs — what was just written is by definition not what an existing picture
     * shows.</p>
     */
    private static void requestPhoto(ServerPlayer player, ServerLevel level, BuilderSave.Written written) {
        BuilderPhotoRequest request = written.kind() == BuilderPhotoPaths.Kind.TRACK
                ? BuilderPhotoRequest.forTrack(TrackKind.fromId(written.subKind()), written.id()).orElse(null)
                : new BuilderPhotoRequest(written.kind(), written.id(),
                        CarriagePartKind.fromId(written.subKind()));
        if (request == null) {
            return;
        }
        BuilderPhotoPacket.send(player, level, request, false);
    }
}
