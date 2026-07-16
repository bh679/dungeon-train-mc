package games.brennan.dungeontrain.client.localization;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * One localizer thank-you entry loaded from a resource pack's
 * {@code assets/<ns>/localization_credits/<slug>.json}. See
 * {@link LocalizationCreditRegistry} for how these are loaded and
 * {@code assets/dungeontrain/localization_credits/CLAUDE.md} for the schema.
 *
 * <p>{@code locale} is a Minecraft locale code (e.g. {@code "es_es"}) — the
 * same code the pack's own {@code lang/<locale>.json} override uses. Credits
 * only ever surface for the client's currently selected locale.</p>
 *
 * <p>{@code humanReviewed} records whether this locale's translation has been
 * proofread by a human (optional {@code human_reviewed} field, default
 * {@code false}). It drives the full-vs-faded Dungeon Train logo in the
 * language-selection list — see {@code LanguageSelectEntryLogoMixin}.</p>
 */
public record LocalizationCredit(
    ResourceLocation id,
    String locale,
    String name,
    Optional<String> url,
    boolean humanReviewed
) {
    public LocalizationCredit {
        url = url == null ? Optional.empty() : url;
    }
}
