package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.Map;

/**
 * The language the game just built, with the player's translation overrides layered on top.
 * Installed through {@link Language#inject} by {@link TranslationOverrides}.
 *
 * <p>A {@link Language} subclass rather than a mixin on {@code ClientLanguage#getOrDefault},
 * for a reason that is easy to miss: {@code TranslatableContents} caches its decomposed parts
 * against the <i>identity</i> of {@code Language.getInstance()} and only re-resolves when that
 * instance changes. Overriding the lookup in place would leave every already-rendered component
 * showing the old text until something else forced a reload — which is exactly the "my edit
 * didn't apply" bug this whole layer exists to avoid. Swapping the instance invalidates those
 * caches for free.</p>
 *
 * <p>Everything except the lookup delegates, including {@link #isDefaultRightToLeft()} and
 * {@link #getVisualOrder} — an override is a different string in the same language, so it must
 * keep that language's bidi handling.</p>
 */
public final class OverlayLanguage extends Language {

    private final Language delegate;
    private final Map<String, String> overrides;

    /** @param overrides key → replacement text; taken as an immutable copy. */
    public OverlayLanguage(Language delegate, Map<String, String> overrides) {
        this.delegate = delegate;
        this.overrides = Map.copyOf(overrides);
    }

    /**
     * The language this one wraps. {@link TranslationOverrides} re-wraps the delegate rather than
     * the overlay when overrides change, so repeated edits can never stack overlays on overlays.
     */
    public Language delegate() {
        return delegate;
    }

    public int overrideCount() {
        return overrides.size();
    }

    @Override
    public String getOrDefault(String key, String fallback) {
        String override = overrides.get(key);
        return override != null ? override : delegate.getOrDefault(key, fallback);
    }

    @Override
    public boolean has(String key) {
        return overrides.containsKey(key) || delegate.has(key);
    }

    @Override
    public boolean isDefaultRightToLeft() {
        return delegate.isDefaultRightToLeft();
    }

    @Override
    public FormattedCharSequence getVisualOrder(FormattedText text) {
        return delegate.getVisualOrder(text);
    }
}
