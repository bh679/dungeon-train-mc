package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.net.BuilderGhostCellsPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Map;

/**
 * Client-side cache of the Train Builder's ghost blocks, as sent by
 * {@code BuilderGhostCellsPacket}.
 *
 * <p>Same shape as {@link BuilderBoundsState} beside it: volatile snapshots written by the network
 * thread and read by the renderer, replaced wholesale rather than mutated so a render pass can
 * never see a half-updated map. Cleared on logout, so one world's carriage can't ghost into the
 * next.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderGhostCellsState {

    private static volatile Map<BlockPos, BlockState> shell = Map.of();
    private static volatile Map<BlockPos, BlockState> backPad = Map.of();
    private static volatile Map<BlockPos, BlockState> frontPad = Map.of();

    private BuilderGhostCellsState() {}

    public static void set(BuilderGhostCellsPacket packet) {
        shell = Map.copyOf(packet.shell());
        backPad = Map.copyOf(packet.backPad());
        frontPad = Map.copyOf(packet.frontPad());
    }

    public static void clear() {
        shell = Map.of();
        backPad = Map.of();
        frontPad = Map.of();
    }

    /** The carriage skin above the floor, local to the carriage box; empty when it wasn't lifted. */
    public static Map<BlockPos, BlockState> shell() {
        return shell;
    }

    /** The low-X flatbed pad, local to its own min corner. */
    public static Map<BlockPos, BlockState> backPad() {
        return backPad;
    }

    /** The high-X flatbed pad — captured already mirrored, so it is not {@link #backPad} flipped. */
    public static Map<BlockPos, BlockState> frontPad() {
        return frontPad;
    }
}
