package games.brennan.dungeontrain.client.display;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * The three Dungeon Train display-scale channels — <b>All Displays</b>, <b>Worldspace</b> and
 * <b>HUD</b> — as vanilla {@link OptionInstance} sliders.
 *
 * <p>Built the same way {@link games.brennan.dungeontrain.client.sound.TrainVolumeOption} is built,
 * and for the same two reasons. A slider is one widget, so it drops straight into a row of the
 * options screen's {@code OptionsList} the way a {@code [-] / label / [+]} stepper triple never
 * could. And running on an {@link OptionInstance.IntRange} of tenths rather than a continuous
 * {@code UnitDouble} bounds a whole drag to nineteen setter calls — which, with the accessors'
 * unchanged-check, bounds it to nineteen TOML writes.</p>
 *
 * <p>Tenths also keep these sliders on exactly the {@link ClientDisplayConfig#STEP} granularity the
 * in-world X-menu's {@code [-] / [+]} steppers use, so the two surfaces cannot disagree about what
 * one step is. Both read and write the same {@link ClientDisplayConfig} accessors — this is a second
 * <em>view</em>, not a second setting.</p>
 */
public final class DisplayScaleOption {

    /** Slider notches: tenths of a scale unit, spanning the config's own clamped range. */
    private static final int MIN_TENTHS = (int) Math.round(ClientDisplayConfig.MIN_SCALE * 10);
    private static final int MAX_TENTHS = (int) Math.round(ClientDisplayConfig.MAX_SCALE * 10);

    private DisplayScaleOption() {}

    /** Master multiplier applied on top of both channels. */
    public static OptionInstance<Integer> allDisplays() {
        return create("all_displays", ClientDisplayConfig::getAllScale, ClientDisplayConfig::setAllScale);
    }

    /** Base scale for the X menu, editor menus and in-world debug labels. */
    public static OptionInstance<Integer> worldspace() {
        return create("worldspace", ClientDisplayConfig::getWorldspaceChannel,
                ClientDisplayConfig::setWorldspaceChannel);
    }

    /** Base scale for the top-left version line and the top-centre editor status bar. */
    public static OptionInstance<Integer> hud() {
        return create("hud", ClientDisplayConfig::getHudChannel, ClientDisplayConfig::setHudChannel);
    }

    /**
     * A fresh slider bound to one channel's accessors.
     *
     * <p>{@code channel} is the lang-key suffix under {@code gui.dungeontrain.editor_settings.} — the
     * caption and its tooltip are both derived from it, so a row can't ship with one localized and one
     * not. The stored value is read once, at construction, which is the right time because the screen
     * builds these in {@code init()}: reopening or resizing picks up a change made from the X-menu.</p>
     */
    private static OptionInstance<Integer> create(String channel, DoubleSupplier get, DoubleConsumer set) {
        String key = "gui.dungeontrain.editor_settings." + channel;
        return new OptionInstance<>(
                key,
                OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tip")),
                DisplayScaleOption::label,
                new OptionInstance.IntRange(MIN_TENTHS, MAX_TENTHS),
                toTenths(get.getAsDouble()),
                tenths -> set.accept(tenths / 10.0));
    }

    /**
     * {@code "<name>: 1.0"} through the shared {@code value_row} pattern, so CJK locales can use the
     * full-width colon. The number itself stays {@link Locale#ROOT}-formatted — it is a config value
     * the player types back into a toml, not prose.
     */
    private static Component label(Component caption, int tenths) {
        return Component.translatable("gui.dungeontrain.options.value_row",
                caption, String.format(Locale.ROOT, "%.1f", tenths / 10.0));
    }

    /** The stored scale as a slider notch, clamping a hand-edited toml value into range. */
    private static int toTenths(double scale) {
        return (int) Math.round(
                Mth.clamp(scale, ClientDisplayConfig.MIN_SCALE, ClientDisplayConfig.MAX_SCALE) * 10);
    }
}
