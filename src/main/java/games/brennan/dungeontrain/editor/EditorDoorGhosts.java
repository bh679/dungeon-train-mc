package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.EditorDoorGhostsPacket;
import games.brennan.dungeontrain.portal.PortalRoomDoorCells;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
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
 * <h2>Geometry, not a sweep</h2>
 * <p>The contrast with {@link EditorStrayBlocks} is the whole design. A stray is a fact about what an
 * author happened to place, so it has to be looked for; a door is a fact about the plot's box, so it
 * is simply computed — {@link PortalRoomDoorCells#forRoom} over each plot's origin and size. There is
 * no budget, no cursor and no per-chunk cache here, and the answer cannot go stale: it is recomputed
 * from the live plot grid each time the dedup key is compared.</p>
 *
 * <p>Rebuilt rather than cached for the same reason {@code EditorStrayBlocks.startCycle} rebuilds its
 * boxes — rooms resize, and variants are created and deleted. A stale door cell is worse than a
 * missing one: it paints a ghost where an author may legitimately build.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorDoorGhosts {

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
     * world dims — two per room, each tagged with which of the room's two mouths it is. The
     * renderer draws the door's upper half from the block above, so a base is the whole door, and
     * it draws the entry and exit mouths in different colours, so the tag is what tells them
     * apart.
     *
     * <p>Absolute positions, like {@link EditorStrayBlocks#snapshot}: the ghosts are drawn in world
     * space, and a door cell sits one column <i>outside</i> its plot, so it has no plot-local
     * coordinate to be relative to in the first place.</p>
     *
     * <p>A plot whose size has not been primed yet ({@link PortalRoomEditor#primeSizes}) contributes
     * whatever the built-in figure says, which is where the plot is actually standing until the
     * template is read — so the ghosts always agree with the box the author can see.</p>
     */
    public static List<EditorDoorGhostsPacket.Door> snapshot(CarriageDims dims) {
        List<String> names = PortalRoomEditor.names();
        List<EditorDoorGhostsPacket.Door> out = new ArrayList<>(names.size() * 2);
        for (String name : names) {
            BlockPos origin = PortalRoomEditor.plotOrigin(name, dims);
            if (origin == null) continue;
            Vec3i size = PortalRoomEditor.plotSize(name, dims);
            // Clamped to what this room's own width and height can actually spend — the same clamps
            // PortalRoomLayout.roomOrigin applies when the real corridors are stamped, so a ghost
            // never shows a door further off centre, or higher, than the room really can build.
            games.brennan.dungeontrain.portal.PortalRoomSettings settings =
                games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
            int offset = games.brennan.dungeontrain.portal.PortalRoomLayout.clampDoorOffset(
                dims, size.getZ(), settings.doorOffset().value());
            int heightOffset = games.brennan.dungeontrain.portal.PortalRoomLayout.clampDoorHeightOffset(
                dims, size.getY(), settings.doorHeightOffset().value());
            // The exit door on its own clamps, not the entry door's: the two ends may stand apart,
            // and a ghost that drew the far door on the near door's line would be showing the author
            // a mouth the builder is not going to cut there.
            int exitOffset = games.brennan.dungeontrain.portal.PortalRoomLayout.clampDoorOffset(
                dims, size.getZ(), settings.exitDoorOffset().value());
            int exitHeightOffset =
                games.brennan.dungeontrain.portal.PortalRoomLayout.clampDoorHeightOffset(
                    dims, size.getY(), settings.exitDoorHeightOffset().value());
            // doorBases returns the entry end first and the exit end second — its own documented
            // order, which PortalRoomDoorCellsTest pins. Tagged here rather than left to the
            // client to infer from the flattened list's parity: a room with a degenerate box
            // contributes no pair at all, and one missing pair would relabel every door after it.
            List<BlockPos> bases = PortalRoomDoorCells.doorBases(origin, size, offset, heightOffset,
                exitOffset, exitHeightOffset);
            for (int i = 0; i < bases.size(); i++) {
                out.add(new EditorDoorGhostsPacket.Door(bases.get(i), /*entry*/ i == 0));
            }
        }
        return out;
    }

    /**
     * Dedup key for the per-player push — the plot grid itself, one {@code origin/size/doorOffset}
     * per room.
     *
     * <p>Keyed on the boxes rather than on the cells because the boxes are what the cells are a
     * function of, and the string stays short as rooms are added. A resize, a new variant or a
     * deletion all move it; nothing else does, so a steady editor sends no traffic.</p>
     *
     * <p>Door offset is included alongside the box, not folded into it: a change to it moves the
     * ghost cells without moving the plot itself, and a dedup key that missed it would leave the
     * ghosts standing at the old line until something else nudged the box. <b>Both doors', not one
     * door's</b> — the two ends may stand apart, and moving only the exit door has to move the key
     * or the far ghost would never be re-sent.</p>
     */
    public static String key(CarriageDims dims) {
        StringBuilder sb = new StringBuilder();
        for (String name : PortalRoomEditor.names()) {
            BlockPos origin = PortalRoomEditor.plotOrigin(name, dims);
            if (origin == null) continue;
            Vec3i size = PortalRoomEditor.plotSize(name, dims);
            games.brennan.dungeontrain.portal.PortalRoomSettings settings =
                games.brennan.dungeontrain.portal.PortalRoomSettings.of(name);
            int offset = games.brennan.dungeontrain.portal.PortalRoomLayout.clampDoorOffset(
                dims, size.getZ(), settings.doorOffset().value());
            int heightOffset = games.brennan.dungeontrain.portal.PortalRoomLayout.clampDoorHeightOffset(
                dims, size.getY(), settings.doorHeightOffset().value());
            int exitOffset = games.brennan.dungeontrain.portal.PortalRoomLayout.clampDoorOffset(
                dims, size.getZ(), settings.exitDoorOffset().value());
            int exitHeightOffset =
                games.brennan.dungeontrain.portal.PortalRoomLayout.clampDoorHeightOffset(
                    dims, size.getY(), settings.exitDoorHeightOffset().value());
            sb.append(origin.getX()).append(',').append(origin.getY()).append(',')
              .append(origin.getZ()).append('/')
              .append(size.getX()).append(',').append(size.getY()).append(',')
              .append(size.getZ()).append('/').append(offset).append(',').append(heightOffset)
              .append(',').append(exitOffset).append(',').append(exitHeightOffset)
              .append(';');
        }
        return sb.toString();
    }
}
