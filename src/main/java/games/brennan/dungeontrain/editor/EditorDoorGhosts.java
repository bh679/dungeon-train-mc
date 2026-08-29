package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalRoomDoorCells;
import games.brennan.dungeontrain.portal.PortalRoomDoorDetection;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Where the two corridor doors fall in every portal-room editor plot, so the client can paint an
 * amber ghost on them.
 *
 * <h2>Why an author needs this</h2>
 * <p>A room plot holds the room and nothing else — {@link PortalRoomEditor#stampPlot} never lays a
 * corridor, because a corridor belongs to a live twin structure rather than to the template. The two
 * openings are therefore invisible while the room is being built, and a wall authored across the
 * walkway centre line yields a room whose door opens onto it. That mistake used to surface only by
 * walking a portal in a real world; the ghosts move it into the plot.</p>
 *
 * <h2>Geometry, not a sweep — but a live one</h2>
 * <p>The contrast with {@link EditorStrayBlocks} is the whole design. A stray is a fact about what an
 * author happened to place, so it has to be looked for; a door <b>cell</b> is a fact about the plot's
 * box, so it is simply computed — {@link PortalRoomDoorCells#forRoom} over each plot's origin and
 * size. There is no budget, no cursor and no per-chunk cache here.</p>
 *
 * <p>Where the door actually <b>sits</b> along that box, though, is a fact about what an author has
 * built — {@link PortalRoomDoorDetection} reads it off a placed door the same way a stray is read off
 * a misplaced block, and {@link #compute} runs that scan fresh every time it is called, for every
 * room, so the ghost tracks a door as it is placed, moved or removed without needing a save first.</p>
 *
 * <p>Rebuilt rather than cached for the same reason {@code EditorStrayBlocks.startCycle} rebuilds its
 * boxes — rooms resize, and variants are created and deleted. A stale door cell is worse than a
 * missing one: it paints a ghost where an author may legitimately build.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorDoorGhosts {

    /** {@link #compute}'s result: the dedup key and the ghost cells it was built from, together so a caller cannot compute one without the other and have them drift apart. */
    public record Result(String key, List<BlockPos> cells) {}

    /** Players who have turned the door ghosts OFF. Default is on. */
    private static final Set<UUID> DISABLED = new HashSet<>();

    private EditorDoorGhosts() {}

    // --- Per-player toggle ---------------------------------------------------

    /** Toggle the amber door ghosts for {@code playerId}. {@code on == true} resumes them. */
    public static void setEnabled(UUID playerId, boolean on) {
        if (on) DISABLED.remove(playerId);
        else DISABLED.add(playerId);
    }

    public static boolean isEnabled(UUID playerId) {
        return !DISABLED.contains(playerId);
    }

    /**
     * Drop every toggle on world quit. The integrated server runs many worlds in one JVM, so without
     * this a player who turned the ghosts off in world A would find them off in world B — the same
     * leak {@link EditorStrayBlocks#onServerStopped} closes.
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        DISABLED.clear();
    }

    // --- Snapshot ------------------------------------------------------------

    /**
     * The lower cell of every corridor door at every registered portal room plot, at the current
     * world dims, plus the dedup key that says whether any of it changed — two cells per room, live
     * off {@code level}.
     *
     * <p>Absolute positions, like {@link EditorStrayBlocks#snapshot}: the ghosts are drawn in world
     * space, and a door cell sits one column <i>outside</i> its plot, so it has no plot-local
     * coordinate to be relative to in the first place.</p>
     *
     * <p>A plot whose size has not been primed yet ({@link PortalRoomEditor#primeSizes}) contributes
     * whatever the built-in figure says, which is where the plot is actually standing until the
     * template is read — so the ghosts always agree with the box the author can see.</p>
     *
     * <p>Runs {@link PortalRoomDoorDetection#detect} for every room on every call — the same scan
     * {@code PortalRoomEditor.save} runs once at save time, run continuously here instead so the
     * ghost tracks a door the moment it is placed. That is two thin, one-block-wide columns per room,
     * not a chunk sweep, so the cost stays well under what {@link EditorStrayBlocks} already pays
     * every tick in the same editor.</p>
     */
    public static Result compute(ServerLevel level, CarriageDims dims) {
        List<String> names = PortalRoomEditor.names();
        List<BlockPos> cells = new ArrayList<>(names.size() * 2);
        StringBuilder key = new StringBuilder();
        for (String name : names) {
            BlockPos origin = PortalRoomEditor.plotOrigin(name, dims);
            if (origin == null) continue;
            Vec3i size = PortalRoomEditor.plotSize(name, dims);
            int offset = PortalRoomDoorDetection.detect(level, origin, size, dims).value();
            cells.addAll(PortalRoomDoorCells.doorBases(origin, size, offset));

            key.append(origin.getX()).append(',').append(origin.getY()).append(',')
               .append(origin.getZ()).append('/')
               .append(size.getX()).append(',').append(size.getY()).append(',')
               .append(size.getZ()).append('/').append(offset).append(';');
        }
        return new Result(key.toString(), cells);
    }
}
