package games.brennan.dungeontrain.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only accessor for the language code on a row of the vanilla language list.
 *
 * <p>The list's selection is <em>pending</em> — vanilla does not apply it until Done — and the code
 * behind the highlighted row is a package-private field on a private inner class, so there is no way
 * to ask "which language is the player pointing at?" without this. {@code LanguageSelectEntryLogoMixin}
 * shadows the same field for its badge, which is what confirms the target and field name here.</p>
 */
@Mixin(targets = "net.minecraft.client.gui.screens.options.LanguageSelectScreen$LanguageSelectionList$Entry")
public interface LanguageSelectEntryAccessor {

    @Accessor("code")
    String dungeontrain$code();
}
