package games.brennan.dungeontrain.client.menu.number;

import games.brennan.dungeontrain.client.menu.NumberInputScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * The number pad as a {@link NumberStepper.Typing}: cmd-click opens {@link NumberInputScreen} over
 * whatever is showing, and closing it returns there.
 */
@OnlyIn(Dist.CLIENT)
public final class NumberPad {

    private NumberPad() {}

    /**
     * Type into the pad, returning to {@code parent} afterwards. A world-space panel passes
     * {@code null} — its panel is a HUD overlay, still drawn behind the pad and still there after.
     */
    public static NumberStepper.Typing returningTo(@Nullable Screen parent) {
        return field -> Minecraft.getInstance().setScreen(new NumberInputScreen(field, parent));
    }

    /**
     * Type into the pad, returning to a screen built when the pad closes — for a panel whose
     * screen-space host must be rebuilt rather than reused.
     */
    public static NumberStepper.Typing returningToBuilt(Supplier<Screen> parent) {
        return field -> Minecraft.getInstance().setScreen(new NumberInputScreen(field, parent.get()));
    }

    /** Type into the pad, returning to the screen that is up right now (or the world). */
    public static NumberStepper.Typing overCurrent() {
        return field -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new NumberInputScreen(field, mc.screen));
        };
    }
}
