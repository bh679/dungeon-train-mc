package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;

import java.util.Optional;

/**
 * The {@link PlotEditor} that owns a template's plot, for callers that hold only a {@link Template}.
 * The records themselves call their editor typed; this is the untyped view the commands reach for.
 * Empty for kinds whose editor is still a static class.
 */
public final class PlotEditors {

    private PlotEditors() {}

    public static Optional<PlotEditor<? extends Template>> editorFor(Template model) {
        return Optional.ofNullable(switch (model) {
            case Template.Track t -> TrackSideEditors.tile();
            case Template.Pillar p -> TrackSideEditors.pillar(p.section());
            case Template.Adjunct a -> TrackSideEditors.adjunct(a.adjunct());
            case Template.Tunnel t -> TrackSideEditors.tunnel(t.variant());
            default -> null;
        });
    }
}
