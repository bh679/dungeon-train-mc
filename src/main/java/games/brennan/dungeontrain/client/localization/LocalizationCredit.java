package games.brennan.dungeontrain.client.localization;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.OptionalDouble;

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
 *
 * <p>{@code aiCounts} carries the GENERATED per-locale AI-translation summary
 * (optional {@code total_keys} / {@code ai_authored} / {@code ai_unreviewed}
 * fields, stamped by {@code scripts/localization/stamp-provenance.py} from the
 * repo-side provenance sidecars). Present only when all three fields parse as
 * consistent non-negative integers; drives the blue AI-fraction ring around the
 * logo in the language-selection list.</p>
 *
 * <p>{@code contributedKeys} is the GENERATED per-contributor count (optional
 * {@code contributed_keys} field, stamped only when {@code name} authored or
 * reviewed at least one line) — how many of this locale's keys this person
 * touched. Drives the per-translator contribution % on the in-game Credits page;
 * its presence also distinguishes a real human contributor from the AI-placeholder
 * credit (whose name matches no author, so it carries no such field).</p>
 */
public record LocalizationCredit(
    ResourceLocation id,
    String locale,
    String name,
    Optional<String> url,
    boolean humanReviewed,
    Optional<AiCounts> aiCounts,
    Optional<Integer> contributedKeys
) {
    public LocalizationCredit {
        url = url == null ? Optional.empty() : url;
        aiCounts = aiCounts == null ? Optional.empty() : aiCounts;
        contributedKeys = contributedKeys == null ? Optional.empty() : contributedKeys;
    }

    /**
     * The fraction of this locale's keys this person authored or reviewed, in
     * {@code (0, 1]} — empty unless both {@code contributedKeys} and the
     * {@code aiCounts} total are present (the total is the denominator). Rendered
     * as a percentage on the Credits page.
     */
    public OptionalDouble contributionFraction() {
        if (contributedKeys.isEmpty() || aiCounts.isEmpty()) {
            return OptionalDouble.empty();
        }
        int total = aiCounts.get().totalKeys();
        if (total <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of((double) contributedKeys.get() / total);
    }

    /**
     * The generated AI-translation counts for one locale. Invariant (enforced at
     * parse time): {@code 0 <= aiUnreviewed <= aiAuthored <= totalKeys} and
     * {@code totalKeys > 0}, so the fractions are always in {@code [0, 1]}.
     */
    public record AiCounts(int totalKeys, int aiAuthored, int aiUnreviewed) {

        /** Fraction of lines that are AI-authored with no human reviewer. */
        public double unreviewedFraction() {
            return (double) aiUnreviewed / totalKeys;
        }

        /** Fraction of lines that are AI-authored, reviewed or not. */
        public double authoredFraction() {
            return (double) aiAuthored / totalKeys;
        }
    }
}
