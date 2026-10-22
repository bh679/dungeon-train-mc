package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.compat.AdvancementHintText;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

/**
 * The click rule both advancements screens share: a left click while a trackable, unearned
 * advancement's tooltip is showing toggles tracking on it — even one already lost this life, so a
 * player can still set it up for the next life; the tooltip and chat summary just don't mention
 * it while it is gone. Kept out of the two screen mixins
 * (vanilla + Better Advancements) so they can't drift apart. A left click on an <em>earned</em> camera
 * advancement instead opens the photo that earned it ({@link EarnedPhotos}). A trackable capstone (All at Once,
 * The Challenge) does both: the click pins its "what you still need" halo ({@link CapstoneNeeds}) and toggles
 * tracking. The chat confirmation comes once the screen closes — see {@link TrackedAdvancementsSummary}.
 */
public final class AdvancementTrackClick {

    private static final int LEFT_BUTTON = 0;

    private AdvancementTrackClick() {}

    /** The advancement's display title, or its id when the client doesn't have it. */
    private static Component titleOf(ResourceLocation id) {
        var connection = Minecraft.getInstance().getConnection();
        AdvancementHolder holder = connection == null ? null : connection.getAdvancements().get(id);
        return holder == null ? Component.literal(String.valueOf(id))
            : holder.value().display().map(DisplayInfo::getTitle).orElse(Component.literal(id.toString()));
    }

    /** Handle a screen click; returns true when a tracking toggle happened (the screen is not cancelled either way). */
    public static boolean handle(int button) {
        if (button != LEFT_BUTTON) return false;
        ResourceLocation id = HoveredAdvancement.current();
        // The Everything Burrito or a tab-complete advancement pins "what you still need"; any other advancement
        // clicked clears the pin and goes on to its own click behaviour below. A trackable capstone (All at Once,
        // The Challenge) also toggles tracking, as its tooltip promises.
        if (id != null && CapstoneNeeds.onClick(id)) {
            if (canToggleTracking(id)) TrackedAdvancements.toggle(id);
            playClick();
            return true;
        }
        // A camera advancement opens what this computer kept: a collection's album as it fills, or the
        // photo that earned it once earned.
        if (id != null && EarnedPhotos.tryOpen(id, titleOf(id), HoveredAdvancement.currentProgress())) {
            playClick();
            return true;
        }
        if (!canToggleTracking(id)) return false;
        TrackedAdvancements.toggle(id);
        playClick();
        return true;
    }

    /** A trackable advancement not yet earned (an earned one has nothing left to lose). */
    private static boolean canToggleTracking(ResourceLocation id) {
        return AdvancementHintText.isTrackable(id) && !HoveredAdvancement.currentIsEarned();
    }

    private static void playClick() {
        Minecraft.getInstance().getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
