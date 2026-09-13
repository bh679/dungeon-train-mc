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
    public static BuilderCredit track(TrackKind kind, String name) {
        BuilderCredit authored = TrackVariantWeights.builderFor(kind, name);
        return kind == TrackKind.PORTAL_ROOM
            ? firstOf(authored, BuilderPhotoPaths.Kind.PORTAL_ROOM, "", name)
            : firstOf(authored, BuilderPhotoPaths.Kind.TRACK, kind.id(), name);
    }

    private static BuilderCredit firstOf(BuilderCredit authored, BuilderPhotoPaths.Kind kind, String subKind,
                                         String id) {
        if (authored != null) return authored;
        BuildCredits.Credit downloaded = BuildCredits.get(kind, subKind, id);
        if (downloaded == null || !downloaded.known()) return null;
        return BuilderCredit.ofOrNull(downloaded.creatorUuid(), downloaded.creatorName());
    }
}
