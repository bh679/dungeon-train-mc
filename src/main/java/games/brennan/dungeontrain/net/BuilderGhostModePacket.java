package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderGhostMode;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.builder.BuilderWorldSetup;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * Client → server: cycle what the builder does with the scenery around the build.
 *
 * <p>The pause-menu button's whole job. It needs a server round trip where the old two-state ghost
 * toggle did not, and for one reason: {@link BuilderGhostMode#SOLID} decides whether the shell, the
 * pads and the track are <em>there</em>, not merely whether they are drawn. Blocks existing is not
 * something a client may decide for itself, and it is not something two players in one world can
 * disagree about.</p>
 *
 * <p>Answers with the ghost cells and the bounds, because a mode change can move blocks — entering
 * SOLID puts a carriage's walls back — and the client's caches describe what is out there.</p>
 */
public record BuilderGhostModePacket(String modeId) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<BuilderGhostModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_ghost_mode"));

    public static final StreamCodec<FriendlyByteBuf, BuilderGhostModePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeUtf(packet.modeId, 16),
            buf -> new BuilderGhostModePacket(buf.readUtf(16))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderGhostModePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return;
            }
            MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }
            ServerLevel level = server.overworld();
            if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
                return;   // not a builder world — a client can send anything
            }
            BuilderGhostMode mode = BuilderGhostMode.fromId(packet.modeId).orElse(null);
            if (mode == null) {
                LOGGER.warn("[DungeonTrain] Builder ghost mode: unknown id '{}' — ignoring",
                        packet.modeId);
                return;
            }

            BuilderWorldSetup.setGhostMode(level, mode);
            BuilderGhostCellsPacket.sendTo(player, level);
        });
    }
}
