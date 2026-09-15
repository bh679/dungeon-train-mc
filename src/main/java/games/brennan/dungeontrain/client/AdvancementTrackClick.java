package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.compat.AdvancementHintText;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

/**
 * The click rule both advancements screens share: a left click while a trackable, unearned
 * advancement's tooltip is showing toggles tracking on it. Kept out of the two screen mixins
 * (vanilla + Better Advancements) so they can't drift apart.
 */
public final class AdvancementTrackClick {

    private static final int LEFT_BUTTON = 0;
    private static final String TRACKING_ON_KEY = "chat.dungeontrain.track.enabled";
    private static final String TRACKING_OFF_KEY = "chat.dungeontrain.track.disabled";

    private AdvancementTrackClick() {}

    /** Handle a screen click; returns true when a tracking toggle happened (the screen is not cancelled either way). */
    public static boolean handle(int button) {
        if (button != LEFT_BUTTON) return false;
        ResourceLocation id = HoveredAdvancement.current();
        if (!AdvancementHintText.isTrackable(id)) return false;
        if (HoveredAdvancement.currentIsEarned()) return false; // nothing left to lose
        if (LifeDisqualificationClient.isDisqualified(id)) return false; // already gone this life
        boolean nowTracked = TrackedAdvancements.toggle(id);
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        if (mc.player != null) {
            String key = nowTracked ? TRACKING_ON_KEY : TRACKING_OFF_KEY;
            mc.player.displayClientMessage(
                Component.translatable(key, titleOf(id)).withStyle(ChatFormatting.GRAY), false);
        }
        return true;
    }

    /** The advancement's display title, or its id when the client has no display for it. */
    private static Component titleOf(ResourceLocation id) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        AdvancementHolder holder = connection == null ? null : connection.getAdvancements().get(id);
        if (holder == null) return Component.literal(id.toString());
        return holder.value().display().map(DisplayInfo::getTitle).orElse(Component.literal(id.toString()));
    }
}
