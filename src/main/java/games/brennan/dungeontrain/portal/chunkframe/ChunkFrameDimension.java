package games.brennan.dungeontrain.portal.chunkframe;

import games.brennan.dungeontrain.portal.PortalRoomMode;

import java.util.Locale;

/**
 * How a chunk room dressed by a frame is enclosed — the frame author's choice, stored in its
 * {@code <name>.frame.json} ({@link ChunkFrameMeta#dimension}).
 *
 * <ul>
 *   <li>{@link #BEDROCK} — the sealed skin every chunk room has always had.</li>
 *   <li>{@link #VOID} — no skin: the space around the room is swept and fogged, as a Void room's is.</li>
 *   <li>{@link #WORLD_BORDER} — Void, plus the drawn world border on the box's sides.</li>
 *   <li>{@link #ENDLESS_OPEN} — Void, standing on an open plain of the frame's ground block
 *       ({@link ChunkFrameGround}) level with the chunk's bottom row, its doors the frame's
 *       {@link ChunkFrameMeta#doorDistance} out along it.</li>
 * </ul>
 *
 * <p>Applied at plan time by turning the room's {@link PortalRoomMode#CHUNK_DIMENSION} into the
 * matching terrain mode ({@link #roomMode}), so the skin, clearance, lane claim, fog and border all
 * follow from the structure's mode as they do for every other room.</p>
 */
public enum ChunkFrameDimension {
    BEDROCK("bedrock", "Bedrock lock"),
    VOID("void", "Void"),
    WORLD_BORDER("world_border", "World border"),
    ENDLESS_OPEN("endless_open", "Endless open");

    public static final ChunkFrameDimension DEFAULT = BEDROCK;

    private final String id;
    private final String label;

    ChunkFrameDimension(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** On-disk and command token. */
    public String id() {
        return id;
    }

    /** Editor row label. */
    public String label() {
        return label;
    }

    /** {@code raw} as a dimension; {@link #DEFAULT} for anything unrecognised. Total. */
    public static ChunkFrameDimension parse(String raw) {
        if (raw == null) return DEFAULT;
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (ChunkFrameDimension d : values()) {
            if (d.id.equals(key)) return d;
        }
        return DEFAULT;
    }

    /**
     * The mode a chunk room planned as {@code planned} takes under this dimension. Only a room that
     * would be sealed as a plain chunk dimension changes; anything else is returned as it was.
     */
    public PortalRoomMode roomMode(PortalRoomMode planned) {
        if (planned != PortalRoomMode.CHUNK_DIMENSION) return planned;
        return switch (this) {
            case BEDROCK -> planned;
            case VOID -> PortalRoomMode.CHUNK_VOID;
            case WORLD_BORDER -> PortalRoomMode.CHUNK_WORLD_BORDER;
            case ENDLESS_OPEN -> PortalRoomMode.CHUNK_ENDLESS_OPEN;
        };
    }
}
