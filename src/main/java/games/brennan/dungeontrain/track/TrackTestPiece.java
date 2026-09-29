package games.brennan.dungeontrain.track;

import games.brennan.dungeontrain.track.variant.TrackKind;

import java.util.Optional;

/**
 * Which piece of the line a Tracks-category Test the Carriage is testing, named by the editor's
 * model id — the {@code id()} of {@code Template.Track} / {@code Pillar} / {@code Adjunct} /
 * {@code Tunnel}, which is what the client sends as the command's first argument.
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public enum TrackTestPiece {
    TILE("track", TrackKind.TILE),
    PILLAR_BOTTOM("pillar_bottom", TrackKind.PILLAR_BOTTOM),
    PILLAR_MIDDLE("pillar_middle", TrackKind.PILLAR_MIDDLE),
    PILLAR_TOP("pillar_top", TrackKind.PILLAR_TOP),
    STAIRS("adjunct_stairs", TrackKind.ADJUNCT_STAIRS),
    STAIRS_ENTRANCE("adjunct_stairs_entrance", TrackKind.ADJUNCT_STAIRS_ENTRANCE),
    TUNNEL_SECTION("tunnel_section", TrackKind.TUNNEL_SECTION),
    TUNNEL_PORTAL("tunnel_portal", TrackKind.TUNNEL_PORTAL);

    /** Between the model id and the name in a session's template id — neither ever contains it. */
    private static final char SEPARATOR = ':';

    private final String modelId;
    private final TrackKind kind;

    TrackTestPiece(String modelId, TrackKind kind) {
        this.modelId = modelId;
        this.kind = kind;
    }

    /** The editor's model id — the command token and the dirty scan's key prefix. */
    public String modelId() { return modelId; }

    /** The template kind the piece is stored and rolled as. */
    public TrackKind kind() { return kind; }

    /** The piece a model id names, or empty for one that is not a piece of the line. */
    public static Optional<TrackTestPiece> ofModelId(String modelId) {
        if (modelId == null) return Optional.empty();
        for (TrackTestPiece p : values()) {
            if (p.modelId.equals(modelId)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /**
     * The session's template id for {@code name} of this piece — one string, so a reseed can hand it
     * back to the test command the way every other kind's bare id is handed back.
     */
    public String templateId(String name) {
        return modelId + SEPARATOR + name;
    }

    /** The piece and name a {@link #templateId} was built from, or empty for any other string. */
    public static Optional<Named> parseTemplateId(String templateId) {
        if (templateId == null) return Optional.empty();
        int at = templateId.indexOf(SEPARATOR);
        if (at <= 0 || at == templateId.length() - 1) return Optional.empty();
        String name = templateId.substring(at + 1);
        return ofModelId(templateId.substring(0, at)).map(p -> new Named(p, name));
    }

    /** A piece and the name of the template under test. */
    public record Named(TrackTestPiece piece, String name) {}
}
