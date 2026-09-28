package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.EditorMirrorLiveHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Carries the editor's live mirroring over to Effortless Building's builds, so a line, wall or
 * cube drawn on one side of a mirror-enabled template appears on the other side too.
 *
 * <p><b>Why this is needed.</b> {@link EditorMirrorLiveHandler} mirrors on NeoForge block events,
 * and Effortless Building fires none — it writes with raw {@code setBlock} / {@code destroyBlock}
 * from its packet handlers and its own {@code UndoManager} (see {@link EffortlessBuildingHistory}
 * for the same gap in the undo history).</p>
 *
 * <p><b>How.</b> The mixins wrap each of those writes and {@link #record} the cell; the packet
 * handler's {@code RETURN} injector then {@link #flush}es, mirroring every recorded cell through
 * {@link EditorMirrorLiveHandler#mirrorAt} — the hand-edit path, so plot resolution, bounds and
 * sidecar markers all match. Mirroring after the action rather than per write keeps Effortless
 * Building's own "can I place here" checks and item counting seeing exactly the world they would
 * without DT. The flush runs before {@link EffortlessBuildingHistory#end}, so the mirrored half
 * lands in the same Ctrl+Z step as the build.</p>
 *
 * <p>Each cell's state is read live at flush time (air = a break). When a build wrote both a cell
 * and its image, the first-recorded cell wins and the image is re-derived from it, which is the
 * same "source wins" outcome a hand edit gives.</p>
 *
 * <p><b>Server thread only</b> (Effortless Building enqueues its packet work), hence a plain
 * {@link HashMap}. <b>Fails open</b>: a mirror that cannot be applied never costs the build.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EffortlessBuildingMirror {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** player → cells written by the action currently running, in write order. */
    private static final Map<UUID, Set<BlockPos>> PENDING = new HashMap<>();

    /**
     * The player whose Effortless Building action is running. The wrapped writes inside
     * {@code UndoManager} carry no player argument, so they are attributed through this.
     */
    @Nullable
    private static UUID active;

    private EffortlessBuildingMirror() {}

    /** Open an empty record for {@code player}'s action, dropping any stale one. */
    public static void begin(ServerPlayer player) {
        if (player == null) return;
        begin(player.getUUID());
    }

    static void begin(UUID id) {
        PENDING.put(id, new LinkedHashSet<>());
        active = id;
    }

    /** Note a cell the running action wrote. No-op when no action is open. */
    public static void record(BlockPos pos) {
        if (active == null || pos == null) return;
        Set<BlockPos> cells = PENDING.get(active);
        if (cells != null) cells.add(pos.immutable());
    }

    /** Take and clear {@code id}'s recorded cells; empty when nothing was open. */
    static Set<BlockPos> take(UUID id) {
        if (id.equals(active)) active = null;
        Set<BlockPos> cells = PENDING.remove(id);
        return cells == null ? Set.of() : cells;
    }

    /** Mirror every cell the action wrote, then close the record. */
    public static void flush(ServerPlayer player) {
        if (player == null) return;
        Set<BlockPos> cells = take(player.getUUID());
        if (cells.isEmpty()) return;
        ServerLevel level = player.serverLevel();
        try {
            for (BlockPos pos : cells) {
                BlockState state = level.getBlockState(pos);
                EditorMirrorLiveHandler.mirrorAt(level, pos, state.isAir() ? null : state);
            }
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Could not mirror an Effortless Building action of {} cells: {}",
                cells.size(), t.toString());
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        take(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        active = null;
    }
}
