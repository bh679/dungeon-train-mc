package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.config.LiveViewerClientConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The <b>Stream volume</b> slider over {@link LiveViewerClientConfig#streamVolumePercent()}: how loud a
 * live stream plays from TVs. The sibling of {@link TrainVolumeOption}, shown on vanilla's Music &amp;
 * Sounds screen and on the Dungeon Train options screen's Stream tab, in 5% notches. The speakers read
 * the value every tick ({@code LiveTvSpeakers}), so a drag is heard as it moves.
 */
public final class StreamVolumeOption {

    private static final int STEP = 5;
    private static final int MAX_NOTCH = 100 / STEP;

    private StreamVolumeOption() {}

    /** For vanilla's Music &amp; Sounds screen: the caption sits beside the vanilla categories. */
    public static OptionInstance<Integer> forSoundScreen() {
        return create("gui.dungeontrain.sound.stream", StreamVolumeOption::label,
                OptionInstance.cachedConstantTooltip(Component.translatable("gui.dungeontrain.sound.stream.tip")));
    }

    /** For a settings row that already names the setting: the slider reads only "70%" or OFF. */
    public static OptionInstance<Integer> valueOnly() {
        return create("gui.dungeontrain.options.stream_volume", (caption, notch) -> valueLabel(notch),
                OptionInstance.noTooltip());
    }

    private static OptionInstance<Integer> create(String captionKey,
                                                  OptionInstance.CaptionBasedToString<Integer> label,
                                                  OptionInstance.TooltipSupplier<Integer> tooltip) {
        return new OptionInstance<>(
                captionKey,
                tooltip,
                label,
                new OptionInstance.IntRange(0, MAX_NOTCH),
                Math.round(LiveViewerClientConfig.streamVolumePercent() / (float) STEP),
                notch -> LiveViewerClientConfig.setStreamVolumePercent(notch * STEP));
    }

    private static Component label(Component caption, int notch) {
        return notch <= 0
                ? Options.genericValueLabel(caption, CommonComponents.OPTION_OFF)
                : Component.translatable("options.percent_value", caption, notch * STEP);
    }

    private static Component valueLabel(int notch) {
        return notch <= 0
                ? CommonComponents.OPTION_OFF
                : Component.literal(notch * STEP + "%"); // a unit, not prose
    }
}
