package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whether a portal room is furnished from the ordinary contents pool, and how a furnishing that does
 * not fill the room is placed inside it.
 *
 * <p>A room used to get nothing. Every carriage on the train rolls a {@code contents/*.nbt}
 * furnishing ({@code CarriageContentsRegistry.pick}), and so does every portal <i>corridor</i>
 * ({@link PortalCorridorContents}) — but the pocket room between the two twins was stamped from its
 * template and its block-variant sidecar and nothing else. Furnishing one meant authoring every
 * block of it by hand.</p>
 *
 * <h2>Why this is not a boolean</h2>
 * <p>A contents template is authored against a <b>carriage</b> interior — 7×5×5 at the default dims
 * — and a room interior is bigger, 9×5×11 for the built-in room. {@code CarriageContentsStore.get}'s
 * size gate is exact-match, so "put a contents template in a room" has to answer two questions the
 * carriage path never has to: which templates qualify, and where in the larger box they land. Each
 * value below is one answer to both.</p>
 *
 * <h2>Y is never centred</h2>
 * <p>Contents are authored sitting on their own floor, so every anchor this class returns is at
 * interior {@code y = 0}. Centring vertically would leave the furniture hanging.</p>
 *
 * <p>Stored as the third segment of the room's {@code mode} tag — see {@link PortalRoomSettings},
 * which owns the encoding.</p>
 */
public enum PortalRoomContents {

    /** No furnishing at all. The default, and what every room did before this existed. */
    OFF("off", "Off"),

    /** Any template that fits inside the room, placed once, centred on the interior floor. */
    FIT("fit", "Fit"),

    /** Only a template authored at exactly the room's interior size, filling it. */
    EXACT("exact", "Exact"),

    /** Any template that fits, repeated across the interior on an X/Z grid. */
    TILE("tile", "Tile");

    /** What a room with nothing set behaves as: unfurnished, exactly as before. */
    public static final PortalRoomContents DEFAULT = OFF;

    private final String id;
    private final String displayName;

    PortalRoomContents(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The on-disk / command-line token, e.g. {@code fit}. */
    public String id() {
        return id;
    }

    /** Human-readable label for the editor row. */
    public String displayName() {
        return displayName;
    }

    /** True when the room draws a contents template at all. */
    public boolean furnishes() {
        return this != OFF;
    }

    /**
     * The value named by {@code id}, or {@link #DEFAULT} when it is null, blank or unrecognised.
     *
     * <p>Total, for the same reason {@link PortalRoomMode#parse} and {@link PortalRoomCopies#parse}
     * are: the tag is free text on disk, and a room whose setting was hand-edited to something
     * misspelt should stamp unfurnished rather than fail the pair's stamp.</p>
     */
    public static PortalRoomContents parse(String id) {
        if (id == null) return DEFAULT;
        String key = id.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return DEFAULT;
        for (PortalRoomContents c : values()) {
            if (c.id.equals(key)) return c;
        }
        return DEFAULT;
    }

    /** The value after this one, wrapping — what the editor's Contents button steps through. */
    public PortalRoomContents next() {
        PortalRoomContents[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /**
     * Where inside a room's interior copies of a {@code template}-sized furnishing go — local offsets
     * from the interior's minimum corner, empty when this value places nothing or when the template
     * does not qualify.
     *
     * <p><b>Pure integer geometry.</b> It is the whole of the placement rule and it decides whether a
     * template is written into a live world at all, so it is testable without a level — the same
     * split {@link PortalCorridorContents#seedFor} makes for the same reason.</p>
     *
     * <p>An interior or template with a non-positive extent on any axis yields nothing: a room at its
     * minimum dims can have no interior volume, and a caller must not stamp into a box that does not
     * exist.</p>
     */
    public List<BlockPos> anchorsIn(Vec3i interior, Vec3i template) {
        if (!furnishes()) return List.of();
        if (interior == null || template == null) return List.of();
        if (!isPositive(interior) || !isPositive(template)) return List.of();

        return switch (this) {
            case OFF -> List.of();
            case EXACT -> template.equals(interior) ? List.of(BlockPos.ZERO) : List.of();
            case FIT -> fitsIn(template, interior)
                ? List.of(new BlockPos(centre(interior.getX(), template.getX()), 0,
                                       centre(interior.getZ(), template.getZ())))
                : List.of();
            case TILE -> tileAnchors(interior, template);
        };
    }

    /**
     * The grid {@link #TILE} lays down: as many whole copies as fit along X and Z, with the leftover
     * split evenly between the two edges so the pattern reads as centred rather than pushed into a
     * corner.
     *
     * <p>Whole copies only. A partial copy at the far edge would be a furnishing cut through the
     * middle, and the template stamp has no way to place half of one.</p>
     */
    private static List<BlockPos> tileAnchors(Vec3i interior, Vec3i template) {
        if (!fitsIn(template, interior)) return List.of();

        int countX = interior.getX() / template.getX();
        int countZ = interior.getZ() / template.getZ();
        int marginX = centre(interior.getX(), template.getX() * countX);
        int marginZ = centre(interior.getZ(), template.getZ() * countZ);

        List<BlockPos> anchors = new ArrayList<>(countX * countZ);
        for (int x = 0; x < countX; x++) {
            for (int z = 0; z < countZ; z++) {
                anchors.add(new BlockPos(
                    marginX + x * template.getX(), 0, marginZ + z * template.getZ()));
            }
        }
        return List.copyOf(anchors);
    }

    /** True when {@code inner} is no bigger than {@code outer} on any axis. */
    private static boolean fitsIn(Vec3i inner, Vec3i outer) {
        return inner.getX() <= outer.getX()
            && inner.getY() <= outer.getY()
            && inner.getZ() <= outer.getZ();
    }

    private static boolean isPositive(Vec3i size) {
        return size.getX() > 0 && size.getY() > 0 && size.getZ() > 0;
    }

    /** The offset that leaves {@code inner} centred in {@code outer}, biased towards the low edge. */
    private static int centre(int outer, int inner) {
        return (outer - inner) / 2;
    }
}
