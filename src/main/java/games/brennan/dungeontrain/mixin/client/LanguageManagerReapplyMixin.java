package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.localization.edit.TranslationOverrides;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-applies the player's translation overrides after every language rebuild.
 *
 * <p>{@code onResourceManagerReload} builds a fresh {@code ClientLanguage} and installs it with
 * {@code Language.inject} + {@code I18n.setLanguage}, discarding whatever was there — which
 * includes the override overlay. That happens on startup, on every resource reload, on a
 * resource-pack toggle, and on every language switch, so without this the overrides would
 * silently vanish the first time a player touched any of those.</p>
 *
 * <p>A mixin at {@code TAIL} rather than a {@code RegisterClientReloadListenersEvent} listener
 * because ordering has to be guaranteed: a listener could run before {@code LanguageManager} and
 * would then overlay the language that is about to be thrown away. This also picks up the newly
 * selected locale for free — {@code getSelected()} is already updated by the time we run.</p>
 */
@Mixin(LanguageManager.class)
public abstract class LanguageManagerReapplyMixin {

    @Inject(method = "onResourceManagerReload", at = @At("TAIL"))
    private void dungeontrain$reapplyTranslationOverrides(ResourceManager resourceManager,
                                                          CallbackInfo ci) {
        TranslationOverrides.reload(((LanguageManager) (Object) this).getSelected());
    }
}
