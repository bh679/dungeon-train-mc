package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtModId;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: the full detail snapshot for the Stage Blocks panel (the "stage V menu") —
 * the stage's aggregated unique block ids, its per-part sections (each with its own capped icon
 * strip), the world anchor the client billboards the panel at (beside the Stages panel), and the
 * current {@code hideUnused} parts-grid filter state (drives the toggle button label).
 *
 * <p>{@code open == false} closes the panel client-side; every other field is empty/ignored.
 * Server replies with this to {@link StagePanelEditPacket} OPEN / TOGGLE_HIDE_UNUSED /
 * SWAP_BLOCK ops, and pushes {@link #closed()} when the shown stage is deleted.</p>
 *
 * <p>The aggregated {@code blocks} are <b>usage-ordered</b> (most-used first) and carry a display
 * {@code count} for the panel's count column — mirroring #636's {@code TemplateBlocksSyncPacket}.</p>
 */
public record StageBlocksSyncPacket(
    boolean open,
    String stageId,
    String stageName,
    BlockPos anchorPos,
    List<BlockCount> blocks,
    int totalBlocks,
    List<PartEntry> parts,
    boolean hideUnused
) implements CustomPacketPayload {

    /** One aggregated block row: registry id + rounded usage count (usage-ordered by the server). */
    public record BlockCount(String blockId, int count) {}

    /**
     * One part row: the part's kind ordinal + name, up to {@link #PART_STRIP_CAP} of its block
     * ids (usage-ordered), and the real unique count for the {@code +K} label.
     */
    public record PartEntry(byte kindOrd, String partName, List<String> blockIds, int totalUnique) {}

    /** Server-side cap on the aggregated blocks list (the grid shows fewer + "{@code +K} more"). */
    public static final int BLOCKS_CAP = 96;

    /** Server-side cap on ids per part row. */
    public static final int PART_STRIP_CAP = 6;

    public static final Type<StageBlocksSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "stage_blocks_sync"));

    public static final StreamCodec<FriendlyByteBuf, StageBlocksSyncPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            StageBlocksSyncPacket::decode
        );

    /** The close-panel sentinel. */
    public static StageBlocksSyncPacket closed() {
        return new StageBlocksSyncPacket(false, "", "", BlockPos.ZERO, List.of(), 0, List.of(), false);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
        if (!open) return;
        buf.writeUtf(stageId);
        buf.writeUtf(stageName);
        buf.writeBlockPos(anchorPos);
        buf.writeVarInt(blocks.size());
        for (BlockCount b : blocks) {
            buf.writeUtf(b.blockId());
            buf.writeVarInt(b.count());
        }
        buf.writeVarInt(totalBlocks);
        buf.writeVarInt(parts.size());
        for (PartEntry p : parts) {
            buf.writeByte(p.kindOrd());
            buf.writeUtf(p.partName());
            buf.writeVarInt(p.blockIds().size());
            for (String id : p.blockIds()) buf.writeUtf(id);
            buf.writeVarInt(p.totalUnique());
        }
        buf.writeBoolean(hideUnused);
    }

    public static StageBlocksSyncPacket decode(FriendlyByteBuf buf) {
        boolean open = buf.readBoolean();
        if (!open) return closed();
        String stageId = buf.readUtf(64);
        // Display names are unbounded server-side (greedyString rename); the sender clamps to 64
        // but decode stays generous so a cap drift can never DecoderException-kick the client.
        String stageName = buf.readUtf(256);
        BlockPos anchor = buf.readBlockPos();
        int nBlocks = buf.readVarInt();
        List<BlockCount> blocks = new ArrayList<>(nBlocks);
        for (int i = 0; i < nBlocks; i++) blocks.add(new BlockCount(buf.readUtf(256), buf.readVarInt()));
        int totalBlocks = buf.readVarInt();
        int nParts = buf.readVarInt();
        List<PartEntry> parts = new ArrayList<>(nParts);
        for (int i = 0; i < nParts; i++) {
            byte kindOrd = buf.readByte();
            String name = buf.readUtf(64);
            int m = buf.readVarInt();
            List<String> ids = new ArrayList<>(m);
            for (int j = 0; j < m; j++) ids.add(buf.readUtf(256));
            int total = buf.readVarInt();
            parts.add(new PartEntry(kindOrd, name, List.copyOf(ids), total));
        }
        boolean hideUnused = buf.readBoolean();
        return new StageBlocksSyncPacket(true, stageId, stageName, anchor,
            List.copyOf(blocks), totalBlocks, List.copyOf(parts), hideUnused);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StageBlocksSyncPacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenu.applySync(packet));
    }
}
