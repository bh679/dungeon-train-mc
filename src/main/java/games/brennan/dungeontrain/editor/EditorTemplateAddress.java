package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageGroupRegistry;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A Train Editor template as three strings, and back.
 *
 * <p>The Save-as prompt goes to the client and the player's answer comes back, so the template being
 * saved has to survive the round trip. The plot panel's {@code (category, modelId, modelName)} cannot
 * carry it: parts have no category there, and the track-side kinds overload {@code modelId}. This is
 * one flat form for every kind, with the resolve done once, here.</p>
 *
 * @param type which {@link Template} record — the lower-cased record name
 * @param sub  the kind-within-a-kind where there is one (part kind, pillar section, adjunct, tunnel
 *             variant); empty otherwise
 * @param name the template's own name
 */
public record EditorTemplateAddress(String type, String sub, String name) {

    public static final String CARRIAGE = "carriage";
    public static final String CONTENTS = "contents";
    public static final String WHOLE = "whole";
    public static final String GROUP = "group";
    public static final String HALF = "half";
    public static final String PART = "part";
    public static final String TRACK = "track";
    public static final String PILLAR = "pillar";
    public static final String ADJUNCT = "adjunct";
    public static final String TUNNEL = "tunnel";
    public static final String PORTAL_ROOM = "portal_room";
    public static final String CHUNK_FRAME = "chunk_frame";
    public static final String BUILDING = "building";
    public static final String LOST_CITY = "lost_city";
    public static final String STAGED = "staged";

    public EditorTemplateAddress {
        type = type == null ? "" : type;
        sub = sub == null ? "" : sub;
        name = name == null ? "" : name;
    }

    /** The address of {@code model} — {@link Template#address()}. */
    public static EditorTemplateAddress of(Template model) {
        return model.address();
    }

    /** One record's half of the round trip: the template {@code (sub, name)} names, or empty. */
    @FunctionalInterface
    public interface Parser {
        Optional<Template> parse(String sub, String name);
    }

    /**
     * One parser per type token. A record's {@code address()} writes its token; the parser under
     * that token reads it back. A String switch cannot be exhaustive, so coverage is pinned by
     * {@code TemplateAddressRoundTripTest}, which walks every permitted {@link Template} record —
     * a new record without an entry here fails that test rather than round-tripping to empty.
     */
    private static final Map<String, Parser> PARSERS = Map.ofEntries(
            Map.entry(CARRIAGE, (sub, name) -> CarriageVariantRegistry.find(name).map(Template.Carriage::new)),
            Map.entry(CONTENTS, (sub, name) -> CarriageContentsRegistry.find(name).map(Template.Contents::new)),
            Map.entry(WHOLE, (sub, name) -> WholeCarriageRegistry.find(name).map(Template.WholeCarriage::new)),
            Map.entry(GROUP, (sub, name) -> CarriageGroupRegistry.find(name).map(Template.CarriageGroup::new)),
            Map.entry(HALF, (sub, name) -> games.brennan.dungeontrain.train.WholeHalfRegistry.find(name).map(Template.WholeHalf::new)),
            Map.entry(PART, (sub, name) -> Optional.ofNullable(CarriagePartKind.fromId(sub)).map(kind -> new Template.Part(kind, name))),
            Map.entry(TRACK, (sub, name) -> Optional.of(new Template.Track(name))),
            Map.entry(PILLAR, (sub, name) -> pillarSection(sub).map(s -> new Template.Pillar(s, name))),
            Map.entry(ADJUNCT, (sub, name) -> adjunct(sub).map(a -> new Template.Adjunct(a, name))),
            Map.entry(TUNNEL, (sub, name) -> tunnelVariant(sub).map(v -> new Template.Tunnel(v, name))),
            Map.entry(PORTAL_ROOM, (sub, name) -> Optional.of(new Template.PortalRoom(name))),
            Map.entry(CHUNK_FRAME, (sub, name) -> games.brennan.dungeontrain.portal.chunkframe.ChunkFrameRegistry.names().contains(name)
                    ? Optional.of(new Template.ChunkFrame(name)) : Optional.empty()),
            Map.entry(BUILDING, (sub, name) -> games.brennan.dungeontrain.building.BuildingRegistry.contains(name)
                    ? Optional.of(new Template.Building(name)) : Optional.empty()),
            Map.entry(LOST_CITY, (sub, name) -> games.brennan.dungeontrain.building.LostCityReferences.find(name)
                    .map(r -> new Template.LostCity(r.name()))),
            Map.entry(STAGED, (sub, name) -> WorkbenchTemplates.registry().find(name).map(s -> (Template) s)));

    /** The type tokens with a registered parser — what the round-trip test checks against. */
    public static Set<String> registeredTypes() {
        return PARSERS.keySet();
    }

    /** The template this names, or empty when it no longer resolves (renamed, deleted, bad input). */
    public Optional<Template> resolve() {
        if (name.isEmpty()) return Optional.empty();
        Parser parser = PARSERS.get(type);
        return parser == null ? Optional.empty() : parser.parse(sub, name);
    }

    public static Optional<PillarSection> pillarSection(String id) {
        for (PillarSection s : PillarSection.values()) {
            if (s.id().equals(id)) return Optional.of(s);
        }
        return Optional.empty();
    }

    public static Optional<PillarAdjunct> adjunct(String id) {
        for (PillarAdjunct a : PillarAdjunct.values()) {
            if (a.id().equals(id)) return Optional.of(a);
        }
        return Optional.empty();
    }

    public static Optional<TunnelPlacer.TunnelVariant> tunnelVariant(String id) {
        for (TunnelPlacer.TunnelVariant v : TunnelPlacer.TunnelVariant.values()) {
            if (v.name().toLowerCase(Locale.ROOT).equals(id)) return Optional.of(v);
        }
        return Optional.empty();
    }
}
