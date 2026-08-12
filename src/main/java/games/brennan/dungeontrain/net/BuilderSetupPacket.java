package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderCinematicService;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderSpawn;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.builder.BuilderWorldSetup;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Client → server: which Train Builder tile the player clicked.
 *
 * <p>The mode is a client-side choice — it's whichever tile was pressed on the title screen — and
 * the world itself carries no record of it, so this packet is the only way the server learns how
 * much train to park. It arrives once, shortly after join, and the setup it triggers is
 * idempotent, so a duplicate or a rejoin costs nothing.</p>
 *
 * <p>Ignored outside a {@code dungeontrain:builder} world: a client can send anything, and this
 * one stamps 180 000 blocks, so {@link BuilderWorldSetup#setupIfNeeded} checks the dimension type
 * before it does any work.</p>
 */
public record BuilderSetupPacket(String modeId) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<BuilderSetupPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_setup"));

    public static final StreamCodec<FriendlyByteBuf, BuilderSetupPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeUtf(packet.modeId, 32),
            buf -> new BuilderSetupPacket(buf.readUtf(32))
        );

    public BuilderSetupPacket(BuilderMode mode) {
        this(mode.id());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderSetupPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;

            Optional<BuilderMode> mode = BuilderMode.fromId(packet.modeId);
            if (mode.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Builder setup: unknown mode id '{}' — ignoring", packet.modeId);
                return;
            }

            ServerLevel level = server.overworld();
            if (!BuilderWorldSetup.setupIfNeeded(level, mode.get())) {
                return;
            }
            // Stand the player on the platform. The world spawn was anchored at server start —
            // before this packet arrived and therefore before there was anything to stand on —
            // so on a fresh builder world the player is currently in mid-air over the void.
            CarriageDims dims = DungeonTrainWorldData.get(level).dims();
            // Far enough back that the whole run fits on screen — a three-carriage train seen
            // from the old fixed margin was a wall of hull.
            BlockPos spawn = BuilderSpawn.forLevel(level);
            // Facing the template, not vanilla's yaw 0 — the spawn sits on the +Z side of the
            // train, so yaw 0 (straight down +Z) would put the build squarely behind them.
            float[] facing = BuilderCinematicService.facingFrom(
                level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
            level.setDefaultSpawnPos(spawn, facing[0]);
            player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                facing[0], facing[1]);
            // First entry, and the case this exists for: in Train Dimensions there is no platform
            // under that spot at all.
            BuilderSpawn.startFlying(player);
            // The join-time bounds packet was sent before this stamp existed, so resend now that
            // the carriages (and therefore the build volumes) are real.
            BuilderBoundsPacket.sendTo(player, level);
            // The template exists as of this tick and the player is stood in front of it — show
            // it to them. (The reopen path is triggered from PlayerJoinEvents instead.)
            BuilderCinematicService.playNow(player);
        });
    }
}
