package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker for {@link I18n}'s package-private {@code setLanguage}, so the translation-override
 * layer can point {@code I18n} at its overlay.
 *
 * <p>{@code I18n} caches the language in its own static field instead of reading
 * {@code Language.getInstance()} on each call, which is why {@code LanguageManager} sets both on
 * every reload. Any override layer has to do the same: without this, {@code Language.inject}
 * alone would leave every {@code I18n.get} / {@code I18n.exists} call site — a few hundred of
 * them across vanilla and the mod — reading the un-overridden text.</p>
 */
@Mixin(I18n.class)
public interface I18nAccessor {

    @Invoker("setLanguage")
    static void dungeontrain$setLanguage(Language language) {
        throw new AssertionError("mixin invoker not applied");
    }
}
