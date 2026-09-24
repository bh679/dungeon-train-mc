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
import java.util.Optional;

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
    public static final String PART = "part";
    public static final String TRACK = "track";
    public static final String PILLAR = "pillar";
    public static final String ADJUNCT = "adjunct";
    public static final String TUNNEL = "tunnel";
    public static final String PORTAL_ROOM = "portal_room";

    public EditorTemplateAddress {
        type = type == null ? "" : type;
        sub = sub == null ? "" : sub;
        name = name == null ? "" : name;
    }

    /** The address of {@code model}. */
    public static EditorTemplateAddress of(Template model) {
        return switch (model) {
            case Template.Carriage c -> new EditorTemplateAddress(CARRIAGE, "", c.variant().id());
            case Template.Contents c -> new EditorTemplateAddress(CONTENTS, "", c.contents().id());
            case Template.WholeCarriage w -> new EditorTemplateAddress(WHOLE, "", w.wholeCarriage().id());
            case Template.CarriageGroup g -> new EditorTemplateAddress(GROUP, "", g.group().id());
            case Template.Part p -> new EditorTemplateAddress(PART, p.partKind().id(), p.name());
            case Template.Track t -> new EditorTemplateAddress(TRACK, "", t.name());
            case Template.Pillar p -> new EditorTemplateAddress(PILLAR, p.section().id(), p.name());
            case Template.Adjunct a -> new EditorTemplateAddress(ADJUNCT, a.adjunct().id(), a.name());
            case Template.Tunnel t -> new EditorTemplateAddress(TUNNEL,
                    t.variant().name().toLowerCase(Locale.ROOT), t.name());
            case Template.PortalRoom r -> new EditorTemplateAddress(PORTAL_ROOM, "", r.name());
        };
    }

    /** The template this names, or empty when it no longer resolves (renamed, deleted, bad input). */
    public Optional<Template> resolve() {
        if (name.isEmpty()) return Optional.empty();
        return switch (type) {
            case CARRIAGE -> CarriageVariantRegistry.find(name).map(Template.Carriage::new);
            case CONTENTS -> CarriageContentsRegistry.find(name).map(Template.Contents::new);
            case WHOLE -> WholeCarriageRegistry.find(name).map(Template.WholeCarriage::new);
            case GROUP -> CarriageGroupRegistry.find(name).map(Template.CarriageGroup::new);
            case PART -> Optional.ofNullable(CarriagePartKind.fromId(sub))
                    .map(kind -> new Template.Part(kind, name));
            case TRACK -> Optional.of(new Template.Track(name));
            case PILLAR -> pillarSection(sub).map(s -> new Template.Pillar(s, name));
            case ADJUNCT -> adjunct(sub).map(a -> new Template.Adjunct(a, name));
            case TUNNEL -> tunnelVariant(sub).map(v -> new Template.Tunnel(v, name));
            case PORTAL_ROOM -> Optional.of(new Template.PortalRoom(name));
            default -> Optional.empty();
        };
    }

    private static Optional<PillarSection> pillarSection(String id) {
        for (PillarSection s : PillarSection.values()) {
            if (s.id().equals(id)) return Optional.of(s);
        }
        return Optional.empty();
    }

    private static Optional<PillarAdjunct> adjunct(String id) {
        for (PillarAdjunct a : PillarAdjunct.values()) {
            if (a.id().equals(id)) return Optional.of(a);
        }
        return Optional.empty();
    }

    private static Optional<TunnelPlacer.TunnelVariant> tunnelVariant(String id) {
        for (TunnelPlacer.TunnelVariant v : TunnelPlacer.TunnelVariant.values()) {
            if (v.name().toLowerCase(Locale.ROOT).equals(id)) return Optional.of(v);
        }
        return Optional.empty();
    }
}
