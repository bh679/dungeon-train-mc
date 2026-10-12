package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.BookEditScreenAccessor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Puts a {@link BookFilterInfoButton} on every book-writing screen, centred in the 12px gap above
 * Scribble's bold button, and hides it while the book is being signed (Scribble hides its whole
 * formatting column there too).
 *
 * <p>The position is read from the live bold widget rather than computed, so it follows Scribble's
 * {@code centerBookGui} offset and any window size. Without Scribble it falls back to where the
 * bold button would be.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BookFilterInfoEvents {

    private static final String BOLD_KEY = "text.scribble.modifier.bold";

    /** Gap between the info button's bottom edge and the bold button's top. */
    private static final int GAP = 1;

    /** Scribble's formatting column sits at {@code width/2 + 78} and is 22 wide. */
    private static final int FALLBACK_COLUMN_X_FROM_CENTRE = 78;
    private static final int COLUMN_WIDTH = 22;
    private static final int FALLBACK_Y = 2;

    private BookFilterInfoEvents() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof BookEditScreen screen)) return;

        AbstractWidget bold = findBold(event);
        int x;
        int y;
        if (bold != null) {
            x = bold.getX() + (bold.getWidth() - BookFilterInfoButton.SIZE) / 2;
            y = bold.getY() - BookFilterInfoButton.SIZE - GAP;
        } else {
            x = screen.width / 2 + FALLBACK_COLUMN_X_FROM_CENTRE + (COLUMN_WIDTH - BookFilterInfoButton.SIZE) / 2;
            y = FALLBACK_Y;
        }
        event.addListener(new BookFilterInfoButton(x, y));
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof BookEditScreen screen)) return;
        boolean signing = ((BookEditScreenAccessor) screen).dungeontrain$isSigning();
        for (GuiEventListener child : screen.children()) {
            if (child instanceof BookFilterInfoButton button) {
                button.visible = !signing;
            }
        }
    }

    private static AbstractWidget findBold(ScreenEvent.Init.Post event) {
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget widget
                    && widget.getClass().getName().startsWith("me.chrr.scribble.")
                    && widget.getClass().getName().endsWith(".ModifierButtonWidget")
                    && BOLD_KEY.equals(ScribbleBookButtonsToggle.translationKey(widget))) {
                return widget;
            }
        }
        return null;
    }
}
