package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayStage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: put relay build {@code relayId} on the editor's Workbench — staged, not installed.
 *
 * <p>The Workbench's counterpart to {@link BuilderProfileDownloadPacket}. No resolution, name or
 * parent: those are decided later, at commit, once the author has stood in the build. {@code ownerUuid}
 * and {@code live} are honoured exactly as the download honours them
 * ({@link BuilderProfileRequestPacket#viewedOwner}, {@link BuilderProfileRequestPacket#liveRequested}).</p>
 *
 * <p>Answered with a {@link BuilderProfileDownloadResultPacket} whose outcome is one of the staging
 * ones ({@code STAGED}, {@code STAGED_NOT_SHOWING}, {@code TOO_TALL}) or a fetch failure; its {@code id}
 * is the staged id.</p>
 */
public record WorkbenchStagePacket(int relayId, String ownerUuid, String ownerName, boolean live)
        implements CustomPacketPayload {

    public WorkbenchStagePacket {
        ownerUuid = ownerUuid == null ? "" : ownerUuid;
        ownerName = ownerName == null ? "" : ownerName;
    }

    public static final Type<WorkbenchStagePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "workbench_stage"));

    public static final StreamCodec<FriendlyByteBuf, WorkbenchStagePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeUtf(packet.ownerUuid, 48);
                buf.writeUtf(packet.ownerName, 64);
                buf.writeBoolean(packet.live);
            },
            buf -> new WorkbenchStagePacket(buf.readVarInt(), buf.readUtf(48), buf.readUtf(64), buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WorkbenchStagePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (player.getServer() == null) return;
            // OP-only, like every editor write.
            if (!player.hasPermissions(2)) return;
            ServerLevel level = player.getServer().overworld();
            String owner = BuilderProfileRequestPacket.viewedOwner(player, packet.ownerUuid);
            boolean live = BuilderProfileRequestPacket.liveRequested(packet.live);
            BuilderRelayStage.stage(player, level, packet.relayId, owner, packet.ownerName, live)
                    .thenAccept(result -> player.getServer().execute(() -> {
                        if (player.hasDisconnected()) return;
                        DungeonTrainNet.sendTo(player, BuilderProfileDownloadResultPacket.of(result, packet.relayId));
                    }));
        });
    }
}
