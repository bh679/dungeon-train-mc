package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The wind volume slider, in tenths, over {@link ClientDisplayConfig#getWindVolume()} — the sibling of
 * {@code TrainVolumeOption}, built the same way, shown on vanilla's Music &amp; Sounds screen and on the Dungeon
 * Train options screen's Train tab. Scales both wind layers in {@link WindSound}.
 */
public final class WindVolumeOption {

    private static final int MIN_TENTHS = 0;
    private static final int MAX_TENTHS = 10;

    private WindVolumeOption() {}

    /** For vanilla's Music &amp; Sounds screen: the caption names the mod beside the vanilla categories. */
    public static OptionInstance<Integer> forSoundScreen() {
        return create("gui.dungeontrain.sound.wind", WindVolumeOption::label, tip());
    }

    /** For a settings row that already names the setting: the slider reads only "70%" or OFF. */
    public static OptionInstance<Integer> valueOnly() {
        return create("gui.dungeontrain.options.wind_volume", (caption, tenths) -> valueLabel(tenths),
                OptionInstance.noTooltip());
    }

    private static OptionInstance.TooltipSupplier<Integer> tip() {
        return OptionInstance.cachedConstantTooltip(Component.translatable("gui.dungeontrain.sound.wind.tip"));
    }

    private static OptionInstance<Integer> create(String captionKey,
                                                  OptionInstance.CaptionBasedToString<Integer> label,
                                                  OptionInstance.TooltipSupplier<Integer> tooltip) {
        return new OptionInstance<>(
                captionKey,
                tooltip,
                label,
                new OptionInstance.IntRange(MIN_TENTHS, MAX_TENTHS),
                toTenths(ClientDisplayConfig.getWindVolume()),
                tenths -> ClientDisplayConfig.setWindVolume(tenths / (double) MAX_TENTHS));
    }

    private static Component label(Component caption, int tenths) {
        return tenths <= MIN_TENTHS
                ? Options.genericValueLabel(caption, CommonComponents.OPTION_OFF)
                : Component.translatable("options.percent_value", caption, tenths * (100 / MAX_TENTHS));
    }

    private static Component valueLabel(int tenths) {
        return tenths <= MIN_TENTHS
                ? CommonComponents.OPTION_OFF
                : Component.literal(tenths * (100 / MAX_TENTHS) + "%"); // a unit, not prose
    }

    private static int toTenths(double volume) {
        return (int) Math.round(Mth.clamp(volume, 0.0, 1.0) * MAX_TENTHS);
    }
}
