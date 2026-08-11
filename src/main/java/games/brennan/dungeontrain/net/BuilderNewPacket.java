package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.BuilderNewRequest;
import games.brennan.dungeontrain.builder.BuilderPhotoRequest;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.builder.BuilderWorldSetup;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
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
 * Client → server: the Train Builder's <b>New</b> selection.
 *
 * <p>Applies to the world you're standing in, the same way the tile picker does — this is
 * deliberately not {@code dungeontrain editor new}, which resolves a plot from the player's
 * position and so can't work in a builder world at all.</p>
 *
 * <p>An empty {@code name} is normal, not an error: it makes an unnamed draft. Nothing is written
 * to disk until Save, at which point the builder is asked for the name.</p>
 */
public record BuilderNewPacket(String modeId, String subTypeId,
                               String typeId, String copyFrom, String name)
        implements CustomPacketPayload {
    // `typeId`, not `type`: a record component called `type` would collide with
    // CustomPacketPayload.type(), which every payload has to implement. It carries the part kind,
    // which is the one selection the picker itself can't express.

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<BuilderNewPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_new"));

    public static final StreamCodec<FriendlyByteBuf, BuilderNewPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.modeId, 32);
                buf.writeUtf(p.subTypeId, 32);
                buf.writeUtf(p.typeId, 32);
                buf.writeUtf(p.copyFrom, 64);
                buf.writeUtf(p.name, 32);
            },
            buf -> new BuilderNewPacket(buf.readUtf(32), buf.readUtf(32),
                    buf.readUtf(32), buf.readUtf(64), buf.readUtf(32))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderNewPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ServerLevel level = server.overworld();
            if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
                return;   // a client can send anything; this one stamps blocks
            }

            Optional<BuilderMode> mode = BuilderMode.fromId(packet.modeId);
            if (mode.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Builder new: unknown mode '{}' — ignoring", packet.modeId);
                return;
            }
            BuilderNewOptions.SubType subType = subTypeOf(packet.subTypeId);
            BuilderNewOptions.CopySource kind = BuilderNewOptions.copySourceFor(mode.get(), subType);
            Optional<CarriageVariant> shell = resolveShell(level, kind, packet.copyFrom);
            if (shell.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "gui.dungeontrain.builder.save_failed", "no carriage variant")
                        .withStyle(ChatFormatting.RED));
                return;
            }

            // The Whole Carriage picker holds two lists, so a pick has to say which one it came out
            // of before either half can be read. Every other CopySource has one list and no tag.
            BuilderNewOptions.Pick pick = kind == BuilderNewOptions.CopySource.STAGES
                    ? BuilderNewOptions.parsePick(packet.copyFrom)
                    : new BuilderNewOptions.Pick(BuilderNewOptions.PickKind.STAGE, "");

            // A stage pick drives which parts get stamped onto a fresh shell, and BuilderSave links
            // the saved template to it.
            String stage = pick.kind() == BuilderNewOptions.PickKind.STAGE ? pick.id() : "";
            // A saved build is stamped back verbatim instead. Resolved here rather than down in
            // setup so an id with nothing behind it is caught before any blocks move.
            String wholeCarriage = "";
            if (pick.kind() == BuilderNewOptions.PickKind.WHOLE_CARRIAGE) {
                if (WholeCarriageRegistry.find(pick.id()).isEmpty()) {
                    LOGGER.warn("[DungeonTrain] Builder new: no whole carriage '{}' — starting from the bare shell",
                            pick.id());
                } else {
                    wholeCarriage = pick.id();
                }
            }
            // What goes on top of the shell. A whole carriage has nothing on top — its selection is
            // either the shell itself or a saved build that replaces the whole volume.
            String picked = switch (kind) {
                case CONTENTS, PARTS -> packet.copyFrom;
                default -> "";
            };
            BuilderNewRequest request = new BuilderNewRequest(mode.get(), subType, shell.get(),
                    picked, CarriagePartKind.fromId(packet.typeId), packet.name, stage, wholeCarriage);
            if (!BuilderWorldSetup.applyNew(level, request)) {
                return;
            }
            BuilderBoundsPacket.sendTo(player, level);
            DungeonTrainNet.sendTo(player, BuilderDirtyPacket.state(0));

            // Stand the player clear of whatever was just stamped — a shorter mode would otherwise
            // leave them inside solid blocks.
            BlockPos spawn = BuilderWorldLayout.spawnPos(DungeonTrainWorldData.get(level).dims());
            player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    player.getYRot(), player.getXRot());

            // Backfill: the world now holds this template, so if it has never been photographed,
            // this is a free chance to do it. `onlyIfMissing` keeps it to once — browsing the
            // library fills it in rather than rewriting the same picture on every visit.
            BuilderPhotoRequest.forSelection(subType, shell.get().id(), picked, request.partKind())
                    .ifPresent(photo -> BuilderPhotoPacket.send(player, level, photo, true));
        });
    }

    /**
     * The carriage the world gets parked with.
     *
     * <p>Only a {@code CARRIAGES} pick names one. A stage doesn't: it decides which <em>parts</em>
     * get stamped onto whatever shell is there, so picking a stage keeps the shell the world already
     * has. Contents and parts likewise keep the current shell — they're what goes on it, not what it
     * is.</p>
     */
    private static Optional<CarriageVariant> resolveShell(ServerLevel level,
                                                          BuilderNewOptions.CopySource kind,
                                                          String picked) {
        if (kind == BuilderNewOptions.CopySource.CARRIAGES) {
            Optional<CarriageVariant> named = CarriageVariantRegistry.find(picked);
            if (named.isPresent()) {
                return named;
            }
            LOGGER.warn("[DungeonTrain] Builder new: no carriage '{}'; keeping the current shell", picked);
        }
        Optional<CarriageVariant> current = BuilderWorldSetup.currentSource(level);
        if (current.isPresent()) {
            return current;
        }
        List<CarriageVariant> all = CarriageVariantRegistry.allVariants();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Tolerant of an unknown id — an odd sub type shouldn't drop the whole request. */
    private static BuilderNewOptions.SubType subTypeOf(String id) {
        for (BuilderNewOptions.SubType value : BuilderNewOptions.SubType.values()) {
            if (value.id().equals(id)) {
                return value;
            }
        }
        return BuilderNewOptions.SubType.WHOLE_CARRIAGE;
    }
}
