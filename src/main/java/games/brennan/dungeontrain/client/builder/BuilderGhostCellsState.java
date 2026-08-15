package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderGhostMode;
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
    private static volatile Map<BlockPos, BlockState> track = Map.of();
    private static volatile BlockPos trackOrigin = BlockPos.ZERO;
    private static volatile BuilderGhostMode mode = BuilderGhostMode.DEFAULT;

    private BuilderGhostCellsState() {}

    public static void set(BuilderGhostCellsPacket packet) {
        shell = Map.copyOf(packet.shell());
        backPad = Map.copyOf(packet.backPad());
        frontPad = Map.copyOf(packet.frontPad());
        track = Map.copyOf(packet.track());
        trackOrigin = packet.trackOrigin();
        mode = BuilderGhostMode.orDefault(packet.modeId());
    }

    public static void clear() {
        shell = Map.of();
        backPad = Map.of();
        frontPad = Map.of();
        track = Map.of();
        trackOrigin = BlockPos.ZERO;
        mode = BuilderGhostMode.DEFAULT;
    }

    /**
     * What this world does with the scenery around the build.
     *
     * <p>The server's answer, not a local preference: {@code SOLID} means the blocks are really
     * there, so the client cannot hold an opinion of its own about it. The pause-menu button reads
     * this to know what it is showing and what to cycle to.</p>
     */
    public static BuilderGhostMode mode() {
        return mode;
    }

    /** The bed and rails down the corridor, local to {@link #trackOrigin()}. */
    public static Map<BlockPos, BlockState> track() {
        return track;
    }

    /** Where the lifted track is measured from — its own origin, not the carriage's. */
    public static BlockPos trackOrigin() {
        return trackOrigin;
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
