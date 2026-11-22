package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuildCredits;
import games.brennan.dungeontrain.template.BuilderCredit;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriageWeights;

/**
 * Who a template row should say built it — the one answer the roster gives from two records.
 *
 * <p>The authored credit in the kind's {@code weights.json} ({@code TemplateMeta#builder()}) wins:
 * it is the developer's word, it ships in the jar, and it is the one the Credits page reads. When
 * nobody has set one, a build this install downloaded from another player's profile still knows
 * who it came from — {@link BuildCredits}, filed by the download path — so the sheet says so rather
 * than showing a downloaded build as anonymous. That fallback is display only: nothing here writes
 * it into the weights, which stays a deliberate act in the editor.</p>
 */
public final class TemplateBuilderLookup {

    private TemplateBuilderLookup() {}

    /** The credit for a carriage template, or {@code null}. */
    public static BuilderCredit carriage(CarriageWeights weights, String id) {
        return firstOf(weights.builderFor(id), BuilderPhotoPaths.Kind.CARRIAGE, "", id);
    }

    /** The credit for a carriage-contents template, or {@code null}. */
    public static BuilderCredit contents(CarriageContentsWeights weights, String id) {
        return firstOf(weights.builderFor(id), BuilderPhotoPaths.Kind.CONTENTS, "", id);
    }

    /** The credit for a track-side template (a portal room included), or {@code null}. */
    /** A whole room or group's credit — rooms share the carriage kind on the relay, groups have their own. */
    public static BuilderCredit whole(games.brennan.dungeontrain.train.WholeKind kind, String id) {
        BuilderCredit authored = games.brennan.dungeontrain.train.WholeWeights.builderFor(kind, id);
        return kind == games.brennan.dungeontrain.train.WholeKind.GROUP
            ? firstOf(authored, BuilderPhotoPaths.Kind.CARRIAGE_GROUP, "", id)
            : firstOf(authored, BuilderPhotoPaths.Kind.CARRIAGE, "", id);
    }

    public static BuilderCredit track(TrackKind kind, String name) {
        BuilderCredit authored = TrackVariantWeights.builderFor(kind, name);
        return kind == TrackKind.PORTAL_ROOM
            ? firstOf(authored, BuilderPhotoPaths.Kind.PORTAL_ROOM, "", name)
            : firstOf(authored, BuilderPhotoPaths.Kind.TRACK, kind.id(), name);
    }

    /**
     * {@code byId} with every uncredited entry given the downloaded build's credit, for the dev-mode
     * write into the <b>source tree</b> only. That file ships in the jar, and a build downloaded from
     * another player and saved there would otherwise ship anonymous: the editor holds no builder for
     * it, so each save wrote the entry back without one and a hand-added credit was lost on the next
     * save. The player's own overlay is not touched, so outside the source tree the fallback above
     * stays display only. An authored credit always wins. Returns {@code byId} itself when nothing changes.
     *
     * @param kind    the relay kind the download filed the credit under
     * @param subKind its sub-kind ({@code ""}, or a track kind's id)
     */
    public static java.util.Map<String, games.brennan.dungeontrain.template.TemplateMeta> withDownloadedCredits(
            java.util.Map<String, games.brennan.dungeontrain.template.TemplateMeta> byId,
            BuilderPhotoPaths.Kind kind, String subKind) {
        java.util.Map<String, games.brennan.dungeontrain.template.TemplateMeta> out = null;
        for (java.util.Map.Entry<String, games.brennan.dungeontrain.template.TemplateMeta> e : byId.entrySet()) {
            if (e.getValue().builder() != null) continue;
            BuilderCredit downloaded = firstOf(null, kind, subKind, e.getKey());
            if (downloaded == null) continue;
            if (out == null) out = new java.util.LinkedHashMap<>(byId);
            out.put(e.getKey(), e.getValue().withBuilder(downloaded));
        }
        return out == null ? byId : out;
    }

    /** {@link #withDownloadedCredits} for a track-side kind's weights (portal rooms included). */
    public static java.util.Map<String, games.brennan.dungeontrain.template.TemplateMeta> withDownloadedCredits(
            TrackKind kind, java.util.Map<String, games.brennan.dungeontrain.template.TemplateMeta> byId) {
        return kind == TrackKind.PORTAL_ROOM
            ? withDownloadedCredits(byId, BuilderPhotoPaths.Kind.PORTAL_ROOM, "")
            : withDownloadedCredits(byId, BuilderPhotoPaths.Kind.TRACK, kind.id());
    }

    private static BuilderCredit firstOf(BuilderCredit authored, BuilderPhotoPaths.Kind kind, String subKind,
                                         String id) {
        if (authored != null) return authored;
        BuildCredits.Credit downloaded = BuildCredits.get(kind, subKind, id);
        if (downloaded == null || !downloaded.known()) return null;
        return BuilderCredit.ofOrNull(downloaded.creatorUuid(), downloaded.creatorName());
    }
}
