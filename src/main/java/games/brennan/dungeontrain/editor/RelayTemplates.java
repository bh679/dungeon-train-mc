package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupRegistry;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;

import java.util.Optional;

/**
 * The relay's vocabulary — a {@link BuilderPhotoPaths.Kind}, a sub kind and an id — resolved to the
 * {@link Template} it names, once, at the edge.
 *
 * <p>A download holds only these three strings until it is installed, so the install path, the
 * dirty check, the sidecar document and the New copy used to switch on the kind a second time each.
 * This is now the one switch; everything after it asks the template for its own facts
 * ({@link Template#sidecars()}, {@link Template#plotKey()}, {@link Template#dirtyKey()},
 * {@link Template#weightStore()}).</p>
 *
 * <p>Records are built directly, not looked up: the id may not be registered yet (it is about to
 * be installed) or may be a placeholder. Empty only when the sub kind a part or track needs does not
 * parse — the same refusal-rather-than-guess the install makes, for the same reason: {@code standard}
 * is both a floor and a door.</p>
 */
public final class RelayTemplates {

    private RelayTemplates() {}

    public static Optional<Template> resolve(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        if (kind == null || id == null || id.isEmpty()) return Optional.empty();
        String sub = subKind == null ? "" : subKind;
        return switch (kind) {
            case CARRIAGE -> Optional.of(new Template.Carriage(
                    CarriageVariantRegistry.find(id).orElseGet(() -> CarriageVariant.custom(id))));
            case CONTENTS -> Optional.of(new Template.Contents(
                    CarriageContentsRegistry.find(id).orElseGet(() -> CarriageContents.custom(id))));
            case CARRIAGE_GROUP -> Optional.of(new Template.CarriageGroup(
                    CarriageGroupRegistry.find(id).orElseGet(() -> new CarriageGroup(id))));
            case PART -> Optional.ofNullable(CarriagePartKind.fromId(sub)).map(k -> new Template.Part(k, id));
            case TRACK -> Optional.ofNullable(TrackKind.fromId(sub)).map(k -> trackSide(k, id));
            case PORTAL_ROOM -> Optional.of(new Template.PortalRoom(id));
            case CHUNK_FRAME -> Optional.of(new Template.ChunkFrame(id));
            case BUILDING -> Optional.of(new Template.Building(id));
            case LOST_CITY -> Optional.of(new Template.LostCity(id));
        };
    }

    /** The track-side template a {@link TrackKind} and a name address — the one switch over the kind. */
    public static Template trackSide(TrackKind kind, String name) {
        return switch (kind) {
            case TILE -> new Template.Track(name);
            case PILLAR_TOP -> new Template.Pillar(PillarSection.TOP, name);
            case PILLAR_MIDDLE -> new Template.Pillar(PillarSection.MIDDLE, name);
            case PILLAR_BOTTOM -> new Template.Pillar(PillarSection.BOTTOM, name);
            case ADJUNCT_STAIRS -> new Template.Adjunct(PillarAdjunct.STAIRS, name);
            case ADJUNCT_STAIRS_ENTRANCE -> new Template.Adjunct(PillarAdjunct.STAIRS_ENTRANCE, name);
            case TUNNEL_SECTION -> new Template.Tunnel(TunnelVariant.SECTION, name);
            case TUNNEL_PORTAL -> new Template.Tunnel(TunnelVariant.PORTAL, name);
            case PORTAL_ROOM -> new Template.PortalRoom(name);
        };
    }
}
