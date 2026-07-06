package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: the per-stage block icon strips shown on the Stages panel rows — for every
 * stage, up to {@link #STRIP_CAP} block registry ids plus the real unique-block count (the client
 * renders a {@code +K} overflow label). Cached client-side in
 * {@link games.brennan.dungeontrain.client.menu.ClientStageBlocks}.
 *
 * <p>Sent on its own channel rather than inside {@link EditorTypeMenusPacket} — block aggregation
 * (NBT palette scans) must never run inside the per-tick menu-snapshot key builder; the server
 * pushes this only when {@code StageBlockIndex.generation()} moves (same separate-channel shape as
 * the package menu's {@link PackageListSyncPacket}). An empty strips list clears the client cache
 * (editor exit).</p>
 *
 * <p>Ids are bare Block registry ids ({@code "minecraft:stone_bricks"}) — Block granularity, not
 * BlockState; icons only need {@code block.asItem()}.</p>
 */
public record StageBlockStripsPacket(List<Strip> strips) implements CustomPacketPayload {

    /** One stage row's strip: capped ids + the real unique count for the {@code +K} label. */
    public record Strip(String stageId, List<String> blockIds, int totalUnique) {}

    /** Server-side cap on ids per strip — the row draws fewer; the rest collapse into {@code +K}. */
    public static final int STRIP_CAP = 8;

    public static final Type<StageBlockStripsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "stage_block_strips"));

    public static final StreamCodec<FriendlyByteBuf, StageBlockStripsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            StageBlockStripsPacket::decode
        );

    public static StageBlockStripsPacket empty() {
        return new StageBlockStripsPacket(List.of());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(strips.size());
        for (Strip s : strips) {
            buf.writeUtf(s.stageId());
            buf.writeVarInt(s.blockIds().size());
            for (String id : s.blockIds()) buf.writeUtf(id);
            buf.writeVarInt(s.totalUnique());
        }
    }

    public static StageBlockStripsPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Strip> strips = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String stageId = buf.readUtf(64);
            int m = buf.readVarInt();
            List<String> ids = new ArrayList<>(m);
            for (int j = 0; j < m; j++) ids.add(buf.readUtf(256));
            int total = buf.readVarInt();
            strips.add(new Strip(stageId, List.copyOf(ids), total));
        }
        return new StageBlockStripsPacket(List.copyOf(strips));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StageBlockStripsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.ClientStageBlocks.apply(packet));
    }
}
