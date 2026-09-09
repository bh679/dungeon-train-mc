package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.EditorDoorGhostsPacket;
import games.brennan.dungeontrain.portal.PortalRoomDoorCells;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageDoorCells;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
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
 * Where a plot's two doorways fall, so the client can name them — for the portal rooms, the
 * carriages, and the contents plots alike.
 *
 * <h2>Why an author needs this</h2>
 * <p>Two different reasons, which is why one class answers for three categories. A portal room's
 * doorways are <b>invisible</b> in the plot; a carriage's are visible but <b>anonymous</b> — the two
 * ends look alike, and nothing in the plot says which one a player arrives through. Both are the
 * same question ("which end is which?") asked of the same geometry, so both are answered from the
 * plot's own box.</p>
 *
 * <h2>The portal case</h2>
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
        return portalSnapshot(dims);
    }

    /**
     * The door markers for whichever category is stamped — the portal rooms' corridor mouths, or a
     * carriage / contents row's two end doorways.
     *
     * <p>Empty for any other category: tracks and architecture have no two ends to name, and a
     * marker painted over one of those plots would stand on a line nothing is ever cut on.</p>
     */
    public static List<EditorDoorGhostsPacket.Door> snapshot(EditorCategory category, CarriageDims dims) {
        if (category == null) return List.of();
        return switch (category) {
            case PORTALS -> portalSnapshot(dims);
            case CARRIAGES -> carriageSnapshot(dims);
            case CONTENTS -> contentsSnapshot(dims);
            default -> List.of();
        };
    }

    /**
     * The two end doorways of every carriage variant's plot.
     *
     * <p>Each plot is measured with its own {@link CarriageEditor#plotDims} rather than the world's
     * dims — the portal-corridor variant's plot is the longer of the two, and the world figure would
     * put its exit marker several blocks short of the end the doorway is actually cut in.</p>
     */
    private static List<EditorDoorGhostsPacket.Door> carriageSnapshot(CarriageDims dims) {
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        List<EditorDoorGhostsPacket.Door> out =
            new ArrayList<>(variants.size() * CarriageDoorCells.DOORS_PER_CARRIAGE);
        for (CarriageVariant variant : variants) {
            BlockPos origin = CarriageEditor.plotOrigin(variant, dims);
            if (origin == null) continue;
            addCarriageDoors(out, origin, CarriageEditor.plotDims(variant, dims));
        }
        return out;
    }

    /**
     * The two end doorways of every contents plot — group parents in the {@code +X} row and their
     * sub-variants in the {@code +Z} columns alike, since {@link CarriageContentsEditor#plotOrigin}
     * resolves both.
     */
    private static List<EditorDoorGhostsPacket.Door> contentsSnapshot(CarriageDims dims) {
        List<CarriageContents> all = CarriageContentsRegistry.allContents();
        List<EditorDoorGhostsPacket.Door> out =
            new ArrayList<>(all.size() * CarriageDoorCells.DOORS_PER_CARRIAGE);
        for (CarriageContents contents : all) {
            BlockPos origin = CarriageContentsEditor.plotOrigin(contents, dims);
            if (origin == null) continue;
            addCarriageDoors(out, origin, CarriageContentsEditor.plotDims(contents, dims));
        }
        return out;
    }

    /**
     * Append one plot's pair of markers. {@code model} is false: a carriage doorway is authored by
     * the person standing in the plot, so what is added is the naming — outline and word — not a
     * ghost door standing in their own blocks.
     *
     * <p>{@link CarriageDoorCells#doorBases} returns the {@code -X} end first, which is the end a
     * player walking the train arrives through — the same "entry mouth is the near column" order
     * {@link PortalRoomDoorCells#doorBases} uses, so one tag means one thing everywhere.</p>
     */
    private static void addCarriageDoors(List<EditorDoorGhostsPacket.Door> out, BlockPos origin,
                                         CarriageDims box) {
        List<BlockPos> bases = CarriageDoorCells.doorBases(origin, box);
        for (int i = 0; i < bases.size(); i++) {
            out.add(new EditorDoorGhostsPacket.Door(bases.get(i), /*entry*/ i == 0, /*model*/ false));
        }
    }

    private static List<EditorDoorGhostsPacket.Door> portalSnapshot(CarriageDims dims) {
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
                out.add(new EditorDoorGhostsPacket.Door(bases.get(i), /*entry*/ i == 0, /*model*/ true));
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
        return portalKey(dims);
    }

    /**
     * Dedup key for whichever category is stamped. Prefixed with the category name, so stepping
     * from the carriage row to the contents row always re-pushes even in the unlikely event the two
     * grids hash to the same string.
     *
     * <p>Carriage and contents plots key on {@code origin/box} alone — they have no authored door
     * offset to fold in, the doorway line being a function of the box the same way the plot grid is.
     * Both editors resolve an origin through a memoised slot-index map, so this stays a map lookup
     * per plot on the tick it is compared.</p>
     */
    public static String key(EditorCategory category, CarriageDims dims) {
        if (category == null) return "";
        return switch (category) {
            case PORTALS -> "portals/" + portalKey(dims);
            case CARRIAGES -> "carriages/" + carriageKey(dims);
            case CONTENTS -> "contents/" + contentsKey(dims);
            default -> "";
        };
    }

    private static String carriageKey(CarriageDims dims) {
        StringBuilder sb = new StringBuilder();
        for (CarriageVariant variant : CarriageVariantRegistry.allVariants()) {
            BlockPos origin = CarriageEditor.plotOrigin(variant, dims);
            if (origin == null) continue;
            appendBox(sb, origin, CarriageEditor.plotDims(variant, dims));
        }
        return sb.toString();
    }

    private static String contentsKey(CarriageDims dims) {
        StringBuilder sb = new StringBuilder();
        for (CarriageContents contents : CarriageContentsRegistry.allContents()) {
            BlockPos origin = CarriageContentsEditor.plotOrigin(contents, dims);
            if (origin == null) continue;
            appendBox(sb, origin, CarriageContentsEditor.plotDims(contents, dims));
        }
        return sb.toString();
    }

    private static void appendBox(StringBuilder sb, BlockPos origin, CarriageDims box) {
        sb.append(origin.getX()).append(',').append(origin.getY()).append(',')
          .append(origin.getZ()).append('/')
          .append(box.length()).append(',').append(box.height()).append(',')
          .append(box.width()).append(';');
    }

    private static String portalKey(CarriageDims dims) {
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
