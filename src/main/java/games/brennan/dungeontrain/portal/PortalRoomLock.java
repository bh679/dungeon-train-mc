package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * The block a sealed room is wrapped in — the skin one block outside the room box, the shell around
 * each corridor, and the plugs behind their dead outer doors.
 *
 * <p>A sub-setting rather than a mode of its own: it only means anything while the walls are set to
 * one of the modes that seals ({@link PortalRoomMode#sealsRoomBox}), and it says nothing about
 * <i>whether</i> they seal. Bedrock is what every sealed room was wrapped in before this existed, so
 * a room that says nothing behaves exactly as it did.</p>
 *
 * <h2>Both sealing modes, not Bedrock Lock alone</h2>
 * <p>{@link PortalRoomMode#CHUNK_DIMENSION} writes the same skin for a reason of its own — a sampled
 * hillside runs straight into the box's faces, and the skin is the only thing between a player and
 * the basement. A setting that stopped at {@link PortalRoomMode#BEDROCK_LOCK} would leave one mode
 * whose seal ignored the author, so this follows the writer rather than the mode's name.</p>
 *
 * <h2>Air is a value, and it genuinely unseals the room</h2>
 * <p>Setting the block to air is the author saying the shell should not be there: the skin, the
 * corridor shells and the plugs are all written as air, and the room is minable straight out into
 * the basement. That is deliberate — the same gesture that authors air on a Copies plane
 * ({@link PortalRoomCopies.Kind#SINGLE}'s floor and roof rows) means the same thing here — and it is
 * not the same as {@link PortalRoomMode#BEDROCKLESS}, which additionally sweeps a clearance around
 * the room and hides its edge behind fog.</p>
 *
 * <p>Stored as the last segment of the room's {@code mode} tag — {@link PortalRoomSettings} owns the
 * encoding, and this class owns its own segment's grammar, which is simply the block id.</p>
 *
 * @param blockId the block the seal is written in, as a namespaced id
 */
public record PortalRoomLock(String blockId) {

    /**
     * What a room with nothing set is wrapped in — what {@link PortalRoomMode#BEDROCK_LOCK} is named
     * for, and what every sealed room was made of before the block could be chosen.
     */
    public static final String DEFAULT_BLOCK = "minecraft:bedrock";

    /** The author's way of saying "no shell at all" — see the class javadoc. */
    public static final String AIR_BLOCK = "minecraft:air";

    /**
     * Longest block id this setting will store.
     *
     * <p>A bound on the <b>tag</b>, not a judgement about block names — the same cap
     * {@link PortalRoomCopies#BLOCK_ID_MAX} carries, and for the same reason: the tag rides
     * {@code EditorStatusPacket.MODE_TAG_MAX} as a capped {@code writeUtf}, so an id long enough to
     * push the whole string past that cap would throw on a live server rather than draw a wrong
     * room. Anything beyond it reads back as {@link #DEFAULT_BLOCK}.</p>
     */
    public static final int BLOCK_ID_MAX = PortalRoomCopies.BLOCK_ID_MAX;

    /** Bedrock, as every sealed room has always been. */
    public static final PortalRoomLock DEFAULT = new PortalRoomLock(DEFAULT_BLOCK);

    public PortalRoomLock {
        blockId = normaliseBlock(blockId);
    }

    /** {@code raw} as a stored block id, or {@link #DEFAULT_BLOCK} when it says nothing usable. */
    private static String normaliseBlock(String raw) {
        if (raw == null) return DEFAULT_BLOCK;
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || text.length() > BLOCK_ID_MAX) return DEFAULT_BLOCK;
        return text;
    }

    /**
     * Read one stored segment. Total, for the same reason {@link PortalRoomMode#parse} and
     * {@link PortalRoomCopies#parse} are: the tag is free text on disk, and a misspelling should
     * stamp a room in bedrock rather than fail a pair.
     */
    public static PortalRoomLock parse(String segment) {
        if (segment == null) return DEFAULT;
        String text = segment.trim();
        if (text.isEmpty()) return DEFAULT;
        return new PortalRoomLock(text);
    }

    /** The segment to store, which is simply the block id. */
    public String id() {
        return blockId;
    }

    /** True when the author asked for no shell at all. */
    public boolean isAir() {
        return AIR_BLOCK.equals(blockId);
    }

    public PortalRoomLock withBlock(String newBlockId) {
        return new PortalRoomLock(newBlockId);
    }
}
