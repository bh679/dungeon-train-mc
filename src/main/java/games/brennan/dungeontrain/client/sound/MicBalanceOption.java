package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;

/**
 * The game/mic balance slider over {@link LiveFeedClientConfig#micMixPercent()}, in 5% notches: what a
 * streamer's viewers hear while the mic is on, with the game at {@code 100 - that}. One definition,
 * shown on vanilla's Music &amp; Sounds screen and on the Dungeon Train options screen's Stream tab.
 */
public final class MicBalanceOption {

    private static final String MOD_KEY = "gui.dungeontrain.options.mic_mix";
    private static final int STEP = 5;

    private MicBalanceOption() {}

    /** For vanilla's Music &amp; Sounds screen: the caption says whose mic and which sound it balances. */
    public static OptionInstance<Integer> forSoundScreen() {
        return create("gui.dungeontrain.sound.mic_balance",
                OptionInstance.cachedConstantTooltip(Component.translatable(MOD_KEY + ".tip")),
                (caption, notch) -> Component.translatable("options.percent_value", caption, notch * STEP));
    }

    /** For a settings row that already names the setting: the slider reads {@code Mic 30%}. */
    public static OptionInstance<Integer> forModScreen() {
        return create(MOD_KEY, OptionInstance.noTooltip(),
                (caption, notch) -> Component.translatable(MOD_KEY + ".value", notch * STEP));
    }

    private static OptionInstance<Integer> create(String captionKey,
                                                  OptionInstance.TooltipSupplier<Integer> tooltip,
                                                  OptionInstance.CaptionBasedToString<Integer> label) {
        return new OptionInstance<>(
                captionKey,
                tooltip,
                label,
                new OptionInstance.IntRange(0, 100 / STEP),
                Math.round(LiveFeedClientConfig.micMixPercent() / (float) STEP),
                notch -> LiveFeedClientConfig.setMicMixPercent(notch * STEP));
    }
}
