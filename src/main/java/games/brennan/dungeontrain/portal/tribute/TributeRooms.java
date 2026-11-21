package games.brennan.dungeontrain.portal.tribute;

import games.brennan.dungeontrain.editor.PortalRoomTemplateStore;
import games.brennan.dungeontrain.portal.PortalStructure;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;

/**
 * A player's <b>tribute room</b>: the one dimensional room that is theirs, visited after every death and
 * kept between runs ({@link TributeRoomStore}).
 *
 * <p>It is an ordinary portal room on the inside — the bundled {@value #TEMPLATE} template is its
 * starting shape, at weight 0 so the room lottery never stamps the raw default — and the player's own
 * copy of it travels as a {@link games.brennan.dungeontrain.portal.PortalRoomBlob}, laid by the same
 * stamp a drifted (shared) room uses. One size for everyone: a blob is only ever laid into the box
 * the template was authored at, so every player's room is interchangeable with every other's.</p>
 */
public final class TributeRooms {

    /** The bundled room every tribute room starts as: {@code data/dungeontrain/portals/room/home.nbt}. */
    public static final String TEMPLATE = "tribute";

    /**
     * The pair key a death visit stamps under. The same key the portal test rig uses: both stand one
     * structure in a private band of the basement, and neither is a pair the train knows. They are kept
     * apart by Z ({@link TributeRoomVisitSite#Z_BAND}), not by key.
     */
    public static final int PAIR_KEY = 0;

    private TributeRooms() {}

    /** Whether {@code structure} is somebody's tribute room rather than the raw template or another room. */
    public static boolean isHomeStructure(PortalStructure structure) {
        return structure != null && TEMPLATE.equals(structure.roomName())
            && structure.blob() != null && !structure.blob().owner().isEmpty();
    }

    /** The one box every tribute room is laid into — the template's own size. */
    public static Vec3i size(ServerLevel level, CarriageDims dims) {
        return PortalRoomTemplateStore.sizeOf(level, TEMPLATE, dims);
    }
}
