package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderStructureStamp;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.builder.structure.BuilderStructureCells;
import games.brennan.dungeontrain.builder.structure.BuilderStructureRefresh;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: when the Train Builder's structures should re-read the template behind them.
 *
 * <p>The sibling of {@link BuilderStructureModePacket}, and world state for the same reason — while
 * the structures are solid, refreshing them is the server rewriting blocks. See
 * {@link BuilderStructureRefresh}.</p>
 *
 * <p>The value is a request, not an instruction: an unrecognised token falls back to the default
 * rather than being an error, and the authoritative result comes back on the
 * {@link BuilderBoundsPacket} pushed afterwards, which is what lets the button read its own label off
 * the server's answer instead of off the click.</p>
 */
public record BuilderStructureRefreshPacket(String refreshId) implements CustomPacketPayload {

    public static final Type<BuilderStructureRefreshPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(
                DungeonTrain.MOD_ID, "builder_structure_refresh"));

    public static final StreamCodec<FriendlyByteBuf, BuilderStructureRefreshPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> buf.writeUtf(p.refreshId, 16),
            buf -> new BuilderStructureRefreshPacket(buf.readUtf(16)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderStructureRefreshPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ServerLevel level = server.overworld();
            // A client can send anything, and this one clears and stamps blocks.
            if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
                return;
            }

            BuilderStructureRefresh refresh = BuilderStructureRefresh.orDefault(packet.refreshId);
            DungeonTrainWorldData.get(level).setBuilderStructureRefresh(refresh.id());
            // The setting decides whether a cached entry was resolved from the store or from the open
            // build, so entries cached under the old one are the wrong answer under the new one.
            BuilderStructureCells.clear();
            BuilderStructureStamp.apply(level);

            // The button's label and tint read from the state this echoes, so without the resend a
            // click would appear not to have taken.
            BuilderBoundsPacket.sendTo(player, level);
        });
    }
}
