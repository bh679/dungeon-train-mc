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
 * Server → client: the Stage Palette panel snapshot — every stage placeholder block
 * ({@code stage_block_1} … {@code stage_stone_feature_wall}) with the block it currently resolves
 * to for the shown stage, whether that is a user override, the wood / stone family ids (and whether
 * the user locked them), and the world anchor the client billboards the panel at (beside the Stage
 * Blocks panel). Sent alongside every {@link StageBlocksSyncPacket}; {@code open == false} closes.
 */
public record StagePaletteSyncPacket(
    boolean open,
    String stageId,
    BlockPos anchorPos,
    List<Entry> entries,
    String wood,
    String stone,
    boolean woodLocked,
    boolean stoneLocked
) implements CustomPacketPayload {

    /** One placeholder: registry name (no namespace), the block it resolves to, override flag. */
    public record Entry(String name, String blockId, boolean overridden) {}

    public static final Type<StagePaletteSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "stage_palette_sync"));

    public static final StreamCodec<FriendlyByteBuf, StagePaletteSyncPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), StagePaletteSyncPacket::decode);

    /** The close-panel sentinel. */
    public static StagePaletteSyncPacket closed() {
        return new StagePaletteSyncPacket(false, "", BlockPos.ZERO, List.of(), "", "", false, false);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
        if (!open) return;
        buf.writeUtf(stageId);
        buf.writeBlockPos(anchorPos);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.name());
            buf.writeUtf(e.blockId());
            buf.writeBoolean(e.overridden());
        }
        buf.writeUtf(wood);
        buf.writeUtf(stone);
        buf.writeBoolean(woodLocked);
        buf.writeBoolean(stoneLocked);
    }

    public static StagePaletteSyncPacket decode(FriendlyByteBuf buf) {
        boolean open = buf.readBoolean();
        if (!open) return closed();
        String stageId = buf.readUtf(64);
        BlockPos anchor = buf.readBlockPos();
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(new Entry(buf.readUtf(64), buf.readUtf(256), buf.readBoolean()));
        }
        String wood = buf.readUtf(32);
        String stone = buf.readUtf(32);
        boolean woodLocked = buf.readBoolean();
        boolean stoneLocked = buf.readBoolean();
        return new StagePaletteSyncPacket(true, stageId, anchor, List.copyOf(entries), wood, stone,
            woodLocked, stoneLocked);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StagePaletteSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.stagepalette.StagePaletteMenu.applySync(packet));
    }
}
