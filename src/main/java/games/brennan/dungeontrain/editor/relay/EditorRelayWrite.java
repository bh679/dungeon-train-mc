package games.brennan.dungeontrain.editor.relay;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.BuilderSave;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TrackPlotLocator;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;

/**
 * What the relay should call an editor save, and where its blocks are.
 *
 * <p>The Train Builder answers both questions inside {@code BuilderSave}, per arm, because each arm
 * had already resolved the volume it was about to capture. The Train Editor has no single save body
 * — a template is written through its own store, out of a plot the editor world laid out — so the
 * same two answers are given here instead, once.</p>
 *
 * <p>Split into three pure pieces and one lookup so the decisions can be tested without a world.
 * They are worth testing: two independent things depend on them and neither can check the other.
 * The upload captures the volume {@link #capturedOrigin}/{@link #capturedSize} name, and the relay
 * keys the build by what {@link #namingOf} names. A wrong volume uploads the wrong blocks; a wrong
 * kind files a portal room in the pool as a cart.</p>
 *
 * <p>The switch is exhaustive over a <b>sealed</b> {@link Template}, deliberately: a record added
 * later fails to compile here rather than silently becoming a kind that never uploads.</p>
 */
public final class EditorRelayWrite {

    private EditorRelayWrite() {}

    /**
     * How one template is filed on the relay.
     *
     * @param kind    the store it belongs to, as the profile browses it
     * @param subKind the id-space {@code id} belongs to — a part kind, a {@link TrackKind} — or
     *                {@code ""} for the kinds with one flat namespace
     * @param id      the template id within that space
     */
    public record Naming(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        public Naming {
            subKind = subKind == null ? "" : subKind;
        }
    }

    /**
     * What a save of {@code model} just wrote, or {@code null} when there is nothing to upload.
     *
     * <p>Null for a template with no editor plot — a whole carriage, which is authored in a builder
     * world and has none, and any variant whose plot origin does not resolve (an unregistered
     * carriage, a contents id the registry has forgotten). Those are not failures; they are "this
     * did not come out of a plot", and the caller drops them.</p>
     */
    public static BuilderSave.Written of(Template model, ServerLevel level, CarriageDims dims) {
        if (model == null || level == null || dims == null) {
            return null;
        }
        Naming naming = namingOf(model);
        if (naming == null) {
            return null;
        }
        BlockPos plotOrigin = model.editorPlotOrigin(level, dims);
        if (plotOrigin == null) {
            return null;
        }
        return new BuilderSave.Written(naming.kind(), naming.id(), naming.subKind(),
                capturedOrigin(model, plotOrigin), capturedSize(model, dims));
    }

    /**
     * The relay's name for {@code model}, or {@code null} for a template the editor cannot upload.
     *
     * <p>The sub kinds here deliberately match what {@code BuilderSave.savePart} and
     * {@code BuilderSave.saveTrack} write, so the same template saved from either tool resolves to
     * one {@code BuilderRelayBuilds} key and so updates one profile entry rather than making a
     * second.</p>
     */
    public static Naming namingOf(Template model) {
        if (model == null) {
            return null;
        }
        return switch (model) {
            case Template.Carriage carriage ->
                    new Naming(BuilderPhotoPaths.Kind.CARRIAGE, "", carriage.variant().id());
            case Template.Contents contents ->
                    new Naming(BuilderPhotoPaths.Kind.CONTENTS, "", contents.contents().id());

            // Authored in a builder world, which has no plot grid to locate one in — Template's own
            // editorPlotOrigin says so by returning null. Nothing to file.
            case Template.WholeCarriage ignored -> null;

            // A part id is only unique within its kind — 'standard' is both a floor and a door — so
            // the kind rides along as the sub kind.
            case Template.Part part ->
                    new Naming(BuilderPhotoPaths.Kind.PART, part.partKind().id(), part.name());

            // Every track-side kind shares one relay kind and is told apart by its TrackKind, which
            // is exactly how the builder keys them.
            case Template.Track track -> track(TrackKind.TILE, track.name());
            case Template.Pillar pillar ->
                    track(TrackPlotLocator.pillarKind(pillar.section()), pillar.name());
            case Template.Adjunct adjunct ->
                    track(PillarTemplateStore.adjunctKind(adjunct.adjunct()), adjunct.name());
            case Template.Tunnel tunnel ->
                    track(TrackPlotLocator.tunnelKind(tunnel.variant()), tunnel.name());

            // Its own kind rather than a TRACK with a kind attached — the profile browses rooms by
            // what they do at their walls, never by the eight track kinds. See BuilderPhotoPaths.
            case Template.PortalRoom room ->
                    new Naming(BuilderPhotoPaths.Kind.PORTAL_ROOM, "", room.name());
        };
    }

    /**
     * The minimum corner of the volume a save of {@code model} captured, given its plot's origin.
     *
     * <p>The same as the plot's for every kind but one. The contents editor writes the <b>interior</b>
     * — one block in from each wall — so a contents upload anchored at the plot origin would carry
     * the shell wrapped around the room. See {@code CarriageContentsEditor.save}.</p>
     */
    public static BlockPos capturedOrigin(Template model, BlockPos plotOrigin) {
        if (plotOrigin == null) {
            return null;
        }
        return model instanceof Template.Contents
                ? CarriageContentsPlacer.interiorOrigin(plotOrigin)
                : plotOrigin;
    }

    /**
     * The extent of that volume.
     *
     * <p>{@link Template#plotSize} for every kind but contents, whose plot is a whole carriage and
     * whose template is the interior of one. Resolved from the contents id rather than from
     * {@link CarriageDims} alone, because the portal corridor's contents are authored against a
     * longer box than a carriage.</p>
     */
    public static Vec3i capturedSize(Template model, CarriageDims dims) {
        if (model == null || dims == null) {
            return null;
        }
        return model instanceof Template.Contents contents
                ? CarriageContentsPlacer.interiorSizeFor(contents.contents(), dims)
                : model.plotSize(dims);
    }

    /** A track-side template: one relay kind, told apart by its {@link TrackKind} sub kind. */
    private static Naming track(TrackKind kind, String name) {
        return kind == null ? null : new Naming(BuilderPhotoPaths.Kind.TRACK, kind.id(), name);
    }
}
