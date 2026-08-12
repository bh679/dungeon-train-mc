package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderDirtyCheck;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderOpenRequest;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.BuilderPhotoRequest;
import games.brennan.dungeontrain.builder.BuilderSpawn;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.builder.BuilderWorldSetup;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Client → server: open an existing template in this builder world.
 *
 * <p>Separate from {@link BuilderNewPacket} rather than a special case of it, because the two want
 * opposite behaviour from the same lookups. New is lenient — an id it can't resolve becomes a bare
 * shell, which is a fine start for a new build. Open names an existing file and points
 * {@code builderName} at it, so the same leniency would arm the next Save to overwrite the very
 * template the builder asked to edit. This packet therefore carries an explicit
 * {@code (kind, id, partKind)} rather than New's mode-dependent {@code copyFrom} string: there is no
 * tag to mis-tag, no arm where an id means something else, and no silent fallback.</p>
 *
 * <p>Unsaved work is the client's question, not this packet's. The Open screen checks
 * {@code BuilderDirtyState} and puts up the Save / Discard / Go Back prompt before sending, the same
 * way {@code BuilderSwitchPacket} is guarded — but the dirty count is re-checked here too, because a
 * client can send anything and this one clears the train.</p>
 */
/**
 * @param stageId the stage the grid was browsing when the tile was clicked, empty when it wasn't
 *                browsing one. Deliberately <em>not</em> part of {@link BuilderOpenRequest}: it does
 *                not name the template being opened, it says which stretch of the game to show it
 *                dressed for — see {@code BuilderWorldSetup.applyOpen}.
 */
public record BuilderOpenPacket(String modeId, String kindId, String id, String partKindId,
                                boolean force, String stageId) implements CustomPacketPayload {

    /** Back-compat 5-arg form: open with no browsed stage, the template's own link deciding. */
    public BuilderOpenPacket(String modeId, String kindId, String id, String partKindId,
                             boolean force) {
        this(modeId, kindId, id, partKindId, force, "");
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<BuilderOpenPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_open"));

    public static final StreamCodec<FriendlyByteBuf, BuilderOpenPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.modeId, 32);
                buf.writeUtf(p.kindId, 16);
                buf.writeUtf(p.id, 64);
                buf.writeUtf(p.partKindId, 16);
                buf.writeBoolean(p.force);
                buf.writeUtf(p.stageId, 32);
            },
            buf -> new BuilderOpenPacket(buf.readUtf(32), buf.readUtf(16), buf.readUtf(64),
                    buf.readUtf(16), buf.readBoolean(), buf.readUtf(32))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderOpenPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ServerLevel level = server.overworld();
            if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
                return;   // a client can send anything; this one clears and stamps blocks
            }

            Optional<BuilderMode> mode = BuilderMode.fromId(packet.modeId);
            if (mode.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Builder open: unknown mode '{}' — ignoring", packet.modeId);
                return;
            }
            Optional<BuilderPhotoPaths.Kind> kind = BuilderPhotoPaths.Kind.fromId(packet.kindId);
            if (kind.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Builder open: unknown kind '{}' — ignoring", packet.kindId);
                return;
            }

            // Backstop for a client whose dirty state has gone stale. The Open screen puts the
            // Save / Discard / Go Back prompt up itself and then sends force — so reaching here
            // unforced with dirty carriages means the two disagree, and the safe reading of a
            // disagreement about unsaved work is "don't discard it".
            //
            // Deliberately a refusal plus a corrected state push, not a BuilderDirtyPacket carrying
            // a target mode: that shape is the *switch* prompt, and answering an open request with
            // a dialog whose buttons re-stamp the world into a different mode would do something
            // the player never asked for.
            if (!packet.force) {
                List<Integer> dirty = BuilderDirtyCheck.dirtyCarriages(level);
                if (!dirty.isEmpty()) {
                    DungeonTrainNet.sendTo(player, BuilderDirtyPacket.state(dirty.size()));
                    player.sendSystemMessage(Component.translatable(
                                    "gui.dungeontrain.builder.open_unsaved")
                            .withStyle(ChatFormatting.RED));
                    return;
                }
            }

            BuilderOpenRequest request = new BuilderOpenRequest(
                    kind.get(), packet.id, CarriagePartKind.fromId(packet.partKindId));
            if (!BuilderWorldSetup.applyOpen(level, mode.get(), request, packet.stageId)) {
                // Loud on purpose. A failed open is the one case where saying nothing would be
                // dangerous: the builder would assume their template loaded and keep working.
                player.sendSystemMessage(Component.translatable(
                                "gui.dungeontrain.builder.open_failed", packet.id)
                        .withStyle(ChatFormatting.RED));
                return;
            }

            BuilderBoundsPacket.sendTo(player, level);
            DungeonTrainNet.sendTo(player, BuilderDirtyPacket.state(0));

            // Stand the player clear of what was just stamped — the opened template may be a
            // different height from whatever was parked here before.
            BlockPos spawn = BuilderSpawn.forLevel(level);
            player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    player.getYRot(), player.getXRot());
            // Hovering, not falling: the spot above may be open void, and creative removes fall
            // damage without stopping the fall.
            BuilderSpawn.startFlying(player);

            // Backfill the library: this template is now in the world, so if it has never been
            // photographed this is a free chance to take the picture. Built from the *opened* id
            // rather than from BuilderPhotoRequest.forSelection, which reads the parked shell — on
            // an Open those differ for rooms and parts, and filing a room's photo under the
            // carriage's name would put the wrong picture on the wrong tile.
            BuilderPhotoPacket.send(player, level,
                    new BuilderPhotoRequest(request.kind(), request.id(), request.partKind()), true);
        });
    }
}
