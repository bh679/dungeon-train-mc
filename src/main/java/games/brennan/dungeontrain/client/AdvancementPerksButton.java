package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.perks.PerksScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * A nether star on the advancements screen, beside its Done button (or, on a screen without one, under
 * the window's bottom-right corner, {@link AdvancementWindowRow#row}), opening {@link PerksScreen}. The player's unspent Advancement
 * Points ({@link PerkClientBalances#unlockPoints}) sit on it like a stack count; hovering names both.
 * Re-placed every frame, since Better Advancements only reports its window while drawing; hidden
 * until it has.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class AdvancementPerksButton {

    private static final Component LABEL = Component.translatable("gui.dungeontrain.perks.title");
    private static final Component TOOLTIP = Component.empty().append(LABEL).append("\n")
            .append(Component.translatable("gui.dungeontrain.advancements.points").withStyle(ChatFormatting.GRAY));
    /** The Perks page's button height. */
    private static final int HEIGHT = 16;
    /** The star, drawn at three quarters of an item's 16 px. */
    private static final float STAR_SCALE = 0.75F;
    private static final int STAR = 12;
    private static final int INSET = 2;
    /** How far the count reaches back over the star, as a stack's count does. */
    private static final int OVERLAP = 4;
    /** The count's top below the star's, low on it as a stack's count sits. */
    private static final int COUNT_DROP = 5;
    private static final ItemStack ICON = new ItemStack(Items.NETHER_STAR);

    private static final Map<Screen, Button> BUTTONS = new WeakHashMap<>();

    private AdvancementPerksButton() {}

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!DefaultAdvancementsTab.isAdvancementsScreen(screen)) return;
        Button button = new StarButton();
        button.setTooltip(Tooltip.create(TOOLTIP));
        button.visible = false;
        event.addListener(button);
        BUTTONS.put(screen, button);
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        Screen screen = event.getScreen();
        Button button = BUTTONS.get(screen);
        if (button == null) return;
        int width = StarButton.width(screen.getMinecraft().font);
        button.setWidth(width);
        AbstractWidget done = doneButton(screen, button);
        if (done != null) {
            button.visible = true;
            button.setHeight(done.getHeight());
            button.setPosition(done.getX() + done.getWidth() + AdvancementWindowRow.GAP * 2, done.getY());
            return;
        }
        AdvancementWindowRow.Row row = AdvancementWindowRow.row(screen, HEIGHT);
        button.visible = row != null;
        if (row == null) return;
        button.setHeight(HEIGHT);
        button.setPosition(row.right() - width, row.top());
    }

    /** The screen's Done button (vanilla's footer has one), or null. */
    private static AbstractWidget doneButton(Screen screen, Button self) {
        String done = CommonComponents.GUI_DONE.getString();
        for (GuiEventListener child : screen.children()) {
            if (child != self && child instanceof AbstractButton b && b.visible
                    && done.equals(b.getMessage().getString())) return b;
        }
        return null;
    }

    /** The Perks page's dark button, holding the star with the points count over its corner like a stack's. */
    private static final class StarButton extends DarkTintedButton {

        StarButton() {
            super(0, 0, HEIGHT, HEIGHT, LABEL, b -> PerksScreen.open(false));
        }

        private static String count() {
            return Integer.toString(PerkClientBalances.unlockPoints());
        }

        static int width(Font font) {
            return Math.max(HEIGHT, INSET + STAR - OVERLAP + font.width(count()) + INSET);
        }

        @Override
        public void renderString(GuiGraphics g, Font font, int colour) {
            int x = getX() + INSET;
            int y = getY() + (getHeight() - STAR) / 2;
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(STAR_SCALE, STAR_SCALE, 1F);
            g.renderItem(ICON, 0, 0);
            g.pose().popPose();
            // Always shown, 0 included, unlike a real stack's count; above the item, as vanilla draws it.
            String count = count();
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            g.drawString(font, count, getX() + getWidth() - INSET + 1 - font.width(count), y + COUNT_DROP, colour, true);
            g.pose().popPose();
        }
    }
}
