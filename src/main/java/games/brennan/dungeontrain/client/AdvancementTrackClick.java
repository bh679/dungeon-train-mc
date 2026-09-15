package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.compat.AdvancementHintText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

/**
 * The click rule both advancements screens share: a left click while a trackable, unearned
 * advancement's tooltip is showing toggles tracking on it. Kept out of the two screen mixins
 * (vanilla + Better Advancements) so they can't drift apart.
 */
public final class AdvancementTrackClick {

    private static final int LEFT_BUTTON = 0;

    private AdvancementTrackClick() {}

    /** Handle a screen click; returns true when a tracking toggle happened (the screen is not cancelled either way). */
    public static boolean handle(int button) {
        if (button != LEFT_BUTTON) return false;
        ResourceLocation id = HoveredAdvancement.current();
        if (!AdvancementHintText.isTrackable(id)) return false;
        if (HoveredAdvancement.currentIsEarned()) return false; // nothing left to lose
        TrackedAdvancements.toggle(id);
        Minecraft.getInstance().getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        return true;
    }
}
