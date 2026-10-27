package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VariantHotkeyClient;
import games.brennan.dungeontrain.client.live.audio.LiveMic;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * The streamer's mic keys: Toggle Mic (unbound by default; the icon by LIVE does the same,
 * {@link LiveRecOverlay}) and Push to Talk (V), which only matters with push to talk set
 * ({@code LiveFeedClientConfig.micPushToTalk}). {@link #toggle} is the one switch both use.
 *
 * <p>The inner tick class has a unique simple name — NeoForge silently dedupes inner
 * {@code @EventBusSubscriber} classes that share one (see {@code ContainerHotkeyClient}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveMicHotkeys {

    static final KeyMapping TOGGLE = new KeyMapping(
        "key." + DungeonTrain.MOD_ID + ".live_mic_toggle",
        InputConstants.Type.KEYSYM,
        InputConstants.UNKNOWN.getValue(),
        VariantHotkeyClient.CATEGORY
    );

    static final KeyMapping PUSH_TO_TALK = new KeyMapping(
        "key." + DungeonTrain.MOD_ID + ".live_mic_push_to_talk",
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_V,
        VariantHotkeyClient.CATEGORY
    );

    private LiveMicHotkeys() {}

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        event.register(PUSH_TO_TALK);
    }

    /**
     * Flips the stream's mic with a click to hear and a line over the hotbar; a mic that would not
     * open says so in chat. Nothing happens on a stream without a mic track.
     */
    static void toggle() {
        Minecraft mc = Minecraft.getInstance();
        LiveMic.Toggle t = LiveMic.toggle();
        if (t == LiveMic.Toggle.NOT_ENABLED) return;
        if (t == LiveMic.Toggle.NO_DEVICE) {
            if (mc.player != null) mc.player.displayClientMessage(
                Component.translatable("chat.dungeontrain.live.mic_no_device").withStyle(ChatFormatting.RED), false);
            return;
        }
        boolean on = t == LiveMic.Toggle.ON;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, on ? 1.4f : 0.8f));
        if (mc.player != null) mc.player.displayClientMessage(Component.translatable(
            on ? "gui.dungeontrain.live.mic_on" : "gui.dungeontrain.live.mic_off"), true);
    }

    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
    public static final class LiveMicKeyTickWatcher {

        private LiveMicKeyTickWatcher() {}

        /** Drain every queued press first, so presses made behind a screen are not banked. */
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            // Held, not pressed: let go of the key (or open a screen) and the mic is quiet again.
            LiveMic.setPushToTalkHeld(mc.screen == null && PUSH_TO_TALK.isDown());
            int presses = 0;
            while (TOGGLE.consumeClick()) presses++;
            if (presses == 0 || mc.player == null || mc.screen != null) return;
            if (presses % 2 == 1) toggle();
        }
    }
}
