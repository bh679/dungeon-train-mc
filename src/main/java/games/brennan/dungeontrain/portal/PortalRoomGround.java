package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * Which rows of an {@link PortalRoomMode#ENDLESS_OPEN} room its copies repeat as the ground.
 *
 * <p>A copy always repeated the room's bottom row, which is right only while the door stands on it.
 * A room whose door was raised walks out onto a drop, so the author can name the row the plain is
 * laid at instead — by what it lines up with rather than by a number, so it follows the door when
 * the door moves.</p>
 *
 * <p>Stored as the fifteenth segment of the room's {@code mode} tag, e.g. {@code door:below} — see
 * {@link PortalRoomSettings}, which owns the encoding. {@link Level#BOTTOM} is never written, so
 * every tag from before the setting existed reads back to the behaviour it always had.</p>
 *
 * @param level which row the ground is
 * @param below whether every row under it repeats too, rather than leaving open air down there.
 *              Means nothing at {@link Level#BOTTOM}, which has no rows under it, so it is settled
 *              to false there and the default has one form to compare against
 */
public record PortalRoomGround(Level level, boolean below) {

    /** What a room with no ground segment — or an unreadable one — behaves as: the bottom row. */
    public static final PortalRoomGround DEFAULT = new PortalRoomGround(Level.BOTTOM, false);

    /** Separates the level from the Everything Below flag in the stored segment. */
    private static final String BELOW_SUFFIX = ":below";

    public PortalRoomGround {
        if (level == null) level = Level.BOTTOM;
        if (level == Level.BOTTOM) below = false;
    }

    /** The named rows a ground can be laid at. */
    public enum Level {

        /** The room's own bottom row. The default. */
        BOTTOM("bottom", "Bottom"),

        /** The entry door's floor line — the row a player stands on walking in. */
        ENTER("enter", "Enter"),

        /** One row above that, the bottom block of the door opening. */
        DOOR("door", "Door");

        private final String id;
        private final String displayName;

        Level(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        /** The on-disk / command-line token. */
        public String id() {
            return id;
        }

        /** Human-readable label for the editor row. */
        public String displayName() {
            return displayName;
        }

        /** The level named by {@code key}, or null when nothing answers to it. */
        public static Level byId(String key) {
            if (key == null) return null;
            String trimmed = key.trim().toLowerCase(Locale.ROOT);
            for (Level level : values()) {
                if (level.id.equals(trimmed)) return level;
            }
            return null;
        }
    }

    /**
     * The rows a copy's ground fills, relative to the room's bottom row, both ends inclusive.
     *
     * @param bottom the lowest ground row
     * @param top    the highest ground row, which is the level itself
     */
    public record Rows(int bottom, int top) {

        /** The bottom row alone — what every room but a raised Endless Open one answers. */
        public static final Rows FLOOR = new Rows(0, 0);

        /** True when {@code row} is one of the ground rows. */
        public boolean contains(int row) {
            return row >= bottom && row <= top;
        }
    }

    /** The on-disk / command-line token. */
    public String id() {
        return below ? level.id() + BELOW_SUFFIX : level.id();
    }

    /**
     * The value named by {@code segment}, or {@link #DEFAULT} when it is null, blank or unreadable.
     * Total, for the same reason every other segment parser in this package is.
     */
    public static PortalRoomGround parse(String segment) {
        if (segment == null) return DEFAULT;
        String key = segment.trim().toLowerCase(Locale.ROOT);
        boolean below = key.endsWith(BELOW_SUFFIX);
        if (below) key = key.substring(0, key.length() - BELOW_SUFFIX.length());
        Level level = Level.byId(key);
        return level == null ? DEFAULT : new PortalRoomGround(level, below);
    }

    /** The same setting at the next level, wrapping — what the editor's cycling button steps to. */
    public PortalRoomGround nextLevel() {
        Level[] all = Level.values();
        return withLevel(all[(level.ordinal() + 1) % all.length]);
    }

    /** The same setting at {@code newLevel}. */
    public PortalRoomGround withLevel(Level newLevel) {
        return new PortalRoomGround(newLevel, below);
    }

    /** The same setting with the Everything Below box flipped. A no-op at the bottom row. */
    public PortalRoomGround toggleBelow() {
        return new PortalRoomGround(level, !below);
    }

    /** The row this level names in a room whose entry door's floor line is {@code doorFloor}. */
    public int row(int doorFloor) {
        return switch (level) {
            case BOTTOM -> 0;
            case ENTER -> doorFloor;
            case DOOR -> doorFloor + 1;
        };
    }

    /**
     * The rows a copy's ground fills in a room {@code roomHeight} tall.
     *
     * <p>Held two rows under the roof so the roof plane survives and at least one row stays open
     * between the two, the same room {@code PortalRoomSinglePlanes.floorHeightFor} leaves. A door
     * cannot ask for more than that — it needs its two open rows inside the room — so this only
     * ever catches a hand-edited tag.</p>
     *
     * @param doorFloor the entry door's floor line, already clamped to this room
     */
    public Rows rows(int doorFloor, int roomHeight) {
        int top = Math.max(0, Math.min(roomHeight - 3, row(doorFloor)));
        return new Rows(below ? 0 : top, top);
    }
}
