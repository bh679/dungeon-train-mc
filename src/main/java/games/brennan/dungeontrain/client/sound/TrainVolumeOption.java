package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The <b>Train Engine Volume</b> control, as a vanilla {@link OptionInstance} — one definition,
 * used wherever the setting is offered.
 *
 * <p>Built the way {@code Options.createSoundSliderOptionInstance} builds Minecraft's own volume
 * sliders, because one of the two places it appears <i>is</i> Minecraft's sound screen (see
 * {@code SoundOptionsScreenTrainVolumeMixin}), where a control that didn't look and read like its
 * neighbours would be the odd one out. The mod's own options screen then reuses this same instance
 * through {@link OptionInstance#createButton}, so the two surfaces are not merely consistent —
 * they are the same widget over the same accessor.</p>
 *
 * <p><b>Notched into tenths rather than continuous.</b> Vanilla's volume sliders run on
 * {@code UnitDouble}, a smooth 0–1. This one runs on an {@link OptionInstance.IntRange} of 0..10
 * for two reasons: a drag fires the setter on every step, and {@code IntRange} bounds that to
 * eleven values — which, with {@link ClientDisplayConfig#setTrainEngineVolume}'s unchanged-check,
 * bounds a whole drag to eleven TOML writes. It also keeps this slider on the same 10% granularity
 * as the {@code [-] / [+]} stepper the in-world X-menu offers, so the two never disagree about
 * what a step is.</p>
 *
 * <p>The value labels restate vanilla's own {@code percentValueOrOffLabel} — {@code "70%"}, or
 * {@code OFF} at zero — rather than calling it, because that method is private. Both lang keys it
 * uses ship translated with the game.</p>
 */
public final class TrainVolumeOption {

    /** Slider notches: tenths of full volume, so {@code 0..10} reads as {@code OFF..100%}. */
    private static final int MIN_TENTHS = 0;
    private static final int MAX_TENTHS = 10;

    private TrainVolumeOption() {}

    /**
     * For vanilla's Music &amp; Sounds screen, where the caption has to read as one more sound
     * category beside "Music" and "Ambient/Environment" — so it names the mod.
     */
    public static OptionInstance<Integer> forSoundScreen() {
        return create("gui.dungeontrain.sound.train_engine");
    }

    /**
     * For the mod's own options screen, which is already titled Dungeon Train — so the caption
     * names the setting instead of repeating the mod.
     */
    public static OptionInstance<Integer> forModScreen() {
        return create("gui.dungeontrain.options.train_volume");
    }

    /**
     * A fresh control bound to {@link ClientDisplayConfig}'s stored volume.
     *
     * <p>Reads the stored value once, at construction — which is the right time because every
     * screen that hosts one builds it in {@code init()}, so re-opening (or resizing) a screen picks
     * up a change made on any of the others. Only the caption differs between surfaces; the
     * tooltip, the notches and the accessor are one definition.</p>
     */
    private static OptionInstance<Integer> create(String captionKey) {
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(
                Component.translatable("gui.dungeontrain.sound.train_engine.tip")),
            TrainVolumeOption::label,
            new OptionInstance.IntRange(MIN_TENTHS, MAX_TENTHS),
            toTenths(ClientDisplayConfig.getTrainEngineVolume()),
            tenths -> ClientDisplayConfig.setTrainEngineVolume(tenths / (double) MAX_TENTHS));
    }

    /** {@code "Dungeon Train Engine: 70%"}, or {@code "…: OFF"} at zero — vanilla's own wording. */
    private static Component label(Component caption, int tenths) {
        return tenths <= MIN_TENTHS
            ? Options.genericValueLabel(caption, CommonComponents.OPTION_OFF)
            : Component.translatable("options.percent_value", caption, tenths * (100 / MAX_TENTHS));
    }

    /** The stored {@code 0.0..1.0} volume as a slider notch, clamping a hand-edited toml value. */
    private static int toTenths(double volume) {
        return (int) Math.round(Mth.clamp(volume, 0.0, 1.0) * MAX_TENTHS);
    }
}
