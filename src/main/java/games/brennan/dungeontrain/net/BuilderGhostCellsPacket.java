package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderGhostCells;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.client.builder.BuilderGhostCellsState;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server → client: the blocks the Train Builder draws as ghosts rather than standing up for real.
 *
 * <p>The client cannot work these out for itself, and for two different reasons. The flatbed pads
 * are a half-slice of the {@code FLATBED} structure template, which lives behind {@code ServerLevel};
 * and the carriage shell has been <em>erased</em> from the world by the time anyone would look for
 * it — that erasure is the whole point (see {@link BuilderGhostCells}), and what is not there cannot
 * be swept for.</p>
 *
 * <p>Sent at the same moments as {@link BuilderBoundsPacket} and for the same reason: the ghosts and
 * the volumes change together, at entry, New, Open, and a mode switch. A separate payload rather
 * than more fields on that one because this is kilobytes of block data next to a handful of ints,
 * and the bounds packet is resent by things — the mirror command — that cannot change a ghost.</p>
 *
 * <p><b>Block states go over the wire as registry ids</b>, the way vanilla's own chunk packets send
 * them: both ends are the same session with the same registry, so an id is exact and a few bytes.
 * The <em>persisted</em> copy in {@link DungeonTrainWorldData} is deliberately not this — see
 * {@code BuilderGhostCells.toTag}, which writes the block's name so the blob survives a
 * renumbering.</p>
 */
public record BuilderGhostCellsPacket(Map<BlockPos, BlockState> shell,
                                      Map<BlockPos, BlockState> backPad,
                                      Map<BlockPos, BlockState> frontPad)
        implements CustomPacketPayload {

    /**
     * Guards against a malformed or hostile payload allocating an unbounded map. Matches the
     * per-frame cell ceiling the ghost renderers hold, so a payload this size is already more than
     * anything will be drawn from it.
     */
    private static final int MAX_CELLS = 40_000;

    public static final Type<BuilderGhostCellsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_ghost_cells"));

    public static final StreamCodec<FriendlyByteBuf, BuilderGhostCellsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                writeCells(buf, packet.shell);
                writeCells(buf, packet.backPad);
                writeCells(buf, packet.frontPad);
            },
            buf -> new BuilderGhostCellsPacket(readCells(buf), readCells(buf), readCells(buf))
        );

    private static void writeCells(FriendlyByteBuf buf, Map<BlockPos, BlockState> cells) {
        int count = Math.min(cells.size(), MAX_CELLS);
        buf.writeVarInt(count);
        int written = 0;
        for (Map.Entry<BlockPos, BlockState> entry : cells.entrySet()) {
            if (written++ >= count) {
                break;
            }
            BlockPos pos = entry.getKey();
            // Local coordinates, so never negative and always small — a varint each.
            buf.writeVarInt(pos.getX());
            buf.writeVarInt(pos.getY());
            buf.writeVarInt(pos.getZ());
            buf.writeVarInt(Block.getId(entry.getValue()));
        }
    }

    private static Map<BlockPos, BlockState> readCells(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_CELLS);
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>(Math.max(16, count));
        for (int i = 0; i < count; i++) {
            BlockPos pos = new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
            BlockState state = Block.stateById(buf.readVarInt());
            if (!state.isAir()) {
                cells.put(pos, state);
            }
        }
        return cells;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Build the packet for a player's current world — empty outside a builder world. */
    public static BuilderGhostCellsPacket forLevel(ServerLevel overworld) {
        if (!overworld.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return empty();
        }
        BuilderGhostCells.Cells cells = BuilderGhostCells.fromTag(
                DungeonTrainWorldData.get(overworld).builderGhostCells(),
                overworld.holderLookup(Registries.BLOCK));
        return new BuilderGhostCellsPacket(cells.shell(), cells.backPad(), cells.frontPad());
    }

    /** An empty payload, which is how a world with nothing ghosted tells the client to stop. */
    public static BuilderGhostCellsPacket empty() {
        return new BuilderGhostCellsPacket(Map.of(), Map.of(), Map.of());
    }

    public static void sendTo(ServerPlayer player, ServerLevel overworld) {
        DungeonTrainNet.sendTo(player, forLevel(overworld));
    }

    public static void handle(BuilderGhostCellsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BuilderGhostCellsState.set(packet));
    }
}
