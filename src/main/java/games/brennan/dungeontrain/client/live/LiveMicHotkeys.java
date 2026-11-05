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
 * The streamer's mic keys: Toggle Mic (K by default: vanilla binds nothing to it, and Dungeon Train's
 * other keys sit on Z, C, J, O, V, Y and R) and Push to Talk (V), which only matters with push to talk
 * set ({@code LiveFeedClientConfig.micPushToTalk}). The key, the mic button by LIVE in the inventory
 * ({@link LiveRecOverlay}) and the Stream tab's Microphone option are one switch
 * ({@link LiveMic#set}); {@link #apply} is what the key and the button say about the result.
 *
 * <p>The inner tick class has a unique simple name — NeoForge silently dedupes inner
 * {@code @EventBusSubscriber} classes that share one (see {@code ContainerHotkeyClient}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveMicHotkeys {

    static final KeyMapping TOGGLE = new KeyMapping(
        "key." + DungeonTrain.MOD_ID + ".live_mic_toggle",
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_K,
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

    /** The Toggle Mic key's name as bound right now, for the hint by LIVE. */
    static Component toggleKeyName() {
        return TOGGLE.getTranslatedKeyMessage();
    }

    /**
     * Flips the one mic switch from the key or the inventory button, with a click to hear and a line
     * over the hotbar; a mic that would not open says so in chat, and so does a switch-on during a
     * stream without a mic track (the voice joins from the next stream). Nothing is said outside a
     * stream: the option is simply saved.
     */
    static void toggle(LiveMic.Source source) {
        apply(LiveMic.toggle(source));
    }

    /** The Stream tab's Microphone option flipped: the same switch, with the same word on the result. */
    public static void setFromOptions(boolean on) {
        apply(LiveMic.set(on, LiveMic.Source.OPTIONS));
    }

    private static void apply(LiveMic.Toggle t) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        switch (t) {
            // The relay turned the mic off for this streamer mid-stream: the switch does nothing, say why again.
            case BARRED -> LiveStreamController.sayMicBarred();
            case NO_DEVICE -> mc.player.displayClientMessage(
                Component.translatable("chat.dungeontrain.live.mic_no_device").withStyle(ChatFormatting.RED), false);
            case NEXT_STREAM -> {
                if (LiveStreamController.get().streaming()) mc.player.displayClientMessage(
                    Component.translatable("chat.dungeontrain.live.mic_next_stream").withStyle(ChatFormatting.YELLOW), false);
            }
            case ON, OFF -> {
                if (!LiveStreamController.get().streaming()) return;
                boolean on = t == LiveMic.Toggle.ON;
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, on ? 1.4f : 0.8f));
                mc.player.displayClientMessage(Component.translatable(
                    on ? "gui.dungeontrain.live.mic_on" : "gui.dungeontrain.live.mic_off"), true);
            }
        }
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
            // The key is only a switch while on air: elsewhere the Stream tab's option is the place.
            if (presses % 2 == 1 && LiveStreamController.get().streaming()) toggle(LiveMic.Source.KEY);
        }
    }
}
