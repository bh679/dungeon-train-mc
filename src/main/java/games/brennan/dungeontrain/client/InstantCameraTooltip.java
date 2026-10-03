package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import io.github.mortuusars.exposure_polaroid.ExposurePolaroid;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Drops Polaroid's slide lines from the instant camera's hover text — the "Slides: n/m" count and the
 * insert/remove-slides hints, plus the "Hold [Shift]" prompt that only led to those hints. DT's camera is disposable: its one slide can't be swapped, so both only
 * mislead.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class InstantCameraTooltip {

    /** Polaroid's camera tooltip keys: {@code .slides} and {@code .details_insert/remove[_on_stand]}. */
    private static final String SLIDE_KEY_PREFIX = "item.exposure_polaroid.instant_camera.tooltip.";

    /** Exposure's "Hold [Shift] for Details" — the camera's only Shift details are the slide hints. */
    private static final String HOLD_FOR_DETAILS_KEY = "tooltip.exposure.hold_for_details";

    private InstantCameraTooltip() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().is(ExposurePolaroid.Items.INSTANT_CAMERA.get())) {
            event.getToolTip().removeIf(InstantCameraTooltip::isSlideLine);
        }
    }

    private static boolean isSlideLine(Component line) {
        if (line.getContents() instanceof TranslatableContents tc
                && (tc.getKey().startsWith(SLIDE_KEY_PREFIX) || tc.getKey().equals(HOLD_FOR_DETAILS_KEY))) {
            return true;
        }
        return line.getSiblings().stream().anyMatch(InstantCameraTooltip::isSlideLine);
    }
}
