package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

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
 * REPLACE_BLOCK ops, and pushes {@link #closed()} when the shown stage is deleted.</p>
 */
public record StageBlocksSyncPacket(
    boolean open,
    String stageId,
    String stageName,
    BlockPos anchorPos,
    List<String> blocks,
    List<PartEntry> parts,
    boolean hideUnused
) implements CustomPacketPayload {

    /**
     * One part row: the part's kind ordinal + name, up to {@link #PART_STRIP_CAP} of its block
     * ids, and the real unique count for the {@code +K} label.
     */
    public record PartEntry(byte kindOrd, String partName, List<String> blockIds, int totalUnique) {}

    /** Server-side cap on the aggregated blocks list (the grid shows fewer + "{@code +K} more"). */
    public static final int BLOCKS_CAP = 96;

    /** Server-side cap on ids per part row. */
    public static final int PART_STRIP_CAP = 6;

    public static final Type<StageBlocksSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "stage_blocks_sync"));

    public static final StreamCodec<FriendlyByteBuf, StageBlocksSyncPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            StageBlocksSyncPacket::decode
        );

    /** The close-panel sentinel. */
    public static StageBlocksSyncPacket closed() {
        return new StageBlocksSyncPacket(false, "", "", BlockPos.ZERO, List.of(), List.of(), false);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
        if (!open) return;
        buf.writeUtf(stageId);
        buf.writeUtf(stageName);
        buf.writeBlockPos(anchorPos);
        buf.writeVarInt(blocks.size());
        for (String id : blocks) buf.writeUtf(id);
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
        String stageName = buf.readUtf(64);
        BlockPos anchor = buf.readBlockPos();
        int nBlocks = buf.readVarInt();
        List<String> blocks = new ArrayList<>(nBlocks);
        for (int i = 0; i < nBlocks; i++) blocks.add(buf.readUtf(256));
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
            List.copyOf(blocks), List.copyOf(parts), hideUnused);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StageBlocksSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenu.applySync(packet));
    }
}
