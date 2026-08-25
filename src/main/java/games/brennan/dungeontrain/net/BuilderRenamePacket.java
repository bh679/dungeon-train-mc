package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
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

import java.util.Locale;

/**
 * Client → server: give the current build a name.
 *
 * <p>Sent by the Save-as screen just before {@link BuilderSavePacket}, which is what turns an
 * unnamed draft into a template on disk. It only records the intended id — the write itself is
 * still the save's job, so a rejected name can't leave a half-saved build behind.</p>
 */
public record BuilderRenamePacket(String name) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<BuilderRenamePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_rename"));

    public static final StreamCodec<FriendlyByteBuf, BuilderRenamePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeUtf(packet.name, 32),
            buf -> new BuilderRenamePacket(buf.readUtf(32))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderRenamePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ServerLevel level = server.overworld();
            if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
                return;
            }
            String id = packet.name.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                return;   // clearing the name would silently turn a saved build back into a draft
            }
            DungeonTrainWorldData.get(level).setBuilderName(id);
            LOGGER.info("[DungeonTrain] Builder: current build named '{}'", id);
        });
    }
}
