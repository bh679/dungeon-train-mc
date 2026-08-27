package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.EditorPlotSnapshots;
import games.brennan.dungeontrain.editor.PortalRoomEditor;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Change the footprint of the portal room open in a builder world.
 *
 * <p>A room is the one thing the builder edits whose size is the author's rather than a constant —
 * every carriage plot is {@link CarriageDims} — so it is also the one thing that needs a control
 * like this at all. The dial matters most on length, which is how far a player walks between the
 * two corridor mouths, and is the reason a portal room has a variable size in the first place.</p>
 *
 * <p>Deliberately not {@code PortalRoomEditor.setSize}. That one goes through {@code relayout},
 * which erases and restamps every plot the change shifts along the editor's shared column. A
 * builder world holds one room standing on its own, so there is no row to keep packed — and running
 * the editor's version here would re-lay out the <em>editor's</em> plots in a world that has
 * none.</p>
 */
public final class BuilderRoomResize {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Which axis of the room a resize addresses. */
    public enum Axis {
        LENGTH, HEIGHT, WIDTH;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Axis fromId(String raw) {
            if (raw == null) {
                return null;
            }
            String needle = raw.trim().toUpperCase(Locale.ROOT);
            for (Axis a : values()) {
                if (a.name().equals(needle)) {
                    return a;
                }
            }
            return null;
        }
    }

    private BuilderRoomResize() {}

    /**
     * Restamp the open room with one axis set to {@code value}.
     *
     * <p>Keeps what was authored, the same way the editor's resize does: the saved template is laid
     * back over the new box — clipped, so shrinking crops rather than spilling — and only the space
     * the resize newly exposed comes back as the built-in room. The change is a
     * {@link PortalRoomSizes#pending} state until a Save writes a template of that size.</p>
     *
     * <p>The old box is cleared <b>before</b> the new one is stamped, and at the old size. Shrinking
     * otherwise leaves the outer shell of the larger room standing around the smaller one, because
     * a stamp only writes the cells inside its own box.</p>
     *
     * @return the size actually applied after {@link PortalRoomLayout#clampSize}, or null when this
     *         world has no room open
     */
    public static Vec3i apply(ServerLevel level, Axis axis, int value) {
        if (axis == null) {
            return null;
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(data.builderSubType())) {
            return null;
        }
        String name = data.builderName();
        if (name == null || name.isEmpty()) {
            return null;
        }

        CarriageDims dims = data.dims();
        Vec3i current = PortalRoomSizes.sizeOf(name, dims);
        Vec3i wanted = switch (axis) {
            case LENGTH -> new Vec3i(value, current.getY(), current.getZ());
            case HEIGHT -> new Vec3i(current.getX(), value, current.getZ());
            case WIDTH -> new Vec3i(current.getX(), current.getY(), value);
        };
        Vec3i clamped = PortalRoomLayout.clampSize(dims, wanted);
        if (clamped.equals(current)) {
            return clamped;   // already at the limit — nothing to restamp
        }

        BlockPos oldOrigin = BuilderWorldLayout.portalRoomOrigin(current);
        PortalClear.clearBoxRelit(level, BoundingBox.fromCorners(oldOrigin,
                oldOrigin.offset(current.getX() - 1, current.getY() - 1, current.getZ() - 1)),
                PortalCorridorMask.NONE);

        PortalRoomSizes.pending(name, clamped);

        BlockPos origin = BuilderWorldLayout.portalRoomOrigin(clamped);
        PortalRoomEditor.stampRoomInto(level, origin, clamped, name, dims, /*outline*/ false);

        // Fresh baseline at the new box, or the restamp itself reads as unsaved work — and the
        // baseline must be the box that is actually there now, not the one Save last wrote.
        EditorPlotSnapshots.capture(BuilderDirtyCheck.snapshotKey(0), level, origin,
                clamped.getX(), clamped.getY(), clamped.getZ());

        LOGGER.info("[DungeonTrain] Builder resize: portal room '{}' restamped at {}x{}x{} ({} -> {})",
                name, clamped.getX(), clamped.getY(), clamped.getZ(), axis.id(), value);
        return clamped;
    }
}
