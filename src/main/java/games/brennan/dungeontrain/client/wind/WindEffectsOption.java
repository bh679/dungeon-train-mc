package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The wind screen-effects slider, OFF then tiers 1 to {@link ClientDisplayConfig#MAX_WIND_SCREEN_EFFECTS}, over
 * {@link ClientDisplayConfig#getWindScreenEffects()}: shown on the Dungeon Train options screen's Train tab beside
 * Wind volume, built the same way as {@link WindVolumeOption}. Scales the edge streaks in {@link WindFeelOverlay}.
 */
public final class WindEffectsOption {

    private WindEffectsOption() {}

    /** For a settings row that already names the setting: the slider reads only "3" or OFF. */
    public static OptionInstance<Integer> valueOnly() {
        return new OptionInstance<>(
                "gui.dungeontrain.options.wind_effects",
                OptionInstance.noTooltip(),
                (caption, tier) -> valueLabel(tier),
                new OptionInstance.IntRange(0, ClientDisplayConfig.MAX_WIND_SCREEN_EFFECTS),
                ClientDisplayConfig.getWindScreenEffects(),
                ClientDisplayConfig::setWindScreenEffects);
    }

    private static Component valueLabel(int tier) {
        return tier <= 0 ? CommonComponents.OPTION_OFF : Component.literal(Integer.toString(tier)); // a number, not prose
    }
}
