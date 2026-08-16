package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.render.SurroundingStructureGhostRenderer;
import games.brennan.dungeontrain.editor.surrounding.SurroundingStructureCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: the resolved GHOST preview blocks for one
 * {@link SurroundingStructureCategory} around the player's current editor plot. Consumed by
 * {@link SurroundingStructureGhostRenderer}, which caches the last snapshot per category and renders
 * each block translucently.
 *
 * <p>{@code entries} empty (or {@code replace = true} with nothing after it) clears that category's
 * ghost cache client-side — see {@link #empty}. A single resolved set larger than {@link #CHUNK_SIZE}
 * is split across several packets by the sender ({@code SurroundingStructureManager}): the first
 * carries {@code replace = true} (client wipes its cache for this category before applying), every
 * following chunk carries {@code replace = false} (appended to what the first chunk started).</p>
 */
public record SurroundingStructureGhostPacket(
    SurroundingStructureCategory category, List<Entry> entries, boolean replace
) implements CustomPacketPayload {

    /** One ghost block: absolute world position + the block state to render translucently. */
    public record Entry(BlockPos pos, BlockState state) {}

    /** Chunk size for very large resolved sets — keeps a single packet well under the payload limit. */
    public static final int CHUNK_SIZE = 4096;

    public static final Type<SurroundingStructureGhostPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "surrounding_structure_ghost"));

    public static final StreamCodec<FriendlyByteBuf, SurroundingStructureGhostPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            SurroundingStructureGhostPacket::decode
        );

    /** The first (or only) chunk for a resolved set — {@code replace = true}. */
    public static SurroundingStructureGhostPacket chunkFirst(SurroundingStructureCategory category, List<Entry> entries) {
        int end = Math.min(CHUNK_SIZE, entries.size());
        return new SurroundingStructureGhostPacket(category, entries.subList(0, end), true);
    }

    /** Clear this category's ghost cache client-side. */
    public static SurroundingStructureGhostPacket empty(SurroundingStructureCategory category) {
        return new SurroundingStructureGhostPacket(category, List.of(), true);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(category);
        buf.writeBoolean(replace);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeBlockPos(e.pos());
            buf.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(e.state()));
        }
    }

    public static SurroundingStructureGhostPacket decode(FriendlyByteBuf buf) {
        SurroundingStructureCategory category = buf.readEnum(SurroundingStructureCategory.class);
        boolean replace = buf.readBoolean();
        int n = buf.readVarInt();
        List<Entry> out = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            BlockPos pos = buf.readBlockPos();
            int stateId = buf.readVarInt();
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(stateId);
            if (state == null) state = Blocks.AIR.defaultBlockState();
            out.add(new Entry(pos, state));
        }
        return new SurroundingStructureGhostPacket(category, out, replace);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SurroundingStructureGhostPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> SurroundingStructureGhostRenderer.applySnapshot(packet));
    }
}
