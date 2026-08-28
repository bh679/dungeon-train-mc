package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;

/**
 * Where one template lives in the in-world Train Editor, and the command that teleports to it.
 *
 * <p>The editor addresses a template by <em>category</em> plus a per-category enter command, and the
 * two are asked at different moments: the category decides whether the player has to be moved at all
 * (a switch clears and restamps every plot, so it is never done idly), and the command is what
 * actually puts them in front of the build. A download knows neither — it holds a relay kind — so
 * this is the translation.</p>
 *
 * <p>The command shapes are {@link CategoryTemplatesScreen#trackEnterCommandFor}'s, restated from a
 * {@link TrackKind} rather than a {@code Template} because a downloaded build has no Template to
 * hand until it has been installed and the registries have caught up.</p>
 */
public final class EditorTemplateJump {

    private EditorTemplateJump() {}

    /**
     * The editor category a template of this kind is edited in, or {@code null} when the editor has
     * no home for it.
     *
     * <p>Null for a carriage group: a run of carriages is authored in the Train Builder and the
     * editor has no category that holds one. The caller installs it and says so rather than sending
     * the player somewhere it isn't.</p>
     */
    public static String categoryIdFor(BuilderPhotoPaths.Kind kind, String subKind) {
        if (kind == null) return null;
        return switch (kind) {
            // Parts are stamped as part of the carriages plots — PlotCategory.PARTS.owner() says so.
            case CARRIAGE, PART -> PlotCategory.CARRIAGES.id();
            case CONTENTS -> PlotCategory.CONTENTS.id();
            case TRACK -> TrackKind.PORTAL_ROOM == TrackKind.fromId(subKind)
                    ? PlotCategory.PORTALS.id()
                    : PlotCategory.TRACKS.id();
            case PORTAL_ROOM -> PlotCategory.PORTALS.id();
            case CARRIAGE_GROUP -> null;
        };
    }

    /**
     * The command that teleports to this template's plot, or {@code null} when the category is as
     * close as the editor can get.
     *
     * <p>Null for a part: the editor shows parts as a grid within the carriages plots and has no
     * per-part enter command, so arriving in Carriages is the whole journey.</p>
     */
    public static String enterCommandFor(BuilderPhotoPaths.Kind kind, String id, String subKind) {
        if (kind == null || id == null || id.isEmpty()) return null;
        return switch (kind) {
            case CARRIAGE -> "dungeontrain editor enter " + id;
            case CONTENTS -> "dungeontrain editor contents enter " + id;
            case PORTAL_ROOM -> "dungeontrain editor portals enter " + id;
            case TRACK -> trackEnterCommandFor(TrackKind.fromId(subKind), id);
            case PART, CARRIAGE_GROUP -> null;
        };
    }

    /**
     * The enter command for one track-side kind.
     *
     * <p>Each shape differs because each editor addresses its plots differently: a track tile has
     * exactly one plot and so takes no name, pillars and stairs are named by their section/adjunct,
     * and a tunnel is named by the {@link TrackKind} id itself ({@code tunnel_section},
     * {@code tunnel_portal}) — which is the same string {@code CategoryTemplatesScreen} builds out
     * of a tunnel Template's variant.</p>
     */
    private static String trackEnterCommandFor(TrackKind kind, String id) {
        if (kind == null) return null;
        return switch (kind) {
            case TILE -> "dungeontrain editor track enter";
            case TUNNEL_SECTION, TUNNEL_PORTAL -> "dungeontrain editor enter " + kind.id();
            case PILLAR_TOP -> "dungeontrain editor pillar enter " + PillarSection.TOP.id();
            case PILLAR_MIDDLE -> "dungeontrain editor pillar enter " + PillarSection.MIDDLE.id();
            case PILLAR_BOTTOM -> "dungeontrain editor pillar enter " + PillarSection.BOTTOM.id();
            case ADJUNCT_STAIRS -> "dungeontrain editor pillar enter " + PillarAdjunct.STAIRS.id();
            case ADJUNCT_STAIRS_ENTRANCE ->
                    "dungeontrain editor pillar enter " + PillarAdjunct.STAIRS_ENTRANCE.id();
            case PORTAL_ROOM -> "dungeontrain editor portals enter " + id;
        };
    }
}
