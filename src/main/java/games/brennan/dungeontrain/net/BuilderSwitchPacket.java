package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderDirtyCheck;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderSpawn;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.builder.BuilderWorldSetup;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.network.FriendlyByteBuf;
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
 * Client → server: switch this builder world to a different mode.
 *
 * <p>The server owns the unsaved-changes decision, because it owns the baselines. With
 * {@code force} unset it will refuse to discard work: if any carriage differs from its post-stamp
 * snapshot it replies {@link BuilderDirtyPacket} and leaves the world alone, and the client puts
 * the Save / Discard / Go Back prompt up. {@code force} is what the prompt's Discard sends back.</p>
 */
public record BuilderSwitchPacket(String modeId, boolean force) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<BuilderSwitchPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_switch"));

    public static final StreamCodec<FriendlyByteBuf, BuilderSwitchPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeUtf(packet.modeId, 32);
                buf.writeBoolean(packet.force);
            },
            buf -> new BuilderSwitchPacket(buf.readUtf(32), buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderSwitchPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ServerLevel level = server.overworld();
            if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
                return; // not a builder world — a client can send anything
            }

            Optional<BuilderMode> mode = BuilderMode.fromId(packet.modeId);
            if (mode.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Builder switch: unknown mode id '{}' — ignoring", packet.modeId);
                return;
            }

            if (!packet.force) {
                List<Integer> dirty = BuilderDirtyCheck.dirtyCarriages(level);
                if (!dirty.isEmpty()) {
                    DungeonTrainNet.sendTo(player, new BuilderDirtyPacket(dirty.size(), packet.modeId));
                    return;
                }
            }

            BuilderWorldSetup.restamp(level, mode.get());
            BuilderBoundsPacket.sendTo(player, level);
            BuilderGhostCellsPacket.sendTo(player, level);
            DungeonTrainNet.sendTo(player, BuilderDirtyPacket.state(0));
            // Put the player back on the platform: the mode they switched to may be shorter than
            // the one they were standing in, which would otherwise leave them inside solid blocks
            // or hanging in the air where a carriage used to be.
            var spawn = BuilderSpawn.forLevel(level);
            player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                player.getYRot(), player.getXRot());
            BuilderSpawn.startFlying(player);
        });
    }
}
