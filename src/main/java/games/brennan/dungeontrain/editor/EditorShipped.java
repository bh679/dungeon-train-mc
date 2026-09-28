package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.builder.BuilderBuiltins;
import games.brennan.dungeontrain.builder.BuilderTemplateIdentity;
import games.brennan.dungeontrain.editor.relay.EditorRelayWrite;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;

/**
 * Whether a Train Editor template is one the mod ships — the Editor's side of
 * {@link BuilderBuiltins}.
 *
 * <p>{@link Template#isBuiltin()} was the Editor's only answer, and it is too narrow for this: a
 * carriage is "built in" only for the three enum variants, so {@code black}, {@code cracked} and the
 * other shipped carriages that register as customs read as the player's own — as do every shipped
 * contents template, every shipped part and every named track, tunnel and room. Saving one of those
 * wrote over the mod's template and uploaded it to My Builds under the mod's name.</p>
 *
 * <p>So the jar decides, by the same per-kind check the Builder uses, and {@code isBuiltin()} is kept
 * beside it rather than replaced: the default tunnel and the default dimensional carriage are made in
 * code and ship no {@code .nbt}, so the jar alone would call them the player's.</p>
 */
public final class EditorShipped {

    private EditorShipped() {}

    /** Whether the mod ships {@code model}, by name. */
    public static boolean isShipped(Template model) {
        if (model == null) return false;
        if (model.isBuiltin()) return true;
        if (model instanceof Template.WholeCarriage whole) {
            // Not a relay kind (namingOf has no name for it), but the registry knows its jar tier.
            return WholeCarriageRegistry.isBundled(whole.wholeCarriage().id());
        }
        EditorRelayWrite.Naming naming = EditorRelayWrite.namingOf(model);
        if (naming == null) return false;
        return BuilderBuiltins.isShipped(
                new BuilderTemplateIdentity.Identity(naming.kind(), naming.subKind(), naming.id()));
    }

    /**
     * Whether Save has to stop and offer a name of the player's own: a shipped template, outside a
     * dev checkout. Dev mode is how the mod's own templates are authored, so it is exempt — the same
     * rule, and the same method, as the Builder.
     */
    public static boolean isProtected(Template model) {
        return BuilderBuiltins.isProtected(isShipped(model), EditorDevMode.isEnabled());
    }
}
