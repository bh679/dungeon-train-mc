package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupRegistry;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import games.brennan.dungeontrain.train.WholeHalfRegistry;

import java.util.Optional;

/**
 * The inverse of {@link Template#dirtyKey()}: a dirty-scan row's {@code (categoryId, modelId)} back
 * to the template it names.
 *
 * <p>What {@code /dt save model <category> <id>}, {@code /dt editor view <category> <id>} and the
 * changes-list drilldown resolve through — the three used to each parse the {@code kind.name} shape
 * by hand. The category is a {@link PlotCategory} token, so a frame row
 * ({@code chunk_frames chunk_frame.<name>}) resolves here where it never could through
 * {@code EditorCategory}.</p>
 *
 * <p>Pure: records are built, not looked up, so an id that is not registered still parses — the
 * caller that needs the template to exist asks its registry ({@link #parseExisting}). Empty for a
 * category the scan never emits rows for and for an id in the wrong shape.</p>
 */
public final class TemplateDirtyKeys {

    private TemplateDirtyKeys() {}

    public static Optional<Template> parse(String categoryId, String modelId) {
        if (categoryId == null || modelId == null || modelId.isEmpty()) return Optional.empty();
        return PlotCategory.fromId(categoryId).flatMap(category -> parse(category, modelId));
    }

    public static Optional<Template> parse(PlotCategory category, String modelId) {
        if (category == null || modelId == null || modelId.isEmpty()) return Optional.empty();
        return switch (category) {
            case CARRIAGES -> Optional.of(new Template.Carriage(
                    CarriageVariantRegistry.find(modelId).orElseGet(() -> CarriageVariant.custom(modelId))));
            case CONTENTS -> Optional.of(new Template.Contents(
                    CarriageContentsRegistry.find(modelId).orElseGet(() -> CarriageContents.custom(modelId))));
            case WHOLE -> Optional.of(new Template.WholeCarriage(
                    WholeCarriageRegistry.find(modelId).orElseGet(() -> new WholeCarriage(modelId))));
            case WHOLE_HALF -> Optional.of(new Template.WholeHalf(
                    WholeHalfRegistry.find(modelId).orElseGet(() -> new WholeCarriage(modelId))));
            case WHOLE_GROUP -> Optional.of(new Template.CarriageGroup(
                    CarriageGroupRegistry.find(modelId).orElseGet(() -> new CarriageGroup(modelId))));
            // Dot, not colon, between the kind token and the name: the id travels as one Brigadier
            // word() argument, and colons are not allowed in an unquoted one.
            case TRACKS -> prefixed(modelId).flatMap(p -> trackSide(p.prefix(), p.name()));
            case PORTALS -> prefixed(modelId)
                    .filter(p -> new Template.PortalRoom(p.name()).id().equals(p.prefix()))
                    .map(p -> new Template.PortalRoom(p.name()));
            case CHUNK_FRAMES -> prefixed(modelId)
                    .filter(p -> new Template.ChunkFrame(p.name()).id().equals(p.prefix()))
                    .map(p -> new Template.ChunkFrame(p.name()));
            case BUILDINGS -> prefixed(modelId)
                    .filter(p -> new Template.Building(p.name()).id().equals(p.prefix()))
                    .map(p -> new Template.Building(p.name()));
            case WORKBENCH -> Optional.of(new Template.Staged(modelId));
            // Never a scan row: parts are stamped inside the carriages plots, official buildings
            // are view-only, architecture has no plots.
            case PARTS, LOST_CITY, ARCHITECTURE -> Optional.empty();
        };
    }

    /** As {@link #parse}, but only a template its registry knows — what a save by id must insist on. */
    public static Optional<Template> parseExisting(String categoryId, String modelId) {
        return parse(categoryId, modelId).filter(TemplateDirtyKeys::exists);
    }

    private static boolean exists(Template model) {
        return switch (model) {
            case Template.Carriage c -> CarriageVariantRegistry.find(c.id()).isPresent();
            case Template.Contents c -> CarriageContentsRegistry.find(c.id()).isPresent();
            case Template.WholeCarriage w -> WholeCarriageRegistry.find(w.id()).isPresent();
            case Template.WholeHalf h -> WholeHalfRegistry.find(h.id()).isPresent();
            case Template.CarriageGroup g -> CarriageGroupRegistry.find(g.id()).isPresent();
            // Named by name alone: the dirty row is the proof the plot stands, and a save of a
            // name that has no file yet is exactly how a new one is written.
            case Template.Part p -> true;
            case Template.Track t -> true;
            case Template.Pillar p -> true;
            case Template.Adjunct a -> true;
            case Template.Tunnel t -> true;
            case Template.PortalRoom r -> true;
            case Template.ChunkFrame f -> true;
            case Template.Building b -> true;
            case Template.LostCity l -> true;
            case Template.Staged s -> true;
        };
    }

    private record Prefixed(String prefix, String name) {}

    private static Optional<Prefixed> prefixed(String modelId) {
        int sep = modelId.indexOf('.');
        if (sep <= 0 || sep == modelId.length() - 1) return Optional.empty();
        return Optional.of(new Prefixed(modelId.substring(0, sep), modelId.substring(sep + 1)));
    }

    /** A tracks row's {@code <kind>.<name>}: the kind token is each track-side record's {@code id()}. */
    private static Optional<Template> trackSide(String prefix, String name) {
        if (new Template.Track(name).id().equals(prefix)) return Optional.of(new Template.Track(name));
        if (prefix.startsWith("pillar_")) {
            return EditorTemplateAddress.pillarSection(prefix.substring("pillar_".length()))
                    .map(s -> new Template.Pillar(s, name));
        }
        if (prefix.startsWith("adjunct_")) {
            return EditorTemplateAddress.adjunct(prefix.substring("adjunct_".length()))
                    .map(a -> new Template.Adjunct(a, name));
        }
        if (prefix.startsWith("tunnel_")) {
            return EditorTemplateAddress.tunnelVariant(prefix.substring("tunnel_".length()))
                    .map(v -> new Template.Tunnel(v, name));
        }
        return Optional.empty();
    }
}
